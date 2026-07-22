package com.example.offlinellm.llama

import com.example.offlinellm.data.local.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class LlamaInferenceEngine(
    private val modelPath: String,
    private var nCtx: Int = 2048,
    private var nGpuLayers: Int = 0,
    private var threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    private var maxTokens: Int = 128,
    private var temperature: Float = 0.65f,
    private var topP: Float = 0.85f,
    private var repeatPenalty: Float = 1.25f,
    private var frequencyPenalty: Float = 0.30f
) {
    private var contextPtr: Long = 0L

    val activeBackend: String
        get() = LlamaBridge.getBackendInfoSafe()

    fun updateSampling(
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        frequencyPenalty: Float
    ) {
        this.maxTokens = maxTokens
        this.temperature = temperature
        this.topP = topP
        this.repeatPenalty = repeatPenalty
        this.frequencyPenalty = frequencyPenalty
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) return@withContext
        if (!LlamaBridge.load()) {
            throw IllegalStateException(
                "Failed to load native llama.cpp libraries" +
                    (LlamaBridge.lastError?.let { ": $it" } ?: "")
            )
        }
        AppLogger.d(
            "Engine",
            "createContext path=$modelPath nCtx=$nCtx gpuLayers=$nGpuLayers threads=$threads " +
                "temp=$temperature topP=$topP maxTok=$maxTokens repPen=$repeatPenalty"
        )
        contextPtr = LlamaBridge.createContext(modelPath, nCtx, nGpuLayers, threads)
        if (contextPtr == 0L) {
            throw IllegalStateException("Failed to create llama context for $modelPath")
        }
        AppLogger.d("Engine", "context ready ptr=$contextPtr backend=${LlamaBridge.backendName}")
    }

    suspend fun generate(
        prompt: String,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        LlamaBridge.runInference(
            contextPtr, prompt, systemPrompt, maxTokens, temperature, topP,
            repeatPenalty, frequencyPenalty
        )
    }

    fun generateStream(
        prompt: String,
        systemPrompt: String = ""
    ): Flow<String> = callbackFlow {
        try {
            if (contextPtr == 0L) {
                if (!LlamaBridge.load()) {
                    close(
                        IllegalStateException(
                            "Failed to load native llama.cpp libraries" +
                                (LlamaBridge.lastError?.let { ": $it" } ?: "")
                        )
                    )
                    return@callbackFlow
                }
                contextPtr = LlamaBridge.createContext(modelPath, nCtx, nGpuLayers, threads)
                if (contextPtr == 0L) {
                    close(IllegalStateException("Failed to create llama context for $modelPath"))
                    return@callbackFlow
                }
            }
            // JNI now pushes the full cleaned assistant text each time (not token deltas)
            LlamaBridge.runInferenceStream(
                contextPtr,
                prompt,
                systemPrompt,
                maxTokens,
                temperature,
                topP,
                repeatPenalty,
                frequencyPenalty,
                LlamaBridge.TokenCallback { text ->
                    if (!isActive) return@TokenCallback
                    trySend(text)
                }
            )
            close()
        } catch (t: Throwable) {
            AppLogger.e("Engine", "generateStream failed: ${t.message}", t)
            close(t)
        }
        awaitClose { }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            try {
                LlamaBridge.releaseContext(contextPtr)
            } catch (t: Throwable) {
                AppLogger.e("Engine", "release: ${t.message}", t)
            }
            contextPtr = 0L
        }
    }

    suspend fun benchmark(pp: Int = 128, tg: Int = 64): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        LlamaBridge.benchmark(contextPtr, pp, tg)
    }

    private fun ensureLoaded() {
        if (contextPtr == 0L) {
            throw IllegalStateException("Engine not loaded. Call load() first.")
        }
    }
}
