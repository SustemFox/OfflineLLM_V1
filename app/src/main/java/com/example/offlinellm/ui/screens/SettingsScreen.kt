package com.example.offlinellm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
) {
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSetStoragePath(uri.toString())
    }

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
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "title_spacer") { Spacer(modifier = Modifier.height(4.dp)) }

            item(key = "http_server") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("HTTP Сервер (хостинг)", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.isServerRunning) "Сервер запущен" else "Сервер остановлен",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state.isServerRunning) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (state.isServerRunning)
                                        "http://<телефон>:${state.serverPort ?: 8080}/v1"
                                    else
                                        "OpenAI-compatible API для Kai / curl",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = state.isServerRunning, onCheckedChange = onToggleServer)
                        }
                    }
                }
            }

            item(key = "backend") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Бэкенд / ускорители", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Активно: ${state.activeBackend.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Модель: ${state.selectedModel?.name ?: "не выбрана"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                state.isLoading -> "Статус: загрузка модели…"
                                state.isRealEngine -> "Статус: llama.cpp (real)"
                                else -> "Статус: demo / fake"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.isNativeAvailable) "Native libs: OK" else "Native libs: недоступны",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isNativeAvailable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Предпочтение:", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "Auto", "cpu" to "CPU", "vulkan" to "Vulkan").forEach { (id, label) ->
                                FilterChip(
                                    selected = state.accelPref == id,
                                    onClick = { onAccelPref(id) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Text(
                            "Vulkan сработает только если backend собран в APK; иначе CPU.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item(key = "theme") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Тема", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (state.isDarkMode) "Тёмная (сохраняется)" else "Светлая (сохраняется)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.isDarkMode, onCheckedChange = { onToggleTheme() })
                    }
                }
            }

            item(key = "history") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("История чата", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Сообщения сохраняются локально и восстанавливаются после перезапуска.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onClearChat, modifier = Modifier.fillMaxWidth()) {
                            Text("Очистить историю")
                        }
                    }
                }
            }

            item(key = "storage") {
                var showPathInput by remember { mutableStateOf(false) }
                var pathText by remember { mutableStateOf("") }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Хранилище моделей", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.storagePath.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Папка")
                            }
                            OutlinedButton(onClick = { showPathInput = !showPathInput }, modifier = Modifier.weight(1f)) {
                                Text("Путь")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onResetStoragePath,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.hasCustomStorage
                        ) { Text("Сбросить на внутреннюю память") }
                        if (showPathInput) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pathText,
                                onValueChange = { pathText = it },
                                label = { Text("Путь к папке") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (pathText.isNotBlank()) {
                                        onSetStoragePath(pathText.trim())
                                        showPathInput = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Применить") }
                        }
                    }
                }
            }

            item(key = "hf") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Hugging Face", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Ссылка вида https://huggingface.co/.../resolve/main/*.gguf — скачивание идёт в фоне.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.hfUrlInput,
                            onValueChange = onHfUrlChange,
                            label = { Text("URL .gguf") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.hfToken,
                            onValueChange = onHfTokenChange,
                            label = { Text("HF token (опционально, для gated)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onDownloadHfUrl,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.downloadState !is DownloadState.InProgress
                        ) { Text("Скачать с Hugging Face") }
                    }
                }
            }

            item(key = "download_banner") {
                when (val ds = state.downloadState) {
                    is DownloadState.InProgress -> {
                        val name = state.availableModels.firstOrNull { it.id == state.downloadingModelId }?.name
                            ?: state.selectedModel?.name ?: "модель"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Скачивание (фон): $name", style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(8.dp))
                                if (ds.progress > 0f) {
                                    LinearProgressIndicator(progress = ds.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (ds.progress > 0f) "${(ds.progress * 100).toInt()}%" else "Подключение…",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(onClick = onCancelDownload) { Text("Отмена") }
                                }
                            }
                        }
                    }
                    is DownloadState.Failed -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text(
                                "Ошибка загрузки: ${ds.reason}",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is DownloadState.Completed -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Text(
                                "✅ Модель готова. Нажми «Выбрать».",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> Unit
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("📋 Логи", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (state.logsEnabled) "Запись включена (сохраняется)" else "Запись выключена (сохраняется)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.logsEnabled,
                                onCheckedChange = onSetLogsEnabled
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Показать панель", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (expanded) {
                                    IconButton(onClick = {
                                        AppLogger.clear()
                                        logText = ""
                                    }) {
                                        Icon(Icons.Default.Delete, "Очистить", Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { logText = AppLogger.getLogText() }) {
                                        Icon(Icons.Default.Refresh, "Обновить", Modifier.size(20.dp))
                                    }
                                }
                                Switch(
                                    checked = expanded,
                                    onCheckedChange = onSetLogsPanelExpanded
                                )
                            }
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { AppLogger.copyToClipboard(localCtx) }, modifier = Modifier.fillMaxWidth()) {
                                Text("📋 Копировать логи")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    text = when {
                                        !state.logsEnabled -> "Логирование выключено"
                                        logText.isEmpty() -> "Логов пока нет"
                                        else -> logText
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            item(key = "models_header") {
                Text("Модели", style = MaterialTheme.typography.titleMedium)
            }

            if (state.isLoading && state.availableModels.isEmpty()) {
                item(key = "models_loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Загрузка списка моделей…")
                        }
                    }
                }
            }

            items(items = state.availableModels, key = { it.id }) { model ->
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

            item(key = "refresh_btn") {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Обновить список") }
                Spacer(Modifier.height(24.dp))
            }
        }
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
    val isDownloadingThis = downloadState is DownloadState.InProgress && downloadingModelId == model.id
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                when {
                    isActive -> Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("В движке") }
                    model.isDownloaded -> Badge { Text("Скачана") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${model.sizeFormatted} · ${model.quantType} · ${model.parameterCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isDownloadingThis) {
                Spacer(Modifier.height(8.dp))
                if (downloadProgress > 0f) {
                    LinearProgressIndicator(progress = downloadProgress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    if (downloadProgress > 0f) "Загрузка… ${(downloadProgress * 100).toInt()}%" else "Подключение…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (engineLoading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Загрузка в llama.cpp…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isDownloaded) {
                    Button(onClick = onSelect, modifier = Modifier.weight(1f), enabled = !engineLoading && !anyDownloadInProgress) {
                        Text(if (isActive) "Активна" else "Выбрать")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !isDownloadingThis && !engineLoading,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Удалить") }
                } else if (isDownloadingThis) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отменить") }
                } else {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        enabled = !anyDownloadInProgress && model.downloadUrl.isNotBlank()
                    ) { Text("Скачать") }
                }
            }
        }
    }
}
