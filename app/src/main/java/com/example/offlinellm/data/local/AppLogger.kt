package com.example.offlinellm.data.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring-buffer logger. Can be disabled via prefs (toggle persists).
 */
object AppLogger {

    private const val MAX_ENTRIES = 500

    @Volatile
    private var enabled: Boolean = true

    private val buffer = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String
    )

    fun setEnabled(value: Boolean) {
        enabled = value
        android.util.Log.d("AppLogger", "logging enabled=$value")
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) {
        if (!enabled) {
            // still emit to logcat at verbose only when disabled? skip both for privacy/perf
            return
        }
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            message = message
        )
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_ENTRIES) {
                buffer.removeAt(0)
            }
        }
        android.util.Log.d(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        // errors always go to logcat; buffer only if enabled
        android.util.Log.e(tag, msg, throwable)
        if (enabled) {
            val entry = LogEntry(
                timestamp = dateFormat.format(Date()),
                tag = tag,
                message = msg
            )
            synchronized(buffer) {
                buffer.add(entry)
                if (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
            }
        }
    }

    fun getLogs(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun getLogText(): String = synchronized(buffer) {
        buffer.joinToString("\n") { "[${it.timestamp}] [${it.tag}] ${it.message}" }
    }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun copyToClipboard(context: Context) {
        val text = getLogText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AppLogs", text))
    }
}
