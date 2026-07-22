package com.example.offlinellm.di

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.repository.FakeLlmRepository
import com.example.offlinellm.data.repository.LocalLlmRepository
import com.example.offlinellm.data.repository.ModelRepositoryImpl
import com.example.offlinellm.domain.repository.LlmRepository
import com.example.offlinellm.domain.repository.ModelRepository
import com.example.offlinellm.llama.LlamaBridge

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

    /** Initialize with real llama.cpp engine. Throws if native/model load fails. */
    fun initRealEngine(context: Context, modelPath: String) {
        this.context = context
        if (!LlamaBridge.load()) {
            throw IllegalStateException(
                "Native llama libraries unavailable" +
                    (LlamaBridge.lastError?.let { ": $it" } ?: "")
            )
        }
        val repo = LocalLlmRepository(context, modelPath)
        // Eager load so failures surface here (not on first Send)
        repo.ensureReady()
        llmRepository = repo
        modelRepository = ModelRepositoryImpl(context)
        isRealEngine = true
        useFake = false
        AppLogger.d("AppProvider", "Real engine ready path=$modelPath backend=${LlamaBridge.backendName}")
    }

    /** Initialize with fake implementations (for UI development). */
    fun initFake(context: Context) {
        this.context = context
        llmRepository = FakeLlmRepository()
        modelRepository = ModelRepositoryImpl(context)
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
