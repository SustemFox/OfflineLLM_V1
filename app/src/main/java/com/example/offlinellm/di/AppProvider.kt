package com.example.offlinellm.di

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.repository.FakeLlmRepository
import com.example.offlinellm.data.repository.LocalLlmRepository
import com.example.offlinellm.data.repository.ModelRepositoryImpl
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.domain.repository.ModelRepository
import com.example.offlinellm.llama.LlamaBridge
import kotlinx.coroutines.runBlocking

/**
 * Simple dependency injection.
 * Switches between fake (dev) and real (production) implementations.
 */
object AppProvider {
    lateinit var context: Context
    lateinit var llmRepository: LlmRepository
    lateinit var modelRepository: ModelRepository
    var isRealEngine: Boolean = false
        private set

    /** Whether to use fake implementations (UI dev without model). */
    var useFake: Boolean = true

    private fun ensureModelRepository(ctx: Context) {
        if (!::modelRepository.isInitialized || modelRepository !is ModelRepositoryImpl) {
            modelRepository = ModelRepositoryImpl(ctx.applicationContext)
        }
    }

    private suspend fun releasePreviousLlm() {
        if (!::llmRepository.isInitialized) return
        val prev = llmRepository
        if (prev is LocalLlmRepository) {
            try {
                prev.release()
                AppLogger.d("AppProvider", "Released previous LocalLlmRepository")
            } catch (t: Throwable) {
                AppLogger.e("AppProvider", "release previous LLM: ${t.message}", t)
            }
        }
    }

    /** Initialize with real llama.cpp engine. Throws if native/model load fails. */
    suspend fun initRealEngine(context: Context, modelPath: String) {
        this.context = context.applicationContext
        if (!LlamaBridge.load()) {
            throw IllegalStateException(
                "Native llama libraries unavailable" +
                    (LlamaBridge.lastError?.let { ": $it" } ?: "")
            )
        }
        // Drop previous native context before allocating a new one
        releasePreviousLlm()
        val repo = LocalLlmRepository(this.context, modelPath)
        // Eager load so failures surface here (not on first Send)
        repo.ensureReady()
        llmRepository = repo
        ensureModelRepository(this.context)
        isRealEngine = true
        useFake = false
        AppLogger.d(
            "AppProvider",
            "Real engine ready path=$modelPath backend=${LlamaBridge.backendName}"
        )
    }

    /** Initialize with fake implementations (for UI development). */
    fun initFake(context: Context) {
        this.context = context.applicationContext
        // Best-effort release if leaving a real engine (initFake is non-suspend for cold start)
        if (::llmRepository.isInitialized && llmRepository is LocalLlmRepository) {
            try {
                runBlocking { releasePreviousLlm() }
            } catch (t: Throwable) {
                AppLogger.e("AppProvider", "initFake release: ${t.message}", t)
            }
        }
        llmRepository = FakeLlmRepository()
        ensureModelRepository(this.context)
        isRealEngine = false
        useFake = true
    }

    /** Check if native libraries are available on this device. */
    fun isNativeAvailable(): Boolean = try {
        LlamaBridge.load()
    } catch (_: Throwable) {
        false
    }
}
