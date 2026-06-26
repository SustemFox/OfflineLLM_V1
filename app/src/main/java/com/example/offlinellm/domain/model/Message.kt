package com.example.offlinellm.domain.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Sender { USER, LLM, SYSTEM }
}
