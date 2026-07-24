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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.ui.chat.ChatUiState
import kotlin.math.roundToInt

@Composable
internal fun LlmTab(
    state: ChatUiState,
    cb: SettingsCallbacks,
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
                    "Ускоритель: Auto/CPU стабильно. OpenCL = эксп. GPU. Vulkan отключён в этой сборке (краш Adreno CreateFence).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to "Auto (CPU)",
                        "cpu" to "CPU",
                        "opencl" to "OpenCL",
                        "vulkan" to "Vulkan (off)"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = state.accelPref == id,
                            onClick = { cb.onAccelPref(id) },
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
                    onValueChange = cb.onTemperature,
                    valueRange = 0.05f..1.5f
                )
                Text("Top-p: ${"%.2f".format(state.topP)}")
                Slider(value = state.topP, onValueChange = cb.onTopP, valueRange = 0.1f..1f)
                Text("Max tokens: ${state.maxTokens}")
                Slider(
                    value = state.maxTokens.toFloat(),
                    onValueChange = { cb.onMaxTokens(it.roundToInt()) },
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
                    onValueChange = cb.onRepeatPenalty,
                    valueRange = 1.0f..1.5f
                )
                Text("Frequency penalty: ${"%.2f".format(state.frequencyPenalty)}")
                Slider(
                    value = state.frequencyPenalty,
                    onValueChange = cb.onFrequencyPenalty,
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
                    onValueChange = { cb.onNCtx((it / 256f).roundToInt() * 256) },
                    valueRange = 512f..4096f,
                    steps = 13
                )
                Text("Потоки CPU: ${state.threads}")
                Slider(
                    value = state.threads.toFloat(),
                    onValueChange = { cb.onThreads(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6
                )
                Text("GPU layers (OpenCL/Vulkan offload): ${state.nGpuLayers}")
                Text(
                    "0 = только CPU; 99 ≈ все слои. Нужен повторный «Выбрать».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.nGpuLayers.toFloat().coerceIn(0f, 99f),
                    onValueChange = { cb.onNGpuLayers(it.roundToInt()) },
                    valueRange = 0f..99f,
                    steps = 98
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
                    Switch(checked = state.showThinking, onCheckedChange = cb.onShowThinking)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = cb.onSystemPrompt,
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 10
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}