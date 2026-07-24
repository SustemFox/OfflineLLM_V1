package com.example.offlinellm.domain.repository

import kotlinx.coroutines.flow.Flow

interface LlmRepository {
    /**
     * @param systemPrompt if non-null/non-blank, used as ChatML system; otherwise prefs default.
     */
    suspend fun generateResponse(prompt: String, systemPrompt: String? = null): Flow<String>
    suspend fun loadSystemPrompt(): String
}
