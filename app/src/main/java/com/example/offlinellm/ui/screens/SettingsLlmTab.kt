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
                    "Ускоритель — куда класть слои модели. " +
                        "Auto/CPU = только процессор (стабильно, предсказуемая скорость). " +
                        "OpenCL = попытка GPU offload (на Adreno часто без выигрыша или нестабильно). " +
                        "Vulkan в этой сборке вырезан (краши драйвера). " +
                        "Смена ускорителя и GPU layers требует снова «Выбрать» модель.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to "Auto (CPU)",
                        "cpu" to "CPU",
                        "opencl" to "OpenCL"
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
                subtitle = "На качество и длину ответа. Применяется сразу (без перезагрузки модели)"
            ) {
                Text("Temperature: ${"%.2f".format(state.temperature)}")
                Text(
                    "Случайность выбора токенов. Ниже (0.1–0.4) — суше, предсказуемее, меньше «воды». " +
                        "Выше (0.8–1.2) — креативнее, но больше бреда и риска зацикливания. " +
                        "⚡ На скорость токенов почти не влияет (только качество).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.temperature,
                    onValueChange = cb.onTemperature,
                    valueRange = 0.05f..1.5f
                )

                Spacer(Modifier.height(8.dp))
                Text("Top-p: ${"%.2f".format(state.topP)}")
                Text(
                    "Nucleus sampling: из скольки «вероятностной массы» брать кандидатов. " +
                        "0.7–0.9 обычно достаточно. Очень низкий top-p ≈ более жёсткий/скучный стиль. " +
                        "⚡ На скорость почти не влияет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(value = state.topP, onValueChange = cb.onTopP, valueRange = 0.1f..1f)

                Spacer(Modifier.height(8.dp))
                Text("Max tokens: ${state.maxTokens}")
                Text(
                    "Жёсткий потолок длины ответа (в токенах ≈ кусках слова). " +
                        "Меньше = короче ответ и быстрее конец генерации. " +
                        "⚡ Сильно влияет на время: 4B CPU ~0.5–3 tok/s на OP7 — " +
                        "128 tok ≈ десятки секунд, 512 tok ≈ минуты. HTTP API может переопределить max_tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                subtitle = "Против зацикливания абзацев (часто на маленьких моделях)"
            ) {
                Text("Repeat penalty: ${"%.2f".format(state.repeatPenalty)}")
                Text(
                    "Штраф за повтор недавних токенов. 1.0 = выкл. 1.15–1.35 обычно ок. " +
                        "Слишком высоко — ломает нормальные повторы (имена, списки). " +
                        "⚡ На скорость почти не влияет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.repeatPenalty,
                    onValueChange = cb.onRepeatPenalty,
                    valueRange = 1.0f..1.5f
                )

                Spacer(Modifier.height(8.dp))
                Text("Frequency penalty: ${"%.2f".format(state.frequencyPenalty)}")
                Text(
                    "Доп. штраф за часто встречавшиеся в ответе токены (глобальнее repeat). " +
                        "0 = выкл. 0.1–0.35 помогает от «каши»-петель. " +
                        "⚡ На скорость почти не влияет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                subtitle = "n_ctx / потоки / GPU layers — после смены снова «Выбрать» модель"
            ) {
                Text("n_ctx: ${state.nCtx}")
                Text(
                    "Размер контекстного окна (промпт + ответ в токенах). " +
                        "Больше = длиннее system/история/HTTP-диалоги влезают, но " +
                        "больше RAM и чуть медленнее prefill (обработка входа). " +
                        "⚡ 2048 быстрее и легче; 4096 удобнее для API; 8192 на 4B+телефоне может не влезть в память. " +
                        "На скорость каждого нового токена влияет слабее, чем max tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.nCtx.toFloat(),
                    onValueChange = { cb.onNCtx((it / 256f).roundToInt() * 256) },
                    valueRange = 512f..8192f,
                    steps = 29
                )

                Spacer(Modifier.height(8.dp))
                Text("Потоки CPU: ${state.threads}")
                Text(
                    "Сколько ядер CPU крутить decode. " +
                        "На 8-ядерном SD855 обычно 4–6: больше не всегда быстрее (нагрев/троттлинг). " +
                        "⚡ Прямо влияет на tok/s на CPU. 1 = медленно; 6–8 = максимум, но греется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.threads.toFloat(),
                    onValueChange = { cb.onThreads(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6
                )

                Spacer(Modifier.height(8.dp))
                Text("GPU layers (OpenCL offload): ${state.nGpuLayers}")
                Text(
                    "Сколько слоёв модели пытаться отдать GPU при OpenCL. " +
                        "0 = только CPU. 99 ≈ «все, что получится». " +
                        "На многих Adreno offload не даёт ускорения или падает — тогда оставь 0 / Auto. " +
                        "⚡ Если GPU реально работает — может ускорить; если нет — только риск и время на init.",
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
                            "Показывать текст мышления модели в чате (если модель его пишет). " +
                                "Выкл — в пузыре только финальный ответ. " +
                                "⚡ На скорость генерации не влияет (только UI). " +
                                "В system лучше держать /no_think, чтобы Qwen3.5 не тратил max tokens на think.",
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
                    supportingText = {
                        Text(
                            "Инструкция «кто ты». Длинный system + история едят n_ctx и " +
                                "замедляют prefill (старт ответа). Для скорости — коротко + /no_think."
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 10
                )
            }
        }

        item {
            Text(
                "Итого по скорости на телефоне: сильнее всего — размер модели, max tokens, " +
                    "потоки CPU и длина промпта (n_ctx/system). Temperature/top-p/penalty — про стиль.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
