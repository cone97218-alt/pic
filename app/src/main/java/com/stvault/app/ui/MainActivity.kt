package com.stvault.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.stvault.app.BuildConfig
import com.stvault.app.R
import com.stvault.app.core.ReleaseInfo
import com.stvault.app.core.UpdateManager
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityMainBinding
import com.stvault.app.databinding.DialogSourceDetailBinding
import com.stvault.app.model.RepairResult
import com.stvault.app.model.SourceRecord
import com.stvault.app.model.UrlRecord
import com.stvault.app.service.VaultDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: VaultRepository
    private lateinit var sourcesAdapter: SourcesAdapter
    private lateinit var urlsAdapter: UrlsAdapter

    private var savedTreeUri: Uri? = null
    private var vaultSearchModeSources = true

    private var pendingReplaceUrl: String? = null
    private var pendingSaveImageRecord: UrlRecord? = null
    private var pendingExportRepair: RepairResult? = null
    private var pendingInstallApk: File? = null
    private var repairInputFileName: String = "repaired-theme.json"
    private var activeSourceDialog: AlertDialog? = null

    private val prefs by lazy { getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE) }

    // 1. SAF Folder Picker
    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            savedTreeUri = uri
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            updateFolderLabel()
            runFolderScan(uri)
        }

    // 2. Pick Broken Theme File for Repair
    private val pickRepairFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val pair = withContext(Dispatchers.IO) {
                    val doc = DocumentFile.fromSingleUri(this@MainActivity, uri)
                    val name = doc?.name ?: "repaired-theme.json"
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: ""
                    name to text
                }
                repairInputFileName = pair.first
                binding.etRepairInput.setText(pair.second)
                toast("已载入：${pair.first}")
            }
        }

    // 3. Save Repaired Theme File
    private val createRepairedFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val repair = pendingExportRepair ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(repair.repairedText.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(if (ok) "复活版文件已保存" else "保存失败")
            }
        }

    // 4. Replace Single URL from Phone Gallery
    private val replacePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val targetUrl = pendingReplaceUrl ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    repo.replaceImageFromUri(targetUrl, uri)
                }
                toast(if (ok) "已用相册图片补全" else "替换失败")
                refreshVaultList()
                activeSourceDialog?.dismiss()
            }
        }

    // 5. Export Single Cached Image
    private val saveSingleImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val rec = pendingSaveImageRecord ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val file = repo.getImageFile(rec) ?: return@withContext false
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(if (ok) "原图已导出" else "导出失败")
            }
        }

    // 6. Android 13+ Notification Permission
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    // 7. Unknown App Sources Permission for OTA Install
    private val installPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstallApk
            if (apk != null && apk.exists() && canInstallPackages()) {
                launchApkInstaller(apk)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"

        repo = VaultRepository.getInstance(this)
        savedTreeUri = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }
        updateFolderLabel()

        requestNotificationPermissionIfNeeded()
        setupRecyclerViews()
        setupListeners()
        observeState()
        checkUpdateSilently()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupRecyclerViews() {
        binding.rvVault.layoutManager = LinearLayoutManager(this)
        sourcesAdapter = SourcesAdapter(repo) { source -> showSourceDetailDialog(source) }
        urlsAdapter = UrlsAdapter(
            repo = repo,
            onReplaceClick = { rec ->
                pendingReplaceUrl = rec.url
                replacePhotoLauncher.launch("image/*")
            },
            onSaveClick = { rec ->
                pendingSaveImageRecord = rec
                saveSingleImageLauncher.launch(rec.file.ifBlank { "cached_image.png" })
            }
        )
        binding.rvVault.adapter = sourcesAdapter
        updateSegmentedStyle()
    }

    private fun updateSegmentedStyle() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val transparent = android.graphics.Color.TRANSPARENT
        val white = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        if (vaultSearchModeSources) {
            binding.btnModeSources.backgroundTintList = ColorStateList.valueOf(accent)
            binding.btnModeSources.setTextColor(white)
            binding.btnModeUrls.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.btnModeUrls.setTextColor(muted)
        } else {
            binding.btnModeUrls.backgroundTintList = ColorStateList.valueOf(accent)
            binding.btnModeUrls.setTextColor(white)
            binding.btnModeSources.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.btnModeSources.setTextColor(muted)
        }
    }

    private fun setupListeners() {
        // OTA Update Check Button
        binding.btnCheckUpdate.setOnClickListener {
            checkUpdateManually()
        }

        // Bottom Nav
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.tabCache.visibility = if (item.itemId == R.id.nav_cache) View.VISIBLE else View.GONE
            binding.tabVault.visibility = if (item.itemId == R.id.nav_vault) View.VISIBLE else View.GONE
            binding.tabRepair.visibility = if (item.itemId == R.id.nav_repair) View.VISIBLE else View.GONE
            if (item.itemId == R.id.nav_vault) refreshVaultList()
            true
        }

        // Tab 1: Folder Pick & Scan
        binding.btnPickFolder.setOnClickListener { pickFolderLauncher.launch(savedTreeUri) }
        binding.btnScanFolder.setOnClickListener {
            val uri = savedTreeUri
            if (uri == null) {
                toast("请先选择美化目录")
                pickFolderLauncher.launch(null)
            } else {
                runFolderScan(uri)
            }
        }

        // Tab 1: Start / Retry / Stop Foreground Cache
        binding.btnStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = 6)
            toast("已启动后台缓存服务")
        }
        binding.btnRetryFailed.setOnClickListener {
            VaultDownloadService.start(this, mode = "failed", concurrency = 4)
            toast("开始重试失败链接")
        }
        binding.btnStopCache.setOnClickListener {
            VaultDownloadService.stop(this)
            toast("正在暂停并保存断点...")
        }

        // Tab 2: Vault Search
        binding.btnModeSources.setOnClickListener {
            vaultSearchModeSources = true
            binding.rvVault.adapter = sourcesAdapter
            updateSegmentedStyle()
            refreshVaultList()
        }
        binding.btnModeUrls.setOnClickListener {
            vaultSearchModeSources = false
            binding.rvVault.adapter = urlsAdapter
            updateSegmentedStyle()
            refreshVaultList()
        }
        binding.btnDoSearch.setOnClickListener { refreshVaultList() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                refreshVaultList()
                true
            } else false
        }

        // Tab 3: One-Click Repair
        binding.btnPickRepairFile.setOnClickListener { pickRepairFileLauncher.launch(arrayOf("*/*")) }
        binding.btnRunRepair.setOnClickListener {
            val raw = binding.etRepairInput.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) {
                toast("请先选择文件或粘贴代码")
                return@setOnClickListener
            }
            val mode = when (binding.rgRepairMode.checkedRadioButtonId) {
                R.id.rbModeCatbox -> "catbox"
                R.id.rbModePath -> "path"
                else -> "base64_webp"
            }
            binding.btnRunRepair.isEnabled = false
            binding.tvRepairStatus.visibility = View.VISIBLE
            binding.tvRepairStatus.text = if (mode == "catbox") "正在转存原图并生成新链接..." else "正在处理美化文件..."

            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        repo.repairRawText(raw, repairInputFileName, mode) { done, total ->
                            runOnUiThread {
                                binding.tvRepairStatus.text = "正在处理图片：$done / $total ..."
                            }
                        }
                    }
                    binding.tvRepairStatus.text =
                        "处理完成：共 ${res.totalUrls} 个图床，成功替换 ${res.matchedCount} 张" +
                            if (res.missingCount > 0) "（${res.missingCount} 张未缓存）" else ""
                    triggerSaveRepairedFile(res)
                } catch (e: Exception) {
                    binding.tvRepairStatus.text = "修复失败：${e.message}"
                } finally {
                    binding.btnRunRepair.isEnabled = true
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repo.statsFlow.collectLatest { st ->
                binding.tvStatSources.text = st.totalSources.toString()
                binding.tvStatOk.text = st.okUrls.toString()
                binding.tvStatTotalSlash.text = "/ ${st.totalUrls} 张图床"
                binding.tvStatPending.text = st.pendingUrls.toString()
                binding.tvStatErr.text = st.errorUrls.toString()
                binding.tvVaultSize.text = formatBytes(st.totalBytes)
                binding.btnStartCache.text = if (st.pendingUrls > 0) "开始缓存 (${st.pendingUrls})" else "开始缓存"
            }
        }

        lifecycleScope.launch {
            repo.progressFlow.collectLatest { p ->
                binding.btnStartCache.isEnabled = !p.running
                binding.btnRetryFailed.isEnabled = !p.running
                binding.btnStopCache.visibility = if (p.running) View.VISIBLE else View.GONE
                binding.boxJobProgress.visibility = if (p.running || p.total > 0) View.VISIBLE else View.GONE

                if (p.total > 0) {
                    val pct = ((p.done * 100.0) / p.total).toInt().coerceIn(0, 100)
                    binding.pbJob.progress = pct
                    binding.tvJobStatus.text = if (p.running) {
                        "缓存中 ${p.done} / ${p.total} ($pct%)  ·  成功 ${p.okInRun}  失败 ${p.errInRun}"
                    } else {
                        "本轮完成 ${p.done} / ${p.total}  ·  成功 ${p.okInRun}  失败 ${p.errInRun}"
                    }
                    binding.tvJobCurrentUrl.text = p.currentUrl
                }
            }
        }
    }

    // ================== OTA Update Flow ==================
    private fun checkUpdateSilently() {
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { UpdateManager.checkLatestRelease() }
                if (info.hasUpdate) {
                    binding.dotUpdateAvailable.visibility = View.VISIBLE
                    binding.tvUpdateLabel.text = "新版本"
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkUpdateManually() {
        toast("正在检查 GitHub 最新版本...")
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { UpdateManager.checkLatestRelease() }
                if (info.hasUpdate) {
                    binding.dotUpdateAvailable.visibility = View.VISIBLE
                    showUpdateDialog(info)
                } else {
                    binding.dotUpdateAvailable.visibility = View.GONE
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本 v${BuildConfig.VERSION_NAME} 已与 GitHub 仓库最新 Release (${info.tagName}) 同步。")
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("重新下载安装") { _, _ -> downloadAndInstallUpdate(info) }
                        .show()
                }
            } catch (e: Exception) {
                toast("检查更新失败：${e.message}")
            }
        }
    }

    private fun showUpdateDialog(info: ReleaseInfo) {
        val sizeStr = if (info.apkSize > 0) " (${formatBytes(info.apkSize)})" else ""
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${info.tagName}")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：${info.tagName}$sizeStr\n\n${info.changelog}")
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstallUpdate(info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun downloadAndInstallUpdate(info: ReleaseInfo) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val statusTv = TextView(this).apply {
            text = "正在连接 GitHub 下载安装包..."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        container.addView(statusTv)
        container.addView(pb)

        val dlg = AlertDialog.Builder(this)
            .setTitle("正在更新至 ${info.tagName}")
            .setView(container)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    UpdateManager.downloadApk(this@MainActivity, info.apkUrl) { pct, down, total ->
                        runOnUiThread {
                            if (pct >= 0) {
                                pb.isIndeterminate = false
                                pb.progress = pct
                                statusTv.text = "下载进度：$pct% (${formatBytes(down)} / ${formatBytes(total)})"
                            } else {
                                statusTv.text = "已下载：${formatBytes(down)}"
                            }
                        }
                    }
                }
                dlg.dismiss()
                pendingInstallApk = apkFile
                if (canInstallPackages()) {
                    launchApkInstaller(apkFile)
                } else {
                    toast("请先允许「安装未知应用」权限")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                        installPermLauncher.launch(intent)
                    }
                }
            } catch (e: Exception) {
                dlg.dismiss()
                toast("下载更新失败：${e.message}")
            }
        }
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
    }

    private fun launchApkInstaller(apkFile: File) {
        startActivity(UpdateManager.buildInstallIntent(this, apkFile))
    }

    // ================== Folder Scan & Vault UI ==================
    private fun updateFolderLabel() {
        val uri = savedTreeUri
        if (uri == null) {
            binding.tvSelectedFolder.text = "点击下方按钮授权你的美化目录"
        } else {
            val doc = DocumentFile.fromTreeUri(this, uri)
            binding.tvSelectedFolder.text = "已绑定目录：${doc?.name ?: uri.lastPathSegment}"
        }
    }

    private fun runFolderScan(uri: Uri) {
        val includePng = binding.cbIncludePng.isChecked
        binding.btnPickFolder.isEnabled = false
        binding.btnScanFolder.isEnabled = false
        binding.tvScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "正在递归扫描目录..."

        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    repo.scanTreeUri(uri, includePng) { scanned, matched ->
                        runOnUiThread {
                            binding.tvScanProgress.text = "已扫描 $scanned 个文件，命中 $matched 个美化..."
                        }
                    }
                }
                binding.tvScanProgress.text =
                    "扫描完成：共扫 ${res.scannedFiles} 个文件，命中 ${res.matchedFiles} 个美化，新增 ${res.newUrls} 个图床"
                toast("扫描完成，新增 ${res.newUrls} 个图床")
            } catch (e: Exception) {
                binding.tvScanProgress.text = "扫描失败：${e.message}"
            } finally {
                binding.btnPickFolder.isEnabled = true
                binding.btnScanFolder.isEnabled = true
            }
        }
    }

    private fun refreshVaultList() {
        val q = binding.etSearch.text?.toString().orEmpty()
        lifecycleScope.launch {
            if (vaultSearchModeSources) {
                val list = withContext(Dispatchers.IO) { repo.searchSources(q) }
                sourcesAdapter.submitList(list)
                binding.tvVaultCount.text = "共 ${list.size} 个美化文件"
            } else {
                val list = withContext(Dispatchers.IO) { repo.searchUrls(q, "all") }
                urlsAdapter.submitList(list)
                binding.tvVaultCount.text = "共 ${list.size} 张图床记录"
            }
        }
    }

    private fun showSourceDetailDialog(source: SourceRecord) {
        val dBinding = DialogSourceDetailBinding.inflate(LayoutInflater.from(this))
        dBinding.tvDialogSourceTitle.text = source.name
        dBinding.rvDialogUrls.layoutManager = LinearLayoutManager(this)

        val detailAdapter = UrlsAdapter(
            repo = repo,
            onReplaceClick = { rec ->
                pendingReplaceUrl = rec.url
                replacePhotoLauncher.launch("image/*")
            },
            onSaveClick = { rec ->
                pendingSaveImageRecord = rec
                saveSingleImageLauncher.launch(rec.file.ifBlank { "cached_image.png" })
            }
        )
        dBinding.rvDialogUrls.adapter = detailAdapter
        detailAdapter.submitList(repo.getUrlRecordsForSource(source))

        fun runModalRepair(mode: String) {
            toast(if (mode == "catbox") "正在上传到 Catbox 换新链..." else "正在 WebP 压缩并打包...")
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) { repo.repairSourceById(source.id, mode) }
                    activeSourceDialog?.dismiss()
                    toast("已替换 ${res.matchedCount}/${res.totalUrls} 张原图，请选择保存位置")
                    triggerSaveRepairedFile(res)
                } catch (e: Exception) {
                    toast("修复失败：${e.message}")
                }
            }
        }

        dBinding.btnDialogRepairCatbox.setOnClickListener { runModalRepair("catbox") }
        dBinding.btnDialogRepairBase64.setOnClickListener { runModalRepair("base64_webp") }

        activeSourceDialog = AlertDialog.Builder(this)
            .setView(dBinding.root)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun triggerSaveRepairedFile(res: RepairResult) {
        pendingExportRepair = res
        val base = res.fileName.substringBeforeLast('.')
        val ext = res.fileName.substringAfterLast('.', if (res.isJson) "json" else "css")
        createRepairedFileLauncher.launch("${base}_已复活.$ext")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
