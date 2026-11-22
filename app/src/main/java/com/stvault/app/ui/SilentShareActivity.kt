package com.stvault.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.stvault.app.STVaultApp
import com.stvault.app.core.InboxManager
import com.stvault.app.core.SmartImportSummary
import com.stvault.app.core.SmartImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Transparent zero-interruption Share / Open-With receiver.
 * Accepts files (PNG/JSON/ZIP/CSS/JS/HTML) and text containing URLs from QQ / Discord / browsers,
 * stages URI bytes while permissions are live, exits immediately so the user stays in their app,
 * and completes smart classification + deduplication + import silently in the background.
 */
class SilentShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
    }

    private fun handleIncoming(incoming: Intent?) {
        val intent = incoming ?: run { finish(); return }
        val action = intent.action ?: run { finish(); return }

        val uris = LinkedHashSet<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (single != null) uris.add(single)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (list != null) uris.addAll(list)
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                cd.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val hasUrls = sharedText.isNotBlank() && InboxManager.extractDownloadableUrls(sharedText, strictOnly = false).isNotEmpty()

        if (uris.isEmpty() && !hasUrls) {
            showToast("未检测到可导入的文件或下载链接")
            finish()
            return
        }

        val appCtx = applicationContext
        val uriList = uris.toList()

        // Stage URI contents quickly on IO while activity URI grants are valid, then finish immediately
        STVaultApp.appScope.launch(Dispatchers.IO) {
            val stageDir = File(appCtx.cacheDir, "silent_share_${System.currentTimeMillis()}")
            val stagedFiles = mutableListOf<File>()

            if (uriList.isNotEmpty()) {
                stageDir.mkdirs()
                for ((idx, uri) in uriList.withIndex()) {
                    val doc = try { DocumentFile.fromSingleUri(this@SilentShareActivity, uri) } catch (_: Exception) { null }
                    val rawName = doc?.name?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "shared_${idx + 1}"
                    val safeName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    val subDir = File(stageDir, idx.toString()).apply { mkdirs() }
                    val target = File(subDir, safeName)
                    try {
                        contentResolver.openInputStream(uri)?.use { inp ->
                            target.outputStream().use { out -> inp.copyTo(out) }
                        }
                        if (target.exists() && target.length() > 0L) {
                            stagedFiles.add(target)
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // Close transparent activity right away so user stays uninterrupted in QQ / Discord
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }

            if (hasUrls && stagedFiles.isEmpty()) {
                showToast("酒馆匣：正在后台抓取链接...")
            }

            val autoTag = InboxManager.getEffectiveAutoTag(appCtx)
            val totalSummary = SmartImportSummary()

            if (stagedFiles.isNotEmpty()) {
                val fileSummary = SmartImporter.importLocalFiles(appCtx, stagedFiles, autoTag = autoTag)
                mergeSummary(totalSummary, fileSummary)
            }

            if (hasUrls) {
                val (_, urlSummary) = InboxManager.importUrlsFromText(appCtx, sharedText)
                mergeSummary(totalSummary, urlSummary)
            }

            // Cleanup staging dir
            try {
                if (stageDir.exists()) stageDir.deleteRecursively()
            } catch (_: Exception) {
            }

            val msg = if (totalSummary.totalImported > 0) {
                "✓ 酒馆匣${totalSummary.formatMessage()}"
            } else {
                "酒馆匣：${totalSummary.formatMessage()}"
            }
            showToast(msg)
        }
    }

    private fun mergeSummary(dst: SmartImportSummary, src: SmartImportSummary) {
        dst.themes += src.themes
        dst.characters += src.characters
        dst.worldBooks += src.worldBooks
        dst.presets += src.presets
        dst.components += src.components
        dst.duplicates += src.duplicates
        dst.newUrls += src.newUrls
        dst.skipped += src.skipped
    }

    private fun showToast(msg: String) {
        val ctx = applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
