@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.offlinellm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.ui.chat.ChatViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class SettingsTab(val title: String) {
    Models("Модели"),
    Llm("LLM"),
    Server("Сервер"),
    System("Система"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatViewModel.ChatUiState,
    onBack: () -> Unit = {},
    onToggleServer: (Boolean) -> Unit,
    onDownloadModel: (LlmModel) -> Unit,
    onCancelDownload: () -> Unit = {},
    onDeleteModel: (LlmModel) -> Unit,
    onSelectModel: (LlmModel) -> Unit,
    onRefresh: () -> Unit,
    onSetStoragePath: (String?) -> Unit = {},
    onResetStoragePath: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    onSetLogsEnabled: (Boolean) -> Unit = {},
    onSetLogsPanelExpanded: (Boolean) -> Unit = {},
    onHfTokenChange: (String) -> Unit = {},
    onHfUrlChange: (String) -> Unit = {},
    onDownloadHfUrl: () -> Unit = {},
    onClearChat: () -> Unit = {},
    onAccelPref: (String) -> Unit = {},
    onServerPortInput: (String) -> Unit = {},
    onApplyServerPort: () -> Unit = {},
    onRefreshIps: () -> Unit = {},
    onTemperature: (Float) -> Unit = {},
    onTopP: (Float) -> Unit = {},
    onMaxTokens: (Int) -> Unit = {},
    onNCtx: (Int) -> Unit = {},
    onThreads: (Int) -> Unit = {},
    onSystemPrompt: (String) -> Unit = {},
    onShowThinking: (Boolean) -> Unit = {},
    onRepeatPenalty: (Float) -> Unit = {},
    onFrequencyPenalty: (Float) -> Unit = {},
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = SettingsTab.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (tabs[tabIndex] == SettingsTab.Models) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(tab.title) },
                    )
                }
            }

            when (tabs[tabIndex]) {
                SettingsTab.Models -> ModelsTab(
                    state = state,
                    onDownloadModel = onDownloadModel,
                    onCancelDownload = onCancelDownload,
                    onDeleteModel = onDeleteModel,
                    onSelectModel = onSelectModel,
                    onRefresh = onRefresh,
                    onHfTokenChange = onHfTokenChange,
                    onHfUrlChange = onHfUrlChange,
                    onDownloadHfUrl = onDownloadHfUrl,
                    onSetStoragePath = onSetStoragePath,
                    onResetStoragePath = onResetStoragePath,
                )
                SettingsTab.Llm -> LlmTab(
                    state = state,
                    onTemperature = onTemperature,
                    onTopP = onTopP,
                    onMaxTokens = onMaxTokens,
                    onNCtx = onNCtx,
                    onThreads = onThreads,
                    onSystemPrompt = onSystemPrompt,
                    onShowThinking = onShowThinking,
                    onRepeatPenalty = onRepeatPenalty,
                    onFrequencyPenalty = onFrequencyPenalty,
                    onAccelPref = onAccelPref,
                )
                SettingsTab.Server -> ServerTab(
                    state = state,
                    onToggleServer = onToggleServer,
                    onServerPortInput = onServerPortInput,
                    onApplyServerPort = onApplyServerPort,
                    onRefreshIps = onRefreshIps,
                )
                SettingsTab.System -> SystemTab(
                    state = state,
                    onToggleTheme = onToggleTheme,
                    onSetLogsEnabled = onSetLogsEnabled,
                    onSetLogsPanelExpanded = onSetLogsPanelExpanded,
                    onClearChat = onClearChat,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ModelsTab(
    state: ChatViewModel.ChatUiState,
    onDownloadModel: (LlmModel) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (LlmModel) -> Unit,
    onSelectModel: (LlmModel) -> Unit,
    onRefresh: () -> Unit,
    onHfTokenChange: (String) -> Unit,
    onHfUrlChange: (String) -> Unit,
    onDownloadHfUrl: () -> Unit,
    onSetStoragePath: (String?) -> Unit,
    onResetStoragePath: () -> Unit,
) {
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onSetStoragePath(uri.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "download_banner") {
            when (val ds = state.downloadState) {
                is DownloadState.InProgress -> {
                    SettingsCard(title = "Скачивание…") {
                        if (ds.progress > 0f) {
                            LinearProgressIndicator(
                                progress = ds.progress.coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) {
                            Text("Отмена")
                        }
                    }
                }
                is DownloadState.Failed -> {
                    Text("Ошибка: ${ds.reason}", color = MaterialTheme.colorScheme.error)
                }
                is DownloadState.Completed -> {
                    Text("✅ Готово — нажми «Выбрать» у модели")
                }
                else -> Unit
            }
        }

        item(key = "hf") {
            SettingsCard(
                title = "Hugging Face",
                subtitle = "Прямой URL .gguf (можно свернуть — фоновая загрузка)"
            ) {
                OutlinedTextField(
                    value = state.hfUrlInput,
                    onValueChange = onHfUrlChange,
                    label = { Text("URL .gguf") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.hfToken,
                    onValueChange = onHfTokenChange,
                    label = { Text("HF token (опц.)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDownloadHfUrl,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.downloadState !is DownloadState.InProgress
                ) {
                    Text("Скачать с HF")
                }
            }
        }

        item(key = "storage") {
            var showPathInput by remember { mutableStateOf(false) }
            var pathText by remember { mutableStateOf("") }
            SettingsCard(title = "Хранилище моделей", subtitle = state.storagePath) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Папка")
                    }
                    OutlinedButton(
                        onClick = { showPathInput = !showPathInput },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Путь")
                    }
                }
                OutlinedButton(
                    onClick = onResetStoragePath,
                    enabled = state.hasCustomStorage,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Сбросить путь")
                }
                if (showPathInput) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pathText,
                        onValueChange = { pathText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Абсолютный путь / URI") }
                    )
                    Button(
                        onClick = {
                            if (pathText.isNotBlank()) {
                                onSetStoragePath(pathText.trim())
                                showPathInput = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Применить")
                    }
                }
            }
        }

        item {
            Text("Каталог моделей", style = MaterialTheme.typography.titleMedium)
        }

        items(state.availableModels, key = { it.id }) { model ->
            ModelCard(
                model = model,
                isActive = model.id == state.selectedModel?.id && state.isRealEngine,
                isSelected = model.id == state.selectedModel?.id,
                downloadState = state.downloadState,
                downloadingModelId = state.downloadingModelId,
                engineLoading = state.isLoading && model.id == state.selectedModel?.id,
                onSelect = { onSelectModel(model) },
                onDownload = { onDownloadModel(model) },
                onCancel = onCancelDownload,
                onDelete = { onDeleteModel(model) }
            )
        }

        item {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить список")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LlmTab(
    state: ChatViewModel.ChatUiState,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onNCtx: (Int) -> Unit,
    onThreads: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onShowThinking: (Boolean) -> Unit,
    onRepeatPenalty: (Float) -> Unit,
    onFrequencyPenalty: (Float) -> Unit,
    onAccelPref: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "backend") {
            SettingsCard(title = "Бэкенд") {
                Text("Активно: ${state.activeBackend.ifBlank { "—" }}")
                Text(
                    "Модель: ${state.selectedModel?.name ?: "—"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    when {
                        state.isLoading -> "Статус: загрузка…"
                        state.isRealEngine -> "Статус: llama.cpp"
                        else -> "Статус: demo (модель не выбрана)"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ускоритель (предпочтение; runtime пока CPU)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "cpu" to "CPU", "vulkan" to "Vulkan").forEach { (id, label) ->
                        FilterChip(
                            selected = state.accelPref == id,
                            onClick = { onAccelPref(id) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        item(key = "sampling") {
            SettingsCard(
                title = "Сэмплинг",
                subtitle = "Применяется к следующим ответам сразу"
            ) {
                Text("Temperature: ${"%.2f".format(state.temperature)}")
                Slider(
                    value = state.temperature,
                    onValueChange = onTemperature,
                    valueRange = 0.05f..1.5f
                )
                Text("Top-p: ${"%.2f".format(state.topP)}")
                Slider(value = state.topP, onValueChange = onTopP, valueRange = 0.1f..1f)
                Text("Max tokens: ${state.maxTokens}")
                Slider(
                    value = state.maxTokens.toFloat(),
                    onValueChange = { onMaxTokens(it.roundToInt()) },
                    valueRange = 32f..1024f,
                    steps = 30
                )
            }
        }

        item(key = "penalties") {
            SettingsCard(
                title = "Анти-повтор",
                subtitle = "Снижает зацикливание ответов"
            ) {
                Text("Repeat penalty: ${"%.2f".format(state.repeatPenalty)}")
                Slider(
                    value = state.repeatPenalty,
                    onValueChange = onRepeatPenalty,
                    valueRange = 1.0f..1.5f
                )
                Text("Frequency penalty: ${"%.2f".format(state.frequencyPenalty)}")
                Slider(
                    value = state.frequencyPenalty,
                    onValueChange = onFrequencyPenalty,
                    valueRange = 0f..0.5f
                )
            }
        }

        item(key = "engine") {
            SettingsCard(
                title = "Движок",
                subtitle = "n_ctx и потоки — после «Выбрать» модели заново"
            ) {
                Text("n_ctx: ${state.nCtx}")
                Slider(
                    value = state.nCtx.toFloat(),
                    onValueChange = { onNCtx((it / 256f).roundToInt() * 256) },
                    valueRange = 512f..4096f,
                    steps = 13
                )
                Text("Потоки CPU: ${state.threads}")
                Slider(
                    value = state.threads.toFloat(),
                    onValueChange = { onThreads(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6
                )
            }
        }

        item(key = "thinking") {
            SettingsCard(title = "Ответ") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Блок мышления", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Показывать <think>…</think> в чате",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.showThinking, onCheckedChange = onShowThinking)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = onSystemPrompt,
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 10
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ServerTab(
    state: ChatViewModel.ChatUiState,
    onToggleServer: (Boolean) -> Unit,
    onServerPortInput: (String) -> Unit,
    onApplyServerPort: () -> Unit,
    onRefreshIps: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsCard(
                title = "HTTP-сервер",
                subtitle = "OpenAI-compatible /v1 для Kai, curl, OpenClaw"
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (state.isServerRunning) "Сервер запущен" else "Сервер остановлен",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.isServerRunning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.isServerRunning, onCheckedChange = onToggleServer)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.serverPortInput,
                        onValueChange = onServerPortInput,
                        label = { Text("Порт") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = onApplyServerPort) { Text("OK") }
                    IconButton(onClick = onRefreshIps) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить IP")
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (state.isServerRunning && state.serverBaseUrls.isNotEmpty()) {
                    Text("Готовые URL", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            state.serverBaseUrls.joinToString("\n"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    val ips = state.localIps
                    Text(
                        if (ips.isEmpty()) {
                            "IP: не найден (включи Wi‑Fi / LAN)"
                        } else {
                            "IP устройства: ${ips.joinToString(", ")}\n" +
                                "После старта: http://<IP>:${state.serverPort}/v1"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SystemTab(
    state: ChatViewModel.ChatUiState,
    onToggleTheme: () -> Unit,
    onSetLogsEnabled: (Boolean) -> Unit,
    onSetLogsPanelExpanded: (Boolean) -> Unit,
    onClearChat: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "theme") {
            SettingsCard(title = "Оформление") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (state.isDarkMode) "Тёмная тема" else "Светлая тема")
                    Switch(checked = state.isDarkMode, onCheckedChange = { onToggleTheme() })
                }
            }
        }

        item(key = "history") {
            SettingsCard(
                title = "История чата",
                subtitle = "Сообщения сохраняются локально на устройстве"
            ) {
                OutlinedButton(onClick = onClearChat, modifier = Modifier.fillMaxWidth()) {
                    Text("Очистить историю")
                }
            }
        }

        item(key = "logs") {
            var logText by remember { mutableStateOf("") }
            val localCtx = LocalContext.current
            val expanded = state.logsPanelExpanded
            LaunchedEffect(expanded, state.logsEnabled) {
                if (expanded && state.logsEnabled) {
                    while (true) {
                        logText = AppLogger.getLogText()
                        delay(2000)
                    }
                }
            }
            SettingsCard(title = "Логи") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Запись логов")
                        Text(
                            "Сохраняется между запусками",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = state.logsEnabled, onCheckedChange = onSetLogsEnabled)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Показать панель")
                    Switch(checked = expanded, onCheckedChange = onSetLogsPanelExpanded)
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { AppLogger.copyToClipboard(localCtx) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Копировать")
                        }
                        IconButton(onClick = {
                            AppLogger.clear()
                            logText = ""
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Очистить")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            logText.ifEmpty { "пусто" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCard(
    model: LlmModel,
    isActive: Boolean,
    isSelected: Boolean,
    downloadState: DownloadState,
    downloadingModelId: String?,
    engineLoading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDownloadingThis =
        downloadState is DownloadState.InProgress && downloadingModelId == model.id
    val downloadProgress = (downloadState as? DownloadState.InProgress)?.progress ?: 0f
    val anyDownloadInProgress = downloadState is DownloadState.InProgress
    Card(
        onClick = { if (model.isDownloaded) onSelect() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                when {
                    isActive -> Badge { Text("В движке") }
                    model.isDownloaded -> Badge { Text("Скачана") }
                }
            }
            Text(
                "${model.sizeFormatted} · ${model.quantType}",
                style = MaterialTheme.typography.bodySmall
            )
            if (isDownloadingThis) {
                LinearProgressIndicator(
                    progress = downloadProgress.coerceAtLeast(0.01f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (engineLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isDownloaded) {
                    Button(
                        onClick = onSelect,
                        modifier = Modifier.weight(1f),
                        enabled = !engineLoading && !anyDownloadInProgress
                    ) {
                        Text(if (isActive) "Активна" else "Выбрать")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Удалить")
                    }
                } else if (isDownloadingThis) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Отменить")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        enabled = !anyDownloadInProgress && model.downloadUrl.isNotBlank()
                    ) {
                        Text("Скачать")
                    }
                }
            }
        }
    }
}
