package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Text(state.selectedModel?.name ?: "No model selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = { viewModel.downloadSelectedModel() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedModel != null && state.downloadState !is DownloadState.InProgress
            ) { Text("Download selected model") }
            Button(
                onClick = { viewModel.clearChat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Clear chat") }
            Button(
                onClick = { viewModel.toggleTheme() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Toggle dark theme") }
        }
    }
}
