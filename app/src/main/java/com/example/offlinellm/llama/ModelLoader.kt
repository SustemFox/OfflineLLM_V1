package com.example.offlinellm.llama

import android.content.Context
import java.io.File

/**
 * Scans device storage for GGUF model files and provides metadata.
 * Supports models in app-private storage and shared storage.
 */
data class GgufModelInfo(
    val id: String,
    val name: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val quantType: String = "Q4_0",
    val parameterCount: String = "?B"
)

object ModelLoader {

    private const val MODELS_DIR = "models"

    /** Default directory inside app files for storing GGUF models. */
    fun getModelsDirectory(context: Context): File =
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    /** Scan for downloaded GGUF models. */
    fun scanLocalModels(context: Context): List<GgufModelInfo> {
        val modelsDir = getModelsDirectory(context)
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.map { file -> parseModelFile(file) }
            ?.sortedByDescending { it.fileSizeBytes }
            ?: emptyList()
    }

    /**
     * Suggest a model to download based on phone specs.
     * For OnePlus 7 (SD855, 8GB RAM):
     *   - 7-8B Q4_0 (~5GB) works great via GPU/NPU
     *   - 3B Q4_0 (~2GB) very fast
     *   - 1B Q4_0 (~700MB) instant
     */
    fun getRecommendedModels(): List<GgufModelInfo> = listOf(
        GgufModelInfo(
            id = "qwen2.5-7b-instruct-q4_0",
            name = "Qwen 2.5 7B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 4_070_000_000L,
            parameterCount = "7B"
        ),
        GgufModelInfo(
            id = "llama-3.2-3b-instruct-q4_0",
            name = "Llama 3.2 3B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 1_760_000_000L,
            parameterCount = "3B"
        ),
        GgufModelInfo(
            id = "qwen2.5-1.5b-instruct-q4_0",
            name = "Qwen 2.5 1.5B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 930_000_000L,
            parameterCount = "1.5B"
        ),
        GgufModelInfo(
            id = "llama-3.2-1b-instruct-q4_0",
            name = "Llama 3.2 1B Instruct (Q4_0)",
            filePath = "",
            fileSizeBytes = 680_000_000L,
            parameterCount = "1B"
        )
    )

    private fun parseModelFile(file: File): GgufModelInfo {
        val name = file.nameWithoutExtension
        // Extract rough param count from filename
        val paramMatch = Regex("""(\d+\.?\d*)[bB]""").find(name)
        val paramStr = paramMatch?.groupValues?.get(0)?.uppercase() ?: "?B"
        // Extract quant type
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
