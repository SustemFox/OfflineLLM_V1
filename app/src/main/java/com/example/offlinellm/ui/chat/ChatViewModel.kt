package com.example.offlinellm.ui.chat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.data.local.ChatHistoryStore
import com.example.offlinellm.data.local.ModelsDirectoryManager
import com.example.offlinellm.data.service.LlmHttpServer
import com.example.offlinellm.data.service.ModelDownloadService
import com.example.offlinellm.di.AppProvider
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.llama.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var httpServer: LlmHttpServer? = null
    private var saveJob: Job? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ModelDownloadService.ACTION_PROGRESS) return
            val status = intent.getStringExtra(ModelDownloadService.EXTRA_STATUS) ?: return
            val modelId = intent.getStringExtra(ModelDownloadService.EXTRA_MODEL_ID)
            val progress = intent.getFloatExtra(ModelDownloadService.EXTRA_PROGRESS, 0f)
            val error = intent.getStringExtra(ModelDownloadService.EXTRA_ERROR)
            when (status) {
                ModelDownloadService.STATUS_RUNNING,
                ModelDownloadService.STATUS_PROGRESS -> {
                    _uiState.value = _uiState.value.copy(
                        downloadingModelId = modelId,
                        downloadState = DownloadState.InProgress(progress.coerceIn(0f, 0.999f))
                    )
                }
                ModelDownloadService.STATUS_COMPLETED -> {
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Completed,
                        downloadingModelId = null
                    )
                    addMessage(
                        Message(
                            text = "✅ Модель скачана (фон). Нажми «Выбрать», чтобы загрузить в движок.",
                            sender = Message.Sender.SYSTEM
                        )
                    )
                    loadModels()
                }
                ModelDownloadService.STATUS_FAILED -> {
                    _uiState.value = _uiState.value.copy(
                        downloadState = DownloadState.Failed(error ?: "Download failed"),
                        downloadingModelId = null
                    )
                    addMessage(
                        Message(
                            text = "Ошибка скачивания: ${error ?: "unknown"}",
                            sender = Message.Sender.SYSTEM
                        )
                    )
                    loadModels()
                }
            }
        }
    }

    init {
        AppProvider.initFake(application)
        AppLogger.setEnabled(AppPreferences.isLogsEnabled(application))

        val welcome = Message(
            text = "Привет! Я твой оффлайн-помощник.\n" +
                "📱 Движок: llama.cpp\n" +
                "⚡ Ускорение: CPU (+ Vulkan если собран)\n" +
                "🌐 HTTP-сервер: вкл/выкл в настройках\n" +
                "⬇️ Скачивание работает в фоне (уведомление)\n" +
                "1) ⚙ → скачай модель (список или HF URL)\n" +
                "2) Нажми «Выбрать»\n" +
                "3) Пиши в чат",
            sender = Message.Sender.SYSTEM
        )
        val history = ChatHistoryStore.load(application)
        val messages = if (history.isEmpty()) listOf(welcome) else history

        _uiState.value = _uiState.value.copy(
            messages = messages,
            isDarkMode = AppPreferences.isDarkMode(application),
            logsEnabled = AppPreferences.isLogsEnabled(application),
            logsPanelExpanded = AppPreferences.isLogsPanelExpanded(application),
            hfToken = AppPreferences.getHfToken(application),
            hfUrlInput = AppPreferences.getLastHfUrl(application),
            accelPref = AppPreferences.getAccelPref(application),
            storagePath = ModelsDirectoryManager.getStorageLabel(application),
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application)
        )

        val filter = IntentFilter(ModelDownloadService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) {
            application.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(downloadReceiver, filter)
        }

        loadModels()
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppProvider.modelRepository.refreshModels()
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "refreshModels failed: ${e.message}", e)
            }
            withContext(Dispatchers.Main) { applyModelsFromRepo() }
        }
    }

    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppProvider.modelRepository.refreshModels()
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "loadModels failed: ${e.message}", e)
            }
            withContext(Dispatchers.Main) { applyModelsFromRepo() }
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
        val prefId = AppPreferences.getSelectedModelId(application)
        val currentId = _uiState.value.selectedModel?.id ?: prefId
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
            } catch (e: Throwable) {
                AppLogger.e("ChatVM", "switchToRealEngine failed: ${e.message}", e)
                try {
                    AppProvider.initFake(application)
                } catch (_: Throwable) {
                }
                _uiState.value = _uiState.value.copy(isRealEngine = false)
                addMessage(
                    Message(
                        text = "Не удалось загрузить модель: ${e.message}\n" +
                            "Остаёмся в demo-режиме. Проверь native libs / GGUF.",
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
                .catch { error ->
                    addMessage(
                        Message(
                            text = "Error: ${error.localizedMessage ?: "Failed to get response"}",
                            sender = Message.Sender.SYSTEM
                        )
                    )
                }
                .collect { partialText ->
                    _uiState.value = _uiState.value.copy(isGenerating = true)
                    if (assistantMessage == null) {
                        assistantMessage = Message(text = partialText, sender = Message.Sender.LLM)
                        addMessage(assistantMessage!!)
                    } else {
                        assistantMessage = assistantMessage!!.copy(text = partialText)
                        updateLastMessage(assistantMessage!!)
                    }
                }
            _uiState.value = _uiState.value.copy(isGenerating = false)
            scheduleSaveHistory()
        }
    }

    private fun generateRealResponse(prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var assistantMessage: Message? = null
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isGenerating = true)
                }
                AppProvider.llmRepository.generateResponse(prompt)
                    .catch { error ->
                        withContext(Dispatchers.Main) {
                            addMessage(
                                Message(
                                    text = "Ошибка инференса: ${error.message ?: "Inference failed"}",
                                    sender = Message.Sender.SYSTEM
                                )
                            )
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
            } catch (t: Throwable) {
                AppLogger.e("ChatVM", "generateRealResponse failed: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    addMessage(
                        Message(
                            text = "Ошибка инференса: ${t.message ?: t.javaClass.simpleName}",
                            sender = Message.Sender.SYSTEM
                        )
                    )
                    try {
                        AppProvider.initFake(application)
                        _uiState.value = _uiState.value.copy(isRealEngine = false)
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isGenerating = false)
                    scheduleSaveHistory()
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
                withContext(Dispatchers.IO) { server.start() }
                httpServer = server
                _uiState.value = _uiState.value.copy(isServerRunning = true, serverPort = port)
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
        val next = !_uiState.value.isDarkMode
        AppPreferences.setDarkMode(application, next)
        _uiState.value = _uiState.value.copy(isDarkMode = next)
    }

    fun setLogsEnabled(enabled: Boolean) {
        AppPreferences.setLogsEnabled(application, enabled)
        _uiState.value = _uiState.value.copy(logsEnabled = enabled)
    }

    fun setLogsPanelExpanded(expanded: Boolean) {
        AppPreferences.setLogsPanelExpanded(application, expanded)
        _uiState.value = _uiState.value.copy(logsPanelExpanded = expanded)
    }

    fun setHfToken(token: String) {
        AppPreferences.setHfToken(application, token)
        _uiState.value = _uiState.value.copy(hfToken = token)
    }

    fun setHfUrlInput(url: String) {
        _uiState.value = _uiState.value.copy(hfUrlInput = url)
    }

    fun setAccelPref(pref: String) {
        AppPreferences.setAccelPref(application, pref)
        _uiState.value = _uiState.value.copy(accelPref = pref)
        addMessage(
            Message(
                text = "Предпочтение ускорителя: $pref (применится при следующей загрузке модели; Vulkan только если собран в APK).",
                sender = Message.Sender.SYSTEM
            )
        )
    }

    fun downloadFromHfUrl() {
        val url = _uiState.value.hfUrlInput.trim()
        if (url.isBlank()) {
            addMessage(Message(text = "Вставь Hugging Face URL (.gguf resolve/main/...).", sender = Message.Sender.SYSTEM))
            return
        }
        if (!url.contains("http", ignoreCase = true)) {
            addMessage(Message(text = "Некорректный URL.", sender = Message.Sender.SYSTEM))
            return
        }
        AppPreferences.setLastHfUrl(application, url)
        val info = ModelLoader.modelInfoFromUrl(url)
        val model = LlmModel(
            id = info.id,
            name = info.name,
            sizeBytes = info.fileSizeBytes,
            downloadUrl = info.downloadUrl,
            isDownloaded = false,
            quantType = info.quantType,
            parameterCount = info.parameterCount
        )
        downloadModel(model)
    }

    fun selectModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
        AppPreferences.setSelectedModelId(application, model.id)
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
                    text = "У «${model.name}» нет URL. Вставь HF URL ниже или положи .gguf вручную.",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }
        if (_uiState.value.downloadState is DownloadState.InProgress) {
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
        AppLogger.d("ChatVM", "downloadModel FGS: ${model.id} from ${model.downloadUrl}")
        try {
            ModelDownloadService.start(
                application,
                modelId = model.id,
                url = model.downloadUrl,
                name = model.name
            )
            addMessage(
                Message(
                    text = "⬇️ Скачивание «${model.name}» запущено в фоне. Можно свернуть приложение — прогресс в уведомлении.",
                    sender = Message.Sender.SYSTEM
                )
            )
        } catch (t: Throwable) {
            AppLogger.e("ChatVM", "FGS start failed: ${t.message}", t)
            _uiState.value = _uiState.value.copy(
                downloadState = DownloadState.Failed(t.message ?: "Cannot start download service"),
                downloadingModelId = null
            )
        }
    }

    fun cancelDownload() {
        val id = _uiState.value.downloadingModelId
        AppLogger.d("ChatVM", "cancelDownload: $id")
        ModelDownloadService.cancel(application, id)
        try {
            if (id != null) AppProvider.modelRepository.cancelDownload(id)
        } catch (_: Throwable) {
        }
        _uiState.value = _uiState.value.copy(
            downloadState = DownloadState.Idle,
            downloadingModelId = null
        )
        addMessage(Message(text = "Скачивание отменено.", sender = Message.Sender.SYSTEM))
        loadModels()
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
                    Message(text = "Сначала скачай и выбери модель.", sender = Message.Sender.SYSTEM)
                )
            }
        } else {
            stopHttpServer()
            addMessage(Message(text = "HTTP-сервер остановлен.", sender = Message.Sender.SYSTEM))
        }
    }

    fun deleteModel(model: LlmModel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppProvider.modelRepository.deleteModel(model.id)
                }
                if (_uiState.value.selectedModel?.id == model.id) {
                    AppProvider.initFake(application)
                    stopHttpServer()
                    _uiState.value = _uiState.value.copy(isRealEngine = false)
                }
                addMessage(
                    Message(text = "🗑 Модель ${model.name} удалена.", sender = Message.Sender.SYSTEM)
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
        ChatHistoryStore.clear(application)
        _uiState.value = _uiState.value.copy(
            messages = listOf(Message(text = "История очищена.", sender = Message.Sender.SYSTEM))
        )
        scheduleSaveHistory()
    }

    private fun addMessage(message: Message) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + message)
        scheduleSaveHistory()
    }

    private fun updateLastMessage(message: Message) {
        val messages = _uiState.value.messages.toMutableList()
        if (messages.isNotEmpty()) {
            messages[messages.lastIndex] = message
            _uiState.value = _uiState.value.copy(messages = messages)
        }
        scheduleSaveHistory()
    }

    private fun scheduleSaveHistory() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            ChatHistoryStore.save(application, _uiState.value.messages)
        }
    }

    data class ChatUiState(
        val messages: List<Message> = emptyList(),
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
        val hasCustomStorage: Boolean = false,
        val logsEnabled: Boolean = true,
        val logsPanelExpanded: Boolean = false,
        val hfToken: String = "",
        val hfUrlInput: String = "",
        val accelPref: String = "auto"
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
        try {
            application.unregisterReceiver(downloadReceiver)
        } catch (_: Throwable) {
        }
        saveJob?.cancel()
        ChatHistoryStore.save(application, _uiState.value.messages)
        // Do NOT cancel download service on VM clear — it should keep running in background
        stopHttpServer()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(application) as T
        }
    }
}
