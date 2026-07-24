package com.example.offlinellm.ui.chat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences
import com.example.offlinellm.data.local.ChatHistoryStore
import com.example.offlinellm.data.local.ModelsDirectoryManager
import com.example.offlinellm.data.local.NetworkUtils
import com.example.offlinellm.data.remote.HfGgufFile
import com.example.offlinellm.data.remote.HfHubClient
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
                    updateState {
                        copy(
                            downloadingModelId = modelId,
                            downloadState = DownloadState.InProgress(progress.coerceIn(0f, 0.999f))
                        )
                    }
                }
                ModelDownloadService.STATUS_COMPLETED -> {
                    updateState {
                        copy(downloadState = DownloadState.Completed, downloadingModelId = null)
                    }
                    systemMsg("✅ Модель скачана (фон). Нажми «Выбрать», чтобы загрузить в движок.")
                    loadModels()
                }
                ModelDownloadService.STATUS_FAILED -> {
                    updateState {
                        copy(
                            downloadState = DownloadState.Failed(error ?: "Download failed"),
                            downloadingModelId = null
                        )
                    }
                    systemMsg("Ошибка скачивания: ${error ?: "unknown"}")
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
        val app = application

        _uiState.value = ChatUiState(
            messages = messages,
            isDarkMode = AppPreferences.isDarkMode(app),
            logsEnabled = AppPreferences.isLogsEnabled(app),
            logsPanelExpanded = AppPreferences.isLogsPanelExpanded(app),
            hfToken = AppPreferences.getHfToken(app),
            hfUrlInput = AppPreferences.getLastHfUrl(app),
            accelPref = AppPreferences.getAccelPref(app),
            storagePath = ModelsDirectoryManager.getStorageLabel(app),
            hasCustomStorage = ModelsDirectoryManager.hasCustomPath(app),
            serverPort = AppPreferences.getServerPort(app),
            serverPortInput = AppPreferences.getServerPort(app).toString(),
            localIps = NetworkUtils.getLocalIpv4Addresses(app),
            temperature = AppPreferences.getTemperature(app),
            topP = AppPreferences.getTopP(app),
            maxTokens = AppPreferences.getMaxTokens(app),
            nCtx = AppPreferences.getNCtx(app),
            threads = AppPreferences.getThreads(app),
            systemPrompt = AppPreferences.getSystemPrompt(app),
            showThinking = AppPreferences.isShowThinking(app),
            repeatPenalty = AppPreferences.getRepeatPenalty(app),
            frequencyPenalty = AppPreferences.getFrequencyPenalty(app),
            nGpuLayers = AppPreferences.getNGpuLayers(app),
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

    // --- helpers ---

    private fun updateState(block: ChatUiState.() -> ChatUiState) {
        _uiState.value = _uiState.value.block()
    }

    private fun systemMsg(text: String) {
        addMessage(Message(text = text, sender = Message.Sender.SYSTEM))
    }

    private fun applyLiveSampling() {
        try {
            (AppProvider.llmRepository as? LocalLlmRepository)?.applySamplingFromPrefs()
        } catch (_: Throwable) {
        }
    }

    // --- models / engine ---

    fun refreshModels() = loadModels()

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
        updateState {
            copy(
                availableModels = models,
                selectedModel = selected,
                activeBackend = backend,
                isNativeAvailable = AppProvider.isNativeAvailable(),
                localIps = NetworkUtils.getLocalIpv4Addresses(application)
            )
        }
    }

    fun switchToRealEngine(modelPath: String) {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
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
                updateState { copy(isRealEngine = true, activeBackend = backend) }
                systemMsg("✅ Модель загружена в llama.cpp ($backend).")
            } catch (e: Throwable) {
                AppLogger.e("ChatVM", "switchToRealEngine failed: ${e.message}", e)
                try {
                    AppProvider.initFake(application)
                } catch (_: Throwable) {
                }
                updateState { copy(isRealEngine = false) }
                systemMsg("Не удалось загрузить модель: ${e.message}\nОстаёмся в demo-режиме.")
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    // --- chat generation ---

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isGenerating) return

        addMessage(Message(text = trimmed, sender = Message.Sender.USER))
        updateState { copy(inputText = "") }
        applyLiveSampling()
        streamAssistantResponse(trimmed)
    }

    private fun applyParsedToMessage(raw: String, base: Message): Message {
        val parts = ResponseParser.parse(raw, _uiState.value.showThinking)
        return base.copy(
            text = parts.answer,
            thinking = parts.thinking,
            thinkingExpanded = base.thinkingExpanded ||
                (parts.thinking != null && parts.answer.isBlank())
        )
    }

    /** Unified stream path for fake + real engines. */
    private fun streamAssistantResponse(prompt: String) {
        genJob?.cancel()
        genJob = viewModelScope.launch(Dispatchers.IO) {
            var assistantMessage: Message? = null
            var lastUiMs = 0L
            var pendingRaw: String? = null
            try {
                withContext(Dispatchers.Main) {
                    updateState { copy(isGenerating = true) }
                }

                suspend fun flushUi(raw: String, force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastUiMs < UI_STREAM_MIN_MS) {
                        pendingRaw = raw
                        return
                    }
                    lastUiMs = now
                    pendingRaw = null
                    // Parse off main, only state write on Main
                    val base = assistantMessage ?: Message(text = "", sender = Message.Sender.LLM)
                    val parsed = applyParsedToMessage(raw, base)
                    withContext(Dispatchers.Main) {
                        if (assistantMessage == null) {
                            assistantMessage = parsed
                            addMessage(parsed, persist = false)
                        } else {
                            assistantMessage = parsed
                            updateLastMessage(parsed, persist = false)
                        }
                    }
                }

                AppProvider.llmRepository.generateResponse(prompt)
                    .catch { error ->
                        withContext(Dispatchers.Main) {
                            systemMsg(
                                if (AppProvider.useFake) {
                                    "Error: ${error.localizedMessage ?: "Failed"}"
                                } else {
                                    "Ошибка инференса: ${error.message ?: "Inference failed"}"
                                }
                            )
                        }
                    }
                    .collect { partial ->
                        flushUi(partial, force = false)
                    }
                // final frame
                pendingRaw?.let { flushUi(it, force = true) }
            } catch (t: Throwable) {
                AppLogger.e("ChatVM", "streamAssistantResponse failed: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    systemMsg("Ошибка инференса: ${t.message ?: t.javaClass.simpleName}")
                    if (!AppProvider.useFake) {
                        try {
                            AppProvider.initFake(application)
                            updateState { copy(isRealEngine = false) }
                        } catch (_: Throwable) {
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    updateState { copy(isGenerating = false) }
                    scheduleSaveHistory()
                }
            }
        }
    }

    companion object {
        /** Min interval between chat bubble redraws while streaming (avoids Main ANR). */
        private const val UI_STREAM_MIN_MS = 80L
    }

    fun toggleThinking(messageId: String) {
        updateState {
            copy(
                messages = messages.map {
                    if (it.id == messageId) it.copy(thinkingExpanded = !it.thinkingExpanded)
                    else it
                }
            )
        }
    }

    // --- HTTP server ---

    fun startHttpServer(port: Int = _uiState.value.serverPort) {
        viewModelScope.launch {
            try {
                stopHttpServer()
                val ips = NetworkUtils.getLocalIpv4Addresses(application)
                val usePort = port.coerceIn(1024, 65535)
                AppPreferences.setServerPort(application, usePort)
                val server = LlmHttpServer(
                    port = usePort,
                    generate = { userPrompt, systemPrompt ->
                        applyLiveSampling()
                        AppProvider.llmRepository.generateResponse(
                            userPrompt,
                            systemPrompt.takeIf { it.isNotBlank() }
                        )
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
                updateState {
                    copy(
                        isServerRunning = true,
                        serverPort = usePort,
                        serverPortInput = usePort.toString(),
                        localIps = ips,
                        serverBaseUrls = ips.map { NetworkUtils.openaiBase(it, usePort) }
                    )
                }
                systemMsg(
                    "🌐 HTTP-сервер запущен (порт $usePort)\n" +
                        "OpenAI base URL:\n$ipLine\n" +
                        "Эндпоинты: /v1/models , /v1/chat/completions"
                )
            } catch (e: Exception) {
                AppLogger.e("ChatVM", "startHttpServer failed: ${e.message}", e)
                updateState { copy(isServerRunning = false) }
                systemMsg("Не удалось запустить HTTP-сервер: ${e.message}")
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
            updateState { copy(isServerRunning = false, serverBaseUrls = emptyList()) }
        }
    }

    fun updateInput(text: String) = updateState { copy(inputText = text) }

    fun setServerPortInput(text: String) {
        updateState { copy(serverPortInput = text.filter { it.isDigit() }.take(5)) }
    }

    fun applyServerPort() {
        val p = _uiState.value.serverPortInput.toIntOrNull()
        if (p == null || p !in 1024..65535) {
            systemMsg("Порт должен быть числом 1024–65535.")
            return
        }
        AppPreferences.setServerPort(application, p)
        updateState { copy(serverPort = p, serverPortInput = p.toString()) }
        if (_uiState.value.isServerRunning) {
            startHttpServer(p)
        } else {
            systemMsg("Порт сохранён: $p (применится при старте сервера).")
        }
    }

    fun refreshLocalIps() {
        updateState { copy(localIps = NetworkUtils.getLocalIpv4Addresses(application)) }
    }

    // --- LLM / system prefs ---

    fun setTemperature(v: Float) {
        AppPreferences.setTemperature(application, v)
        updateState { copy(temperature = AppPreferences.getTemperature(application)) }
        applyLiveSampling()
    }

    fun setTopP(v: Float) {
        AppPreferences.setTopP(application, v)
        updateState { copy(topP = AppPreferences.getTopP(application)) }
        applyLiveSampling()
    }

    fun setMaxTokens(v: Int) {
        AppPreferences.setMaxTokens(application, v)
        updateState { copy(maxTokens = AppPreferences.getMaxTokens(application)) }
        applyLiveSampling()
    }

    fun setNCtx(v: Int) {
        AppPreferences.setNCtx(application, v)
        updateState { copy(nCtx = AppPreferences.getNCtx(application)) }
        systemMsg("n_ctx=$v — перезагрузи модель («Выбрать»), чтобы применить контекст.")
    }

    fun setThreads(v: Int) {
        AppPreferences.setThreads(application, v)
        updateState { copy(threads = AppPreferences.getThreads(application)) }
        systemMsg("Потоки=$v — перезагрузи модель, чтобы применить.")
    }

    fun setSystemPrompt(v: String) {
        AppPreferences.setSystemPrompt(application, v)
        updateState { copy(systemPrompt = v) }
    }

    fun setShowThinking(v: Boolean) {
        AppPreferences.setShowThinking(application, v)
        updateState { copy(showThinking = v) }
    }

    fun setRepeatPenalty(v: Float) {
        AppPreferences.setRepeatPenalty(application, v)
        updateState { copy(repeatPenalty = AppPreferences.getRepeatPenalty(application)) }
        applyLiveSampling()
    }

    fun setFrequencyPenalty(v: Float) {
        AppPreferences.setFrequencyPenalty(application, v)
        updateState { copy(frequencyPenalty = AppPreferences.getFrequencyPenalty(application)) }
        applyLiveSampling()
    }

    fun setNGpuLayers(v: Int) {
        val c = v.coerceIn(0, 999)
        AppPreferences.setNGpuLayers(application, c)
        updateState { copy(nGpuLayers = c) }
        systemMsg("n_gpu_layers=$c — перезагрузи модель («Выбрать»), чтобы применить.")
    }

    fun toggleTheme() {
        val next = !_uiState.value.isDarkMode
        AppPreferences.setDarkMode(application, next)
        updateState { copy(isDarkMode = next) }
    }

    fun setLogsEnabled(enabled: Boolean) {
        AppPreferences.setLogsEnabled(application, enabled)
        updateState { copy(logsEnabled = enabled) }
    }

    fun setLogsPanelExpanded(expanded: Boolean) {
        AppPreferences.setLogsPanelExpanded(application, expanded)
        updateState { copy(logsPanelExpanded = expanded) }
    }

    fun setHfToken(token: String) {
        AppPreferences.setHfToken(application, token)
        updateState { copy(hfToken = token) }
    }

    fun setHfUrlInput(url: String) = updateState { copy(hfUrlInput = url) }

    fun setHfSearchQuery(q: String) = updateState { copy(hfSearchQuery = q) }

    fun toggleHfManualUrl() = updateState { copy(hfShowManualUrl = !hfShowManualUrl) }

    fun clearHfSelection() = updateState {
        copy(hfSelectedRepo = null, hfFiles = emptyList(), hfFilesLoading = false)
    }

    fun searchHuggingFace() {
        val q = _uiState.value.hfSearchQuery.trim()
        if (q.isBlank()) {
            systemMsg("Введи запрос для поиска на HF.")
            return
        }
        viewModelScope.launch {
            updateState {
                copy(
                    hfSearchLoading = true,
                    hfSearchError = null,
                    hfSelectedRepo = null,
                    hfFiles = emptyList()
                )
            }
            try {
                val token = _uiState.value.hfToken.ifBlank { null }
                val hits = withContext(Dispatchers.IO) {
                    HfHubClient.searchGgufModels(q, token)
                }
                updateState {
                    copy(
                        hfSearchLoading = false,
                        hfSearchResults = hits,
                        hfSearchError = if (hits.isEmpty()) "Ничего не найдено по «$q»" else null
                    )
                }
                if (hits.isNotEmpty()) {
                    systemMsg("HF: найдено ${hits.size} GGUF-репо. Выбери репозиторий, затем .gguf.")
                }
            } catch (t: Throwable) {
                AppLogger.e("ChatVM", "HF search: ${t.message}", t)
                updateState {
                    copy(
                        hfSearchLoading = false,
                        hfSearchResults = emptyList(),
                        hfSearchError = t.message ?: "search failed"
                    )
                }
                systemMsg("Поиск HF: ${t.message}")
            }
        }
    }

    fun selectHfRepo(repoId: String) {
        viewModelScope.launch {
            updateState {
                copy(
                    hfSelectedRepo = repoId,
                    hfFilesLoading = true,
                    hfFiles = emptyList(),
                    hfSearchError = null
                )
            }
            try {
                val token = _uiState.value.hfToken.ifBlank { null }
                val files = withContext(Dispatchers.IO) {
                    HfHubClient.listGgufFiles(repoId, token)
                }
                updateState {
                    copy(
                        hfFilesLoading = false,
                        hfFiles = files,
                        hfSearchError = if (files.isEmpty()) {
                            "В $repoId нет .gguf (или только mmproj)"
                        } else null
                    )
                }
            } catch (t: Throwable) {
                AppLogger.e("ChatVM", "HF list: ${t.message}", t)
                updateState {
                    copy(
                        hfFilesLoading = false,
                        hfFiles = emptyList(),
                        hfSearchError = t.message ?: "list failed"
                    )
                }
                systemMsg("Список файлов HF: ${t.message}")
            }
        }
    }

    fun downloadHfFile(file: HfGgufFile) {
        val repo = _uiState.value.hfSelectedRepo ?: "hf"
        val info = ModelLoader.modelInfoFromUrl(file.resolveUrl)
        val size = if (file.sizeBytes > 0L) file.sizeBytes else info.fileSizeBytes
        downloadModel(
            LlmModel(
                id = info.id,
                name = "${info.name} ($repo)",
                sizeBytes = size,
                downloadUrl = file.resolveUrl,
                quantType = info.quantType,
                parameterCount = info.parameterCount
            )
        )
    }


    fun setAccelPref(pref: String) {
        AppPreferences.setAccelPref(application, pref)
        updateState { copy(accelPref = pref) }
    }

    // --- models download / select ---

    fun downloadFromHfUrl() {
        val url = _uiState.value.hfUrlInput.trim()
        if (url.isBlank() || !url.contains("http", ignoreCase = true)) {
            systemMsg("Вставь корректный Hugging Face URL (.gguf).")
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
        updateState { copy(selectedModel = model) }
        AppPreferences.setSelectedModelId(application, model.id)
        if (!model.isDownloaded) {
            systemMsg("Модель «${model.name}» ещё не скачана.")
            return
        }
        // getModelPath may materialize ~500MB from SAF → must NOT run on main (ANR)
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            systemMsg("Подготовка файла модели (кэш)…")
            val path = try {
                withContext(Dispatchers.IO) {
                    AppProvider.modelRepository.getModelPath(model.id)
                }
            } catch (t: Throwable) {
                AppLogger.e("ChatVM", "getModelPath: ${t.message}", t)
                null
            }
            if (path.isNullOrBlank()) {
                updateState { copy(isLoading = false) }
                systemMsg("Файл модели не найден.")
                return@launch
            }
            switchToRealEngine(path)
        }
    }

    fun downloadModel(model: LlmModel) {
        if (model.isDownloaded) {
            selectModel(model)
            return
        }
        if (model.downloadUrl.isBlank()) {
            systemMsg("Нет URL для скачивания.")
            return
        }
        if (_uiState.value.downloadState is DownloadState.InProgress) {
            systemMsg("Уже идёт скачивание.")
            return
        }
        updateState {
            copy(
                selectedModel = model,
                downloadingModelId = model.id,
                downloadState = DownloadState.InProgress(0f)
            )
        }
        try {
            ModelDownloadService.start(application, model.id, model.downloadUrl, model.name)
            systemMsg("⬇️ Скачивание «${model.name}» в фоне (уведомление).")
        } catch (t: Throwable) {
            updateState {
                copy(
                    downloadState = DownloadState.Failed(t.message ?: "FGS fail"),
                    downloadingModelId = null
                )
            }
        }
    }

    fun cancelDownload() {
        val id = _uiState.value.downloadingModelId
        ModelDownloadService.cancel(application, id)
        try {
            if (id != null) AppProvider.modelRepository.cancelDownload(id)
        } catch (_: Throwable) {
        }
        updateState { copy(downloadState = DownloadState.Idle, downloadingModelId = null) }
        systemMsg("Скачивание отменено.")
        loadModels()
    }

    fun clearDownloadState() {
        if (_uiState.value.downloadState is DownloadState.InProgress) return
        updateState { copy(downloadState = DownloadState.Idle, downloadingModelId = null) }
    }

    fun toggleServer(enabled: Boolean) {
        if (enabled) {
            val model = _uiState.value.selectedModel
            when {
                model != null && model.isDownloaded && _uiState.value.isRealEngine ->
                    startHttpServer(_uiState.value.serverPort)
                model != null && model.isDownloaded ->
                    systemMsg("Сначала «Выбрать» у скачанной модели.")
                else ->
                    systemMsg("Сначала скачай и выбери модель.")
            }
        } else {
            stopHttpServer()
            systemMsg("HTTP-сервер остановлен.")
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
                    updateState { copy(isRealEngine = false) }
                }
                systemMsg("🗑 ${model.name} удалена.")
                loadModels()
            } catch (e: Exception) {
                systemMsg("Удаление: ${e.message}")
            }
        }
    }

    fun clearChat() {
        ChatHistoryStore.clear(application)
        updateState {
            copy(messages = listOf(Message(text = "История очищена.", sender = Message.Sender.SYSTEM)))
        }
        scheduleSaveHistory()
    }

    private fun addMessage(message: Message, persist: Boolean = true) {
        updateState { copy(messages = messages + message) }
        if (persist) scheduleSaveHistory()
    }

    private fun updateLastMessage(message: Message, persist: Boolean = true) {
        val messages = _uiState.value.messages.toMutableList()
        if (messages.isNotEmpty()) {
            messages[messages.lastIndex] = message
            updateState { copy(messages = messages) }
        }
        if (persist) scheduleSaveHistory()
    }

    private fun scheduleSaveHistory() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            ChatHistoryStore.save(application, _uiState.value.messages)
        }
    }

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
                updateState {
                    copy(
                        storagePath = ModelsDirectoryManager.getStorageLabel(application),
                        hasCustomStorage = ModelsDirectoryManager.hasCustomPath(application)
                    )
                }
                loadModels()
            }
        }
    }

    fun resetStoragePath() {
        ModelsDirectoryManager.resetToDefault(application)
        updateState {
            copy(
                storagePath = ModelsDirectoryManager.getStorageLabel(application),
                hasCustomStorage = false
            )
        }
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