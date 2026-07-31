package com.example.offlinellm.llama

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.data.local.RootShell
import com.example.offlinellm.data.local.ModelsDirectoryManager
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class GgufModelInfo(
    val id: String,
    val name: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val downloadUrl: String = "",
    val quantType: String = "Q4_0",
    val parameterCount: String = "?B"
)

object ModelLoader {

    fun getModelsDirectory(context: Context): File =
        ModelsDirectoryManager.getModelsDirectory(context)

    fun scanLocalModels(context: Context): List<GgufModelInfo> {
        // Root experimental: also scan absolute dir (may see files SAF already lists)
        val rootExtra = mutableListOf<GgufModelInfo>()
        if (AppPreferences.isRootModeEnabled(context)) {
            val dir = AppPreferences.getRootDirectModelPath(context).ifBlank {
                ModelsDirectoryManager.getCustomPath(context).orEmpty()
            }
            if (dir.isNotBlank()) {
                val listed = RootShell.listGguf(dir)
                AppLogger.d("ModelLoader", "root direct scan $dir -> ${listed.size} gguf")
                listed.forEach { (name, size) ->
                    val path = dir.trimEnd('/') + "/" + name
                    rootExtra += parseModelName(name, size, filePath = path)
                }
            }
        }

        if (ModelsDirectoryManager.isSafMode(context)) {
            val docs = ModelsDirectoryManager.listGguf(context)
            AppLogger.d("ModelLoader", "Scanning SAF: ${docs.size} gguf")
            val saf = docs.mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                val direct = ModelsDirectoryManager.resolveDirectNativePath(context, name)
                parseModelName(name, doc.length(), filePath = direct?.absolutePath ?: "")
            }
            return (saf + rootExtra).distinctBy { it.id }.sortedByDescending { it.fileSizeBytes }
        }

        val modelsDir = getModelsDirectory(context)
        AppLogger.d("ModelLoader", "Scanning: ${modelsDir.absolutePath}")
        if (!modelsDir.exists()) {
            AppLogger.d("ModelLoader", "Directory does not exist: ${modelsDir.absolutePath}")
            return emptyList()
        }
        val files = modelsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) }
            ?.map { file -> parseModelFile(file) }
            ?.sortedByDescending { it.fileSizeBytes }
        if (files != null) {
            AppLogger.d("ModelLoader", "Found ${files.size} models")
            return files
        }
        AppLogger.d("ModelLoader", "No .gguf files found in ${modelsDir.absolutePath}")
        return emptyList()
    }

    fun getRecommendedModels(): List<GgufModelInfo> = listOf(
        // —— Qwen3.5 (актуальная линейка, телефон: 0.8B / 4B) ——
        GgufModelInfo(
            id = "qwen3.5-0.8b-q4_k_m",
            name = "Qwen 3.5 0.8B (Q4_K_M) — лёгкая ★",
            filePath = "",
            fileSizeBytes = 532_517_120L,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            parameterCount = "0.8B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen3.5-4b-q4_k_m",
            name = "Qwen 3.5 4B (Q4_K_M) — баланс",
            filePath = "",
            fileSizeBytes = 2_740_937_888L,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            parameterCount = "4B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen3.5-4b-q4_0",
            name = "Qwen 3.5 4B (Q4_0) — чуть легче 4B",
            filePath = "",
            fileSizeBytes = 2_583_221_408L,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf",
            parameterCount = "4B",
            quantType = "Q4_0"
        ),
        // —— Qwen3 (предыдущее поколение, стабильные GGUF) ——
        GgufModelInfo(
            id = "qwen3-0.6b-q4_k_m",
            name = "Qwen 3 0.6B (Q4_K_M) — очень лёгкая",
            filePath = "",
            fileSizeBytes = 396_705_472L,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            parameterCount = "0.6B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen3-1.7b-q4_k_m",
            name = "Qwen 3 1.7B (Q4_K_M) — рекомендуется OP7",
            filePath = "",
            fileSizeBytes = 1_107_409_472L,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            parameterCount = "1.7B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen3-4b-q4_k_m",
            name = "Qwen 3 4B (Q4_K_M)",
            filePath = "",
            fileSizeBytes = 2_497_280_256L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            parameterCount = "4B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen3-8b-q4_k_m",
            name = "Qwen 3 8B (Q4_K_M) — тяжёлая (~5 GB)",
            filePath = "",
            fileSizeBytes = 5_027_783_488L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            parameterCount = "8B",
            quantType = "Q4_K_M"
        ),
        // —— запас: Qwen2.5 (если 3.x не взлетит на старом GGUF) ——
        GgufModelInfo(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen 2.5 1.5B Instruct (Q4_K_M) — fallback",
            filePath = "",
            fileSizeBytes = 986_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            parameterCount = "1.5B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "llama-3.2-1b-instruct-q4_k_m",
            name = "Llama 3.2 1B Instruct (Q4_K_M)",
            filePath = "",
            fileSizeBytes = 680_000_000L,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            parameterCount = "1B",
            quantType = "Q4_K_M"
        ),
    )

    fun modelInfoFromUrl(url: String): GgufModelInfo {
        val clean = url.trim().substringBefore("?").substringBefore("#")
        val fileName = try {
            val path = clean.substringAfterLast('/')
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            clean.substringAfterLast('/')
        }
        val base = if (fileName.endsWith(".gguf", true)) fileName.dropLast(5) else fileName
        val id = base.ifBlank { "hf-model-${System.currentTimeMillis()}" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        val paramMatch = Regex("""(\d+\.?\d*)[bB]""").find(base)
        val paramStr = paramMatch?.groupValues?.get(0)?.uppercase() ?: "?B"
        val quantMatch = Regex("""(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z0-9_]+)""", RegexOption.IGNORE_CASE).find(base)
        val quant = quantMatch?.value?.uppercase() ?: "GGUF"
        return GgufModelInfo(
            id = id,
            name = base.replace('-', ' ').replace('_', ' '),
            filePath = "",
            fileSizeBytes = 0L,
            downloadUrl = url.trim(),
            quantType = quant,
            parameterCount = paramStr
        )
    }

    private fun parseModelFile(file: File): GgufModelInfo =
        parseModelName(file.name, file.length(), file.absolutePath)

    private fun parseModelName(fileName: String, size: Long, filePath: String): GgufModelInfo {
        val name = if (fileName.endsWith(".gguf", true)) fileName.dropLast(5) else fileName
        val paramMatch = Regex("""(\d+\.?\d*)[bB]""").find(name)
        val paramStr = paramMatch?.groupValues?.get(0)?.uppercase() ?: "?B"
        val quantMatch = Regex("""(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z0-9_]+)""", RegexOption.IGNORE_CASE).find(name)
        val quant = quantMatch?.value ?: "unknown"
        return GgufModelInfo(
            id = name,
            name = name.replace("-", " ").replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            filePath = filePath,
            fileSizeBytes = size,
            quantType = quant,
            parameterCount = paramStr
        )
    }
}
