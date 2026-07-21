package com.example.offlinellm.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.offlinellm.data.local.AppLogger
import java.io.File

/**
 * Manages the directory where GGUF model files are stored.
 * Default: app-private storage (no permissions needed).
 * Option: user-selected shared folder via SAF.
 */
object ModelsDirectoryManager {

    private const val PREFS_NAME = "offlinellm_prefs"
    private const val KEY_CUSTOM_PATH = "models_custom_path"
    private const val DEFAULT_DIR = "models"

    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        return prefs!!
    }

    /** Get the active models directory. Creates it if needed. */
    fun getModelsDirectory(context: Context): File {
        val customPath = prefs(context).getString(KEY_CUSTOM_PATH, null)
        if (!customPath.isNullOrBlank()) {
            val dir = File(customPath)
            if (dir.exists() || dir.mkdirs()) {
                AppLogger.d("Storage", "Using custom path: ${dir.absolutePath}")
                return dir
            }
            AppLogger.d("Storage", "Custom path ${dir.absolutePath} does not exist and cannot be created")
        }
        val default = File(context.filesDir, DEFAULT_DIR).also { it.mkdirs() }
        AppLogger.d("Storage", "Using default path: ${default.absolutePath}")
        return default
    }

    /** Set a custom shared folder path. Pass null to reset to default. */
    fun setCustomPath(context: Context, path: String?) {
        AppLogger.d("Storage", "Setting custom path: ${path ?: "null (reset to default)"}")
        prefs(context).edit().putString(KEY_CUSTOM_PATH, path).apply()
    }

    /** Get current custom path (or null if using default). */
    fun getCustomPath(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_PATH, null)

    /** Check if custom shared storage is configured. */
    fun hasCustomPath(context: Context): Boolean =
        !prefs(context).getString(KEY_CUSTOM_PATH, null).isNullOrBlank()

    /** Get human-readable label for the current storage location. */
    fun getStorageLabel(context: Context): String {
        val customPath = getCustomPath(context)
        return if (customPath != null) {
            "📁 $customPath"
        } else {
            "📁 Внутренняя память (по умолчанию)"
        }
    }

    /** Get app-external storage directory (SD card / shared storage). */
    fun getExternalModelsDir(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null)
        return if (externalDir != null) {
            File(externalDir, DEFAULT_DIR).also { it.mkdirs() }
        } else null
    }

    /** Reset to default (app-private) storage. */
    fun resetToDefault(context: Context) {
        setCustomPath(context, null)
    }

    /**
     * Convert a SAF (Storage Access Framework) tree URI to a usable file path.
     * Handles:
     *   content://com.android.externalstorage.documents/tree/primary%3AOfflineLLM
     *     → /storage/emulated/0/OfflineLLM
     *   content://com.android.externalstorage.documents/tree/0123-4567%3AOfflineLLM
     *     → /storage/0123-4567/OfflineLLM  (SD card)
     */
    fun safUriToPath(uri: android.net.Uri): String? {
        // Parse SAF URI directly from the path
        // Format: content://com.android.externalstorage.documents/tree/primary%3AOfflineLLM
        // Or:     content://com.android.externalstorage.documents/tree/0123-4567%3AFolder
        return try {
            val path = uri.path ?: return null
            val treePart = path.substringAfter("/tree/")
                .replace("%3A", ":")
                .replace("%2F", "/")
            val parts = treePart.split(":")
            when {
                parts[0] == "primary" -> {
                    val dir = parts.drop(1).joinToString("/")
                    if (dir.isBlank()) "/storage/emulated/0"
                    else "/storage/emulated/0/$dir"
                }
                else -> {
                    val dir = parts.drop(1).joinToString("/")
                    if (dir.isBlank()) "/storage/${parts[0]}"
                    else "/storage/${parts[0]}/$dir"
                }
            }
        } catch (_: Exception) { null }
    }

    /** Convenience: pass a SAF tree URI, convert to path and set it. */
    fun setCustomPathFromSafUri(context: Context, uri: android.net.Uri): Boolean {
        val path = safUriToPath(uri) ?: return false
        setCustomPath(context, path)
        return true
    }
}
