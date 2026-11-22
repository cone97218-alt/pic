package com.stvault.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stvault.app.R
import com.stvault.app.core.TavernBridgeManager
import com.stvault.app.ui.TavernBridgeActivity

/**
 * Lightweight foreground service that keeps the 127.0.0.1 loopback MCP server alive
 * when the user switches from 酒馆匣 to TauriTavern.
 */
class TavernBridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TavernBridgeManager.getInstance(this).stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val port = TavernBridgeManager.getInstance(this).startServer()
        if (port <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(port))
        return START_STICKY
    }

    override fun onDestroy() {
        TavernBridgeManager.getInstance(this).stopServer()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "酒馆互联通道",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本机回环通道在线，方便与 TT 酒馆双向传输"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openIntent = Intent(this, TavernBridgeActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_module_theme)
            .setContentTitle("酒馆互联已开启")
            .setContentText("本机通道 127.0.0.1:$port 运行中，切回 TT 酒馆即可连接")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "stvault_bridge_channel"
        private const val NOTIF_ID = 4108
        private const val ACTION_STOP = "com.stvault.app.BRIDGE_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, TavernBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, TavernBridgeService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }
}
