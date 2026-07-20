package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.llama.LlamaInferenceEngine
import kotlinx.coroutines.flow.Flow

/**
 * Real implementation of LlmRepository using llama.cpp native engine.
 *
 * Hardware acceleration (automatic by priority):
 *   1. Hexagon NPU (Snapdragon) — fastest, lowest power
 *   2. Adreno GPU via Vulkan — good speed
 *   3. CPU NEON — fallback, always works
 *
 * Usage:
 *   val repo = LocalLlmRepository(context, "/path/to/model.gguf")
 *   repo.generateResponse("Hello!").collect { token -> println(token) }
 */
class LocalLlmRepository(
    private val context: Context,
    modelPath: String,
    nCtx: Int = 4096,
    nGpuLayers: Int = 99
) : LlmRepository {

    private val engine = LlamaInferenceEngine(
        modelPath = modelPath,
        nCtx = nCtx,
        nGpuLayers = nGpuLayers,
        threads = 4,
        maxTokens = 2048,
        temperature = 0.7f,
        topP = 0.9f
    )

    private var loaded = false

    /** The active accelerator backend. */
    val backendInfo: String by lazy {
        loadEngine()
        engine.activeBackend
    }

    private fun loadEngine() {
        if (!loaded) {
            kotlinx.coroutines.runBlocking {
                engine.load()
                loaded = true
            }
        }
    }

    override suspend fun generateResponse(prompt: String): Flow<String> {
        loadEngine()
        return engine.generateStream(
            prompt = prompt,
            systemPrompt = loadSystemPrompt()
        )
    }

    override suspend fun loadSystemPrompt(): String {
        return "Ты — локальный оффлайн-ассистент, работающий на телефоне. " +
                "Отвечай полезно, кратко и по делу. " +
                "Все вычисления выполняются на устройстве — данные никуда не отправляются."
    }

    /** Release engine resources when no longer needed. */
    suspend fun release() {
        if (loaded) {
            engine.release()
            loaded = false
        }
    }
}
