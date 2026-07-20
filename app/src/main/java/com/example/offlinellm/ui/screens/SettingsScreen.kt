package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.DownloadState
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.llama.ModelLoader
import com.example.offlinellm.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    state: ChatViewModel.ChatUiState,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Backend Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isNativeAvailable)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hardware Acceleration", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.isNativeAvailable) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (state.isNativeAvailable)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                state.activeBackend.contains("NPU", ignoreCase = true) ->
                                    "⚡ Hexagon NPU — fastest"
                                state.activeBackend.contains("Vulkan", ignoreCase = true) || state.activeBackend.contains("GPU", ignoreCase = true) ->
                                    "🚀 GPU (Vulkan/OpenCL)"
                                state.isNativeAvailable -> "💻 CPU (NEON)"
                                else -> "⚠️ Native libs not loaded — using fake mode"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Model Selection
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (state.availableModels.isEmpty()) {
                Text("No models found. Download one below.")
            }

            state.availableModels.forEach { model ->
                ModelCard(
                    model = model,
                    isSelected = state.selectedModel?.id == model.id,
                    onClick = { viewModel.selectModel(model) }
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(12.dp))

            // Download button
            Button(
                onClick = { viewModel.downloadSelectedModel() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedModel != null &&
                        state.downloadState !is DownloadState.InProgress &&
                        !state.isLoading
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download selected model")
            }

            when (val ds = state.downloadState) {
                is DownloadState.InProgress -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = ds.progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(ds.progress * 100).toInt()}%",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is DownloadState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Error: ${ds.reason}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is DownloadState.Completed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "✅ Model ready. Activate it in the model list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {}
            }

            Spacer(Modifier.height(16.dp))

            // HTTP Server Section
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("🌐 HTTP Server (Host Model)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(
                "Start an OpenAI-compatible API server. " +
                        "Connect from Kai, OpenClaw, or any HTTP client.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.isServerRunning) "Running on port ${state.serverPort}"
                    else "Server stopped",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        val model = state.selectedModel ?: return@Button
                        val path = com.example.offlinellm.llama.ModelLoader
                            .getModelsDirectory(
                                androidx.compose.ui.platform.LocalContext.current
                            )
                            .resolve("${model.id}.gguf")

                        if (state.isServerRunning) viewModel.stopHttpServer()
                        else viewModel.startHttpServer(modelPath = path.absolutePath)
                    },
                    enabled = state.selectedModel != null
                ) {
                    Text(if (state.isServerRunning) "Stop" else "Start")
                }
            }

            if (state.isServerRunning) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connect via: http://192.168.x.x:${state.serverPort}/v1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            // Actions
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.clearChat() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) { Text("Clear chat") }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.toggleTheme() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Toggle dark theme") }

            Spacer(Modifier.height(8.dp))

            // Info
            Text(
                "OfflineLLM_V1 — llama.cpp engine\n" +
                        "GGUF models | OpenCL/Vulkan/NPU acceleration\n" +
                        "OpenAI-compatible API server",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: LlmModel,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge)
                Row {
                    Text(
                        "• ${model.parameterCount}  • ${model.quantType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                model.sizeFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            if (model.isDownloaded) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
