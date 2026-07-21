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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
) {
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onSetStoragePath(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
            item(key = "title_spacer") {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // HTTP Server
            item(key = "http_server") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "HTTP Сервер (хостинг)",
                            style = MaterialTheme.typography.titleMedium
                        )
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
                                    color = if (state.isServerRunning)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.isServerRunning) {
                                    Text(
                                        text = "http://<телефон>:${state.serverPort ?: 8080}/v1",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "OpenAI-compatible API для Kai / curl",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = state.isServerRunning,
                                onCheckedChange = onToggleServer
                            )
                        }
                    }
                }
            }

            // Backend
            item(key = "backend") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Бэкенд", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ускоритель: ${state.activeBackend.ifBlank { "—" }}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Модель: ${state.selectedModel?.name ?: "не выбрана"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when {
                                state.isLoading -> "Статус: загрузка модели…"
                                state.isRealEngine -> "Статус: llama.cpp (real)"
                                else -> "Статус: demo / fake (выбери скачанную модель)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.isNativeAvailable) {
                            Text(
                                text = "Native libs: OK",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "Native libs: недоступны на этом устройстве/ABI",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Theme
            item(key = "theme") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Тема", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (state.isDarkMode) "Тёмная" else "Светлая",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.isDarkMode,
                            onCheckedChange = { onToggleTheme() }
                        )
                    }
                }
            }

            // Storage
            item(key = "storage") {
                var showPathInput by remember { mutableStateOf(false) }
                var pathText by remember { mutableStateOf("") }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Хранилище моделей",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.storagePath.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { folderPicker.launch(null) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Папка")
                            }
                            OutlinedButton(
                                onClick = { showPathInput = !showPathInput },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Путь")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onResetStoragePath,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.hasCustomStorage
                        ) {
                            Text("Сбросить на внутреннюю память")
                        }

                        if (showPathInput) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pathText,
                                onValueChange = { pathText = it },
                                label = { Text("Путь к папке") },
                                placeholder = { Text("/sdcard/OfflineLLM/models") },
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
                            ) {
                                Text("Применить")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "GGUF можно класть вручную в эту папку или скачать из списка ниже.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Global download banner
            item(key = "download_banner") {
                when (val ds = state.downloadState) {
                    is DownloadState.InProgress -> {
                        val name = state.availableModels
                            .firstOrNull { it.id == state.downloadingModelId }
                            ?.name
                            ?: state.selectedModel?.name
                            ?: "модель"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Скачивание: $name",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (ds.progress > 0f) {
                                    LinearProgressIndicator(
                                        progress = { ds.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
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
                                        text = if (ds.progress > 0f)
                                            "${(ds.progress * 100).toInt()}%"
                                        else
                                            "Подключение / размер неизвестен…",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(onClick = onCancelDownload) {
                                        Text("Отмена")
                                    }
                                }
                            }
                        }
                    }
                    is DownloadState.Failed -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = "Ошибка загрузки: ${ds.reason}",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is DownloadState.Completed -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = "✅ Модель готова. Нажми «Выбрать», чтобы загрузить в движок.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> Unit
                }
            }

            // Logs
            item(key = "logs") {
                var expanded by remember { mutableStateOf(false) }
                var logText by remember { mutableStateOf("") }
                val localCtx = LocalContext.current

                LaunchedEffect(expanded) {
                    if (expanded) {
                        while (true) {
                            logText = AppLogger.getLogText()
                            delay(2000)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "📋 Логи", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (expanded) {
                                    IconButton(onClick = {
                                        AppLogger.clear()
                                        logText = ""
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Очистить",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(onClick = { logText = AppLogger.getLogText() }) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Обновить",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Switch(
                                    checked = expanded,
                                    onCheckedChange = { expanded = it }
                                )
                            }
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { AppLogger.copyToClipboard(localCtx) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📋 Копировать логи")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    text = logText.ifEmpty { "Логов пока нет" },
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

            // Models header
            item(key = "models_header") {
                Text(
                    text = "Модели",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (state.isLoading && state.availableModels.isEmpty()) {
                item(key = "models_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Загрузка списка моделей…")
                        }
                    }
                }
            }

            items(
                items = state.availableModels,
                key = { it.id }
            ) { model ->
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
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Обновить список")
                }
                Spacer(modifier = Modifier.height(24.dp))
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
    val isDownloadingThis =
        downloadState is DownloadState.InProgress && downloadingModelId == model.id
    val downloadProgress =
        (downloadState as? DownloadState.InProgress)?.progress ?: 0f
    val anyDownloadInProgress = downloadState is DownloadState.InProgress

    Card(
        onClick = {
            if (model.isDownloaded) onSelect()
        },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                when {
                    isActive -> Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("В движке")
                    }
                    model.isDownloaded -> Badge {
                        Text("Скачана")
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${model.sizeFormatted} · ${model.quantType} · ${model.parameterCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isDownloadingThis) {
                Spacer(modifier = Modifier.height(8.dp))
                if (downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (downloadProgress <= 0f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (downloadProgress > 0f)
                            "Загрузка… ${(downloadProgress * 100).toInt()}%"
                        else
                            "Подключение…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (engineLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Загрузка в llama.cpp…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
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
                        enabled = !isDownloadingThis && !engineLoading,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Удалить")
                    }
                } else if (isDownloadingThis) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
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
