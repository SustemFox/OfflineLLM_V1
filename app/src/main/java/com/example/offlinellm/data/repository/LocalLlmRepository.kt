package com.example.offlinellm.data.repository

import com.example.offlinellm.domain.repository.LlmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stub for a real local LLM backend (e.g., llama.cpp via JNI, MediaPipe, ONNX Runtime).
 * Replace [FakeLlmRepository] with this implementation once the native engine is ready.
 */
class LocalLlmRepository : LlmRepository {

    override suspend fun generateResponse(prompt: String): Flow<String> = flow {
        throw NotImplementedError("Real local LLM inference is not wired yet. Use FakeLlmRepository for now.")
    }

    override suspend fun loadSystemPrompt(): String {
        return "Ты — локальный оффлайн-ассистент."
    }
}
