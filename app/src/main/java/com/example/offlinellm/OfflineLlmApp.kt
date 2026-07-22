package com.example.offlinellm

import android.app.Application
import com.example.offlinellm.data.local.AppLogger
import com.example.offlinellm.data.local.AppPreferences

class OfflineLlmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.setEnabled(AppPreferences.isLogsEnabled(this))
    }
}
