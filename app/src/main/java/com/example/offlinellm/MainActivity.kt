package com.example.offlinellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }
            var primaryColor by remember { mutableStateOf(Color(0xFF8E44AD)) }

            MaterialTheme(
                colorScheme = if (isDarkMode) {
                    darkColorScheme(
                        primary = primaryColor,
                        secondary = primaryColor,
                        background = Color(0xFF121212),
                        surface = Color(0xFF1E1E1E)
                    )
                } else {
                    lightColorScheme(
                        primary = primaryColor,
                        secondary = primaryColor,
                        background = Color(0xFFF5F5F5),
                        surface = Color(0xFFFFFFFF)
                    )
                }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp(
                        isDarkMode = isDarkMode,
                        setDarkMode = { isDarkMode = it },
                        primaryColor = primaryColor,
                        setPrimaryColor = { primaryColor = it }
                    )
                }
            }
        }
    }
}

data class Message(
    val text: String,
    val isUser: Boolean,
    val isSystem: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(
    isDarkMode: Boolean,
    setDarkMode: (Boolean) -> Unit,
    primaryColor: Color,
    setPrimaryColor: (Color) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var messages by remember {
        mutableStateOf(
            listOf(
                Message(
                    "Привет! Я твой будущий оффлайн-помощник. Сейчас я в режиме имитации. Выбери цвет в настройках и нажми «Скачать», чтобы увидеть живой прогресс!",
                    false,
                    true
                )
            )
        )
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Animate smooth progress
    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        animationSpec = tween(durationMillis = 150, easing = LinearEasing),
        label = "progress"
    )

    // Simulate download with live progress
    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            downloadProgress = 0f
            val totalSteps = 100
            for (step in 1..totalSteps) {
                delay(50)
                // Variable speed: fast start, slower middle, fast finish
                val baseProgress = step.toFloat() / totalSteps
                val wobble = ((Math.random() - 0.5) * 0.06).toFloat()
                downloadProgress = (baseProgress + wobble).coerceIn(0f, 0.97f)
            }
            downloadProgress = 1f
            delay(400)
            messages = messages + Message("✅ Модель успешно загружена! (Имитация завершена)", false, true)
            isDownloading = false
            downloadProgress = 0f
        }
    }

    // Auto-scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // === Settings Bottom Sheet ===
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
            ) {
                Text(
                    "Настройки",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(24.dp))

                // Dark mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Тёмная тема", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (isDarkMode) "Включена" else "Выключена",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = isDarkMode, onCheckedChange = setDarkMode)
                }

                Spacer(Modifier.height(24.dp))

                // Color picker
                Text("Цвет акцента", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val colorOptions = listOf(
                        Color(0xFF8E44AD) to "Фиолетовый",
                        Color(0xFF3498DB) to "Синий",
                        Color(0xFF2ECC71) to "Зелёный",
                        Color(0xFFE74C3C) to "Красный",
                        Color(0xFFF39C12) to "Оранжевый"
                    )
                    colorOptions.forEach { (color, _) ->
                        val isSelected = color == primaryColor
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { setPrimaryColor(color) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    border = BorderStroke(3.dp, Color.White),
                                    modifier = Modifier.size(44.dp)
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }

    // === Main Layout ===
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Offline LLM",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(messages, key = { "${it.text}_${messages.indexOf(it)}" }) { msg ->
                MessageBubble(msg)
            }
        }

        // Download progress bar
        AnimatedVisibility(visible = isDownloading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    "Загрузка модели: ${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
        }

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                shape = MaterialTheme.shapes.large,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        messages = messages + Message(text, true)
                        messages = messages + Message(
                            "Бот: [Имитация ответа на: $text]",
                            false
                        )
                        text = ""
                    }
                },
                shape = MaterialTheme.shapes.large,
                enabled = text.isNotBlank()
            ) {
                Text("Отправить")
            }
        }

        // Download button
        Button(
            onClick = { isDownloading = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            enabled = !isDownloading,
            shape = MaterialTheme.shapes.large
        ) {
            Text(if (isDownloading) "Загрузка..." else "Скачать модель (Имитация)")
        }

        // Signature
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Powered by Kai",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun MessageBubble(msg: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = when {
                msg.isSystem -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                msg.isUser -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = msg.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = when {
                    msg.isSystem -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    msg.isUser -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                fontSize = if (msg.isSystem) 13.sp else MaterialTheme.typography.bodyMedium.fontSize
            )
        }
    }
}
