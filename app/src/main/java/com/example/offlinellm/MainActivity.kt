package com.example.offlinellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.offlinellm.domain.model.LlmModel
import com.example.offlinellm.ui.chat.ChatViewModel
import com.example.offlinellm.ui.screens.ChatScreen
import com.example.offlinellm.ui.screens.SettingsScreen
import com.example.offlinellm.ui.theme.OfflineLlmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )
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
                    onToggleServer = { viewModel.toggleServer(it) },
                    onDownloadModel = { viewModel.downloadModel(it) },
                    onDeleteModel = { viewModel.deleteModel(it) },
                    onSelectModel = { viewModel.selectModel(it) },
                    onRefresh = { viewModel.refreshModels() },
                    onSetStoragePath = { viewModel.setCustomStoragePath(it) },
                    onResetStoragePath = { viewModel.resetStoragePath() }
                )
            }
        }
    }
}
