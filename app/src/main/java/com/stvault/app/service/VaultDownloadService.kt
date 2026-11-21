package com.stvault.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.stvault.app.core.ImageDownloader
import com.stvault.app.core.VaultRepository
import com.stvault.app.model.DownloadProgress
import com.stvault.app.ui.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class VaultDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    @Volatile
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                currentJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (currentJob?.isActive == true) return START_STICKY
                val mode = intent.getStringExtra(EXTRA_MODE) ?: "pending"
                val concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 6).coerceIn(1, 10)
                startForegroundWithNotification("准备开始批量缓存图床...", 0, 0)
                runDownloadQueue(mode, concurrency)
            }
        }
        return START_STICKY
    }

    private fun runDownloadQueue(mode: String, concurrency: Int) {
        val repo = VaultRepository.getInstance(this)
        val queue = repo.getUrlsToDownload(mode)
        if (queue.isEmpty()) {
            repo.updateProgress(DownloadProgress(running = false))
            stopSelf()
            return
        }

        stopRequested = false
        val total = queue.size
        val cursor = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val okCount = AtomicInteger(0)
        val errCount = AtomicInteger(0)
        val sinceSave = AtomicInteger(0)

        repo.updateProgress(
            DownloadProgress(running = true, total = total, done = 0, currentUrl = "正在连接图床...")
        )

        currentJob = scope.launch {
            try {
                val workers = List(minOf(concurrency, total)) {
                    launch {
                        while (!stopRequested && isActive) {
                            val idx = cursor.getAndIncrement()
                            if (idx >= total) break
                            val url = queue[idx]
                            try {
                                val res = ImageDownloader.download(url)
                                repo.markDownloadSuccess(url, res.bytes, res.mime)
                                okCount.incrementAndGet()
                            } catch (e: Exception) {
                                repo.markDownloadError(url, e.message ?: "下载失败")
                                errCount.incrementAndGet()
                            }
                            val done = doneCount.incrementAndGet()
                            repo.updateProgress(
                                DownloadProgress(
                                    running = true,
                                    total = total,
                                    done = done,
                                    okInRun = okCount.get(),
                                    errInRun = errCount.get(),
                                    currentUrl = url
                                )
                            )
                            if (done % 5 == 0 || done == total) {
                                updateNotification("已缓存 $done / $total (成功 ${okCount.get()} · 失败 ${errCount.get()})", done, total)
                            }
                            if (sinceSave.incrementAndGet() >= 15) {
                                sinceSave.set(0)
                                repo.saveIndex()
                            }
                        }
                    }
                }
                workers.joinAll()
            } finally {
                repo.saveIndex()
                repo.updateProgress(
                    DownloadProgress(
                        running = false,
                        total = total,
                        done = doneCount.get(),
                        okInRun = okCount.get(),
                        errInRun = errCount.get(),
                        currentUrl = if (stopRequested) "已暂停并保存断点" else "本轮缓存完成"
                    )
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        stopRequested = true
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "酒馆图床批量缓存进度",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ 酒馆图床保险箱")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(max, progress, max == 0)
            .build()
    }

    private fun startForegroundWithNotification(text: String, progress: Int, max: Int) {
        val notif = buildNotification(text, progress, max)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, progress, max))
    }

    companion object {
        private const val CHANNEL_ID = "st_vault_download_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.stvault.app.ACTION_START"
        const val ACTION_STOP = "com.stvault.app.ACTION_STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONCURRENCY = "concurrency"

        fun start(context: Context, mode: String, concurrency: Int = 6) {
            val intent = Intent(context, VaultDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_CONCURRENCY, concurrency)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VaultDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
