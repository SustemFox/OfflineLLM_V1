package com.example.offlinellm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.offlinellm.domain.model.Message
import com.example.offlinellm.ui.theme.UserBubble
import com.example.offlinellm.ui.theme.AssistantBubble
import com.example.offlinellm.ui.theme.ErrorBubble

@Composable
fun MessageItem(message: Message, modifier: Modifier = Modifier) {
    val isUser = message.sender == Message.Sender.USER
    val bubbleColor = when (message.sender) {
        Message.Sender.USER -> UserBubble
        Message.Sender.LLM -> AssistantBubble
        else -> ErrorBubble
    }
    val textColor = if (isUser) Color.White else Color.Black
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(bubbleColor).padding(12.dp)
        ) {
            Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
