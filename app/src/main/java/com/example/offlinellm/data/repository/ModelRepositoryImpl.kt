package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.repository.ModelRepository
import com.example.offlinellm.llama.LlamaBridge
import com.example.offlinellm.llama.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ModelRepositoryImpl(
    private val context: Context
) : ModelRepository {

    private val modelsDir: File = ModelLoader.getModelsDirectory(context)
    private var availableModels: List<LlmModel> = emptyList()
    private var downloadedModelIds: MutableSet<String> = mutableSetOf()

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

        // Scan downloaded files
        val localModels = ModelLoader.scanLocalModels(context)
        localModels.forEach { downloadedModelIds.add(it.id) }

        // Merge with recommended
        val recommended = ModelLoader.getRecommendedModels()
        val allModels = (recommended + localModels).distinctBy { it.id }.map { info ->
            LlmModel(
                id = info.id,
                name = info.name,
                sizeBytes = info.fileSizeBytes,
                downloadUrl = info.downloadUrl,
                isDownloaded = downloadedModelIds.contains(info.id) || info.filePath.isNotEmpty()
            )
        }
        availableModels = allModels
    }

    override fun isModelDownloaded(modelId: String): Boolean =
        downloadedModelIds.contains(modelId) ||
        File(modelsDir, "$modelId.gguf").exists() ||
        File(modelsDir, "$modelId.Q4_0.gguf").exists()

    override fun getModelPath(modelId: String): String? {
        val candidates = listOf(
            File(modelsDir, "$modelId.gguf"),
            File(modelsDir, "$modelId.Q4_0.gguf")
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }

    override fun getActiveBackend(): String = try {
        LlamaBridge.load()
        LlamaBridge.getBackendInfo()
    } catch (_: Throwable) { "CPU (fallback)" }

    override suspend fun downloadModel(
        modelId: String,
        downloadUrl: String
    ): Flow<Float> = flow {
        val destFile = File(modelsDir, "$modelId.gguf")
        val tempFile = File(modelsDir, "$modelId.tmp")

        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()

            val totalBytes = conn.contentLengthLong
            val input = conn.inputStream
            val output = tempFile.outputStream()
            val buffer = ByteArray(65536)
            var read: Int
            var totalRead = 0L

            while (input.read(buffer).also { read = it } > 0) {
                output.write(buffer, 0, read)
                totalRead += read
                val progress = if (totalBytes > 0)
                    (totalRead.toFloat() / totalBytes).coerceAtMost(1f)
                else 0f
                emit(progress)
            }
            input.close()
            output.close()

            tempFile.renameTo(destFile)
            downloadedModelIds.add(modelId)
            refreshModels()
            emit(1f)

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    override suspend fun deleteModel(modelId: String) {
        withContext(Dispatchers.IO) {
            val file = getModelPath(modelId)?.let { File(it) }
            file?.delete()
            downloadedModelIds.remove(modelId)
            refreshModels()
        }
    }
}
