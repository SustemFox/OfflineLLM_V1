package com.example.offlinellm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.offlinellm.ui.chat.ChatViewModel
import com.example.offlinellm.ui.screens.ChatScreen
import com.example.offlinellm.ui.screens.SettingsCallbacks
import com.example.offlinellm.ui.screens.SettingsScreen
import com.example.offlinellm.ui.theme.OfflineLlmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppRoot() }
    }
}

private data class DrawerEntry(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val route: String)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as android.app.Application
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory(app))
    val state by viewModel.uiState.collectAsState()
    val entries = listOf(
        DrawerEntry("Чат", Icons.Default.Chat, "chat"),
        DrawerEntry("Модели", Icons.Default.Download, "settings/0"),
        DrawerEntry("Параметры LLM", Icons.Default.Tune, "settings/1"),
        DrawerEntry("Уведомления", Icons.Default.NotificationsNone, "settings/2"),
        DrawerEntry("Система", Icons.Default.Settings, "settings/3"),
    )

    OfflineLlmTheme(darkTheme = state.isDarkMode, primaryColor = state.primaryColor) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(292.dp)) {
                    TopAppBar(title = { Text("Offline LLM") })
                    Spacer(Modifier.width(1.dp))
                    entries.forEach { entry ->
                        NavigationDrawerItem(
                            label = { Text(entry.label) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(entry.route) { launchSingleTop = true }
                            },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxHeight(0.075f)
                        )
                    }
                }
            }
        ) {
            NavHost(navController = navController, startDestination = "chat") {
                composable("chat") {
                    ChatScreen(
                        viewModel = viewModel,
                        state = state,
                        onOpenSettings = { navController.navigate("settings/1") },
                        onOpenNotifications = { navController.navigate("settings/2") },
                        onOpenMenu = { scope.launch { drawerState.open() } }
                    )
                }
                composable("settings/{tab}") { backStackEntry ->
                    SettingsScreen(
                        state = state,
                        initialTab = backStackEntry.arguments?.getString("tab")?.toIntOrNull() ?: 0,
                        callbacks = SettingsCallbacks(
                            onBack = { navController.navigate("chat") { popUpTo("chat") { inclusive = false } } },
                            onDownloadModel = viewModel::downloadModel,
                            onCancelDownload = viewModel::cancelDownload,
                            onDeleteModel = viewModel::deleteModel,
                            onSelectModel = viewModel::selectModel,
                            onRefresh = viewModel::refreshModels,
                            onSetStoragePath = viewModel::setCustomStoragePath,
                            onResetStoragePath = viewModel::resetStoragePath,
                            onToggleTheme = viewModel::toggleTheme,
                            onSetLogsEnabled = viewModel::setLogsEnabled,
                            onSetLogsPanelExpanded = viewModel::setLogsPanelExpanded,
                            onHfTokenChange = viewModel::setHfToken,
                            onHfUrlChange = viewModel::setHfUrlInput,
                            onDownloadHfUrl = viewModel::downloadFromHfUrl,
                            onHfSearchQueryChange = viewModel::setHfSearchQuery,
                            onHfSearch = viewModel::searchHuggingFace,
                            onHfSelectRepo = viewModel::selectHfRepo,
                            onHfDownloadFile = viewModel::downloadHfFile,
                            onHfClearSelection = viewModel::clearHfSelection,
                            onHfToggleManualUrl = viewModel::toggleHfManualUrl,
                            onClearChat = viewModel::clearChat,
                            onClearNotifications = viewModel::clearNotifications,
                            onAccelPref = viewModel::setAccelPref,
                            onTemperature = viewModel::setTemperature,
                            onTopP = viewModel::setTopP,
                            onMaxTokens = viewModel::setMaxTokens,
                            onNCtx = viewModel::setNCtx,
                            onThreads = viewModel::setThreads,
                            onSystemPrompt = viewModel::setSystemPrompt,
                            onShowThinking = viewModel::setShowThinking,
                            onRepeatPenalty = viewModel::setRepeatPenalty,
                            onFrequencyPenalty = viewModel::setFrequencyPenalty,
                            onNGpuLayers = viewModel::setNGpuLayers,
                        )
                    )
                }
            }
        }
    }
}
