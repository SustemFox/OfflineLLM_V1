package com.example.offlinellm.ui.chat

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.ModelsDirectoryManager
import com.example.offlinellm.data.service.LlmHttpServer
import com.example.offlinellm.di.AppProvider
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var httpServer: LlmHttpServer? = null
    private var downloadJob: Job? = null

    init {
        AppProvider.initFake(application)
        _uiState.value = _uiState.value.copy(
            storagePath = ModelsDirectoryManager.getStorageLabel(application),
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application)
        )
        loadModels()
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppProvider.modelRepository.refreshModels()
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "refreshModels failed: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                applyModelsFromRepo()
            }
        }
    }

    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppProvider.modelRepository.refreshModels()
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "loadModels failed: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                applyModelsFromRepo()
            }
        }
    }

    private fun applyModelsFromRepo() {
        val models = try {
            AppProvider.modelRepository.getAvailableModels()
        } catch (_: Throwable) {
            emptyList()
        }
        val backend = try {
            AppProvider.modelRepository.getActiveBackend()
        } catch (_: Throwable) {
            "CPU"
        }
        val currentId = _uiState.value.selectedModel?.id
        val selected = models.firstOrNull { it.id == currentId }
            ?: models.firstOrNull { it.isDownloaded }
            ?: models.firstOrNull()
        _uiState.value = _uiState.value.copy(
            availableModels = models,
            selectedModel = selected,
            activeBackend = backend,
            isNativeAvailable = AppProvider.isNativeAvailable()
        )
    }

    fun switchToRealEngine(modelPath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                withContext(Dispatchers.IO) {
                    AppProvider.initRealEngine(application, modelPath)
                }
                // Restart HTTP server with real generate if it was running
                val wasRunning = _uiState.value.isServerRunning
                val port = _uiState.value.serverPort ?: 8080
                if (wasRunning) {
                    stopHttpServer()
                    startHttpServer(port)
                }
                val backend = try {
                    AppProvider.modelRepository.getActiveBackend()
                } catch (_: Throwable) {
                    "CPU"
                }
                _uiState.value = _uiState.value.copy(
                    isRealEngine = true,
                    activeBackend = backend
                )
                addMessage(
                    Message(
                        text = "✅ Модель загружена в llama.cpp ($backend).",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "switchToRealEngine failed: ${e.message}", e)
                addMessage(
                    Message(
                        text = "Не удалось загрузить модель: ${e.message}",
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
                    addMessage(
                        Message(
                            text = "Error: ${error.localizedMessage ?: "Failed to get response"}",
                            sender = Message.Sender.SYSTEM
                        )
                    )
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
                .onStart {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isGenerating = true)
                    }
                }
                .catch { error ->
                    withContext(Dispatchers.Main) {
                        addMessage(
                            Message(
                                text = "Error: ${error.message ?: "Inference failed"}",
                                sender = Message.Sender.SYSTEM
                            )
                        )
                    }
                }
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isGenerating = false)
                    }
                }
                .collect { token ->
                    withContext(Dispatchers.Main) {
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
    }

    fun startHttpServer(port: Int = 8080) {
        viewModelScope.launch {
            try {
                stopHttpServer()
                val server = LlmHttpServer(
                    port = port,
                    generate = { prompt ->
                        AppProvider.llmRepository.generateResponse(prompt)
                    },
                    modelId = { _uiState.value.selectedModel?.id ?: "local-model" }
                )
                withContext(Dispatchers.IO) {
                    server.start()
                }
                httpServer = server
                _uiState.value = _uiState.value.copy(
                    isServerRunning = true,
                    serverPort = port
                )
                addMessage(
                    Message(
                        text = "🌐 HTTP-сервер на порту $port (/v1/chat/completions).",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "startHttpServer failed: ${e.message}", e)
                addMessage(
                    Message(
                        text = "Не удалось запустить HTTP-сервер: ${e.message}",
                        sender = Message.Sender.SYSTEM
                    )
                )
            }
        }
    }

    fun stopHttpServer() {
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            AppLogger.e("ChatVM", "stopHttpServer: ${e.message}", e)
        }
        httpServer = null
        if (_uiState.value.isServerRunning) {
            _uiState.value = _uiState.value.copy(isServerRunning = false, serverPort = null)
        }
    }

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun toggleTheme() {
        _uiState.value = _uiState.value.copy(isDarkMode = !_uiState.value.isDarkMode)
    }

    fun setPrimaryColor(color: Color) {
        _uiState.value = _uiState.value.copy(primaryColor = color)
    }

    fun selectModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
        if (!model.isDownloaded) {
            addMessage(
                Message(
                    text = "Модель «${model.name}» ещё не скачана.",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }
        val path = try {
            AppProvider.modelRepository.getModelPath(model.id)
        } catch (_: Throwable) {
            null
        }
        if (path.isNullOrBlank()) {
            addMessage(
                Message(
                    text = "Файл модели «${model.name}» не найден на диске. Обнови список или скачай снова.",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }
        switchToRealEngine(path)
    }

    fun downloadSelectedModel() {
        val model = _uiState.value.selectedModel ?: return
        downloadModel(model)
    }

    fun downloadModel(model: LlmModel) {
        if (model.isDownloaded) {
            selectModel(model)
            return
        }
        if (model.downloadUrl.isBlank()) {
            addMessage(
                Message(
                    text = "У «${model.name}» нет URL для скачивания. Положи .gguf вручную в папку моделей.",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }
        if (downloadJob?.isActive == true) {
            addMessage(
                Message(
                    text = "Уже идёт скачивание. Дождись окончания или нажми «Отмена».",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            selectedModel = model,
            downloadingModelId = model.id,
            downloadState = DownloadState.InProgress(0f)
        )

        AppLogger.d("ChatVM", "downloadModel: ${model.id} from ${model.downloadUrl}")
        downloadJob = viewModelScope.launch {
            try {
                AppProvider.modelRepository.downloadModel(model.id, model.downloadUrl)
                    .flowOn(Dispatchers.IO)
                    .onStart {
                        AppLogger.d("ChatVM", "Download starting for ${model.id}")
                    }
                    .catch { error ->
                        if (!isActive) return@catch
                        AppLogger.e("ChatVM", "Download FAILED: ${model.id} - ${error.message}", error)
                        _uiState.value = _uiState.value.copy(
                            downloadState = DownloadState.Failed(
                                error.localizedMessage ?: error.message ?: "Download failed"
                            ),
                            downloadingModelId = null
                        )
                    }
                    .collect { progress ->
                        if (!isActive) return@collect
                        val completed = progress >= 0.999f
                        if (completed) {
                            _uiState.value = _uiState.value.copy(
                                downloadState = DownloadState.Completed,
                                downloadingModelId = null
                            )
                            AppLogger.d("ChatVM", "Download COMPLETED: ${model.id}")
                            addMessage(
                                Message(
                                    text = "✅ Модель ${model.name} скачана! Нажми «Выбрать», чтобы загрузить в движок.",
                                    sender = Message.Sender.SYSTEM
                                )
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                downloadState = DownloadState.InProgress(progress.coerceIn(0f, 1f)),
                                downloadingModelId = model.id
                            )
                        }
                    }
            } catch (e: Exception) {
                if (isActive) {
                    AppLogger.e("ChatVM", "Download exception: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Failed(e.message ?: "Download failed"),
                        downloadingModelId = null
                    )
                }
            } finally {
                loadModels()
            }
        }
    }

    fun cancelDownload() {
        val id = _uiState.value.downloadingModelId
        AppLogger.d("ChatVM", "cancelDownload: $id")
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (id != null) {
                    AppProvider.modelRepository.cancelDownload(id)
                }
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "cancelDownload cleanup: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    downloadState = DownloadState.Idle,
                    downloadingModelId = null
                )
                addMessage(
                    Message(
                        text = "Скачивание отменено.",
                        sender = Message.Sender.SYSTEM
                    )
                )
                loadModels()
            }
        }
    }

    fun clearDownloadState() {
        if (_uiState.value.downloadState is DownloadState.InProgress) return
        _uiState.value = _uiState.value.copy(
            downloadState = DownloadState.Idle,
            downloadingModelId = null
        )
    }

    fun toggleServer(enabled: Boolean) {
        if (enabled) {
            val model = _uiState.value.selectedModel
            if (model != null && model.isDownloaded && _uiState.value.isRealEngine) {
                startHttpServer(8080)
            } else if (model != null && model.isDownloaded) {
                addMessage(
                    Message(
                        text = "Сначала нажми «Выбрать» у скачанной модели, чтобы загрузить движок.",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } else {
                addMessage(
                    Message(
                        text = "Сначала скачай и выбери модель.",
                        sender = Message.Sender.SYSTEM
                    )
                )
            }
        } else {
            stopHttpServer()
            addMessage(
                Message(text = "HTTP-сервер остановлен.", sender = Message.Sender.SYSTEM)
            )
        }
    }

    fun deleteModel(model: LlmModel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppProvider.modelRepository.deleteModel(model.id)
                }
                if (_uiState.value.selectedModel?.id == model.id) {
                    // If real engine was using it, fall back to fake
                    AppProvider.initFake(application)
                    stopHttpServer()
                    _uiState.value = _uiState.value.copy(isRealEngine = false)
                }
                addMessage(
                    Message(
                        text = "🗑 Модель ${model.name} удалена.",
                        sender = Message.Sender.SYSTEM
                    )
                )
                loadModels()
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "deleteModel failed: ${e.message}", e)
                addMessage(
                    Message(
                        text = "Не удалось удалить ${model.name}: ${e.message}",
                        sender = Message.Sender.SYSTEM
                    )
                )
            }
        }
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
                    "⚡ Ускорение: Vulkan GPU / OpenCL / CPU\n" +
                    "🌐 HTTP-сервер: вкл/выкл в настройках\n" +
                    "1) ⚙ → скачай модель\n" +
                    "2) Нажми «Выбрать»\n" +
                    "3) Пиши в чат",
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
        val downloadingModelId: String? = null,
        val downloadState: DownloadState = DownloadState.Idle,
        val activeBackend: String = "CPU",
        val storagePath: String = "",
        val hasCustomStorage: Boolean = false
    )

    fun setCustomStoragePath(path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (path != null && path.startsWith("content://")) {
                    val uri = android.net.Uri.parse(path)
                    try {
                        application.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        AppLogger.d("ChatVM", "takePersistableUriPermission: ${e.message}")
                    }
                    ModelsDirectoryManager.setCustomPathFromSafUri(application, uri)
                } else {
                    ModelsDirectoryManager.setCustomPath(application, path)
                }
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "setCustomStoragePath: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    storagePath = ModelsDirectoryManager.getStorageLabel(application),
                    hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application)
                )
                loadModels()
            }
        }
    }

    fun resetStoragePath() {
        ModelsDirectoryManager.resetToDefault(application)
        _uiState.value = _uiState.value.copy(
            storagePath = ModelsDirectoryManager.getStorageLabel(application),
            hasCustomStorage = false
        )
        loadModels()
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        stopHttpServer()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(application) as T
        }
    }
}
