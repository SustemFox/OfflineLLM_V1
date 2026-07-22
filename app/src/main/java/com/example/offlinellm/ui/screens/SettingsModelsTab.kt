package com.example.offlinellm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.ui.chat.ChatUiState

@Composable
internal fun ModelsTab(
    state: ChatUiState,
    cb: SettingsCallbacks,
) {
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) cb.onSetStoragePath(uri.toString()) }

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
                        OutlinedButton(
                            onClick = cb.onCancelDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

        item(key = "hf_search") {
            SettingsCard(
                title = "Поиск на Hugging Face",
                subtitle = "GGUF-репозитории → файл → скачать. Токен не ускоряет CDN, но помогает при лимитах/gated."
            ) {
                OutlinedTextField(
                    value = state.hfToken,
                    onValueChange = cb.onHfTokenChange,
                    label = { Text("HF token (опц.)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.hfSearchQuery,
                    onValueChange = cb.onHfSearchQueryChange,
                    label = { Text("Поиск (напр. Qwen3.5, Llama 3.2)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = cb.onHfSearch,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.hfSearchLoading &&
                        state.downloadState !is DownloadState.InProgress
                ) {
                    if (state.hfSearchLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Ищем…")
                    } else {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Найти GGUF")
                    }
                }
                if (state.hfSearchError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.hfSearchError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.hfSelectedRepo != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📦 ${state.hfSelectedRepo}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = cb.onHfClearSelection) { Text("Сбросить") }
                    }
                    if (state.hfFilesLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Список .gguf…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.hfSelectedRepo == null && state.hfSearchResults.isNotEmpty()) {
            item { Text("Репозитории", style = MaterialTheme.typography.titleSmall) }
            items(state.hfSearchResults, key = { it.repoId }) { hit ->
                HfPickCard(
                    title = hit.repoId,
                    subtitle = "↓ ${hit.downloads} · ❤ ${hit.likes}" +
                        (if (hit.pipelineTag.isNotBlank()) " · ${hit.pipelineTag}" else ""),
                    onClick = { cb.onHfSelectRepo(hit.repoId) }
                )
            }
        }

        if (state.hfSelectedRepo != null && state.hfFiles.isNotEmpty()) {
            item { Text("Файлы .gguf (Q4 сверху)", style = MaterialTheme.typography.titleSmall) }
            items(state.hfFiles, key = { it.path }) { f ->
                HfPickCard(
                    title = f.fileName,
                    subtitle = "${f.sizeLabel} · ${f.path}",
                    enabled = state.downloadState !is DownloadState.InProgress,
                    onClick = { cb.onHfDownloadFile(f) }
                )
            }
        }

        item(key = "hf_manual") {
            TextButton(onClick = cb.onHfToggleManualUrl) {
                Text(if (state.hfShowManualUrl) "Скрыть ручной URL" else "Ручной URL .gguf")
            }
            if (state.hfShowManualUrl) {
                SettingsCard(title = "Прямая ссылка") {
                    OutlinedTextField(
                        value = state.hfUrlInput,
                        onValueChange = cb.onHfUrlChange,
                        label = { Text("URL .gguf") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = cb.onDownloadHfUrl,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.downloadState !is DownloadState.InProgress
                    ) {
                        Text("Скачать по URL")
                    }
                }
            }
        }

        item(key = "storage") {
            SettingsCard(
                title = "Хранилище моделей",
                subtitle = state.storagePath
            ) {
                Text(
                    "Системный выбор папки (SAF). Запись идёт через URI, не через путь /storage/… " +
                        "(иначе EACCES). Для llama файл кэшируется во внутренней памяти при «Выбрать».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Выбрать папку")
                }
                OutlinedButton(
                    onClick = cb.onResetStoragePath,
                    enabled = state.hasCustomStorage,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text("Сбросить на внутреннюю память")
                }
            }
        }

        item { Text("Каталог моделей", style = MaterialTheme.typography.titleMedium) }

        items(state.availableModels, key = { it.id }) { model ->
            ModelCard(
                model = model,
                isActive = model.id == state.selectedModel?.id && state.isRealEngine,
                isSelected = model.id == state.selectedModel?.id,
                downloadState = state.downloadState,
                downloadingModelId = state.downloadingModelId,
                engineLoading = state.isLoading && model.id == state.selectedModel?.id,
                onSelect = { cb.onSelectModel(model) },
                onDownload = { cb.onDownloadModel(model) },
                onCancel = cb.onCancelDownload,
                onDelete = { cb.onDeleteModel(model) }
            )
        }

        item {
            Button(onClick = cb.onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить список")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HfPickCard(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (enabled) {
                Text(
                    "Нажми →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
