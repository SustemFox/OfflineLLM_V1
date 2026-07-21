package com.example.offlinellm.domain.repository

import com.example.offlinellm.domain.model.LlmModel
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun getAvailableModels(): List<LlmModel>
    fun getDownloadedModels(): List<LlmModel>
    suspend fun downloadModel(modelId: String, downloadUrl: String): Flow<Float>
    fun cancelDownload(modelId: String)
    fun isModelDownloaded(modelId: String): Boolean
    fun getModelPath(modelId: String): String?
    suspend fun deleteModel(modelId: String)
    fun refreshModels()
    fun getActiveBackend(): String  // e.g. "Hexagon NPU", "Vulkan GPU", "CPU"
}
