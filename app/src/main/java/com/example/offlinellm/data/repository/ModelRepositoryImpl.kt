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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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
        File(modelsDir, "$modelId.tmp").delete()
        File(modelsDir, "$modelId.gguf.part").delete()
    }

    override suspend fun downloadModel(
        modelId: String,
        downloadUrl: String
    ): Flow<Float> = flow {
        cancelFlags[modelId] = false
        val dir = modelsDir.also { it.mkdirs() }
        val destFile = File(dir, "$modelId.gguf")
        val tempFile = File(dir, "$modelId.gguf.part")

        var resumeFrom = 0L
        if (tempFile.exists() && tempFile.length() > 0L) {
            resumeFrom = tempFile.length()
            AppLogger.d("Download", "Resuming from $resumeFrom bytes")
        }

        AppLogger.d("Download", "Starting download: $modelId")
        AppLogger.d("Download", "URL: $downloadUrl")
        AppLogger.d("Download", "Destination: ${destFile.absolutePath}")

        val hfToken = AppPreferences.getHfToken(context)

        try {
            var currentUrl = downloadUrl
            var connection: HttpURLConnection? = null

            // Follow redirects manually; STOP on first 2xx (do NOT return@repeat — that continues the loop!)
            for (hop in 0 until 8) {
                currentCoroutineContext().ensureActive()
                if (cancelFlags[modelId] == true) error("Download cancelled")

                // Close previous failed hop connection if any
                try { connection?.disconnect() } catch (_: Throwable) {}

                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 60_000
                    readTimeout = 300_000
                    setRequestProperty(
                        "User-Agent",
                        "OfflineLLM/1.5 (Android; HF-GGUF)"
                    )
                    setRequestProperty("Accept", "application/octet-stream,*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    if (hfToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $hfToken")
                    }
                    if (resumeFrom > 0L) {
                        setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    requestMethod = "GET"
                }
                c.connect()
                val code = c.responseCode
                AppLogger.d("Download", "hop=$hop code=$code url=$currentUrl")

                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                        ?: error("Redirect without Location (HTTP $code)")
                    c.disconnect()
                    connection = null
                    currentUrl = if (loc.startsWith("http")) loc else {
                        URL(URL(currentUrl), loc).toString()
                    }
                    continue // follow redirect
                }

                if (code !in 200..299) {
                    val err = try {
                        c.errorStream?.bufferedReader()?.readText()?.take(300)
                    } catch (_: Exception) {
                        null
                    }
                    c.disconnect()
                    connection = null
                    if (resumeFrom > 0 && code == 416) {
                        resumeFrom = 0L
                        tempFile.delete()
                        error("Range not satisfiable — retry without resume")
                    }
                    error("HTTP $code${err?.let { ": $it" } ?: ""}")
                }

                // Success 200/206 — use this connection for the body
                if (code == 200 && resumeFrom > 0L) {
                    // Server ignored Range — restart from 0
                    AppLogger.d("Download", "Server ignored Range; restarting from 0")
                    resumeFrom = 0L
                    tempFile.delete()
                }
                connection = c
                break // CRITICAL: leave redirect loop and stream the body
            }

            val conn = connection ?: error("Too many redirects")

            val headerLen = conn.contentLengthLong
            val totalBytes = when {
                headerLen > 0 && resumeFrom > 0 && conn.responseCode == 206 ->
                    resumeFrom + headerLen
                headerLen > 0 -> headerLen
                else -> -1L
            }
            AppLogger.d("Download", "Total bytes est: $totalBytes resumeFrom=$resumeFrom")

            val buffer = ByteArray(BUFFER_SIZE)
            BufferedInputStream(conn.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(
                    FileOutputStream(tempFile, resumeFrom > 0L),
                    BUFFER_SIZE
                ).use { out ->
                    var read: Int
                    var totalRead = resumeFrom
                    var lastEmitMs = 0L
                    var lastLoggedPct = -1
                    var lastBytesForSpeed = totalRead
                    var lastSpeedMs = System.currentTimeMillis()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelFlags[modelId] == true) {
                            error("Download cancelled")
                        }
                        read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= EMIT_INTERVAL_MS || totalBytes <= 0) {
                            val progress = if (totalBytes > 0) {
                                (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.999f)
                            } else {
                                0f
                            }
                            emit(progress)
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
                    AppLogger.d("Download", "$modelId complete! $totalRead bytes written")
                    if (totalRead <= 0L) error("Empty download (0 bytes)")
                }
            }
            try { conn.disconnect() } catch (_: Throwable) {}

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            downloadedModelIds.add(modelId)
            refreshModels()
            emit(1f)
        } catch (e: Exception) {
            AppLogger.e("Download", "$modelId FAILED: ${e.message}", e)
            if (e.message?.contains("cancel", ignoreCase = true) == true) {
                tempFile.delete()
            }
            throw e
        } finally {
            cancelFlags.remove(modelId)
        }
    }.flowOn(Dispatchers.IO)

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

    companion object {
        /** 1 MiB chunks — fewer syscalls than 256 KiB */
        private const val BUFFER_SIZE = 1 * 1024 * 1024
        /** UI progress throttle */
        private const val EMIT_INTERVAL_MS = 750L
    }
}
