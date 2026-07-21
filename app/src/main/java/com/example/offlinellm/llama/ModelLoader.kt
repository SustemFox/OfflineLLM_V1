package com.example.offlinellm.llama

import android.content.Context
import com.example.offlinellm.data.local.ModelsDirectoryManager
import java.io.File

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
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.map { file -> parseModelFile(file) }
            ?.sortedByDescending { it.fileSizeBytes }
            ?: emptyList()
    }

    fun getRecommendedModels(): List<GgufModelInfo> = listOf(
        GgufModelInfo(
            id = "qwen2.5-7b-instruct-q4_0",
            name = "Qwen 2.5 7B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 4_070_000_000L,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_0.gguf",
            parameterCount = "7B"
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
        )
    )

    private fun parseModelFile(file: File): GgufModelInfo {
        val name = file.nameWithoutExtension
        val paramMatch = Regex("""(\d+\.?\d*)[bB]""").find(name)
        val paramStr = paramMatch?.groupValues?.get(0)?.uppercase() ?: "?B"
        val quantMatch = Regex("""(Q[0-9]_[0-9]|IQ[0-9]_[A-Z0-9]+)""").find(name)
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
