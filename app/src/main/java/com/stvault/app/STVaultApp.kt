package com.stvault.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class STVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)
    }

    companion object {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
