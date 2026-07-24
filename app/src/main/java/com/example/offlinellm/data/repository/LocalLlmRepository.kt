package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.llama.LlamaInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class LocalLlmRepository(
    private val context: Context,
    modelPath: String
) : LlmRepository {

    private val engine = LlamaInferenceEngine(
        modelPath = modelPath,
        nCtx = AppPreferences.getNCtx(context),
        nGpuLayers = AppPreferences.resolveNGpuLayers(context),
        threads = AppPreferences.getThreads(context),
        maxTokens = AppPreferences.getMaxTokens(context),
        temperature = AppPreferences.getTemperature(context),
        topP = AppPreferences.getTopP(context),
        repeatPenalty = AppPreferences.getRepeatPenalty(context),
        frequencyPenalty = AppPreferences.getFrequencyPenalty(context)
    )

    @Volatile
    private var loaded = false

    val backendInfo: String
        get() = engine.activeBackend

    suspend fun ensureReady() {
        loadEngine()
    }

    fun applySamplingFromPrefs() {
        engine.updateSampling(
            maxTokens = AppPreferences.getMaxTokens(context),
            temperature = AppPreferences.getTemperature(context),
            topP = AppPreferences.getTopP(context),
            repeatPenalty = AppPreferences.getRepeatPenalty(context),
            frequencyPenalty = AppPreferences.getFrequencyPenalty(context)
        )
    }

    fun applyMaxTokensOverride(maxTokens: Int) {
        engine.updateSampling(
            maxTokens = maxTokens.coerceIn(1, 2048),
            temperature = AppPreferences.getTemperature(context),
            topP = AppPreferences.getTopP(context),
            repeatPenalty = AppPreferences.getRepeatPenalty(context),
            frequencyPenalty = AppPreferences.getFrequencyPenalty(context)
        )
    }

    private suspend fun loadEngine() {
        if (!loaded) {
            withContext(Dispatchers.IO) {
                engine.load()
            }
            loaded = true
        }
        applySamplingFromPrefs()
    }

    override suspend fun generateResponse(
        prompt: String,
        systemPrompt: String?
    ): Flow<String> {
        return try {
            loadEngine()
            val sys = systemPrompt?.takeIf { it.isNotBlank() } ?: loadSystemPrompt()
            // Stream stays on background; UI layer throttles Main updates
            engine.generateStream(
                prompt = prompt,
                systemPrompt = sys
            ).flowOn(Dispatchers.IO)
        } catch (t: Throwable) {
            AppLogger.e("LocalLlm", "generateResponse setup failed: ${t.message}", t)
            flow { throw t }
        }
    }

    override suspend fun loadSystemPrompt(): String {
        return AppPreferences.getSystemPrompt(context)
    }

    suspend fun release() {
        if (loaded) {
            engine.release()
            loaded = false
        } else {
            // Still release native ptr if load failed mid-way / edge cases
            try {
                engine.release()
            } catch (_: Throwable) {
            }
        }
    }
}
