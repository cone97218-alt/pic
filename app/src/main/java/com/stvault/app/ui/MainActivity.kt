package com.stvault.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.stvault.app.BuildConfig
import com.stvault.app.R
import com.stvault.app.core.HostConfig
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: VaultRepository
    private lateinit var sourcesAdapter: SourcesAdapter
    private lateinit var urlsAdapter: UrlsAdapter

    private var savedTreeUri: Uri? = null
    private var savedLocalDirPath: String? = null
    private var vaultSearchModeSources = true
    private var activeSourceFilter = "all"
    private var activeUrlFilter = "all"
    private var downloadConcurrency = 16

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
            savedLocalDirPath = null
            prefs.edit().putString("tree_uri", uri.toString()).remove("local_dir_path").apply()
            updateFolderLabel()
            runFolderScan(uri)
        }

    // 9. SAF Direct Intent (for jumping straight into Android/data/com.tauritavern.client)
    private val openTTTreeIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            savedTreeUri = uri
            savedLocalDirPath = null
            prefs.edit().putString("tree_uri", uri.toString()).remove("local_dir_path").apply()
            updateFolderLabel()
            runFolderScan(uri)
        }

    // 10. Return from All Files Access settings -> auto re-try TT folder scan
    private val allFilesPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val ttThemesDir = File("/storage/emulated/0/Android/data/com.tauritavern.client/data/default-user/themes")
            if (ttThemesDir.exists() && ttThemesDir.canRead() && ttThemesDir.listFiles() != null) {
                savedLocalDirPath = ttThemesDir.absolutePath
                savedTreeUri = null
                prefs.edit().putString("local_dir_path", ttThemesDir.absolutePath).remove("tree_uri").apply()
                updateFolderLabel()
                runLocalDirScan(ttThemesDir)
            } else {
                toast("已获取权限，但你的安卓系统限制了直接读取 Android/data，请改点「跳转授权 TT 目录」")
            }
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

    // 8. Export Entire Vault as ZIP Archive
    private val createVaultZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@registerForActivityResult
            exportVaultArchiveToUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        updateThemeIcon()
        updateHostLabel()

        repo = VaultRepository.getInstance(this)
        savedTreeUri = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }
        savedLocalDirPath = prefs.getString("local_dir_path", null)
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
        sourcesAdapter = SourcesAdapter(
            repo = repo,
            onClick = { source -> showSourceDetailDialog(source) },
            onPreviewClick = { source -> ThemePreviewActivity.open(this, source.id) }
        )
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
        rebuildFilterChips()
    }

    private fun rebuildFilterChips() {
        val group = binding.chipGroupFilter
        group.removeAllViews()
        val options = if (vaultSearchModeSources) {
            listOf(
                "all" to "最新扫描",
                "has_error" to "有失败",
                "incomplete" to "未下完",
                "completed" to "已全缓存",
                "most_imgs" to "图最多"
            )
        } else {
            listOf(
                "all" to "全部",
                "ok" to "已缓存",
                "fonts" to "字体",
                "error" to "失败",
                "ignored" to "已忽略",
                "pending" to "待缓存",
                "largest" to "大图优先"
            )
        }
        val current = if (vaultSearchModeSources) activeSourceFilter else activeUrlFilter
        val surface2 = ContextCompat.getColor(this, R.color.surface_2)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val border = ContextCompat.getColor(this, R.color.border)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textMuted = ContextCompat.getColor(this, R.color.text_muted)

        val bgState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, surface2)
        )
        val textState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(android.graphics.Color.WHITE, textMuted)
        )

        for ((key, label) in options) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = (key == current)
                isCheckedIconVisible = false
                chipBackgroundColor = bgState
                setTextColor(textState)
                chipStrokeWidth = 2f
                chipStrokeColor = ColorStateList.valueOf(border)
                chipCornerRadius = 24f
                textSize = 12f
                setOnClickListener {
                    if (vaultSearchModeSources) activeSourceFilter = key else activeUrlFilter = key
                    refreshVaultList()
                }
            }
            group.addView(chip)
        }
    }

    private fun applySavedNightMode() {
        val saved = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != saved) {
            AppCompatDelegate.setDefaultNightMode(saved)
        }
    }

    private fun isCurrentlyDark(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateThemeIcon() {
        binding.ivThemeIcon.setImageResource(
            if (isCurrentlyDark()) R.drawable.ic_sun else R.drawable.ic_moon
        )
    }

    private fun toggleTheme() {
        val next = if (isCurrentlyDark()) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        prefs.edit().putInt("night_mode", next).apply()
        AppCompatDelegate.setDefaultNightMode(next)
    }

    private fun updateSegmentedStyle() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val transparent = android.graphics.Color.TRANSPARENT
        val white = android.graphics.Color.WHITE
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
        // Day/Night Toggle Button
        binding.btnToggleTheme.setOnClickListener {
            toggleTheme()
        }

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
        binding.btnOpenTTFolder.setOnClickListener { openTauriTavernThemesFolder() }
        binding.btnScanFolder.setOnClickListener {
            val localPath = savedLocalDirPath
            val uri = savedTreeUri
            if (!localPath.isNullOrBlank()) {
                runLocalDirScan(File(localPath))
            } else if (uri != null) {
                runFolderScan(uri)
            } else {
                toast("请先选择美化目录或点一键直达 TT 酒馆")
                pickFolderLauncher.launch(null)
            }
        }

        // Tab 1: Speed Toggle + Start / Retry / Stop Foreground Cache
        binding.btnSpeedToggle.setOnClickListener {
            downloadConcurrency = when (downloadConcurrency) {
                8 -> 16
                16 -> 24
                else -> 8
            }
            val label = "$downloadConcurrency 线程"
            binding.btnSpeedToggle.text = label
            toast("已切换为 $label")
        }
        binding.btnStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
            toast("开始后台缓存")
        }
        binding.btnRetryFailed.setOnClickListener {
            VaultDownloadService.start(this, mode = "failed", concurrency = minOf(12, downloadConcurrency))
            toast("开始重试失败链接")
        }
        binding.btnStopCache.setOnClickListener {
            VaultDownloadService.stop(this)
            toast("正在暂停...")
        }
        binding.btnIgnoreFailed.setOnClickListener {
            val errCount = repo.statsFlow.value.errorUrls
            if (errCount <= 0) {
                toast("当前没有失败链接")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("忽略失败链接")
                .setMessage("将当前 $errCount 个失败链接标记为不再重试？之后仍可在「查图 → 已忽略」中查看或相册替换。")
                .setPositiveButton("确定") { _, _ ->
                    lifecycleScope.launch {
                        val n = withContext(Dispatchers.IO) { repo.ignoreAllFailedUrls() }
                        toast("已忽略 $n 个失败链接")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.btnExportZip.setOnClickListener {
            val stats = repo.statsFlow.value
            if (stats.okUrls <= 0) {
                toast("还没有已缓存的图片，请先扫描并缓存")
                return@setOnClickListener
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            createVaultZipLauncher.launch("ThemeVault_图床存档_$stamp.zip")
        }

        // Tab 2: Vault Search
        binding.btnModeSources.setOnClickListener {
            vaultSearchModeSources = true
            binding.rvVault.adapter = sourcesAdapter
            updateSegmentedStyle()
            rebuildFilterChips()
            refreshVaultList()
        }
        binding.btnModeUrls.setOnClickListener {
            vaultSearchModeSources = false
            binding.rvVault.adapter = urlsAdapter
            updateSegmentedStyle()
            rebuildFilterChips()
            refreshVaultList()
        }
        binding.btnDoSearch.setOnClickListener { refreshVaultList() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                refreshVaultList()
                true
            } else false
        }

        // Tab 3: One-Click Repair & Image Host Settings
        binding.btnHostSettings.setOnClickListener { showHostSettingsDialog() }
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
            val labelPrefix = if (mode == "catbox") "上传换链" else if (mode == "path") "替换路径" else "压缩内嵌"
            binding.tvRepairStatus.text = "正在$labelPrefix..."
            showIsland("正在$labelPrefix...")

            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        repo.repairRawText(raw, repairInputFileName, mode) { done, total ->
                            runOnUiThread {
                                val msg = "$labelPrefix $done / $total"
                                binding.tvRepairStatus.text = msg
                                updateIsland(msg)
                            }
                        }
                    }
                    binding.tvRepairStatus.text =
                        "处理完成：共 ${res.totalUrls} 个图床，成功替换 ${res.matchedCount} 张" +
                            if (res.missingCount > 0) "（${res.missingCount} 张未缓存）" else ""
                    finishIsland("已完成 ${res.matchedCount}/${res.totalUrls}")
                    triggerSaveRepairedFile(res)
                } catch (e: Exception) {
                    binding.tvRepairStatus.text = "修复失败：${e.message}"
                    finishIsland("换链失败")
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

    // ================== Export Entire Vault ZIP & Share ==================
    private fun exportVaultArchiveToUri(uri: Uri) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val statusTv = TextView(this).apply {
            text = "正在整理目录并打包压缩包..."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        container.addView(statusTv)
        container.addView(pb)

        val dlg = AlertDialog.Builder(this)
            .setTitle("打包图床存档")
            .setView(container)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val os = contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入目标文件")
                    os.use { out ->
                        repo.exportVaultToZip(out) { done, total ->
                            if (done % 10 == 0 || done == total) {
                                runOnUiThread {
                                    val pct = if (total > 0) ((done * 100) / total).coerceIn(0, 100) else 100
                                    pb.progress = pct
                                    statusTv.text = "正在打包：$done / $total 张 ($pct%)"
                                }
                            }
                        }
                    }
                }
                dlg.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("存档打包完成")
                    .setMessage(
                        "已将 ${res.imageCount} 张图片（涵盖 ${res.sourceCount} 个美化）以及完整对照索引 manifest.json 整理进 ZIP 压缩包。\n\n压缩包大小：${formatBytes(res.totalBytes)}"
                    )
                    .setPositiveButton("直接分享到其他软件") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "发送图床压缩包到..."))
                    }
                    .setNegativeButton("完成", null)
                    .show()
            } catch (e: Exception) {
                dlg.dismiss()
                toast("打包失败：${e.message}")
            }
        }
    }

    // ================== Folder Scan & Vault UI ==================
    private fun openTauriTavernThemesFolder() {
        val ttThemesDir = File("/storage/emulated/0/Android/data/com.tauritavern.client/data/default-user/themes")
        // 1. If directly readable via File API, scan immediately!
        if (ttThemesDir.exists() && ttThemesDir.canRead() && ttThemesDir.listFiles() != null) {
            savedLocalDirPath = ttThemesDir.absolutePath
            savedTreeUri = null
            prefs.edit().putString("local_dir_path", ttThemesDir.absolutePath).remove("tree_uri").apply()
            updateFolderLabel()
            runLocalDirScan(ttThemesDir)
            return
        }

        // 2. Guide user to pick via MT Manager inside Android's SAF sidebar (works on Android 13/14/15!)
        AlertDialog.Builder(this)
            .setTitle("访问 TT 酒馆目录")
            .setMessage(
                "由于安卓 13/14+ 系统限制，普通应用无法直接打开 Android/data，但你可以直接借助手机里的 MT 管理器授权：\n\n" +
                    "1. 点下方「打开文件选择器」\n" +
                    "2. 点左上角菜单 ≡ → 选择「MT管理器」\n" +
                    "3. 进入 Android/data/com.tauritavern.client/data/default-user/themes\n" +
                    "4. 点底部「使用此文件夹」即可直接扫描"
            )
            .setPositiveButton("打开文件选择器") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra("android.provider.extra.SHOW_ADVANCED", true)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    openTTTreeIntentLauncher.launch(intent)
                } catch (_: Exception) {
                    pickFolderLauncher.launch(null)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runLocalDirScan(dir: File) {
        val includePng = binding.cbIncludePng.isChecked
        binding.btnPickFolder.isEnabled = false
        binding.btnOpenTTFolder.isEnabled = false
        binding.btnScanFolder.isEnabled = false
        binding.tvScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "正在扫描 TT 酒馆目录..."

        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    repo.scanLocalDirectory(dir, includePng) { scanned, matched ->
                        runOnUiThread {
                            binding.tvScanProgress.text = "已扫描 $scanned 个文件，命中 $matched 个美化..."
                        }
                    }
                }
                val dupTip = if (res.duplicateFiles > 0) "（自动去重跳过 ${res.duplicateFiles} 个重复美化）" else ""
                binding.tvScanProgress.text =
                    "扫描完成：共扫 ${res.scannedFiles} 个文件，命中 ${res.matchedFiles} 个美化$dupTip，新增 ${res.newUrls} 个图床"
                toast("扫描完成，新增 ${res.newUrls} 个图床")
            } catch (e: Exception) {
                binding.tvScanProgress.text = "扫描失败：${e.message}"
            } finally {
                binding.btnPickFolder.isEnabled = true
                binding.btnOpenTTFolder.isEnabled = true
                binding.btnScanFolder.isEnabled = true
            }
        }
    }

    private fun updateFolderLabel() {
        val localPath = savedLocalDirPath
        if (!localPath.isNullOrBlank()) {
            binding.tvSelectedFolder.text = "已绑定 TT 目录：$localPath"
            return
        }
        val uri = savedTreeUri
        if (uri == null) {
            binding.tvSelectedFolder.text = "点击下方按钮授权你的美化目录"
            return
        }
        binding.tvSelectedFolder.text = "已绑定目录：读取中..."
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    DocumentFile.fromTreeUri(this@MainActivity, uri)?.name ?: uri.lastPathSegment
                } catch (_: Exception) {
                    uri.lastPathSegment
                }
            }
            binding.tvSelectedFolder.text = "已绑定目录：$name"
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
                val dupTip = if (res.duplicateFiles > 0) "（自动去重跳过 ${res.duplicateFiles} 个重复美化）" else ""
                binding.tvScanProgress.text =
                    "扫描完成：共扫 ${res.scannedFiles} 个文件，命中 ${res.matchedFiles} 个美化$dupTip，新增 ${res.newUrls} 个图床"
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
                val list = withContext(Dispatchers.IO) { repo.searchSources(q, activeSourceFilter) }
                sourcesAdapter.submitList(list)
                binding.tvVaultCount.text = "共 ${list.size} 个美化文件"
            } else {
                val list = withContext(Dispatchers.IO) { repo.searchUrls(q, activeUrlFilter) }
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
            val labelPrefix = if (mode == "catbox") "上传换链" else "压缩内嵌"
            activeSourceDialog?.dismiss()
            showIsland("正在$labelPrefix...")
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        repo.repairSourceById(source.id, mode) { done, total ->
                            runOnUiThread {
                                updateIsland("$labelPrefix $done / $total")
                            }
                        }
                    }
                    finishIsland("已完成 ${res.matchedCount}/${res.totalUrls}")
                    toast("已替换 ${res.matchedCount}/${res.totalUrls} 张，请选择保存位置")
                    triggerSaveRepairedFile(res)
                } catch (e: Exception) {
                    finishIsland("处理失败")
                    toast("修复失败：${e.message}")
                }
            }
        }

        dBinding.btnDialogPreview.setOnClickListener {
            activeSourceDialog?.dismiss()
            ThemePreviewActivity.open(this, source.id)
        }
        dBinding.btnDialogRepairCatbox.setOnClickListener { runModalRepair("catbox") }
        dBinding.btnDialogRepairBase64.setOnClickListener { runModalRepair("base64_webp") }

        activeSourceDialog = AlertDialog.Builder(this)
            .setView(dBinding.root)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun updateHostLabel() {
        val cfg = HostConfig.load(this)
        binding.btnHostSettings.text = "图床：${cfg.displayName()}"
    }

    private fun showHostSettingsDialog() {
        val cfg = HostConfig.load(this)
        val providers = listOf(
            "auto" to "自动 (Catbox / 流浪图床)",
            "sda1" to "流浪图床 (p.sda1.dev)",
            "postimages" to "Postimages (postimages.org)",
            "catbox" to "Catbox (catbox.moe)",
            "imgbb" to "ImgBB",
            "github" to "GitHub 仓库 (jsDelivr)",
            "custom" to "自定义图床 API"
        )

        val scroll = android.widget.ScrollView(this)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        scroll.addView(root)

        val radioGroup = android.widget.RadioGroup(this)
        val radioIds = HashMap<Int, String>()
        for ((key, label) in providers) {
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 13.5f
                isChecked = (key == cfg.provider)
            }
            radioIds[rb.id] = key
            radioGroup.addView(rb)
        }
        root.addView(radioGroup)

        val fieldsContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        root.addView(fieldsContainer)

        fun makeField(hintText: String, value: String): android.widget.EditText {
            return android.widget.EditText(this).apply {
                hint = hintText
                setText(value)
                isSingleLine = true
                textSize = 13f
            }
        }

        val etCatboxHash = makeField("Catbox userhash（选填）", cfg.catboxUserHash)
        val etPiKey = makeField("Postimages API Key (postimages.org/login/api)", cfg.postimagesKey)
        val etPiGallery = makeField("Postimages 相册 ID（选填）", cfg.postimagesGallery)
        val etImgbbKey = makeField("ImgBB API Key", cfg.imgbbApiKey)
        val etGhRepo = makeField("GitHub 仓库，例如 cone97218-alt/pic", cfg.githubRepo)
        val etGhBranch = makeField("分支名（默认 main）", cfg.githubBranch)
        val etGhDir = makeField("存放目录（默认 images）", cfg.githubDir)
        val etGhToken = makeField("GitHub Token (ghp_...)", cfg.githubToken)
        val etCustomUrl = makeField("上传接口 URL，例如 https://xxx/api/v1/upload", cfg.customEndpoint)
        val etCustomField = makeField("文件表单字段名（默认 file）", cfg.customField)
        val etCustomAuth = makeField("Authorization 请求头（选填，如 Bearer xxx）", cfg.customAuthHeader)

        fun refreshFields(selected: String) {
            fieldsContainer.removeAllViews()
            when (selected) {
                "catbox", "auto" -> fieldsContainer.addView(etCatboxHash)
                "postimages" -> {
                    fieldsContainer.addView(etPiKey)
                    fieldsContainer.addView(etPiGallery)
                }
                "imgbb" -> fieldsContainer.addView(etImgbbKey)
                "github" -> {
                    fieldsContainer.addView(etGhRepo)
                    fieldsContainer.addView(etGhBranch)
                    fieldsContainer.addView(etGhDir)
                    fieldsContainer.addView(etGhToken)
                }
                "custom" -> {
                    fieldsContainer.addView(etCustomUrl)
                    fieldsContainer.addView(etCustomField)
                    fieldsContainer.addView(etCustomAuth)
                }
            }
        }

        refreshFields(cfg.provider)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            refreshFields(radioIds[checkedId] ?: "auto")
        }

        AlertDialog.Builder(this)
            .setTitle("选择上传图床")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val picked = radioIds[radioGroup.checkedRadioButtonId] ?: "auto"
                val newCfg = HostConfig(
                    provider = picked,
                    catboxUserHash = etCatboxHash.text.toString(),
                    postimagesKey = etPiKey.text.toString(),
                    postimagesGallery = etPiGallery.text.toString(),
                    imgbbApiKey = etImgbbKey.text.toString(),
                    githubRepo = etGhRepo.text.toString(),
                    githubBranch = etGhBranch.text.toString(),
                    githubDir = etGhDir.text.toString(),
                    githubToken = etGhToken.text.toString(),
                    customEndpoint = etCustomUrl.text.toString(),
                    customField = etCustomField.text.toString(),
                    customAuthHeader = etCustomAuth.text.toString()
                )
                HostConfig.save(this, newCfg)
                updateHostLabel()
                toast("已切换图床：${newCfg.displayName()}")
            }
            .setNegativeButton("取消", null)
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

    private fun showIsland(text: String) {
        val pill = binding.dynamicIsland
        binding.pbIsland.visibility = View.VISIBLE
        binding.tvIslandText.text = text
        pill.animate().cancel()
        if (pill.visibility != View.VISIBLE) {
            pill.alpha = 0f
            pill.translationY = -48f
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun updateIsland(text: String) {
        if (binding.dynamicIsland.visibility != View.VISIBLE) {
            showIsland(text)
        } else {
            binding.tvIslandText.text = text
        }
    }

    private fun finishIsland(text: String) {
        val pill = binding.dynamicIsland
        binding.pbIsland.visibility = View.GONE
        binding.tvIslandText.text = text
        pill.animate().cancel()
        pill.postDelayed({
            pill.animate()
                .alpha(0f)
                .translationY(-48f)
                .setDuration(220)
                .withEndAction { pill.visibility = View.GONE }
                .start()
        }, 1600L)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
