package com.example.offlinellm.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Central app preferences (persist across process death).
 */
object AppPreferences {
    private const val PREFS = "offlinellm_prefs"

    private const val KEY_LOGS_ENABLED = "logs_enabled"
    private const val KEY_LOGS_PANEL = "logs_panel_expanded"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_HF_TOKEN = "hf_token"
    private const val KEY_LAST_HF_URL = "last_hf_url"
    private const val KEY_SELECTED_MODEL = "selected_model_id"
    private const val KEY_ACCEL_PREF = "accel_pref"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_TEMPERATURE = "llm_temperature"
    private const val KEY_TOP_P = "llm_top_p"
    private const val KEY_MAX_TOKENS = "llm_max_tokens"
    private const val KEY_N_CTX = "llm_n_ctx"
    private const val KEY_THREADS = "llm_threads"
    private const val KEY_SYSTEM_PROMPT = "llm_system_prompt"
    private const val KEY_SHOW_THINKING = "llm_show_thinking"
    private const val KEY_REPEAT_PENALTY = "llm_repeat_penalty"
    private const val KEY_FREQ_PENALTY = "llm_freq_penalty"
    private const val KEY_N_GPU_LAYERS = "llm_n_gpu_layers"

    const val DEFAULT_SYSTEM_PROMPT =
        "Ты краткий оффлайн-помощник. Ответь на языке пользователя одним-двумя короткими абзацами. " +
            "Не повторяй текст. Без XML-тегов и без ролевых игр. /no_think"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLogsEnabled(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_LOGS_ENABLED, true)

    fun setLogsEnabled(ctx: Context, enabled: Boolean) {
        p(ctx).edit().putBoolean(KEY_LOGS_ENABLED, enabled).apply()
        AppLogger.setEnabled(enabled)
    }

    fun isLogsPanelExpanded(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_LOGS_PANEL, false)

    fun setLogsPanelExpanded(ctx: Context, expanded: Boolean) {
        p(ctx).edit().putBoolean(KEY_LOGS_PANEL, expanded).apply()
    }

    fun isDarkMode(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(ctx: Context, dark: Boolean) {
        p(ctx).edit().putBoolean(KEY_DARK_MODE, dark).apply()
    }

    fun getHfToken(ctx: Context): String =
        p(ctx).getString(KEY_HF_TOKEN, "") ?: ""

    fun setHfToken(ctx: Context, token: String) {
        p(ctx).edit().putString(KEY_HF_TOKEN, token.trim()).apply()
    }

    fun getLastHfUrl(ctx: Context): String =
        p(ctx).getString(KEY_LAST_HF_URL, "") ?: ""

    fun setLastHfUrl(ctx: Context, url: String) {
        p(ctx).edit().putString(KEY_LAST_HF_URL, url.trim()).apply()
    }

    fun getSelectedModelId(ctx: Context): String? =
        p(ctx).getString(KEY_SELECTED_MODEL, null)

    fun setSelectedModelId(ctx: Context, id: String?) {
        p(ctx).edit().putString(KEY_SELECTED_MODEL, id).apply()
    }

    fun getAccelPref(ctx: Context): String {
        val raw = p(ctx).getString(KEY_ACCEL_PREF, "auto") ?: "auto"
        // Vulkan on Adreno 640 (OP7) SIGSEGVs in libvulkan CreateFence — refuse sticky pref
        if (raw == "vulkan") {
            p(ctx).edit().putString(KEY_ACCEL_PREF, "auto").apply()
            return "auto"
        }
        return raw
    }

    fun setAccelPref(ctx: Context, pref: String) {
        val v = if (pref == "vulkan") {
            // Keep UI selection possible only if a future build re-enables Vulkan;
            // for now coerce to auto so resolveNGpuLayers stays 0.
            "auto"
        } else pref
        p(ctx).edit().putString(KEY_ACCEL_PREF, v).apply()
    }

    fun getServerPort(ctx: Context): Int =
        p(ctx).getInt(KEY_SERVER_PORT, 8080).coerceIn(1024, 65535)

    fun setServerPort(ctx: Context, port: Int) {
        p(ctx).edit().putInt(KEY_SERVER_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun getTemperature(ctx: Context): Float =
        p(ctx).getFloat(KEY_TEMPERATURE, 0.65f)

    fun setTemperature(ctx: Context, v: Float) {
        p(ctx).edit().putFloat(KEY_TEMPERATURE, v.coerceIn(0.01f, 2f)).apply()
    }

    fun getTopP(ctx: Context): Float =
        p(ctx).getFloat(KEY_TOP_P, 0.85f)

    fun setTopP(ctx: Context, v: Float) {
        p(ctx).edit().putFloat(KEY_TOP_P, v.coerceIn(0.05f, 1f)).apply()
    }

    fun getMaxTokens(ctx: Context): Int =
        p(ctx).getInt(KEY_MAX_TOKENS, 128).coerceIn(16, 4096)

    fun setMaxTokens(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_MAX_TOKENS, v.coerceIn(16, 4096)).apply()
    }

    fun getNCtx(ctx: Context): Int =
        p(ctx).getInt(KEY_N_CTX, 2048).coerceIn(512, 8192)

    fun setNCtx(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_N_CTX, v.coerceIn(512, 8192)).apply()
    }

    fun getThreads(ctx: Context): Int {
        val def = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return p(ctx).getInt(KEY_THREADS, def).coerceIn(1, 16)
    }

    fun setThreads(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_THREADS, v.coerceIn(1, 16)).apply()
    }

    fun getSystemPrompt(ctx: Context): String {
        val stored = p(ctx).getString(KEY_SYSTEM_PROMPT, null)?.takeIf { it.isNotBlank() }
            ?: return DEFAULT_SYSTEM_PROMPT
        // Older builds forced <think> in the system prompt — breaks tiny models and leaks tags in UI
        if (stored.contains("<think>") ||
            stored.contains("</think>") ||
            stored.contains("блоке <think") ||
            stored.contains("chain-of-thought")
        ) {
            return DEFAULT_SYSTEM_PROMPT
        }
        return stored
    }

    fun setSystemPrompt(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_SYSTEM_PROMPT, v).apply()
    }

    fun isShowThinking(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_SHOW_THINKING, true)

    fun setShowThinking(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean(KEY_SHOW_THINKING, v).apply()
    }

    fun getRepeatPenalty(ctx: Context): Float =
        p(ctx).getFloat(KEY_REPEAT_PENALTY, 1.28f)

    fun setRepeatPenalty(ctx: Context, v: Float) {
        p(ctx).edit().putFloat(KEY_REPEAT_PENALTY, v.coerceIn(1.0f, 2.0f)).apply()
    }

    fun getFrequencyPenalty(ctx: Context): Float =
        p(ctx).getFloat(KEY_FREQ_PENALTY, 0.35f)

    fun setFrequencyPenalty(ctx: Context, v: Float) {
        p(ctx).edit().putFloat(KEY_FREQ_PENALTY, v.coerceIn(0f, 1f)).apply()
    }

    fun getNGpuLayers(ctx: Context): Int =
        p(ctx).getInt(KEY_N_GPU_LAYERS, 99).coerceIn(0, 999)

    fun setNGpuLayers(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_N_GPU_LAYERS, v.coerceIn(0, 999)).apply()
    }

    /**
     * Resolve effective n_gpu_layers from accel pref + slider.
     *
     * "auto" stays on **CPU** (ngl=0): on many Adreno devices (e.g. SD855 / OP7)
     * ggml-Vulkan can SIGSEGV inside libvulkan CreateFence and kill the process —
     * that is not catchable from Kotlin. User must explicitly pick OpenCL/Vulkan.
     */
    fun resolveNGpuLayers(ctx: Context): Int {
        return when (getAccelPref(ctx)) {
            "cpu", "auto" -> 0
            "opencl", "vulkan" -> getNGpuLayers(ctx).coerceAtLeast(0)
            else -> 0
        }
    }
}
