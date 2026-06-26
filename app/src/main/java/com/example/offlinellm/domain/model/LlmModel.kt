package com.example.offlinellm.domain.model

data class LlmModel(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val isDownloaded: Boolean = false
)
