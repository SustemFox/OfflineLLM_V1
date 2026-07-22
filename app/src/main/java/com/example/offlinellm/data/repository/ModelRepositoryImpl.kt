package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
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

        // Also mark recommended ids if matching files exist under alternate names
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
            File(dir, modelId) // already includes extension edge-case
        )
        candidates.firstOrNull { it.isFile && it.length() > 0L }?.let { return it }

        // Local scan may use filename-without-ext as id
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
        // Best-effort: remove temp
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

        AppLogger.d("Download", "Starting download: $modelId")
        AppLogger.d("Download", "URL: $downloadUrl")
        AppLogger.d("Download", "Destination: ${destFile.absolutePath}")

        try {
            if (tempFile.exists()) tempFile.delete()

            var currentUrl = downloadUrl
            var conn: HttpURLConnection? = null
            // Follow redirects manually (HF often 302) and keep HTTPS
            repeat(8) { hop ->
                currentCoroutineContext().ensureActive()
                if (cancelFlags[modelId] == true) error("Download cancelled")

                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty(
                        "User-Agent",
                        "OfflineLLM_V1/1.0 (Android; llama.cpp)"
                    )
                    setRequestProperty("Accept", "*/*")
                    // Avoid compressed transfer so Content-Length matches bytes written
                    setRequestProperty("Accept-Encoding", "identity")
                    requestMethod = "GET"
                }
                c.connect()
                val code = c.responseCode
                AppLogger.d("Download", "hop=$hop code=$code url=$currentUrl")
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                        ?: error("Redirect without Location (HTTP $code)")
                    c.disconnect()
                    currentUrl = if (loc.startsWith("http")) loc else {
                        URL(URL(currentUrl), loc).toString()
                    }
                    return@repeat
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
                conn = c
                return@repeat
            }
            val connection = conn ?: error("Too many redirects")

            val headerLen = connection.contentLengthLong
            val totalBytes = when {
                headerLen > 0 -> headerLen
                else -> -1L
            }
            AppLogger.d("Download", "Total bytes header: $totalBytes")

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var read: Int
                    var totalRead = 0L
                    var lastEmitMs = 0L
                    var lastLoggedPct = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (cancelFlags[modelId] == true) {
                            error("Download cancelled")
                        }
                        read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        val progress = if (totalBytes > 0) {
                            (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.999f)
                        } else {
                            // Unknown size: keep indeterminate (0f) but still "alive"
                            0f
                        }
                        if (now - lastEmitMs >= 200 || progress >= 0.999f || totalBytes <= 0 && totalRead % (5L * 1024 * 1024) < buffer.size) {
                            emit(progress)
                            lastEmitMs = now
                        }
                        if (totalBytes > 0) {
                            val pct = (progress * 100).toInt()
                            if (pct / 5 != lastLoggedPct / 5) {
                                AppLogger.d(
                                    "Download",
                                    "$modelId: $pct% ($totalRead / $totalBytes)"
                                )
                                lastLoggedPct = pct
                            }
                        } else if (totalRead % (25L * 1024 * 1024) < buffer.size) {
                            AppLogger.d(
                                "Download",
                                "$modelId: $totalRead bytes (size unknown)"
                            )
                        }
                    }
                    output.flush()
                    AppLogger.d("Download", "$modelId complete! $totalRead bytes written")
                    if (totalRead <= 0L) error("Empty download (0 bytes)")
                }
            }
            connection.disconnect()

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
            tempFile.delete()
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
}
