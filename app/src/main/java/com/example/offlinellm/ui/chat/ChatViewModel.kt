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
import com.example.offlinellm.data.local.NetworkUtils
import com.example.offlinellm.data.repository.LocalLlmRepository
import com.example.offlinellm.data.service.LlmHttpServer
import com.example.offlinellm.data.service.ModelDownloadService
import com.example.offlinellm.di.AppProvider
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.domain.model.ResponseParser
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
    private var genJob: Job? = null

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
                "🧠 Блок мышления + настройки LLM в ⚙\n" +
                "🌐 HTTP: IP и порт показываются при включении\n" +
                "1) ⚙ → скачай модель\n" +
                "2) «Выбрать»\n" +
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
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application),
            serverPort = AppPreferences.getServerPort(application),
            serverPortInput = AppPreferences.getServerPort(application).toString(),
            localIps = NetworkUtils.getLocalIpv4Addresses(application),
            temperature = AppPreferences.getTemperature(application),
            topP = AppPreferences.getTopP(application),
            maxTokens = AppPreferences.getMaxTokens(application),
            nCtx = AppPreferences.getNCtx(application),
            threads = AppPreferences.getThreads(application),
            systemPrompt = AppPreferences.getSystemPrompt(application),
            showThinking = AppPreferences.isShowThinking(application),
            repeatPenalty = AppPreferences.getRepeatPenalty(application),
            frequencyPenalty = AppPreferences.getFrequencyPenalty(application),
            nGpuLayers = AppPreferences.getNGpuLayers(application)
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
            isNativeAvailable = AppProvider.isNativeAvailable(),
            localIps = NetworkUtils.getLocalIpv4Addresses(application)
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
                val port = _uiState.value.serverPort
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
                            "Остаёмся в demo-режиме.",
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
        if (_uiState.value.isGenerating) return

        addMessage(Message(text = trimmed, sender = Message.Sender.USER))
        _uiState.value = _uiState.value.copy(inputText = "")

        // refresh sampling from prefs before each gen
        try {
            (AppProvider.llmRepository as? LocalLlmRepository)?.applySamplingFromPrefs()
        } catch (_: Throwable) {
        }

        if (AppProvider.useFake) {
            generateResponse(trimmed)
        } else {
            generateRealResponse(trimmed)
        }
    }

    private fun applyParsedToMessage(raw: String, base: Message): Message {
        val parts = ResponseParser.parse(raw, _uiState.value.showThinking)
        return base.copy(
            text = parts.answer,
            thinking = parts.thinking,
            // auto-expand while thinking streams; collapse when answer appears if was auto
            thinkingExpanded = base.thinkingExpanded ||
                (parts.thinking != null && parts.answer.isBlank())
        )
    }

    private fun generateResponse(prompt: String) {
        genJob?.cancel()
        genJob = viewModelScope.launch {
            var assistantMessage: Message? = null
            _uiState.value = _uiState.value.copy(isGenerating = true)
            try {
                AppProvider.llmRepository.generateResponse(prompt)
                    .catch { error ->
                        addMessage(
                            Message(
                                text = "Error: ${error.localizedMessage ?: "Failed"}",
                                sender = Message.Sender.SYSTEM
                            )
                        )
                    }
                    .collect { partialText ->
                        if (assistantMessage == null) {
                            val base = Message(text = "", sender = Message.Sender.LLM)
                            assistantMessage = applyParsedToMessage(partialText, base)
                            addMessage(assistantMessage!!)
                        } else {
                            assistantMessage = applyParsedToMessage(partialText, assistantMessage!!)
                            updateLastMessage(assistantMessage!!)
                        }
                    }
            } finally {
                _uiState.value = _uiState.value.copy(isGenerating = false)
                scheduleSaveHistory()
            }
        }
    }

    private fun generateRealResponse(prompt: String) {
        genJob?.cancel()
        genJob = viewModelScope.launch(Dispatchers.IO) {
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
                                val base = Message(text = "", sender = Message.Sender.LLM)
                                assistantMessage = applyParsedToMessage(token, base)
                                addMessage(assistantMessage!!)
                            } else {
                                assistantMessage = applyParsedToMessage(token, assistantMessage!!)
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

    fun toggleThinking(messageId: String) {
        val msgs = _uiState.value.messages.map {
            if (it.id == messageId) it.copy(thinkingExpanded = !it.thinkingExpanded) else it
        }
        _uiState.value = _uiState.value.copy(messages = msgs)
    }

    fun startHttpServer(port: Int = _uiState.value.serverPort) {
        viewModelScope.launch {
            try {
                stopHttpServer()
                val ips = NetworkUtils.getLocalIpv4Addresses(application)
                val usePort = port.coerceIn(1024, 65535)
                AppPreferences.setServerPort(application, usePort)
                val server = LlmHttpServer(
                    port = usePort,
                    generate = { prompt ->
                        try {
                            (AppProvider.llmRepository as? LocalLlmRepository)?.applySamplingFromPrefs()
                        } catch (_: Throwable) {
                        }
                        AppProvider.llmRepository.generateResponse(prompt)
                    },
                    modelId = { _uiState.value.selectedModel?.id ?: "local-model" }
                )
                withContext(Dispatchers.IO) { server.start() }
                httpServer = server
                val ipLine = if (ips.isEmpty()) {
                    "IP не найден (Wi‑Fi?). Пробуй http://127.0.0.1:$usePort/v1 с устройства"
                } else {
                    ips.joinToString("\n") { ip ->
                        "• ${NetworkUtils.openaiBase(ip, usePort)}"
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isServerRunning = true,
                    serverPort = usePort,
                    serverPortInput = usePort.toString(),
                    localIps = ips,
                    serverBaseUrls = ips.map { NetworkUtils.openaiBase(it, usePort) }
                )
                addMessage(
                    Message(
                        text = "🌐 HTTP-сервер запущен (порт $usePort)\n" +
                            "OpenAI base URL:\n$ipLine\n" +
                            "Эндпоинты: /v1/models , /v1/chat/completions",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "startHttpServer failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isServerRunning = false)
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
            _uiState.value = _uiState.value.copy(
                isServerRunning = false,
                serverBaseUrls = emptyList()
            )
        }
    }

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun setServerPortInput(text: String) {
        _uiState.value = _uiState.value.copy(serverPortInput = text.filter { it.isDigit() }.take(5))
    }

    fun applyServerPort() {
        val p = _uiState.value.serverPortInput.toIntOrNull()
        if (p == null || p !in 1024..65535) {
            addMessage(
                Message(
                    text = "Порт должен быть числом 1024–65535.",
                    sender = Message.Sender.SYSTEM
                )
            )
            return
        }
        AppPreferences.setServerPort(application, p)
        _uiState.value = _uiState.value.copy(serverPort = p, serverPortInput = p.toString())
        if (_uiState.value.isServerRunning) {
            startHttpServer(p)
        } else {
            addMessage(
                Message(text = "Порт сохранён: $p (применится при старте сервера).", sender = Message.Sender.SYSTEM)
            )
        }
    }

    fun refreshLocalIps() {
        _uiState.value = _uiState.value.copy(
            localIps = NetworkUtils.getLocalIpv4Addresses(application)
        )
    }

    // --- LLM settings ---
    fun setTemperature(v: Float) {
        AppPreferences.setTemperature(application, v)
        _uiState.value = _uiState.value.copy(temperature = AppPreferences.getTemperature(application))
        applyLiveSampling()
    }

    fun setTopP(v: Float) {
        AppPreferences.setTopP(application, v)
        _uiState.value = _uiState.value.copy(topP = AppPreferences.getTopP(application))
        applyLiveSampling()
    }

    fun setMaxTokens(v: Int) {
        AppPreferences.setMaxTokens(application, v)
        _uiState.value = _uiState.value.copy(maxTokens = AppPreferences.getMaxTokens(application))
        applyLiveSampling()
    }

    fun setNCtx(v: Int) {
        AppPreferences.setNCtx(application, v)
        _uiState.value = _uiState.value.copy(nCtx = AppPreferences.getNCtx(application))
        addMessage(
            Message(
                text = "n_ctx=$v — перезагрузи модель («Выбрать»), чтобы применить контекст.",
                sender = Message.Sender.SYSTEM
            )
        )
    }

    fun setThreads(v: Int) {
        AppPreferences.setThreads(application, v)
        _uiState.value = _uiState.value.copy(threads = AppPreferences.getThreads(application))
        addMessage(
            Message(
                text = "Потоки=$v — перезагрузи модель, чтобы применить.",
                sender = Message.Sender.SYSTEM
            )
        )
    }

    fun setSystemPrompt(v: String) {
        AppPreferences.setSystemPrompt(application, v)
        _uiState.value = _uiState.value.copy(systemPrompt = v)
    }

    fun setShowThinking(v: Boolean) {
        AppPreferences.setShowThinking(application, v)
        _uiState.value = _uiState.value.copy(showThinking = v)
    }

    fun setRepeatPenalty(v: Float) {
        AppPreferences.setRepeatPenalty(application, v)
        _uiState.value = _uiState.value.copy(repeatPenalty = AppPreferences.getRepeatPenalty(application))
        applyLiveSampling()
    }

    fun setFrequencyPenalty(v: Float) {
        AppPreferences.setFrequencyPenalty(application, v)
        _uiState.value = _uiState.value.copy(frequencyPenalty = AppPreferences.getFrequencyPenalty(application))
        applyLiveSampling()
    }

    fun setNGpuLayers(v: Int) {
        val c = v.coerceIn(0, 999)
        AppPreferences.setNGpuLayers(application, c)
        _uiState.value = _uiState.value.copy(nGpuLayers = c)
    }


    private fun applyLiveSampling() {
        try {
            (AppProvider.llmRepository as? LocalLlmRepository)?.applySamplingFromPrefs()
        } catch (_: Throwable) {
        }
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
    }

    fun downloadFromHfUrl() {
        val url = _uiState.value.hfUrlInput.trim()
        if (url.isBlank() || !url.contains("http", ignoreCase = true)) {
            addMessage(Message(text = "Вставь корректный Hugging Face URL (.gguf).", sender = Message.Sender.SYSTEM))
            return
        }
        AppPreferences.setLastHfUrl(application, url)
        val info = ModelLoader.modelInfoFromUrl(url)
        downloadModel(
            LlmModel(
                id = info.id,
                name = info.name,
                sizeBytes = info.fileSizeBytes,
                downloadUrl = info.downloadUrl,
                quantType = info.quantType,
                parameterCount = info.parameterCount
            )
        )
    }

    fun selectModel(model: LlmModel) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
        AppPreferences.setSelectedModelId(application, model.id)
        if (!model.isDownloaded) {
            addMessage(Message(text = "Модель «${model.name}» ещё не скачана.", sender = Message.Sender.SYSTEM))
            return
        }
        val path = try {
            AppProvider.modelRepository.getModelPath(model.id)
        } catch (_: Throwable) {
            null
        }
        if (path.isNullOrBlank()) {
            addMessage(Message(text = "Файл модели не найден.", sender = Message.Sender.SYSTEM))
            return
        }
        switchToRealEngine(path)
    }

    fun downloadModel(model: LlmModel) {
        if (model.isDownloaded) {
            selectModel(model)
            return
        }
        if (model.downloadUrl.isBlank()) {
            addMessage(Message(text = "Нет URL для скачивания.", sender = Message.Sender.SYSTEM))
            return
        }
        if (_uiState.value.downloadState is DownloadState.InProgress) {
            addMessage(Message(text = "Уже идёт скачивание.", sender = Message.Sender.SYSTEM))
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedModel = model,
            downloadingModelId = model.id,
            downloadState = DownloadState.InProgress(0f)
        )
        try {
            ModelDownloadService.start(application, model.id, model.downloadUrl, model.name)
            addMessage(
                Message(
                    text = "⬇️ Скачивание «${model.name}» в фоне (уведомление).",
                    sender = Message.Sender.SYSTEM
                )
            )
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(
                downloadState = DownloadState.Failed(t.message ?: "FGS fail"),
                downloadingModelId = null
            )
        }
    }

    fun cancelDownload() {
        val id = _uiState.value.downloadingModelId
        ModelDownloadService.cancel(application, id)
        try {
            if (id != null) AppProvider.modelRepository.cancelDownload(id)
        } catch (_: Throwable) {
        }
        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Idle, downloadingModelId = null)
        addMessage(Message(text = "Скачивание отменено.", sender = Message.Sender.SYSTEM))
        loadModels()
    }

    fun clearDownloadState() {
        if (_uiState.value.downloadState is DownloadState.InProgress) return
        _uiState.value = _uiState.value.copy(downloadState = DownloadState.Idle, downloadingModelId = null)
    }

    fun toggleServer(enabled: Boolean) {
        if (enabled) {
            val model = _uiState.value.selectedModel
            if (model != null && model.isDownloaded && _uiState.value.isRealEngine) {
                startHttpServer(_uiState.value.serverPort)
            } else if (model != null && model.isDownloaded) {
                addMessage(
                    Message(
                        text = "Сначала «Выбрать» у скачанной модели.",
                        sender = Message.Sender.SYSTEM
                    )
                )
            } else {
                addMessage(Message(text = "Сначала скачай и выбери модель.", sender = Message.Sender.SYSTEM))
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
                addMessage(Message(text = "🗑 ${model.name} удалена.", sender = Message.Sender.SYSTEM))
                loadModels()
            } catch (e: Exception) {
                addMessage(Message(text = "Удаление: ${e.message}", sender = Message.Sender.SYSTEM))
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
        val accelPref: String = "auto",
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val maxTokens: Int = 256,
        val nCtx: Int = 2048,
        val threads: Int = 4,
        val systemPrompt: String = "",
        val showThinking: Boolean = true,
        val repeatPenalty: Float = 1.15f,
        val frequencyPenalty: Float = 0.15f
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
                    } catch (_: Exception) {
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
        genJob?.cancel()
        saveJob?.cancel()
        ChatHistoryStore.save(application, _uiState.value.messages)
        stopHttpServer()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(application) as T
        }
    }
}
