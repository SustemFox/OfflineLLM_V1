package com.example.offlinellm.data.repository

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.llama.LlamaInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

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

    fun ensureReady() {
        if (loaded) return
        runBlocking { engine.load() }
        loaded = true
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

    private fun loadEngine() {
        if (!loaded) {
            runBlocking { engine.load() }
            loaded = true
        }
        applySamplingFromPrefs()
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
        }
    }
}
