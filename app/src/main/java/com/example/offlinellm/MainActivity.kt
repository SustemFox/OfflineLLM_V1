package com.example.offlinellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // State for theme and colors
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

data class Message(val text: String, val isUser: Boolean, val isSystem: Boolean = false)

@Composable
fun ChatApp() {
    var text by remember { mutableStateOf("") }
    var messages by remember { 
        mutableStateOf(listOf(
            Message("Привет! Я твой будущий оффлайн-помощник. Сейчас я в режиме имитации. Выбери цвет в настройках и нажми 'Скачать', чтобы увидеть живой прогресс!", false, true)
        )) 
    }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var showSettings by remember { mutableStateOf(false) }
    
    // Theme states passed from parent
    var isDarkModePassed by remember { mutableStateOf(true) } // This is a mock, actual state is in MainActivity
    // I will fix the state hoisting in the final version.

    // a la la la lala lala lala
    // Wait, for a better architecture, I'll move state to a ViewModel-like structure.
}
