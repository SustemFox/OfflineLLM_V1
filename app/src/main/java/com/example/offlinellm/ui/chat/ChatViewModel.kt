package com.example.offlinellm.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.offlinellm.data.service.LlmHttpServer
import com.example.offlinellm.di.AppProvider
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.data.local.ModelsDirectoryManager
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.llama.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class ChatViewModel(
    private val application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var httpServer: LlmHttpServer? = null

    init {
        AppProvider.initFake(application)
        _uiState.value = _uiState.value.copy(
            storagePath = ModelsDirectoryManager.getStorageLabel(application),
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application)
        )
        loadModels()
    }

    fun refreshModels() { loadModels() }

    private fun loadModels() {
        val models = AppProvider.modelRepository.getAvailableModels()
        val backend = try { AppProvider.modelRepository.getActiveBackend() } catch (_: Throwable) { "CPU" }
        _uiState.value = _uiState.value.copy(
            availableModels = models,
            selectedModel = models.firstOrNull { it.isDownloaded } ?: models.firstOrNull(),
            activeBackend = backend,
            isNativeAvailable = AppProvider.isNativeAvailable()
        )
    }

    /** Switch to real llama.cpp engine with a downloaded model. */
    fun switchToRealEngine(modelPath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                AppProvider.initRealEngine(application, modelPath)
                _uiState.value = _uiState.value.copy(isRealEngine = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + Message(
                        text = "Failed to load model: ${e.message}",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        addMessage(Message(text = trimmed, sender = Message.Sender.USER))
        _uiState.value = _uiState.value.copy(inputText = "")

        if (AppProvider.useFake) {
            generateResponse(trimmed)
        } else {
            generateRealResponse(trimmed)
        }
    }

    private fun generateResponse(prompt: String) {
        viewModelScope.launch {
            var assistantMessage: Message? = null
            AppProvider.llmRepository.generateResponse(prompt)
                .onStart { _uiState.value = _uiState.value.copy(isGenerating = true) }
                .catch { error ->
                    addMessage(Message(
                        text = "Error: ${error.localizedMessage ?: "Failed to get response"}",
                        sender = Message.Sender.SYSTEM
                    ))
                }
                .onCompletion { _uiState.value = _uiState.value.copy(isGenerating = false) }
                .collect { partialText ->
                    if (assistantMessage == null) {
                        assistantMessage = Message(text = partialText, sender = Message.Sender.LLM)
                        addMessage(assistantMessage!!)
                    } else {
                        assistantMessage = assistantMessage!!.copy(text = partialText)
                        updateLastMessage(assistantMessage!!)
                    }
                }
        }
    }

    private fun generateRealResponse(prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var assistantMessage: Message? = null
            AppProvider.llmRepository.generateResponse(prompt)
                .onStart { _uiState.value = _uiState.value.copy(isGenerating = true) }
                .catch { error ->
                    addMessage(Message(
                        text = "Error: ${error.message ?: "Inference failed"}",
                        sender = Message.Sender.SYSTEM
                    ))
                }
                .onCompletion { _uiState.value = _uiState.value.copy(isGenerating = false) }
                .collect { token ->
                    if (assistantMessage == null) {
                        assistantMessage = Message(text = token, sender = Message.Sender.LLM)
                        addMessage(assistantMessage!!)
                    } else {
                        assistantMessage = assistantMessage!!.copy(text = token)
                        updateLastMessage(assistantMessage!!)
                    }
                }
        }
    }

    /** Start HTTP server to host the model for external clients. */
    fun startHttpServer(modelPath: String, port: Int = 8080) {
        viewModelScope.launch {
            try {
                val server = LlmHttpServer(
                    port = port,
                    modelId = { _uiState.value.selectedModel?.name ?: "local-model" }
                )
                server.start()
                httpServer = server
                _uiState.value = _uiState.value.copy(
                    isServerRunning = true,
                    serverPort = port
                )
            } catch (e: Exception) {
                addMessage(Message(
                    text = "Failed to start HTTP server: ${e.message}",
                    sender = Message.Sender.SYSTEM
                ))
            }
        }
    }

    /** Stop the HTTP server. */
    fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        _uiState.value = _uiState.value.copy(isServerRunning = false, serverPort = null)
    }

    fun updateInput(text: String) { _uiState.value = _uiState.value.copy(inputText = text) }
    fun toggleTheme() { _uiState.value = _uiState.value.copy(isDarkMode = !_uiState.value.isDarkMode) }
    fun setPrimaryColor(color: Color) { _uiState.value = _uiState.value.copy(primaryColor = color) }

    fun selectModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun downloadSelectedModel() {
        val model = _uiState.value.selectedModel ?: return
        viewModelScope.launch {
            AppProvider.modelRepository.downloadModel(model.id, model.downloadUrl)
                .onStart { _uiState.value = _uiState.value.copy(downloadState = DownloadState.InProgress(0f)) }
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Failed(error.localizedMessage ?: "Download failed")
                    )
                }
                .collect { progress ->
                    _uiState.value = _uiState.value.copy(
                        downloadState = if (progress >= 1f) DownloadState.Completed else DownloadState.InProgress(progress)
                    )
                }
            loadModels()
        }
    }

    fun toggleServer(enabled: Boolean) {
        if (enabled) {
            val model = _uiState.value.selectedModel
            if (model != null && model.isDownloaded) {
                startHttpServer(model.id, 8080)
            } else {
                addMessage(Message(
                    text = "Сначала выбери скачанную модель.",
                    sender = Message.Sender.SYSTEM
                ))
            }
        } else {
            stopHttpServer()
        }
    }

    fun downloadModel(model: LlmModel) {
        if (model.isDownloaded) {
            selectModel(model)
            return
        }
        _uiState.value = _uiState.value.copy(selectedModel = model)
        downloadSelectedModel()
    }

    fun deleteModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + Message(
                text = "Удаление модели: ${model.name}. Эта функция будет доступна в следующей версии.",
                sender = Message.Sender.SYSTEM
            )
        )
    }

    fun clearChat() {
        _uiState.value = _uiState.value.copy(
            messages = listOf(Message(text = "Chat cleared.", sender = Message.Sender.SYSTEM))
        )
    }

    private fun addMessage(message: Message) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + message)
    }

    private fun updateLastMessage(message: Message) {
        val messages = _uiState.value.messages.toMutableList()
        if (messages.isNotEmpty()) {
            messages[messages.lastIndex] = message
            _uiState.value = _uiState.value.copy(messages = messages)
        }
    }

    data class ChatUiState(
        val messages: List<Message> = listOf(
            Message(
                text = "Привет! Я твой оффлайн-помощник.\n" +
                        "📱 Движок: llama.cpp\n" +
                        "⚡ Ускорение: Hexagon NPU / Vulkan GPU / CPU\n" +
                        "🌐 HTTP-сервер: вкл/выкл в настройках\n" +
                        "Выбери модель в настройках и нажми загрузить.",
                sender = Message.Sender.SYSTEM
            )
        ),
        val inputText: String = "",
        val isGenerating: Boolean = false,
        val isLoading: Boolean = false,
        val isDarkMode: Boolean = true,
        val isRealEngine: Boolean = false,
        val isNativeAvailable: Boolean = false,
        val isServerRunning: Boolean = false,
        val serverPort: Int? = null,
        val primaryColor: Color = Color(0xFF8E44AD),
        val availableModels: List<LlmModel> = emptyList(),
        val selectedModel: LlmModel? = null,
        val downloadState: DownloadState = DownloadState.Idle,
        val activeBackend: String = "CPU",
        val storagePath: String = "",
        val hasCustomStorage: Boolean = false
    )

    fun setCustomStoragePath(path: String?) {
        ModelsDirectoryManager.setCustomPath(getApplication(), path)
        _uiState.value = _uiState.value.copy(
            storagePath = ModelsDirectoryManager.getStorageLabel(getApplication()),
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(getApplication())
        )
        loadModels()
    }

    fun resetStoragePath() {
        ModelsDirectoryManager.resetToDefault(getApplication())
        _uiState.value = _uiState.value.copy(
            storagePath = ModelsDirectoryManager.getStorageLabel(getApplication()),
            hasCustomStorage = false
        )
        loadModels()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(application) as T
        }
    }
}
