package com.example.offlinellm.domain.repository

import com.example.offlinellm.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface LlmRepository {
    suspend fun generateResponse(prompt: String): Flow<String>
    suspend fun loadSystemPrompt(): String
}
