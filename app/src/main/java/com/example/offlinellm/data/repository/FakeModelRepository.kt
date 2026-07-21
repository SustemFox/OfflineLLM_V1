package com.example.offlinellm.data.repository

import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.repository.ModelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class FakeModelRepository : ModelRepository {

    private val models = listOf(
        LlmModel("demo-tiny", "Demo Tiny (fake)", 12_000_000, isDownloaded = true, parameterCount = "0.1B"),
        LlmModel("demo-small", "Demo Small (fake)", 120_000_000, parameterCount = "1B"),
        LlmModel("demo-medium", "Demo Medium (fake)", 700_000_000, parameterCount = "7B")
    )

    private val downloadedModels = mutableSetOf("demo-tiny")

    override fun getAvailableModels(): List<LlmModel> =
        models.map { it.copy(isDownloaded = downloadedModels.contains(it.id)) }

    override fun getDownloadedModels(): List<LlmModel> =
        models.filter { downloadedModels.contains(it.id) }

    override fun getActiveBackend(): String = "Fake (CPU)"

    override fun isModelDownloaded(modelId: String): Boolean =
        downloadedModels.contains(modelId)

    override fun getModelPath(modelId: String): String? =
        if (downloadedModels.contains(modelId)) "/fake/$modelId.gguf" else null

    override fun refreshModels() {}

    override fun cancelDownload(modelId: String) {}

    override suspend fun downloadModel(modelId: String, downloadUrl: String): Flow<Float> = flow {
        val steps = 20
        repeat(steps) { step ->
            delay(150)
            emit((step + 1).toFloat() / steps)
            if (Random.nextFloat() < 0.03f) throw IllegalStateException("Simulated network error")
        }
        downloadedModels.add(modelId)
        emit(1f)
    }

    override suspend fun deleteModel(modelId: String) {
        downloadedModels.remove(modelId)
    }
}
