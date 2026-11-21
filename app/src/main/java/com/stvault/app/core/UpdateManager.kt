package com.stvault.app.core

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.stvault.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val hasUpdate: Boolean
)

object UpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun checkLatestRelease(): ReleaseInfo {
        val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
        val req = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "STThemeVault-Android/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                throw IOException("仓库暂无已发布的 Release 版本")
            }
            if (!resp.isSuccessful) {
                throw IOException("检查更新失败 (HTTP ${resp.code})")
            }
            val json = JSONObject(resp.body?.string() ?: throw IOException("空响应"))
            val tag = json.optString("tag_name", "").trim()
            val cleanRemote = tag.removePrefix("v").removePrefix("V")
            val body = json.optString("body", "例行优化与功能更新").trim()

            var apkUrl = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isEmpty()) {
                throw IOException("最新 Release 未附带 APK 安装包")
            }

            val hasUpdate = isNewerVersion(cleanRemote, BuildConfig.VERSION_NAME)
            return ReleaseInfo(
                versionName = cleanRemote,
                tagName = tag,
                changelog = body,
                apkUrl = apkUrl,
                apkSize = apkSize,
                hasUpdate = hasUpdate
            )
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val lParts = local.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        dir.mkdirs()
        val outFile = File(dir, "STThemeVault-update.apk")
        if (outFile.exists()) outFile.delete()

        val candidates = listOf(
            apkUrl,
            "https://gh-proxy.com/$apkUrl"
        )

        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "STThemeVault-Android/${BuildConfig.VERSION_NAME}")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("空响应")
                    val total = body.contentLength()
                    var downloaded = 0L
                    val buf = ByteArray(16 * 1024)

                    body.byteStream().use { inp ->
                        FileOutputStream(outFile).use { out ->
                            while (true) {
                                val read = inp.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                downloaded += read
                                val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                                onProgress(pct, downloaded, total)
                            }
                        }
                    }
                }
                if (outFile.exists() && outFile.length() > 100_000L) {
                    return outFile
                }
            } catch (e: Exception) {
                lastError = e
                if (outFile.exists()) outFile.delete()
            }
        }
        throw lastError ?: IOException("下载安装包失败")
    }

    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
