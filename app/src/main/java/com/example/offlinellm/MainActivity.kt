package com.example.offlinellm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.offlinellm.ui.chat.ChatViewModel
import com.example.offlinellm.ui.screens.ChatScreen
import com.example.offlinellm.ui.screens.SettingsScreen
import com.example.offlinellm.ui.theme.OfflineLlmTheme

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(app))
    val state by viewModel.uiState.collectAsState()

    OfflineLlmTheme(darkTheme = state.isDarkMode, primaryColor = state.primaryColor) {
        NavHost(navController = navController, startDestination = "chat") {
            composable("chat") {
                ChatScreen(
                    viewModel = viewModel,
                    state = state,
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onToggleServer = { viewModel.toggleServer(it) },
                    onDownloadModel = { viewModel.downloadModel(it) },
                    onCancelDownload = { viewModel.cancelDownload() },
                    onDeleteModel = { viewModel.deleteModel(it) },
                    onSelectModel = { viewModel.selectModel(it) },
                    onRefresh = { viewModel.refreshModels() },
                    onSetStoragePath = { viewModel.setCustomStoragePath(it) },
                    onResetStoragePath = { viewModel.resetStoragePath() },
                    onToggleTheme = { viewModel.toggleTheme() },
                    onSetLogsEnabled = { viewModel.setLogsEnabled(it) },
                    onSetLogsPanelExpanded = { viewModel.setLogsPanelExpanded(it) },
                    onHfTokenChange = { viewModel.setHfToken(it) },
                    onHfUrlChange = { viewModel.setHfUrlInput(it) },
                    onDownloadHfUrl = { viewModel.downloadFromHfUrl() },
                    onClearChat = { viewModel.clearChat() },
                    onAccelPref = { viewModel.setAccelPref(it) },
                    onServerPortInput = { viewModel.setServerPortInput(it) },
                    onApplyServerPort = { viewModel.applyServerPort() },
                    onRefreshIps = { viewModel.refreshLocalIps() },
                    onTemperature = { viewModel.setTemperature(it) },
                    onTopP = { viewModel.setTopP(it) },
                    onMaxTokens = { viewModel.setMaxTokens(it) },
                    onNCtx = { viewModel.setNCtx(it) },
                    onThreads = { viewModel.setThreads(it) },
                    onSystemPrompt = { viewModel.setSystemPrompt(it) },
                    onShowThinking = { viewModel.setShowThinking(it) },
                    onRepeatPenalty = { viewModel.setRepeatPenalty(it) },
                    onFrequencyPenalty = { viewModel.setFrequencyPenalty(it) },
                    onNGpuLayers = { viewModel.setNGpuLayers(it) },
                )
            }
        }
    }
}
