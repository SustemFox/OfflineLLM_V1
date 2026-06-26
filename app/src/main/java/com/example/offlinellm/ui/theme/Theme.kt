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
    primaryColor: Color = Color(0xFF8E44AD),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
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

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
