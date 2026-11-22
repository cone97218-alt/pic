package com.stvault.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.stvault.app.core.InboxManager
import com.stvault.app.databinding.ActivityInboxBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InboxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInboxBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInboxClose.setOnClickListener { finish() }

        // Restore saved config
        binding.etDcToken.setText(InboxManager.getBotToken(this))
        binding.etDcChannelId.setText(InboxManager.getChannelId(this))
        binding.swDcAutoSync.isChecked = InboxManager.isDiscordAutoSyncEnabled(this)
        binding.cbWatchDownload.isChecked = InboxManager.isWatchDownload(this)
        binding.cbWatchDiscordPics.isChecked = InboxManager.isWatchDiscordPics(this)
        binding.swDeleteAfterImport.isChecked = InboxManager.isDeleteAfterImport(this)
        binding.swAutoTagPending.isChecked = InboxManager.isAutoTagEnabled(this)

        refreshStatusUi()

        // Switches & checkboxes
        binding.swDcAutoSync.setOnCheckedChangeListener { _, checked ->
            InboxManager.setDiscordAutoSyncEnabled(this, checked)
        }
        binding.cbWatchDownload.setOnCheckedChangeListener { _, checked ->
            InboxManager.setWatchDownload(this, checked)
        }
        binding.cbWatchDiscordPics.setOnCheckedChangeListener { _, checked ->
            InboxManager.setWatchDiscordPics(this, checked)
        }
        binding.swDeleteAfterImport.setOnCheckedChangeListener { _, checked ->
            InboxManager.setDeleteAfterImport(this, checked)
        }
        binding.swAutoTagPending.setOnCheckedChangeListener { _, checked ->
            InboxManager.setAutoTagEnabled(this, checked)
        }

        // 1. Discord Bot Guide
        binding.btnDcGuide.setOnClickListener { showDiscordGuideSheet() }

        // Test connection
        binding.btnDcTest.setOnClickListener {
            if (busy) return@setOnClickListener
            val token = binding.etDcToken.text?.toString().orEmpty()
            val channelId = binding.etDcChannelId.text?.toString().orEmpty()
            setBusy(true, "正在测试连接 Discord...")
            lifecycleScope.launch(Dispatchers.IO) {
                val res = InboxManager.testDiscordConnection(this@InboxActivity, token, channelId)
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    refreshStatusUi()
                    res.onSuccess { chName ->
                        toast("连接成功：$chName")
                    }.onFailure { err ->
                        toast(err.message ?: "连接失败")
                    }
                }
            }
        }

        // Sync Discord channel
        binding.btnDcSync.setOnClickListener {
            if (busy) return@setOnClickListener
            InboxManager.saveDiscordConfig(
                this,
                binding.etDcToken.text?.toString().orEmpty(),
                binding.etDcChannelId.text?.toString().orEmpty()
            )
            if (!InboxManager.isDiscordConfigured(this)) {
                toast("请先填写 Bot Token 和频道 ID")
                return@setOnClickListener
            }
            setBusy(true, "正在连接 Discord...")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = InboxManager.syncDiscordChannel(this@InboxActivity) { msg ->
                    runOnUiThread { binding.tvInboxProgress.text = msg }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    refreshStatusUi()
                    if (result.error != null) {
                        toast(result.error)
                    } else if (result.messagesChecked == 0) {
                        toast("暂无新消息")
                    } else if (result.filesFetched == 0) {
                        toast("检查了 ${result.messagesChecked} 条消息，未发现新附件")
                    } else {
                        toast(result.summary.formatMessage())
                    }
                }
            }
        }

        // Reset Discord cursor
        binding.btnDcResetCursor.setOnClickListener {
            if (busy) return@setOnClickListener
            VaultSheets.confirm(
                ctx = this,
                title = "重置同步游标",
                message = "重置后，下次同步将重新拉取频道最近 50 条消息（已入库的资源会自动去重，不会重复）。",
                confirmText = "重置"
            ) {
                InboxManager.resetDiscordCursor(this)
                refreshStatusUi()
                toast("已重置，下次同步将拉取最近 50 条")
            }
        }

        // 2. Read Clipboard & Fetch Links
        binding.btnReadClipboard.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
            if (clipText.isBlank()) {
                toast("剪贴板为空")
                return@setOnClickListener
            }
            binding.etLinksInput.setText(clipText)
            val urls = InboxManager.extractDownloadableUrls(clipText, strictOnly = false)
            toast(if (urls.isNotEmpty()) "已填入剪贴板，识别到 ${urls.size} 条链接" else "已填入剪贴板，未检测到直链")
        }

        binding.btnFetchLinks.setOnClickListener {
            if (busy) return@setOnClickListener
            val text = binding.etLinksInput.text?.toString().orEmpty().trim()
            if (text.isBlank()) {
                toast("请先粘贴包含链接的文本或点击读取剪贴板")
                return@setOnClickListener
            }
            val urls = InboxManager.extractDownloadableUrls(text, strictOnly = false)
            if (urls.isEmpty()) {
                toast("未检测到可下载的文件链接")
                return@setOnClickListener
            }
            setBusy(true, "准备抓取 ${urls.size} 条链接...")
            lifecycleScope.launch(Dispatchers.IO) {
                val (fetched, summary) = InboxManager.importUrlsFromText(this@InboxActivity, text) { msg ->
                    runOnUiThread { binding.tvInboxProgress.text = msg }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (fetched == 0) {
                        toast("链接抓取失败（请检查网络/代理或链接是否已失效）")
                    } else {
                        if (summary.totalImported > 0) binding.etLinksInput.setText("")
                        toast(summary.formatMessage())
                    }
                }
            }
        }

        // 3. Local Download Inbox
        binding.btnScanLocalInbox.setOnClickListener {
            if (busy) return@setOnClickListener
            setBusy(true, "正在扫描本地下载目录...")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = InboxManager.scanLocalInbox(this@InboxActivity) { msg ->
                    runOnUiThread { binding.tvInboxProgress.text = msg }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    when {
                        result.needsStoragePermission -> {
                            VaultSheets.confirm(
                                ctx = this@InboxActivity,
                                title = "需要「所有文件访问」权限",
                                message = "扫描手机 Download 与 Pictures/Discord 目录需要开启所有文件访问权限。",
                                confirmText = "去授权"
                            ) {
                                openAllFilesPermission()
                            }
                        }
                        result.error != null -> toast(result.error)
                        result.filesScanned == 0 -> toast("没有发现新的待导入文件")
                        else -> {
                            val delSuffix = if (result.deletedCount > 0) " · 已清理 ${result.deletedCount} 个原文件" else ""
                            toast(result.summary.formatMessage() + delSuffix)
                        }
                    }
                }
            }
        }

        binding.btnResetLocalSeen.setOnClickListener {
            if (busy) return@setOnClickListener
            VaultSheets.confirm(
                ctx = this,
                title = "重置已扫记录",
                message = "清空已扫描文件缓存后，下次扫描会重新检查目录内的所有文件（已在库中的资源会自动去重）。",
                confirmText = "重置"
            ) {
                InboxManager.resetSeenLocalFiles(this)
                toast("已重置本地扫描记录")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Auto-persist edited token/channelId so user never loses input
        InboxManager.saveDiscordConfig(
            this,
            binding.etDcToken.text?.toString().orEmpty(),
            binding.etDcChannelId.text?.toString().orEmpty()
        )
    }

    private fun refreshStatusUi() {
        val configured = InboxManager.isDiscordConfigured(this)
        val chName = InboxManager.getChannelName(this)
        val lastAt = InboxManager.getLastSyncAt(this)
        val lastNote = InboxManager.getLastSyncNote(this)

        binding.tvDcStatus.text = when {
            !configured -> "未配置 · 转发带多附件的消息到私人频道即可一键全收"
            chName.isNotBlank() -> "已配置 · #$chName（主号 / 分身 / 电脑转发均可同步）"
            else -> "已配置频道 ${InboxManager.getChannelId(this)} · 建议先点「测试连接」"
        }

        binding.tvDcLastNote.text = if (lastAt > 0L) {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lastAt))
            "上次：$fmt · ${lastNote.ifBlank { "已完成" }}"
        } else {
            "上次同步：尚未同步"
        }
    }

    private fun setBusy(isBusy: Boolean, text: String = "") {
        busy = isBusy
        binding.inboxProgressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
        if (text.isNotBlank()) binding.tvInboxProgress.text = text
        binding.btnDcTest.isEnabled = !isBusy
        binding.btnDcSync.isEnabled = !isBusy
        binding.btnFetchLinks.isEnabled = !isBusy
        binding.btnScanLocalInbox.isEnabled = !isBusy
    }

    private fun showDiscordGuideSheet() {
        val guide = """
            只需 2 分钟，完全免费、零封号风险：

            1. 在 Discord 里新建一个「仅供自己使用」的私人服务器，新建文字频道（如 #囤货）。
            2. 打开 Discord 开发者后台 → 点「New Application」随便起个名。
            3. 左侧点「Bot」→ 点「Reset Token」复制 Token 填到上面；往下滑打开「Message Content Intent」并保存。
            4. 左侧点「OAuth2」→「URL Generator」→ 勾选 bot，下方权限勾选「Read Messages/View Channels」和「Read Message History」，打开生成的链接把 Bot 拉进你的私人服务器。
            5. 在 Discord 设置 → 高级里打开「开发者模式」，长按 #囤货 频道 →「复制频道 ID」填到上面即可。

            日常用法：在主 DC 或分身 DC 看到带多个附件的帖子/Bot 私聊，长按消息 →「转发」到 #囤货，回这里点「立即同步」一次全收。
        """.trimIndent()

        VaultSheets.info(
            ctx = this,
            title = "2 分钟创建私人中转 Bot",
            message = guide,
            primaryText = "知道了",
            secondaryText = "打开开发者后台",
            onSecondary = {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/developers/applications")))
                } catch (_: Exception) {
                    toast("无法打开浏览器")
                }
            }
        )
    }

    private fun openAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(i)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    toast("请在系统设置中手动授予所有文件访问权限")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
