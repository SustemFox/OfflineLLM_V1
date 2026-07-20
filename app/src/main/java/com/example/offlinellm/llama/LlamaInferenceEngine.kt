package com.example.offlinellm.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * High-level Kotlin wrapper around native llama.cpp inference.
 * Handles model lifecycle, streaming, and context management.
 */
class LlamaInferenceEngine(
    private val modelPath: String,
    private val nCtx: Int = 4096,
    private val nGpuLayers: Int = 99,      // offload all layers to GPU/NPU
    private val threads: Int = 4,
    private val maxTokens: Int = 2048,
    private val temperature: Float = 0.7f,
    private val topP: Float = 0.9f
) {
    private var contextPtr: Long = 0L

    /** The active hardware backend (NPU, Vulkan, or CPU). */
    val activeBackend: String by lazy { getBackend() }

    /** Load the model into memory. Call once before inference. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) return@withContext
        if (!LlamaBridge.load()) {
            throw IllegalStateException("Failed to load native llama.cpp libraries")
        }
        contextPtr = LlamaBridge.createContext(modelPath, nCtx, nGpuLayers, threads)
        if (contextPtr == 0L) {
            throw IllegalStateException("Failed to create llama context. Model: $modelPath")
        }
    }

    /** Synchronous inference — returns full response text. */
    suspend fun generate(
        prompt: String,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        LlamaBridge.runInference(contextPtr, prompt, systemPrompt, maxTokens, temperature, topP)
    }

    /** Streaming inference — emits tokens as they're generated. */
    fun generateStream(
        prompt: String,
        systemPrompt: String = ""
    ): Flow<String> = callbackFlow {
        ensureLoaded()
        LlamaBridge.runInferenceStream(
            contextPtr, prompt, systemPrompt,
            maxTokens, temperature, topP
        ) { token ->
            trySend(token)
        }
        close()
    }

    /** Free model from memory. */
    suspend fun release() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            LlamaBridge.releaseContext(contextPtr)
            contextPtr = 0L
        }
    }

    /** Run a quick benchmark (prompt processing, token generation). */
    suspend fun benchmark(pp: Int = 128, tg: Int = 64): String = withContext(Dispatchers.IO) {
        ensureLoaded()
        LlamaBridge.benchmark(contextPtr, pp, tg)
    }

    private fun getBackend(): String = try {
        LlamaBridge.getBackendInfo()
    } catch (_: Throwable) { "CPU (fallback)" }

    private fun ensureLoaded() {
        if (contextPtr == 0L) throw IllegalStateException("Engine not loaded. Call load() first.")
    }

    protected fun finalize() {
        if (contextPtr != 0L) {
            LlamaBridge.releaseContext(contextPtr)
            contextPtr = 0L
        }
    }
}
