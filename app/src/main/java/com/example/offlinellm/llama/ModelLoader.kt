package com.example.offlinellm.llama

import android.content.Context
import com.example.offlinellm.data.local.AppLogger
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
        GgufModelInfo(
            id = "qwen2.5-0.5b-instruct-q4_k_m",
            name = "Qwen 2.5 0.5B Instruct (Q4_K_M) — лёгкая",
            filePath = "",
            fileSizeBytes = 400_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            parameterCount = "0.5B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen2.5-1.5b-instruct-q4_0",
            name = "Qwen 2.5 1.5B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 930_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf",
            parameterCount = "1.5B"
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
        GgufModelInfo(
            id = "llama-3.2-3b-instruct-q4_k_m",
            name = "Llama 3.2 3B Instruct (Q4_K_M)",
            filePath = "",
            fileSizeBytes = 1_760_000_000L,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            parameterCount = "3B",
            quantType = "Q4_K_M"
        ),
        GgufModelInfo(
            id = "qwen2.5-7b-instruct-q4_0",
            name = "Qwen 2.5 7B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 4_070_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_0.gguf",
            parameterCount = "7B"
        ),
    )

    /**
     * Build model id + display name from a Hugging Face resolve URL or direct .gguf URL.
     */
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

    private fun parseModelFile(file: File): GgufModelInfo {
        val name = file.nameWithoutExtension
        val paramMatch = Regex("""(\d+\.?\d*)[bB]""").find(name)
        val paramStr = paramMatch?.groupValues?.get(0)?.uppercase() ?: "?B"
        val quantMatch = Regex("""(Q[0-9]_[0-9A-Z_]+|IQ[0-9]_[A-Z0-9_]+)""", RegexOption.IGNORE_CASE).find(name)
        val quant = quantMatch?.value ?: "unknown"
        return GgufModelInfo(
            id = name,
            name = name.replace("-", " ").replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            quantType = quant,
            parameterCount = paramStr
        )
    }
}
