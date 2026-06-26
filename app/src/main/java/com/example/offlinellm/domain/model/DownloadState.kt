package com.example.offlinellm.domain.model

sealed class DownloadState {
    object Idle : DownloadState()
    data class InProgress(val progress: Float) : DownloadState()
    object Completed : DownloadState()
    data class Failed(val reason: String) : DownloadState()
}
