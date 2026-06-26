package com.example.offlinellm.domain.repository

import com.example.offlinellm.domain.model.LlmModel
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun getAvailableModels(): List<LlmModel>
    suspend fun downloadModel(modelId: String): Flow<Float>
    fun isModelDownloaded(modelId: String): Boolean
}
