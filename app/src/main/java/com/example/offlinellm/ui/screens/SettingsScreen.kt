package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.ModelsDirectoryManager
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatViewModel.ChatUiState,
    onToggleServer: (Boolean) -> Unit,
    onDownloadModel: (LlmModel) -> Unit,
    onDeleteModel: (LlmModel) -> Unit,
    onSelectModel: (LlmModel) -> Unit,
    onRefresh: () -> Unit,
    onSetStoragePath: (String?) -> Unit = {},
    onResetStoragePath: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // HTTP Server Section
        item {
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
                    Column {
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
                                text = "Порт: ${state.serverPort ?: 8080}",
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
        
        // Backend Info Section
        item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Бэкенд",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ускоритель: ${state.activeBackend}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Модель: ${state.selectedModel?.name ?: "не выбрана"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        }
        
        // Storage Section
        item {
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
                    text = state.storagePath,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onResetStoragePath,
                        modifier = Modifier.weight(1f),
                        enabled = state.hasCustomStorage
                    ) {
                        Text("Сбросить")
                    }
                }
                var showPathInput by remember { mutableStateOf(false) }
                var pathText by remember { mutableStateOf("") }

                if (!state.hasCustomStorage) {
                    Button(
                        onClick = { showPathInput = !showPathInput },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Указать свою папку")
                    }
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

                if (state.hasCustomStorage) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ты можешь вручную указать путь к папке с моделями на SD-карте или в общей памяти.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        }
        
        // Log Section
        item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            var expanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 Логи",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (expanded) {
                            IconButton(onClick = { AppLogger.clear() }) {
                                Icon(Icons.Default.Delete, "Очистить", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { onRefresh() }) {
                                Icon(Icons.Default.Refresh, "Обновить", modifier = Modifier.size(20.dp))
                            }
                        }
                        Switch(
                            checked = expanded,
                            onCheckedChange = { expanded = it }
                        )
                    }
                }
                val localCtx = LocalContext.current
                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { AppLogger.copyToClipboard(localCtx) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Копировать логи")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val logText = remember { mutableStateOf("") }
                    LaunchedEffect(expanded) {
                        logText.value = AppLogger.getLogText()
                    }
                    LaunchedEffect(Unit) {
                        // Refresh logs every 2 seconds while expanded
                        while (true) {
                            kotlinx.coroutines.delay(2000)
                            if (expanded) logText.value = AppLogger.getLogText()
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = logText.value.ifEmpty { "Логов пока нет" },
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
        
        // Models Section
        item {
            Text(
                text = "Модели",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Загрузка моделей...")
                    }
                }
            }
        }
        if (!state.isLoading) {
            items(state.availableModels) { model ->
                ModelCard(
                        model = model,
                        isActive = model.id == state.selectedModel?.id,
                        onSelect = { onSelectModel(model) },
                        onDownload = { onDownloadModel(model) },
                        onDelete = { onDeleteModel(model) },
                        downloadState = state.downloadState,
                        selectedModel = state.selectedModel
                    )
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Обновить список")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ModelCard(
    model: LlmModel,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    downloadState: DownloadState = DownloadState.Idle,
    selectedModel: LlmModel? = null
) {
    val isDownloading = downloadState is DownloadState.InProgress && model.id == selectedModel?.id
    val downloadProgress = (downloadState as? DownloadState.InProgress)?.progress ?: 0f
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
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
                if (isActive) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("Активна")
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${model.sizeFormatted}MB | ${model.quantType}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
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
                            "Загрузка… " + (downloadProgress * 100).toInt() + "%"
                        else
                            "Подключение…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (model.isDownloaded) {
                    Button(
                        onClick = onSelect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Выбрать")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Удалить")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        enabled = !isDownloading
                    ) {
                        if (isDownloading) {
                            Text("Загрузка…")
                        } else {
                            Text("Скачать")
                        }
                    }
                }
            }
        }
    }
}
