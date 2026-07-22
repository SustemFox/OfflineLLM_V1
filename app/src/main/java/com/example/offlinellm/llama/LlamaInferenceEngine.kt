package com.example.offlinellm.llama

import com.example.offlinellm.data.local.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * High-level Kotlin wrapper around native llama.cpp inference.
 */
class LlamaInferenceEngine(
    private val modelPath: String,
    private val nCtx: Int = 2048,
    private val nGpuLayers: Int = 0,       // CPU-safe default (GPU offload only if backend supports)
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.7f,
    private val topP: Float = 0.9f
) {
    private var contextPtr: Long = 0L

    val activeBackend: String
        get() = LlamaBridge.getBackendInfoSafe()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) return@withContext
        if (!LlamaBridge.load()) {
            throw IllegalStateException(
                "Failed to load native llama.cpp libraries" +
                    (LlamaBridge.lastError?.let { ": $it" } ?: "")
            )
        }
        AppLogger.d("Engine", "createContext path=$modelPath nCtx=$nCtx gpuLayers=$nGpuLayers threads=$threads")
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
        LlamaBridge.runInference(contextPtr, prompt, systemPrompt, maxTokens, temperature, topP)
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
            val acc = StringBuilder()
            LlamaBridge.runInferenceStream(
                contextPtr,
                prompt,
                systemPrompt,
                maxTokens,
                temperature,
                topP,
                LlamaBridge.TokenCallback { token ->
                    if (!isActive) return@TokenCallback
                    acc.append(token)
                    trySend(acc.toString())
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
