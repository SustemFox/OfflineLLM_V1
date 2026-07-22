package com.example.offlinellm.data.local

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Models storage.
 *
 * - Default: app-private filesDir/models (always writable, best for llama mmap).
 * - Optional SAF tree: user-picked folder. All I/O goes through DocumentFile /
 *   ContentResolver — never raw java.io.File on /storage/emulated/0/... (EACCES on API 29+).
 * - For native llama.cpp load we materialize (or reuse) a private cache copy under
 *   filesDir/models_cache when the active store is SAF.
 */
object ModelsDirectoryManager {

    private const val PREFS_NAME = "offlinellm_prefs"
    private const val KEY_CUSTOM_PATH = "models_custom_path" // legacy display path only
    private const val KEY_TREE_URI = "models_tree_uri"
    private const val DEFAULT_DIR = "models"
    private const val CACHE_DIR = "models_cache"

    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        return prefs!!
    }

    fun getTreeUri(context: Context): Uri? {
        val s = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return try {
            Uri.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    fun isSafMode(context: Context): Boolean = getTreeUri(context) != null

    /** Writable File dir for non-SAF mode (default private storage). */
    fun getModelsDirectory(context: Context): File {
        // Legacy custom absolute path — only use if actually writable
        val customPath = prefs(context).getString(KEY_CUSTOM_PATH, null)
        val tree = getTreeUri(context)
        if (tree == null && !customPath.isNullOrBlank()) {
            val dir = File(customPath)
            if (canUseFileDir(dir)) {
                AppLogger.d("Storage", "Using custom path: ${dir.absolutePath}")
                return dir
            }
            AppLogger.d(
                "Storage",
                "Custom path not writable (EACCES?) — falling back to internal: $customPath"
            )
            // Drop broken legacy path so we stop retrying EACCES
            prefs(context).edit().remove(KEY_CUSTOM_PATH).apply()
        }
        if (tree != null) {
            // SAF mode: File API path is only a private working/cache root
            val cache = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
            AppLogger.d("Storage", "SAF mode — private cache: ${cache.absolutePath}")
            return cache
        }
        val default = File(context.filesDir, DEFAULT_DIR).also { it.mkdirs() }
        AppLogger.d("Storage", "Using default path: ${default.absolutePath}")
        return default
    }

    private fun canUseFileDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".write_probe_${System.nanoTime()}")
            FileOutputStream(probe).use { it.write(1) }
            probe.delete()
            true
        } catch (t: Throwable) {
            AppLogger.d("Storage", "write probe failed: ${t.message}")
            false
        }
    }

    fun getSafRoot(context: Context): DocumentFile? {
        val uri = getTreeUri(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (t: Throwable) {
            AppLogger.e("Storage", "fromTreeUri failed: ${t.message}", t)
            null
        }
    }

    fun setCustomPath(context: Context, path: String?) {
        AppLogger.d("Storage", "Setting custom path: ${path ?: "null"}")
        prefs(context).edit().putString(KEY_CUSTOM_PATH, path).apply()
    }

    fun getCustomPath(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_PATH, null)

    fun hasCustomPath(context: Context): Boolean =
        getTreeUri(context) != null || !getCustomPath(context).isNullOrBlank()

    fun getStorageLabel(context: Context): String {
        val uri = getTreeUri(context)
        if (uri != null) {
            val root = getSafRoot(context)
            val name = root?.name
            val pathHint = getCustomPath(context)
            return when {
                !pathHint.isNullOrBlank() -> "📁 SAF: $pathHint"
                !name.isNullOrBlank() -> "📁 SAF: $name"
                else -> "📁 SAF: ${uri}"
            }
        }
        val customPath = getCustomPath(context)
        return if (customPath != null) {
            "📁 $customPath"
        } else {
            "📁 Внутренняя память (по умолчанию)"
        }
    }

    fun getExternalModelsDir(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        return File(externalDir, DEFAULT_DIR).also { it.mkdirs() }
    }

    fun resetToDefault(context: Context) {
        AppLogger.d("Storage", "Reset storage to default")
        prefs(context).edit()
            .remove(KEY_CUSTOM_PATH)
            .remove(KEY_TREE_URI)
            .apply()
    }

    fun safUriToPath(uri: Uri): String? {
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Persist SAF tree URI (required for write access). Also stores a human path hint.
     * Does NOT rely on java.io.File for the shared folder.
     */
    fun setCustomPathFromSafUri(context: Context, uri: Uri): Boolean {
        return try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (t: Throwable) {
                AppLogger.d("Storage", "takePersistableUriPermission: ${t.message}")
            }
            val pathHint = safUriToPath(uri)
            prefs(context).edit()
                .putString(KEY_TREE_URI, uri.toString())
                .putString(KEY_CUSTOM_PATH, pathHint)
                .apply()
            AppLogger.d("Storage", "SAF tree set uri=$uri pathHint=$pathHint")
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root == null || !root.canWrite()) {
                AppLogger.d("Storage", "SAF root missing or not writable — still saved URI")
            }
            true
        } catch (t: Throwable) {
            AppLogger.e("Storage", "setCustomPathFromSafUri: ${t.message}", t)
            false
        }
    }

    // ---- SAF document helpers ----

    fun findChild(context: Context, fileName: String): DocumentFile? {
        val root = getSafRoot(context) ?: return null
        return root.listFiles().firstOrNull { it.isFile && it.name == fileName }
    }

    fun listGguf(context: Context): List<DocumentFile> {
        val root = getSafRoot(context) ?: return emptyList()
        return root.listFiles().filter { f ->
            f.isFile && (f.name?.endsWith(".gguf", true) == true)
        }
    }

    fun openOrCreateFile(context: Context, fileName: String, mime: String = "application/octet-stream"): DocumentFile? {
        val root = getSafRoot(context) ?: return null
        findChild(context, fileName)?.let { return it }
        return try {
            root.createFile(mime, fileName.removeSuffix(".gguf").let { base ->
                // createFile may append extension from mime; pass full name carefully
                fileName
            }) ?: root.createFile(mime, fileName)
        } catch (t: Throwable) {
            AppLogger.e("Storage", "createFile $fileName: ${t.message}", t)
            null
        }
    }

    /** Length of SAF file or 0. */
    fun safFileLength(context: Context, fileName: String): Long {
        return findChild(context, fileName)?.length() ?: 0L
    }

    fun openSafOutput(context: Context, fileName: String, append: Boolean): OutputStream? {
        val doc = openOrCreateFile(context, fileName) ?: return null
        val mode = if (append) "wa" else "w"
        return try {
            context.contentResolver.openOutputStream(doc.uri, mode)
        } catch (t: Throwable) {
            AppLogger.e("Storage", "openOutputStream $fileName mode=$mode: ${t.message}", t)
            // Some providers reject "wa" — fall back to "w" only when not appending
            if (append) null else try {
                context.contentResolver.openOutputStream(doc.uri, "w")
            } catch (t2: Throwable) {
                AppLogger.e("Storage", "openOutputStream fallback: ${t2.message}", t2)
                null
            }
        }
    }

    fun openSafInput(context: Context, fileName: String): InputStream? {
        val doc = findChild(context, fileName) ?: return null
        return try {
            context.contentResolver.openInputStream(doc.uri)
        } catch (t: Throwable) {
            AppLogger.e("Storage", "openInputStream $fileName: ${t.message}", t)
            null
        }
    }

    fun deleteSafFile(context: Context, fileName: String): Boolean {
        return try {
            findChild(context, fileName)?.delete() == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Ensure a real filesystem path for llama mmap.
     * If SAF mode: copy document → filesDir/models_cache/<fileName> when missing/size mismatch.
     */
    fun materializeForNative(context: Context, fileName: String): File? {
        if (!isSafMode(context)) {
            val f = File(getModelsDirectory(context), fileName)
            return f.takeIf { it.isFile && it.length() > 0L }
        }
        val cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        val out = File(cacheDir, fileName)
        val doc = findChild(context, fileName) ?: return out.takeIf { it.isFile && it.length() > 0L }
        val docLen = doc.length()
        if (out.isFile && out.length() == docLen && docLen > 0L) {
            AppLogger.d("Storage", "cache hit $fileName ($docLen)")
            return out
        }
        AppLogger.d("Storage", "materialize $fileName ($docLen bytes) → ${out.absolutePath}")
        return try {
            openSafInput(context, fileName)?.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            if (out.isFile && out.length() > 0L) out else null
        } catch (t: Throwable) {
            AppLogger.e("Storage", "materialize failed: ${t.message}", t)
            try { out.delete() } catch (_: Throwable) {}
            null
        }
    }
}
