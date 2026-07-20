package com.example.offlinellm.domain.model

data class LlmModel(
    val id: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val isDownloaded: Boolean = false,
    val downloadUrl: String = "",
    val parameterCount: String = "?B",
    val quantType: String = "Q4_0",
    val requiresGpu: Boolean = false,
    val backend: String = "Auto"
) {
    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1_000_000_000L -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000L -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> "%.0f KB".format(sizeBytes / 1_000.0)
        }
}
