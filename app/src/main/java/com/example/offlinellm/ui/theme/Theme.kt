package com.example.offlinellm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun OfflineLlmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    primaryColor: Color = Color(0xFF4F8C8D),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = primaryColor,
            secondary = Color(0xFFE0A458),
            tertiary = Color(0xFF82B1FF),
            background = Color(0xFF101416),
            surface = Color(0xFF171D1F),
            surfaceVariant = Color(0xFF20292B)
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            secondary = Color(0xFF9A641C),
            tertiary = Color(0xFF386AA3),
            background = Color(0xFFF5F7F6),
            surface = Color.White,
            surfaceVariant = Color(0xFFE8EFED)
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
