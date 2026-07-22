@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.ui.chat.ChatUiState

private enum class SettingsTab(val title: String) {
    Models("Модели"),
    Llm("LLM"),
    Server("Сервер"),
    System("Система"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    callbacks: SettingsCallbacks,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = SettingsTab.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (tabs[tabIndex] == SettingsTab.Models) {
                        IconButton(onClick = callbacks.onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(tab.title) },
                    )
                }
            }

            when (tabs[tabIndex]) {
                SettingsTab.Models -> ModelsTab(state = state, cb = callbacks)
                SettingsTab.Llm -> LlmTab(state = state, cb = callbacks)
                SettingsTab.Server -> ServerTab(state = state, cb = callbacks)
                SettingsTab.System -> SystemTab(state = state, cb = callbacks)
            }
        }
    }
}