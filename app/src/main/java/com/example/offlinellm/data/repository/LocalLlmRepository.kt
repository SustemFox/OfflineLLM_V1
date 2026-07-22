package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.llama.LlamaInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * Real implementation of LlmRepository using llama.cpp native engine.
 */
class LocalLlmRepository(
    private val context: Context,
    modelPath: String,
    nCtx: Int = 2048,
    nGpuLayers: Int = 0
) : LlmRepository {

    private val engine = LlamaInferenceEngine(
        modelPath = modelPath,
        nCtx = nCtx,
        nGpuLayers = nGpuLayers,
        threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        maxTokens = 512,
        temperature = 0.7f,
        topP = 0.9f
    )

    @Volatile
    private var loaded = false

    val backendInfo: String
        get() = engine.activeBackend

    /** Block until engine is ready or throw. Call from IO / initRealEngine. */
    fun ensureReady() {
        if (loaded) return
        runBlocking {
            engine.load()
        }
        loaded = true
    }

    private fun loadEngine() {
        if (!loaded) {
            runBlocking {
                engine.load()
            }
            loaded = true
        }
    }

    override suspend fun generateResponse(prompt: String): Flow<String> {
        return try {
            loadEngine()
            engine.generateStream(
                prompt = prompt,
                systemPrompt = loadSystemPrompt()
            )
        } catch (t: Throwable) {
            AppLogger.e("LocalLlm", "generateResponse setup failed: ${t.message}", t)
            flow {
                throw t
            }
        }
    }

    override suspend fun loadSystemPrompt(): String {
        return "Ты — локальный оффлайн-ассистент, работающий на телефоне. " +
            "Отвечай полезно, кратко и по делу. " +
            "Все вычисления выполняются на устройстве — данные никуда не отправляются."
    }

    suspend fun release() {
        if (loaded) {
            engine.release()
            loaded = false
        }
    }
}
