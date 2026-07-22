package com.example.offlinellm.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.offlinellm.MainActivity
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.repository.ModelRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Foreground download service so HF/GGUF downloads continue when the app is
 * backgrounded or the screen is off.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifMs = 0L
    private var lastNotifPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_MODEL_ID)
                AppLogger.d("DlService", "cancel $id")
                try {
                    if (id != null) ModelRepositoryImpl(applicationContext).cancelDownload(id)
                } catch (_: Throwable) {
                }
                job?.cancel()
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
                val url = intent.getStringExtra(EXTRA_URL) ?: run {
                    stopSelf(); return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_NAME) ?: modelId
                startDownload(modelId, url, name)
            }
        }
        return START_STICKY
    }

    private fun startDownload(modelId: String, url: String, name: String) {
        if (job?.isActive == true) {
            AppLogger.d("DlService", "already running")
            return
        }
        ensureChannel()
        val notification = buildNotification(name, 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireLocks()

        val repo = ModelRepositoryImpl(applicationContext)
        job = scope.launch {
            try {
                AppLogger.d("DlService", "start $modelId")
                broadcast(STATUS_RUNNING, modelId, 0f, null)
                repo.downloadModel(modelId, url)
                    .catch { e ->
                        AppLogger.e("DlService", "fail: ${e.message}", e)
                        broadcast(STATUS_FAILED, modelId, 0f, e.message)
                        notifyDone(name, success = false, err = e.message)
                    }
                    .collect { p ->
                        val pct = (p * 100).toInt().coerceIn(0, 100)
                        // Throttle notification binder calls — was a major slowdown
                        val now = System.currentTimeMillis()
                        if (now - lastNotifMs >= NOTIF_INTERVAL_MS || pct != lastNotifPct && pct % 2 == 0) {
                            updateNotification(name, pct, indeterminate = p <= 0f)
                            lastNotifMs = now
                            lastNotifPct = pct
                        }
                        broadcast(STATUS_PROGRESS, modelId, p, null)
                    }
                broadcast(STATUS_COMPLETED, modelId, 1f, null)
                notifyDone(name, success = true, err = null)
                AppLogger.d("DlService", "done $modelId")
            } catch (t: Throwable) {
                AppLogger.e("DlService", "exception: ${t.message}", t)
                broadcast(STATUS_FAILED, modelId, 0f, t.message)
                notifyDone(name, success = false, err = t.message)
            } finally {
                releaseLocks()
                stopSelfSafe()
            }
        }
    }

    private fun broadcast(status: String, modelId: String, progress: Float, error: String?) {
        val i = Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MODEL_ID, modelId)
            putExtra(EXTRA_PROGRESS, progress)
            if (error != null) putExtra(EXTRA_ERROR, error)
        }
        sendBroadcast(i)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Скачивание моделей",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(name: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Скачивание модели")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Отмена", cancel)
        if (indeterminate) {
            b.setProgress(0, 0, true)
        } else {
            b.setProgress(100, progress, false)
            b.setSubText("$progress%")
        }
        return b.build()
    }

    private fun updateNotification(name: String, progress: Int, indeterminate: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(name, progress, indeterminate))
    }

    private fun notifyDone(name: String, success: Boolean, err: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (success) "Модель скачана" else "Ошибка скачивания")
            .setContentText(if (success) name else (err ?: name))
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "offlinellm:download").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
            AppLogger.e("DlService", "wakelock: ${t.message}", t)
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "offlinellm:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            AppLogger.e("DlService", "wifilock: ${t.message}", t)
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        wifiLock = null
    }

    private fun stopSelfSafe() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "model_download"
        const val NOTIF_ID = 42
        private const val NOTIF_INTERVAL_MS = 1500L

        const val ACTION_START = "com.example.offlinellm.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.example.offlinellm.DOWNLOAD_CANCEL"
        const val ACTION_PROGRESS = "com.example.offlinellm.DOWNLOAD_PROGRESS"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR = "error"

        const val STATUS_RUNNING = "running"
        const val STATUS_PROGRESS = "progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"

        fun start(context: Context, modelId: String, url: String, name: String) {
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun cancel(context: Context, modelId: String? = null) {
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                if (modelId != null) putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(i)
        }
    }
}
