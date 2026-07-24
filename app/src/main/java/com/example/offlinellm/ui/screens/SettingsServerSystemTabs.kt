package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.example.offlinellm.ui.chat.ChatUiState
import kotlinx.coroutines.delay

@Composable
internal fun ServerTab(
    state: ChatUiState,
    cb: SettingsCallbacks,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsCard(
                title = "HTTP-сервер",
                subtitle = "OpenAI-compatible /v1 (models, chat/completions, SSE stream, /health)"
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
                    Switch(checked = state.isServerRunning, onCheckedChange = cb.onToggleServer)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.serverPortInput,
                        onValueChange = cb.onServerPortInput,
                        label = { Text("Порт") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = cb.onApplyServerPort) { Text("OK") }
                    IconButton(onClick = cb.onRefreshIps) {
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
internal fun SystemTab(
    state: ChatUiState,
    cb: SettingsCallbacks,
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
                    Switch(checked = state.isDarkMode, onCheckedChange = { cb.onToggleTheme() })
                }
            }
        }

        item(key = "history") {
            SettingsCard(
                title = "История чата",
                subtitle = "Сообщения сохраняются локально на устройстве"
            ) {
                OutlinedButton(onClick = cb.onClearChat, modifier = Modifier.fillMaxWidth()) {
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
                    Switch(checked = state.logsEnabled, onCheckedChange = cb.onSetLogsEnabled)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Показать панель")
                    Switch(checked = expanded, onCheckedChange = cb.onSetLogsPanelExpanded)
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