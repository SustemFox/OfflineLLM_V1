package com.example.offlinellm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.ui.theme.AssistantBubble
import com.example.offlinellm.ui.theme.ErrorBubble
import com.example.offlinellm.ui.theme.UserBubble

@Composable
fun MessageItem(message: Message, modifier: Modifier = Modifier) {
    val isUser = message.sender == Message.Sender.USER
    val isSystem = message.sender == Message.Sender.SYSTEM
    val bubbleColor = when (message.sender) {
        Message.Sender.USER -> UserBubble
        Message.Sender.LLM -> AssistantBubble
        Message.Sender.SYSTEM -> ErrorBubble.copy(alpha = 0.35f)
    }
    val textColor = when {
        isUser -> Color.White
        isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.Black
    }
    val style = if (isSystem) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyLarge
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = when {
            isUser -> Arrangement.End
            isSystem -> Arrangement.Center
            else -> Arrangement.Start
        }
    ) {
        Column(
            modifier = Modifier
                .padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = if (isSystem) 8.dp else 12.dp)
        ) {
            Text(text = message.text, color = textColor, style = style)
        }
    }
}
