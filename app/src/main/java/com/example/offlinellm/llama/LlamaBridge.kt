package com.example.offlinellm.llama

import com.example.offlinellm.data.local.AppLogger

object LlamaBridge {

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var backendLabel: String = "unavailable"

    val isLoaded: Boolean get() = loaded
    val lastError: String? get() = loadError
    val backendName: String get() = backendLabel

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        loadError = null

        val attempts = listOf(
            listOf("offlinellm_jni"),
            listOf("ggml-base", "ggml-cpu", "ggml", "llama", "offlinellm_jni"),
            listOf("ggml-base", "ggml-cpu", "llama", "offlinellm_jni"),
        )

        var lastErr: String? = null
        for (libs in attempts) {
            val err = tryLoadSequence(libs)
            if (err == null) {
                loaded = true
                backendLabel = try {
                    getBackendInfo().ifBlank { "CPU" }
                } catch (_: Throwable) {
                    "CPU"
                }
                AppLogger.d("LlamaBridge", "Native OK backend=$backendLabel via $libs")
                return true
            }
            lastErr = err
            AppLogger.e("LlamaBridge", "load attempt failed $libs: $err")
        }

        loadError = lastErr ?: "unknown native load failure"
        backendLabel = "unavailable"
        AppLogger.e("LlamaBridge", "All load attempts failed: $loadError")
        return false
    }

    private fun tryLoadSequence(libs: List<String>): String? {
        try {
            System.loadLibrary("OpenCL")
            AppLogger.d("LlamaBridge", "loaded: OpenCL (ICD)")
        } catch (t: Throwable) {
            AppLogger.d("LlamaBridge", "OpenCL ICD not loaded: ${t.message}")
        }
        for (opt in listOf("ggml-hexagon", "ggml-htp-v75", "ggml-htp-v73", "ggml-opencl")) {
            try {
                System.loadLibrary(opt)
                AppLogger.d("LlamaBridge", "optional loaded: $opt")
            } catch (_: Throwable) {
            }
        }
        for (name in libs) {
            try {
                System.loadLibrary(name)
                AppLogger.d("LlamaBridge", "loaded: $name")
            } catch (t: Throwable) {
                return "lib$name: ${t.message}"
            }
        }
        return try {
            getBackendInfo()
            null
        } catch (t: Throwable) {
            "JNI probe failed: ${t.message}"
        }
    }

    fun getBackendInfoSafe(): String {
        if (!load()) return "Native unavailable${loadError?.let { ": $it" } ?: ""}"
        return try {
            getBackendInfo()
        } catch (_: Throwable) {
            backendLabel
        }
    }

    @JvmStatic external fun createContext(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        threads: Int
    ): Long

    @JvmStatic external fun runInference(
        contextPtr: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        frequencyPenalty: Float
    ): String

    @JvmStatic external fun runInferenceStream(
        contextPtr: Long,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        frequencyPenalty: Float,
        callback: TokenCallback
    )

    @JvmStatic external fun releaseContext(contextPtr: Long)

    @JvmStatic external fun getBackendInfo(): String

    @JvmStatic external fun isOpenClBuilt(): Boolean

    @JvmStatic external fun isVulkanBuilt(): Boolean

    @JvmStatic external fun getLoadedGpuLayers(contextPtr: Long): Int

    /** Ask in-flight generate_loop to stop after the current token. */
    @JvmStatic external fun requestCancel()

    @JvmStatic external fun benchmark(contextPtr: Long, pp: Int, tg: Int): String

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    fun requestCancelSafe() {
        if (!loaded) return
        try {
            requestCancel()
        } catch (t: Throwable) {
            AppLogger.e("LlamaBridge", "requestCancel: ${t.message}")
        }
    }
}
