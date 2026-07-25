package com.example.offlinellm.data.local

import android.content.Context
import com.example.offlinellm.domain.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChatHistoryStore {
    private const val FILE = "chat_history.json"
    private const val MAX_MESSAGES = 400

    fun load(context: Context): List<Message> {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val seen = HashSet<String>()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sender = when (o.optString("sender")) {
                        "USER" -> Message.Sender.USER
                        "LLM" -> Message.Sender.LLM
                        else -> Message.Sender.SYSTEM
                    }
                    var id = o.optString("id", "")
                    if (id.isBlank() || !seen.add(id)) {
                        id = java.util.UUID.randomUUID().toString()
                        seen.add(id)
                    }
                    add(
                        Message(
                            id = id,
                            text = o.optString("text"),
                            sender = sender,
                            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                            thinking = o.optString("thinking", "").ifBlank { null }
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            AppLogger.e("ChatHistory", "load failed: ${t.message}", t)
            emptyList()
        }
    }

    fun save(context: Context, messages: List<Message>) {
        try {
            val trimmed = if (messages.size > MAX_MESSAGES) messages.takeLast(MAX_MESSAGES) else messages
            val arr = JSONArray()
            for (m in trimmed) {
                val o = JSONObject()
                    .put("id", m.id)
                    .put("text", m.text)
                    .put("sender", m.sender.name)
                    .put("timestamp", m.timestamp)
                if (!m.thinking.isNullOrBlank()) o.put("thinking", m.thinking)
                arr.put(o)
            }
            File(context.filesDir, FILE).writeText(arr.toString())
        } catch (t: Throwable) {
            AppLogger.e("ChatHistory", "save failed: ${t.message}", t)
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (_: Throwable) {
        }
    }
}
