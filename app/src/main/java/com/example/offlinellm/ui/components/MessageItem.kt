package com.example.offlinellm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.example.offlinellm.domain.model.ResponseParser
import com.example.offlinellm.ui.theme.AssistantBubble
import com.example.offlinellm.ui.theme.ErrorBubble
import com.example.offlinellm.ui.theme.UserBubble

@Composable
fun MessageItem(
    message: Message,
    onToggleThinking: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
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
                .fillMaxWidth(if (isSystem) 0.95f else 0.88f)
        ) {
            if (!message.thinking.isNullOrBlank() && message.sender == Message.Sender.LLM) {
                val label = if (message.thinkingExpanded) "▾ Мышление" else "▸ Мышление"
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = onToggleThinking != null) { onToggleThinking?.invoke() }
                        .padding(bottom = 4.dp)
                )
                AnimatedVisibility(visible = message.thinkingExpanded) {
                    Text(
                        text = message.thinking ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF444444),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22000000))
                            .padding(8.dp)
                    )
                }
                if (message.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                }
            }
            val displayText = if (message.sender == Message.Sender.LLM) {
                ResponseParser.stripThinkTags(message.text)
            } else message.text
            if (displayText.isNotBlank() || message.sender != Message.Sender.LLM) {
                Text(text = displayText, color = textColor, style = style)
            } else if (message.thinking != null && !message.thinkingExpanded) {
                Text(
                    text = "…",
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
