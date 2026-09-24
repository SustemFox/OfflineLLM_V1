package com.example.offlinellm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinellm.ui.chat.ChatUiState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NotificationsTab(
    state: ChatUiState,
    cb: SettingsCallbacks,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.notifications.isEmpty()) {
            item { Text("Уведомлений пока нет") }
        } else {
            item {
                TextButton(
                    onClick = cb.onClearNotifications,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Очистить уведомления") }
            }
            items(state.notifications, key = { it.id }) { notification ->
                SettingsCard(
                    title = DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(notification.time))
                ) {
                    Text(notification.text)
                }
            }
        }
    }
}
