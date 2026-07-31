package com.example.offlinellm.ui.screens

import com.example.offlinellm.domain.model.LlmModel

/**
 * Grouped callbacks for [SettingsScreen] so the root composable signature stays small.
 * Built from [com.example.offlinellm.ui.chat.ChatViewModel] in MainActivity.
 */
data class SettingsCallbacks(
    val onBack: () -> Unit = {},
    val onToggleServer: (Boolean) -> Unit = {},
    val onDownloadModel: (LlmModel) -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onDeleteModel: (LlmModel) -> Unit = {},
    val onSelectModel: (LlmModel) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSetStoragePath: (String?) -> Unit = {},
    val onResetStoragePath: () -> Unit = {},
    val onToggleTheme: () -> Unit = {},
    val onSetLogsEnabled: (Boolean) -> Unit = {},
    val onSetLogsPanelExpanded: (Boolean) -> Unit = {},
    val onHfTokenChange: (String) -> Unit = {},
    val onHfUrlChange: (String) -> Unit = {},
    val onDownloadHfUrl: () -> Unit = {},
    val onHfSearchQueryChange: (String) -> Unit = {},
    val onHfSearch: () -> Unit = {},
    val onHfSelectRepo: (String) -> Unit = {},
    val onHfDownloadFile: (com.example.offlinellm.data.remote.HfGgufFile) -> Unit = {},
    val onHfClearSelection: () -> Unit = {},
    val onHfToggleManualUrl: () -> Unit = {},
    val onClearChat: () -> Unit = {},
    val onAccelPref: (String) -> Unit = {},
    val onServerPortInput: (String) -> Unit = {},
    val onApplyServerPort: () -> Unit = {},
    val onRefreshIps: () -> Unit = {},
    val onTemperature: (Float) -> Unit = {},
    val onTopP: (Float) -> Unit = {},
    val onMaxTokens: (Int) -> Unit = {},
    val onNCtx: (Int) -> Unit = {},
    val onThreads: (Int) -> Unit = {},
    val onSystemPrompt: (String) -> Unit = {},
    val onShowThinking: (Boolean) -> Unit = {},
    val onRepeatPenalty: (Float) -> Unit = {},
    val onFrequencyPenalty: (Float) -> Unit = {},
    val onNGpuLayers: (Int) -> Unit = {},
    val onRootMode: (Boolean) -> Unit = {},
    val onRootDirectPath: (String) -> Unit = {},
    val onRootSkipMaterialize: (Boolean) -> Unit = {},
    val onRootRequest: () -> Unit = {},
    val onRootProbe: () -> Unit = {},
    val onRootUseSafHintPath: () -> Unit = {},
)