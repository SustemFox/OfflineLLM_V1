package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.ui.chat.ChatViewModel
import com.example.offlinellm.ui.components.MessageItem
import com.example.offlinellm.ui.chat.TypingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline LLM") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        }
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            when (state.downloadState) {
                is DownloadState.InProgress -> {
                    val progress = (state.downloadState as DownloadState.InProgress).progress
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading: " + (progress * 100).toInt() + "%", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                is DownloadState.Failed -> {
                    Text("Error: " + (state.downloadState as DownloadState.Failed).reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                is DownloadState.Completed -> {
                    Text("Model ready", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
                else -> {}
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.messages, key = { it.id }) { message -> MessageItem(message = message) }
                if (state.isGenerating) { item { TypingIndicator() } }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = { viewModel.updateInput(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    maxLines = 5
                )
                IconButton(
                    onClick = { viewModel.sendMessage(state.inputText) },
                    enabled = !state.isGenerating && state.inputText.isNotBlank()
                ) {
                    if (state.isGenerating) { CircularProgressIndicator(modifier = Modifier.padding(4.dp)) }
                    else { Icon(Icons.Default.Send, contentDescription = "Send") }
                }
            }
        }
    }
}
