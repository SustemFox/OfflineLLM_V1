package com.example.offlinellm.data.repository

import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.repository.ModelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class FakeModelRepository : ModelRepository {

    private val models = listOf(
        LlmModel("demo-tiny", "Demo Tiny (fake)", 12_000_000, isDownloaded = true),
        LlmModel("demo-small", "Demo Small (fake)", 120_000_000),
        LlmModel("demo-medium", "Demo Medium (fake)", 700_000_000)
    )

    private val downloadedModels = mutableSetOf("demo-tiny")

    override fun getAvailableModels(): List<LlmModel> {
        return models.map { it.copy(isDownloaded = downloadedModels.contains(it.id)) }
    }

    override suspend fun downloadModel(modelId: String): Flow<Float> = flow {
        val model = models.find { it.id == modelId }
            ?: throw IllegalArgumentException("Model not found: $modelId")

        val steps = 20
        repeat(steps) { step ->
            delay(150)
            val progress = (step + 1) / steps.toFloat()
            emit(progress)
            if (Random.nextFloat() < 0.03f) {
                throw IllegalStateException("Network error simulated")
            }
        }
        downloadedModels.add(modelId)
        emit(1f)
    }

    override fun isModelDownloaded(modelId: String): Boolean {
        return downloadedModels.contains(modelId)
    }
}
