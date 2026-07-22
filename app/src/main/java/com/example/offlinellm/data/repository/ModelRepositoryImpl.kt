package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.repository.ModelRepository
import com.example.offlinellm.llama.LlamaBridge
import com.example.offlinellm.llama.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class ModelRepositoryImpl(
    private val context: Context
) : ModelRepository {

    private val modelsDir: File
        get() = ModelLoader.getModelsDirectory(context)

    private var availableModels: List<LlmModel> = emptyList()
    private var downloadedModelIds: MutableSet<String> = mutableSetOf()
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    init {
        refreshModels()
    }

    override fun getAvailableModels(): List<LlmModel> {
        if (availableModels.isEmpty()) refreshModels()
        return availableModels
    }

    override fun getDownloadedModels(): List<LlmModel> =
        availableModels.filter { it.isDownloaded }

    override fun refreshModels() {
        downloadedModelIds.clear()
        val dir = modelsDir
        AppLogger.d("ModelRepo", "refreshModels dir=${dir.absolutePath}")

        val localModels = ModelLoader.scanLocalModels(context)
        localModels.forEach { downloadedModelIds.add(it.id) }

        val recommended = ModelLoader.getRecommendedModels()
        val allModels = (recommended + localModels).distinctBy { it.id }.map { info ->
            val downloaded = downloadedModelIds.contains(info.id) ||
                info.filePath.isNotEmpty() ||
                resolveModelFile(info.id) != null
            if (downloaded) downloadedModelIds.add(info.id)
            LlmModel(
                id = info.id,
                name = info.name,
                sizeBytes = info.fileSizeBytes,
                downloadUrl = info.downloadUrl,
                isDownloaded = downloaded,
                quantType = info.quantType,
                parameterCount = info.parameterCount
            )
        }
        availableModels = allModels
        AppLogger.d("ModelRepo", "models=${allModels.size} downloaded=${downloadedModelIds.size}")
    }

    override fun isModelDownloaded(modelId: String): Boolean =
        downloadedModelIds.contains(modelId) || resolveModelFile(modelId) != null

    override fun getModelPath(modelId: String): String? =
        resolveModelFile(modelId)?.absolutePath

    private fun resolveModelFile(modelId: String): File? {
        val dir = modelsDir
        val candidates = listOf(
            File(dir, "$modelId.gguf"),
            File(dir, "$modelId.Q4_0.gguf"),
            File(dir, "$modelId.Q4_K_M.gguf"),
            File(dir, modelId)
        )
        candidates.firstOrNull { it.isFile && it.length() > 0L }?.let { return it }
        return dir.listFiles()
            ?.firstOrNull { f ->
                f.isFile && f.extension.equals("gguf", true) &&
                    (f.nameWithoutExtension == modelId || f.name == modelId)
            }
    }

    override fun getActiveBackend(): String = LlamaBridge.getBackendInfoSafe()

    override fun cancelDownload(modelId: String) {
        cancelFlags[modelId] = true
        AppLogger.d("Download", "cancel requested: $modelId")
        // Keep .part so a later re-download can resume; only wipe tiny junk
        File(modelsDir, "$modelId.tmp").delete()
    }

    override suspend fun downloadModel(
        modelId: String,
        downloadUrl: String
    ): Flow<Float> = flow {
        cancelFlags[modelId] = false
        val dir = modelsDir.also { it.mkdirs() }
        val destFile = File(dir, "$modelId.gguf")
        val tempFile = File(dir, "$modelId.gguf.part")

        AppLogger.d("Download", "Starting download: $modelId")
        AppLogger.d("Download", "URL: $downloadUrl")
        AppLogger.d("Download", "Destination: ${destFile.absolutePath}")

        val hfToken = AppPreferences.getHfToken(context)
        var attempt = 0
        var lastError: Throwable? = null

        try {
            while (attempt < MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                if (cancelFlags[modelId] == true) error("Download cancelled")

                val resumeFrom = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
                if (resumeFrom > 0L) {
                    AppLogger.d("Download", "attempt=$attempt resumeFrom=$resumeFrom")
                } else {
                    AppLogger.d("Download", "attempt=$attempt fresh start")
                }

                try {
                    val result = downloadOnce(
                        modelId = modelId,
                        downloadUrl = downloadUrl,
                        tempFile = tempFile,
                        resumeFrom = resumeFrom,
                        hfToken = hfToken,
                        onProgress = { p -> emit(p) }
                    )
                    // Success path
                    if (destFile.exists()) destFile.delete()
                    if (!tempFile.renameTo(destFile)) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                    downloadedModelIds.add(modelId)
                    refreshModels()
                    emit(1f)
                    AppLogger.d("Download", "$modelId complete! ${result} bytes")
                    return@flow
                } catch (e: CancelledDownload) {
                    tempFile.delete()
                    throw e
                } catch (e: Exception) {
                    if (cancelFlags[modelId] == true || isCancelMessage(e)) {
                        // User cancel — drop partial to free space
                        tempFile.delete()
                        error("Download cancelled")
                    }
                    lastError = e
                    val keepPart = isTransientNetworkError(e)
                    AppLogger.e(
                        "Download",
                        "$modelId attempt=$attempt failed (keepPart=$keepPart): ${e.message}",
                        e
                    )
                    if (!keepPart) {
                        // Corrupt / hard error — restart clean
                        tempFile.delete()
                    }
                    attempt++
                    if (attempt >= MAX_ATTEMPTS) break
                    val backoff = min(30_000L, 1_000L * (1L shl (attempt - 1).coerceAtMost(5)))
                    AppLogger.d("Download", "retry in ${backoff}ms…")
                    delay(backoff)
                }
            }
            throw lastError ?: IOException("Download failed after $MAX_ATTEMPTS attempts")
        } finally {
            cancelFlags.remove(modelId)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One HTTP session: follow redirects, stream into tempFile (append if resumeFrom>0).
     * Returns total bytes on disk after this attempt.
     */
    private suspend fun downloadOnce(
        modelId: String,
        downloadUrl: String,
        tempFile: File,
        resumeFrom: Long,
        hfToken: String,
        onProgress: suspend (Float) -> Unit
    ): Long {
        var currentUrl = downloadUrl
        var connection: HttpURLConnection? = null
        var actualResume = resumeFrom

        try {
            for (hop in 0 until 8) {
                currentCoroutineContext().ensureActive()
                if (cancelFlags[modelId] == true) throw CancelledDownload()

                try { connection?.disconnect() } catch (_: Throwable) {}

                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 60_000
                    readTimeout = 120_000 // shorter: fail faster → retry/resume
                    setRequestProperty("User-Agent", "OfflineLLM/1.5 (Android; HF-GGUF)")
                    setRequestProperty("Accept", "application/octet-stream,*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    if (hfToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $hfToken")
                    }
                    if (actualResume > 0L) {
                        setRequestProperty("Range", "bytes=$actualResume-")
                    }
                    requestMethod = "GET"
                }
                c.connect()
                val code = c.responseCode
                AppLogger.d("Download", "hop=$hop code=$code resume=$actualResume")

                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                        ?: error("Redirect without Location (HTTP $code)")
                    c.disconnect()
                    connection = null
                    currentUrl = if (loc.startsWith("http")) loc else {
                        URL(URL(currentUrl), loc).toString()
                    }
                    continue
                }

                if (code == 416 && actualResume > 0L) {
                    // Range not satisfiable — file may already be complete or part corrupt
                    c.disconnect()
                    connection = null
                    AppLogger.d("Download", "HTTP 416 — wiping part and restarting")
                    tempFile.delete()
                    actualResume = 0L
                    currentUrl = downloadUrl
                    continue
                }

                if (code !in 200..299) {
                    val err = try {
                        c.errorStream?.bufferedReader()?.readText()?.take(300)
                    } catch (_: Exception) {
                        null
                    }
                    c.disconnect()
                    error("HTTP $code${err?.let { ": $it" } ?: ""}")
                }

                if (code == 200 && actualResume > 0L) {
                    AppLogger.d("Download", "Server ignored Range; restarting from 0")
                    actualResume = 0L
                    tempFile.delete()
                }
                connection = c
                break
            }

            val conn = connection ?: error("Too many redirects")

            val headerLen = conn.contentLengthLong
            val totalBytes = when {
                headerLen > 0 && actualResume > 0 && conn.responseCode == 206 ->
                    actualResume + headerLen
                headerLen > 0 -> headerLen
                else -> -1L
            }
            AppLogger.d("Download", "Total bytes est: $totalBytes resumeFrom=$actualResume")

            // Emit current progress immediately so UI isn't stuck at 0
            if (totalBytes > 0 && actualResume > 0) {
                onProgress((actualResume.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.999f))
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var totalRead = actualResume
            var lastEmitMs = 0L
            var lastLoggedPct = -1
            var lastBytesForSpeed = totalRead
            var lastSpeedMs = System.currentTimeMillis()

            try {
                BufferedInputStream(conn.inputStream, BUFFER_SIZE).use { input ->
                    BufferedOutputStream(
                        FileOutputStream(tempFile, actualResume > 0L),
                        BUFFER_SIZE
                    ).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (cancelFlags[modelId] == true) throw CancelledDownload()
                            val read = try {
                                input.read(buffer)
                            } catch (io: IOException) {
                                // Partial progress is on disk — caller will retry with resume
                                out.flush()
                                throw io
                            }
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            totalRead += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
                                val progress = if (totalBytes > 0) {
                                    (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.999f)
                                } else 0f
                                onProgress(progress)
                                lastEmitMs = now
                            }
                            if (totalBytes > 0) {
                                val pct = ((totalRead * 100) / totalBytes).toInt()
                                if (pct / 10 != lastLoggedPct / 10) {
                                    val dt = (now - lastSpeedMs).coerceAtLeast(1L)
                                    val db = totalRead - lastBytesForSpeed
                                    val mbps = (db * 1000.0 / dt) / (1024.0 * 1024.0)
                                    AppLogger.d(
                                        "Download",
                                        "$modelId: $pct% ($totalRead / $totalBytes) ~${"%.1f".format(mbps)} MB/s"
                                    )
                                    lastLoggedPct = pct
                                    lastBytesForSpeed = totalRead
                                    lastSpeedMs = now
                                }
                            }
                        }
                        out.flush()
                    }
                }
            } finally {
                try { conn.disconnect() } catch (_: Throwable) {}
            }

            if (totalRead <= 0L) error("Empty download (0 bytes)")

            // Incomplete stream (CDN cut connection without error)
            if (totalBytes > 0 && totalRead < totalBytes) {
                throw IOException(
                    "unexpected end of stream: got $totalRead of $totalBytes bytes"
                )
            }
            return totalRead
        } catch (e: Exception) {
            try { connection?.disconnect() } catch (_: Throwable) {}
            throw e
        }
    }

    override suspend fun deleteModel(modelId: String) {
        withContext(Dispatchers.IO) {
            resolveModelFile(modelId)?.delete()
            File(modelsDir, "$modelId.gguf").delete()
            File(modelsDir, "$modelId.gguf.part").delete()
            File(modelsDir, "$modelId.tmp").delete()
            downloadedModelIds.remove(modelId)
            refreshModels()
        }
    }

    private fun isCancelMessage(e: Throwable): Boolean {
        val m = e.message ?: return e is CancelledDownload
        return e is CancelledDownload || m.contains("cancel", ignoreCase = true)
    }

    private fun isTransientNetworkError(e: Throwable): Boolean {
        if (e is CancelledDownload) return false
        if (e is SocketTimeoutException || e is SocketException) return true
        if (e is IOException) {
            val m = (e.message ?: "").lowercase()
            return m.contains("unexpected end of stream") ||
                m.contains("connection reset") ||
                m.contains("broken pipe") ||
                m.contains("software caused connection abort") ||
                m.contains("timeout") ||
                m.contains("failed to connect") ||
                m.contains("stream was reset") ||
                m.contains("got ") && m.contains(" of ") // our incomplete marker
        }
        return false
    }

    /** Typed cancel so we don't treat it as network retry. */
    private class CancelledDownload : IOException("Download cancelled")

    companion object {
        private const val BUFFER_SIZE = 1 * 1024 * 1024
        private const val EMIT_INTERVAL_MS = 750L
        /** Enough to survive flaky mobile CDN cuts */
        private const val MAX_ATTEMPTS = 12
    }
}
