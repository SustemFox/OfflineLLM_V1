package com.example.offlinellm.ui.chat

import androidx.compose.ui.graphics.Color
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.data.remote.HfGgufFile
import com.example.offlinellm.data.remote.HfModelHit
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message

/**
 * Immutable UI snapshot for chat + settings screens.
 * Kept separate from [ChatViewModel] so composables don't depend on the whole VM type.
 */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val isLoading: Boolean = false,
    val isDarkMode: Boolean = true,
    val isRealEngine: Boolean = false,
    val isNativeAvailable: Boolean = false,
    val isServerRunning: Boolean = false,
    val serverPort: Int = 8080,
    val serverPortInput: String = "8080",
    val localIps: List<String> = emptyList(),
    val serverBaseUrls: List<String> = emptyList(),
    val primaryColor: Color = Color(0xFF8E44AD),
    val availableModels: List<LlmModel> = emptyList(),
    val selectedModel: LlmModel? = null,
    val downloadingModelId: String? = null,
    val downloadState: DownloadState = DownloadState.Idle,
    val activeBackend: String = "CPU",
    val storagePath: String = "",
    val hasCustomStorage: Boolean = false,
    val logsEnabled: Boolean = true,
    val logsPanelExpanded: Boolean = false,
    val hfToken: String = "",
    val hfUrlInput: String = "",
    val hfSearchQuery: String = "Qwen3.5 GGUF",
    val hfSearchLoading: Boolean = false,
    val hfSearchError: String? = null,
    val hfSearchResults: List<HfModelHit> = emptyList(),
    val hfSelectedRepo: String? = null,
    val hfFilesLoading: Boolean = false,
    val hfFiles: List<HfGgufFile> = emptyList(),
    val hfShowManualUrl: Boolean = false,
    val accelPref: String = "auto",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 256,
    val nCtx: Int = 2048,
    val threads: Int = 4,
    val systemPrompt: String = "",
    val showThinking: Boolean = true,
    val repeatPenalty: Float = 1.15f,
    val frequencyPenalty: Float = 0.15f,
    val nGpuLayers: Int = 99,
)