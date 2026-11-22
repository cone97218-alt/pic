package com.stvault.app.ui

import android.Manifest
import android.content.ClipboardManager
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
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.stvault.app.BuildConfig
import com.stvault.app.R
import com.stvault.app.core.HostConfig
import com.stvault.app.core.ReleaseInfo
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.UpdateManager
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityMainBinding
import com.stvault.app.databinding.DialogImportCharUrlsBinding
import com.stvault.app.databinding.DialogSourceDetailBinding
import com.stvault.app.model.CharacterRecord
import com.stvault.app.model.ComponentRecord
import com.stvault.app.model.PresetRecord
import com.stvault.app.model.RepairResult
import com.stvault.app.model.SourceRecord
import com.stvault.app.model.UrlRecord
import com.stvault.app.model.WorldBookRecord
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
    private lateinit var libRepo: ResourceLibraryRepository
    private lateinit var sourcesAdapter: SourcesAdapter
    private lateinit var urlsAdapter: UrlsAdapter
    private lateinit var charactersAdapter: CharactersAdapter
    private lateinit var worldBooksAdapter: WorldBooksAdapter
    private lateinit var presetsAdapter: PresetsAdapter
    private lateinit var componentsAdapter: ComponentsAdapter
    private lateinit var presetComponentsAdapter: ComponentsAdapter

    private var activeWorkspace = "themes" // "themes" | "characters" | "worldbooks" | "presets" | "components"
    private var activeCharFilter = "all"
    private var activeWbFilter = "all"
    private var activeCompFilter = "all"

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

    // 11. Import Single or Multiple Theme Files
    private val importSingleFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
            }
            importSingleFiles(uris)
        }

    // 12. Multi-module File & Folder Pickers
    private val importCharFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            runModuleFileImport("角色卡", uris) { libRepo.importCharacterUris(it) }
        }

    private val scanCharFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runModuleFolderScan("角色卡", uri, null) { u, cb ->
                libRepo.scanCharactersTreeUri(u, cb)
            }
        }

    private val importWbFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            runModuleFileImport("世界书", uris) { libRepo.importWorldBookUris(it) }
        }

    private val scanWbFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runModuleFolderScan("世界书", uri, null) { u, cb ->
                libRepo.scanWorldBooksTreeUri(u, cb)
            }
        }

    private val importPresetFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            runModuleFileImport("预设", uris) { libRepo.importPresetUris(it) }
        }

    private val scanPresetFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runModuleFolderScan("预设", uri, null) { u, cb ->
                libRepo.scanPresetsTreeUri(u, cb)
            }
        }

    private val importCompFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            runModuleFileImport("组件", uris) { libRepo.importComponentUris(it) }
        }

    private val scanCompFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runModuleFolderScan("组件", uri, null) { u, cb ->
                libRepo.scanComponentsTreeUri(u, cb)
            }
        }

    private var pendingExportCompFile: File? = null
    private val exportCompFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val f = pendingExportCompFile ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            f.inputStream().use { inp -> inp.copyTo(out) }
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                pendingExportCompFile = null
                toast(if (ok) "组件已导出" else "导出失败")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        updateThemeIcon()
        updateHostLabel()

        repo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)
        savedTreeUri = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }
        savedLocalDirPath = prefs.getString("local_dir_path", null)
        updateFolderLabel()

        requestNotificationPermissionIfNeeded()
        setupRecyclerViews()
        setupDrawer()
        setupListeners()
        observeState()
        checkUpdateSilently()

        val lastWs = prefs.getString("active_workspace", "themes") ?: "themes"
        switchWorkspace(lastWs)
    }

    override fun onResume() {
        super.onResume()
        refreshActiveWorkspace()
        libRepo.refreshCounts()
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
        // 1. Themes
        binding.rvVault.layoutManager = LinearLayoutManager(this)
        sourcesAdapter = SourcesAdapter(
            repo = repo,
            onClick = { source -> showSourceDetailDialog(source) },
            onPreviewClick = { source -> ThemePreviewActivity.open(this, source.id) },
            onDeleteClick = { source -> confirmDeleteSource(source) }
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

        // 2. Characters (2-column cover grid)
        binding.wsCharacters.rvCharacters.layoutManager = GridLayoutManager(this, 2)
        charactersAdapter = CharactersAdapter(
            libRepo = libRepo,
            onClick = { c -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_CHARACTER, c.id) },
            onLongClick = { c -> confirmDeleteCharacter(c) }
        )
        binding.wsCharacters.rvCharacters.adapter = charactersAdapter
        buildSimpleChips(
            binding.wsCharacters.chipGroupCharFilter,
            listOf("all" to "最新导入", "has_book" to "带世界书", "has_script" to "带脚本", "has_urls" to "含外链图"),
            activeCharFilter
        ) { key ->
            activeCharFilter = key
            refreshCharactersList()
        }

        // 3. World Books
        binding.wsWorldBooks.rvWorldBooks.layoutManager = LinearLayoutManager(this)
        worldBooksAdapter = WorldBooksAdapter(
            onClick = { w -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_WORLDBOOK, w.id) },
            onDeleteClick = { w -> confirmDeleteWorldBook(w) }
        )
        binding.wsWorldBooks.rvWorldBooks.adapter = worldBooksAdapter
        buildSimpleChips(
            binding.wsWorldBooks.chipGroupWbFilter,
            listOf("all" to "全部", "standalone" to "独立世界书", "from_card" to "来自角色卡", "has_urls" to "含外链图"),
            activeWbFilter
        ) { key ->
            activeWbFilter = key
            refreshWorldBooksList()
        }

        // 4. Presets & Linked Components
        binding.wsPresets.rvPresets.layoutManager = LinearLayoutManager(this)
        presetsAdapter = PresetsAdapter(
            onClick = { p -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_PRESET, p.id) },
            onDeleteClick = { p -> confirmDeletePreset(p) }
        )
        binding.wsPresets.rvPresets.adapter = presetsAdapter

        binding.wsPresets.rvPresetComponents.layoutManager = LinearLayoutManager(this)
        presetComponentsAdapter = ComponentsAdapter(
            onClick = { c -> showComponentDetailDialog(c) },
            onDeleteClick = { c -> confirmDeleteComponent(c) }
        )
        binding.wsPresets.rvPresetComponents.adapter = presetComponentsAdapter

        // 5. Components (Regex / Helper / QR)
        binding.wsComponents.rvComponents.layoutManager = LinearLayoutManager(this)
        componentsAdapter = ComponentsAdapter(
            onClick = { c -> showComponentDetailDialog(c) },
            onDeleteClick = { c -> confirmDeleteComponent(c) }
        )
        binding.wsComponents.rvComponents.adapter = componentsAdapter
        buildSimpleChips(
            binding.wsComponents.chipGroupCompFilter,
            listOf("all" to "全部", "regex" to "正则", "helper" to "助手脚本", "qr" to "快速回复", "global" to "全局", "linked" to "已关联"),
            activeCompFilter
        ) { key ->
            activeCompFilter = key
            refreshComponentsList()
        }
    }

    private fun buildSimpleChips(
        group: com.google.android.material.chip.ChipGroup,
        options: List<Pair<String, String>>,
        selectedKey: String,
        onSelect: (String) -> Unit
    ) {
        group.removeAllViews()
        val surface2 = ContextCompat.getColor(this, R.color.surface_2)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val border = ContextCompat.getColor(this, R.color.border)
        val textMuted = ContextCompat.getColor(this, R.color.text_muted)

        val bgState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, surface2))
        val textState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(onAccent, textMuted))
        val strokeState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, border))

        for ((key, label) in options) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = (key == selectedKey)
                isCheckedIconVisible = false
                chipBackgroundColor = bgState
                setTextColor(textState)
                chipStrokeWidth = 2f
                chipStrokeColor = strokeState
                chipCornerRadius = 20f
                textSize = 12f
                setOnClickListener { onSelect(key) }
            }
            group.addView(chip)
        }
    }

    private fun setupDrawer() {
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.drawer.drawerItemThemes.setOnClickListener { switchWorkspace("themes") }
        binding.drawer.drawerItemCharacters.setOnClickListener { switchWorkspace("characters") }
        binding.drawer.drawerItemWorldBooks.setOnClickListener { switchWorkspace("worldbooks") }
        binding.drawer.drawerItemPresets.setOnClickListener { switchWorkspace("presets") }
        binding.drawer.drawerItemComponents.setOnClickListener { switchWorkspace("components") }
    }

    private fun switchWorkspace(workspace: String) {
        activeWorkspace = workspace
        prefs.edit().putString("active_workspace", workspace).apply()
        binding.drawerLayout.closeDrawer(GravityCompat.START)

        // Show only active workspace container
        binding.workspaceThemes.visibility = if (workspace == "themes") View.VISIBLE else View.GONE
        binding.wsCharacters.root.visibility = if (workspace == "characters") View.VISIBLE else View.GONE
        binding.wsWorldBooks.root.visibility = if (workspace == "worldbooks") View.VISIBLE else View.GONE
        binding.wsPresets.root.visibility = if (workspace == "presets") View.VISIBLE else View.GONE
        binding.wsComponents.root.visibility = if (workspace == "components") View.VISIBLE else View.GONE

        // Update Top Header title (clean single-line, no subtitle)
        val title = when (workspace) {
            "characters" -> "角色卡"
            "worldbooks" -> "世界书"
            "presets" -> "预设"
            "components" -> "组件"
            else -> "美化"
        }
        binding.tvWorkspaceTitle.text = title

        // Highlight active drawer item
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val items = listOf(
            Triple("themes", binding.drawer.drawerItemThemes, binding.drawer.tvDrawerThemes to binding.drawer.ivDrawerThemes),
            Triple("characters", binding.drawer.drawerItemCharacters, binding.drawer.tvDrawerCharacters to binding.drawer.ivDrawerCharacters),
            Triple("worldbooks", binding.drawer.drawerItemWorldBooks, binding.drawer.tvDrawerWorldBooks to binding.drawer.ivDrawerWorldBooks),
            Triple("presets", binding.drawer.drawerItemPresets, binding.drawer.tvDrawerPresets to binding.drawer.ivDrawerPresets),
            Triple("components", binding.drawer.drawerItemComponents, binding.drawer.tvDrawerComponents to binding.drawer.ivDrawerComponents)
        )
        for ((key, row, pair) in items) {
            val active = (key == workspace)
            row.setBackgroundResource(if (active) R.drawable.bg_input else 0)
            pair.first.setTextColor(if (active) primary else muted)
            pair.first.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            pair.second.imageTintList = ColorStateList.valueOf(if (active) primary else muted)
        }

        // Show sleek floating pill dock ONLY for Themes workspace
        binding.floatingBottomDock.visibility = if (workspace == "themes") View.VISIBLE else View.GONE
        if (workspace == "themes") {
            selectThemeDockTab("cache")
        } else if (workspace == "presets") {
            selectPresetSubMode(showList = true)
        }
        refreshActiveWorkspace()
    }

    private fun selectThemeDockTab(tab: String) {
        binding.tabCache.visibility = if (tab == "cache") View.VISIBLE else View.GONE
        binding.tabVault.visibility = if (tab == "vault") View.VISIBLE else View.GONE
        binding.tabRepair.visibility = if (tab == "repair") View.VISIBLE else View.GONE
        if (tab == "vault") refreshVaultList()

        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val tabs = listOf(
            Triple("cache", binding.dockTabCache, binding.ivDockCache to binding.tvDockCache),
            Triple("vault", binding.dockTabVault, binding.ivDockVault to binding.tvDockVault),
            Triple("repair", binding.dockTabRepair, binding.ivDockRepair to binding.tvDockRepair)
        )
        for ((key, pill, pair) in tabs) {
            val active = (key == tab)
            pill.setBackgroundResource(if (active) R.drawable.bg_dock_item_active else 0)
            pair.first.imageTintList = ColorStateList.valueOf(if (active) onAccent else muted)
            pair.second.setTextColor(if (active) onAccent else muted)
            pair.second.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun selectPresetSubMode(showList: Boolean) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val transparent = android.graphics.Color.TRANSPARENT

        binding.wsPresets.tabPresetList.visibility = if (showList) View.VISIBLE else View.GONE
        binding.wsPresets.tabPresetComponents.visibility = if (showList) View.GONE else View.VISIBLE

        if (showList) {
            binding.wsPresets.btnPresetModeList.backgroundTintList = ColorStateList.valueOf(accent)
            binding.wsPresets.btnPresetModeList.setTextColor(onAccent)
            binding.wsPresets.btnPresetModeComps.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.wsPresets.btnPresetModeComps.setTextColor(muted)
        } else {
            binding.wsPresets.btnPresetModeComps.backgroundTintList = ColorStateList.valueOf(accent)
            binding.wsPresets.btnPresetModeComps.setTextColor(onAccent)
            binding.wsPresets.btnPresetModeList.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.wsPresets.btnPresetModeList.setTextColor(muted)
        }
    }

    private fun refreshActiveWorkspace() {
        when (activeWorkspace) {
            "themes" -> refreshVaultList()
            "characters" -> refreshCharactersList()
            "worldbooks" -> refreshWorldBooksList()
            "presets" -> refreshPresetsList()
            "components" -> refreshComponentsList()
        }
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
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val border = ContextCompat.getColor(this, R.color.border)
        val textMuted = ContextCompat.getColor(this, R.color.text_muted)

        val bgState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, surface2)
        )
        val textState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(onAccent, textMuted)
        )
        val strokeState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, border)
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
                chipStrokeColor = strokeState
                chipCornerRadius = 20f
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
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val transparent = android.graphics.Color.TRANSPARENT
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        if (vaultSearchModeSources) {
            binding.btnModeSources.backgroundTintList = ColorStateList.valueOf(accent)
            binding.btnModeSources.setTextColor(onAccent)
            binding.btnModeUrls.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.btnModeUrls.setTextColor(muted)
            binding.btnImportInVault.visibility = View.VISIBLE
        } else {
            binding.btnModeUrls.backgroundTintList = ColorStateList.valueOf(accent)
            binding.btnModeUrls.setTextColor(onAccent)
            binding.btnModeSources.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.btnModeSources.setTextColor(muted)
            binding.btnImportInVault.visibility = View.GONE
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

        // Floating Pill Bottom Dock (Themes Workspace)
        binding.dockTabCache.setOnClickListener { selectThemeDockTab("cache") }
        binding.dockTabVault.setOnClickListener { selectThemeDockTab("vault") }
        binding.dockTabRepair.setOnClickListener { selectThemeDockTab("repair") }

        // Workspace 2: Characters Listeners
        binding.wsCharacters.btnCharSearch.setOnClickListener { refreshCharactersList() }
        binding.wsCharacters.etCharSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshCharactersList(); true } else false
        }
        binding.wsCharacters.btnCharQuickImport.setOnClickListener { importCharFilesLauncher.launch(arrayOf("*/*")) }
        binding.wsCharacters.btnCharQuickUrl.setOnClickListener { showImportCharacterUrlDialog() }
        binding.wsCharacters.btnCharScanFolder.setOnClickListener { scanCharFolderLauncher.launch(null) }
        binding.wsCharacters.btnCharStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
            toast("开始后台缓存")
        }

        // Workspace 3: World Books Listeners
        binding.wsWorldBooks.btnWbSearch.setOnClickListener { refreshWorldBooksList() }
        binding.wsWorldBooks.etWbSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshWorldBooksList(); true } else false
        }
        binding.wsWorldBooks.btnWbQuickImport.setOnClickListener { importWbFilesLauncher.launch(arrayOf("*/*")) }
        binding.wsWorldBooks.btnWbScanFolder.setOnClickListener { scanWbFolderLauncher.launch(null) }
        binding.wsWorldBooks.btnWbStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
            toast("开始后台缓存")
        }

        // Workspace 4: Presets Listeners
        binding.wsPresets.btnPresetModeList.setOnClickListener { selectPresetSubMode(showList = true) }
        binding.wsPresets.btnPresetModeComps.setOnClickListener {
            selectPresetSubMode(showList = false)
            refreshPresetsList()
        }
        binding.wsPresets.btnPresetSearch.setOnClickListener { refreshPresetsList() }
        binding.wsPresets.etPresetSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshPresetsList(); true } else false
        }
        binding.wsPresets.btnPresetImportFile.setOnClickListener { importPresetFilesLauncher.launch(arrayOf("*/*")) }
        binding.wsPresets.btnPresetScanFolder.setOnClickListener { scanPresetFolderLauncher.launch(null) }

        // Workspace 5: Components Listeners
        binding.wsComponents.btnCompSearch.setOnClickListener { refreshComponentsList() }
        binding.wsComponents.etCompSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshComponentsList(); true } else false
        }
        binding.wsComponents.btnCompImportFile.setOnClickListener { importCompFilesLauncher.launch(arrayOf("*/*")) }
        binding.wsComponents.btnCompScanFolder.setOnClickListener { scanCompFolderLauncher.launch(null) }

        // Tab 1: Folder Pick, Single File Import & Scan
        binding.btnPickFolder.setOnClickListener { pickFolderLauncher.launch(savedTreeUri) }
        binding.btnImportSingleFile.setOnClickListener { importSingleFilesLauncher.launch(arrayOf("*/*")) }
        binding.btnImportInVault.setOnClickListener { importSingleFilesLauncher.launch(arrayOf("*/*")) }
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
                binding.drawer.tvCountThemes.text = st.totalSources.toString()
                libRepo.refreshCounts()
            }
        }

        lifecycleScope.launch {
            libRepo.countsFlow.collectLatest { c ->
                binding.drawer.tvCountThemes.text = c.themes.toString()
                binding.drawer.tvCountChars.text = c.characters.toString()
                binding.drawer.tvCountBooks.text = c.worldBooks.toString()
                binding.drawer.tvCountPresets.text = c.presets.toString()
                binding.drawer.tvCountComps.text = c.components.toString()
                binding.drawer.tvDrawerTotalSize.text = formatBytes(c.totalStorageBytes)
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
        binding.btnPickFolder.isEnabled = false
        binding.btnOpenTTFolder.isEnabled = false
        binding.btnScanFolder.isEnabled = false
        binding.tvScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "正在扫描 TT 酒馆目录..."

        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    repo.scanLocalDirectory(dir, includePng = false) { scanned, matched ->
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
        binding.btnPickFolder.isEnabled = false
        binding.btnScanFolder.isEnabled = false
        binding.tvScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "正在递归扫描目录..."

        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    repo.scanTreeUri(uri, includePng = false) { scanned, matched ->
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

    private fun importSingleFiles(uris: List<Uri>) {
        binding.btnImportSingleFile.isEnabled = false
        showIsland("正在导入美化...")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    repo.importFileUris(uris)
                }
                val msg = when {
                    res.matchedFiles > 0 ->
                        "已导入 ${res.matchedFiles} 个美化，新增 ${res.newUrls} 个图床"
                    res.duplicateFiles > 0 ->
                        "所选美化已在库中"
                    else ->
                        "未检测到外链图床"
                }
                finishIsland(msg)
                toast(msg)
                refreshVaultList()
            } catch (e: Exception) {
                finishIsland("导入失败")
                toast("导入失败：${e.message}")
            } finally {
                binding.btnImportSingleFile.isEnabled = true
            }
        }
    }

    private fun confirmDeleteSource(source: SourceRecord) {
        val cleanName = source.name.substringAfterLast('/')
        AlertDialog.Builder(this)
            .setTitle("删除美化")
            .setMessage("确定删除「$cleanName」？\n\n将同时清理仅由该美化引用的独享图床缓存，不影响其他美化共用的图片。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { repo.deleteSource(source.id) }
                    activeSourceDialog?.dismiss()
                    if (res != null) {
                        val freedStr = if (res.freedBytes > 0L) "，释放 ${formatBytes(res.freedBytes)}" else ""
                        toast("已删除，清理 ${res.removedUrls} 条独享链接$freedStr")
                        refreshVaultList()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

        dBinding.btnDialogDelete.setOnClickListener {
            confirmDeleteSource(source)
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
        val etPiKey = makeField("Postimages API Key（留空则免登录匿名上传）", cfg.postimagesKey)
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

    // ================== Multi-Module Workspace Actions ==================

    private fun runModuleFileImport(
        label: String,
        uris: List<Uri>,
        importer: (List<Uri>) -> com.stvault.app.model.ScanResult
    ) {
        showIsland("正在导入$label...")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { importer(uris) }
                val msg = when {
                    res.matchedFiles > 0 ->
                        "已导入 ${res.matchedFiles} 个$label" + if (res.newUrls > 0) "，提取 ${res.newUrls} 个图床" else ""
                    res.duplicateFiles > 0 -> "所选$label 已在库中"
                    else -> "未识别到有效的$label 文件"
                }
                finishIsland(msg)
                toast(msg)
                refreshActiveWorkspace()
            } catch (e: Exception) {
                finishIsland("导入失败")
                toast("导入失败：${e.message}")
            }
        }
    }

    private fun runModuleFolderScan(
        label: String,
        uri: Uri,
        statusTv: TextView?,
        scanner: (Uri, (Int, Int) -> Unit) -> com.stvault.app.model.ScanResult
    ) {
        statusTv?.visibility = View.VISIBLE
        statusTv?.text = "正在扫描$label 目录..."
        showIsland("正在扫描$label...")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    scanner(uri) { scanned, matched ->
                        runOnUiThread {
                            val txt = "已扫 $scanned 个，命中 $matched 个$label"
                            statusTv?.text = txt
                            updateIsland(txt)
                        }
                    }
                }
                val dupStr = if (res.duplicateFiles > 0) "，去重 ${res.duplicateFiles} 个" else ""
                val msg = "扫描完成：新增 ${res.matchedFiles} 个$label$dupStr"
                statusTv?.text = msg
                finishIsland(msg)
                toast(msg)
                refreshActiveWorkspace()
            } catch (e: Exception) {
                statusTv?.text = "扫描失败：${e.message}"
                finishIsland("扫描失败")
                toast("扫描失败：${e.message}")
            }
        }
    }

    private fun showImportCharacterUrlDialog() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogImportCharUrlsBinding.inflate(LayoutInflater.from(this))
        sheet.setContentView(sheetBinding.root)
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        sheetBinding.btnPasteUrls.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
            if (clipText.isEmpty()) {
                toast("剪贴板为空")
            } else {
                val cur = sheetBinding.etBatchCharUrls.text?.toString().orEmpty()
                val next = if (cur.isBlank()) clipText else "$cur\n$clipText"
                sheetBinding.etBatchCharUrls.setText(next)
                sheetBinding.etBatchCharUrls.setSelection(next.length)
            }
        }

        sheetBinding.btnCancelUrlImport.setOnClickListener { sheet.dismiss() }

        sheetBinding.btnConfirmUrlImport.setOnClickListener {
            val raw = sheetBinding.etBatchCharUrls.text?.toString().orEmpty()
            val urls = raw.lines()
                .map { it.trim() }
                .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                .distinct()
            if (urls.isEmpty()) {
                toast("请粘贴至少一条有效的 http/https 直链")
                return@setOnClickListener
            }
            sheet.dismiss()
            runBatchCharacterUrlImport(urls)
        }

        sheet.show()
    }

    private fun runBatchCharacterUrlImport(urls: List<String>) {
        val total = urls.size
        showIsland(if (total == 1) "正在下载角色卡..." else "链接导入 1 / $total")
        lifecycleScope.launch {
            var okCount = 0
            var dupCount = 0
            var failCount = 0
            var lastSingleMsg = ""

            withContext(Dispatchers.IO) {
                urls.forEachIndexed { idx, url ->
                    if (total > 1) {
                        runOnUiThread { updateIsland("链接导入 ${idx + 1} / $total") }
                    }
                    try {
                        val (rec, msg) = libRepo.importCharacterFromUrl(url)
                        lastSingleMsg = msg
                        when {
                            msg.contains("已在库中") -> dupCount++
                            rec != null -> okCount++
                            else -> failCount++
                        }
                    } catch (e: Exception) {
                        lastSingleMsg = "失败：${e.message}"
                        failCount++
                    }
                }
            }

            val summary = if (total == 1) {
                lastSingleMsg
            } else {
                buildString {
                    append("导入完成：成功 $okCount")
                    if (dupCount > 0) append(" · 重复 $dupCount")
                    if (failCount > 0) append(" · 失败 $failCount")
                }
            }
            finishIsland(summary)
            toast(summary)
            if (okCount > 0) refreshCharactersList()
        }
    }

    private fun refreshCharactersList() {
        val q = binding.wsCharacters.etCharSearch.text?.toString().orEmpty()
        lifecycleScope.launch {
            val (list, pendingCount) = withContext(Dispatchers.IO) {
                val chars = libRepo.searchCharacters(q, activeCharFilter)
                val urls = libRepo.getAllCharacterUrls()
                val counts = repo.getCountsForUrls(urls)
                chars to counts.third
            }
            charactersAdapter.submitList(list)
            binding.wsCharacters.tvCharCount.text = "共 ${list.size} 张"
            if (pendingCount > 0) {
                binding.wsCharacters.btnCharStartCache.visibility = View.VISIBLE
                binding.wsCharacters.btnCharStartCache.text = "缓存图床 ($pendingCount)"
            } else {
                binding.wsCharacters.btnCharStartCache.visibility = View.GONE
            }
        }
    }

    private fun confirmDeleteCharacter(c: CharacterRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除角色卡")
            .setMessage("确定删除「${c.name}」？将同时清理该卡独享的外链图床缓存。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { libRepo.deleteCharacter(c.id) }
                    toast("已删除角色卡")
                    refreshCharactersList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshWorldBooksList() {
        val q = binding.wsWorldBooks.etWbSearch.text?.toString().orEmpty()
        lifecycleScope.launch {
            val (list, pendingCount) = withContext(Dispatchers.IO) {
                val books = libRepo.searchWorldBooks(q, activeWbFilter)
                val urls = libRepo.getAllWorldBookUrls()
                val counts = repo.getCountsForUrls(urls)
                books to counts.third
            }
            worldBooksAdapter.submitList(list)
            binding.wsWorldBooks.tvWbCount.text = "共 ${list.size} 本"
            if (pendingCount > 0) {
                binding.wsWorldBooks.btnWbStartCache.visibility = View.VISIBLE
                binding.wsWorldBooks.btnWbStartCache.text = "缓存图床 ($pendingCount)"
            } else {
                binding.wsWorldBooks.btnWbStartCache.visibility = View.GONE
            }
        }
    }

    private fun confirmDeleteWorldBook(w: WorldBookRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除世界书")
            .setMessage("确定删除「${w.name}」？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { libRepo.deleteWorldBook(w.id) }
                    toast("已删除世界书")
                    refreshWorldBooksList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshPresetsList() {
        val q = binding.wsPresets.etPresetSearch.text?.toString().orEmpty()
        lifecycleScope.launch {
            val (presets, linkedComps) = withContext(Dispatchers.IO) {
                val p = libRepo.searchPresets(q)
                val c = libRepo.searchComponents("", "linked").filter { it.linkedTargetType == "preset" }
                p to c
            }
            presetsAdapter.submitList(presets)
            binding.wsPresets.tvPresetCount.text = "共 ${presets.size} 个预设"
            presetComponentsAdapter.submitList(linkedComps)
        }
    }

    private fun confirmDeletePreset(p: PresetRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除预设")
            .setMessage("确定删除「${p.name}」？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { libRepo.deletePreset(p.id) }
                    toast("已删除预设")
                    refreshPresetsList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshComponentsList() {
        val q = binding.wsComponents.etCompSearch.text?.toString().orEmpty()
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { libRepo.searchComponents(q, activeCompFilter) }
            componentsAdapter.submitList(list)
            binding.wsComponents.tvCompCount.text = "共 ${list.size} 个组件"
        }
    }

    private fun showComponentDetailDialog(comp: ComponentRecord) {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { libRepo.readComponentText(comp) }
            val linkInfo = if (comp.linkedTargetName.isNotEmpty()) {
                "当前关联：${comp.linkedTargetName}"
            } else {
                "当前状态：全局独立"
            }
            val preview = "$linkInfo\n\n${content.take(1500)}" + if (content.length > 1500) "\n..." else ""
            AlertDialog.Builder(this@MainActivity)
                .setTitle(comp.name)
                .setMessage(preview)
                .setPositiveButton("关联宿主") { _, _ -> showLinkComponentDialog(comp) }
                .setNeutralButton("导出") { _, _ ->
                    pendingExportCompFile = libRepo.getComponentFile(comp)
                    exportCompFileLauncher.launch(comp.fileName)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun showLinkComponentDialog(comp: ComponentRecord) {
        val options = arrayOf("设为全局独立", "关联到角色卡", "关联到预设", "关联到世界书", "关联到美化")
        AlertDialog.Builder(this)
            .setTitle("关联「${comp.name}」")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        libRepo.linkComponent(comp.id, "", "", "")
                        toast("已设为全局独立")
                        refreshActiveWorkspace()
                    }
                    1 -> pickLinkTarget(comp, "character", "选择角色卡") {
                        libRepo.searchCharacters("").map { it.id to it.name }
                    }
                    2 -> pickLinkTarget(comp, "preset", "选择预设") {
                        libRepo.searchPresets("").map { it.id to it.name }
                    }
                    3 -> pickLinkTarget(comp, "worldbook", "选择世界书") {
                        libRepo.searchWorldBooks("").map { it.id to it.name }
                    }
                    4 -> pickLinkTarget(comp, "theme", "选择美化") {
                        repo.searchSources("").map { it.id to it.name.substringAfterLast('/') }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickLinkTarget(
        comp: ComponentRecord,
        targetType: String,
        title: String,
        loader: () -> List<Pair<String, String>>
    ) {
        lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) { loader() }
            if (targets.isEmpty()) {
                toast("该模块暂无可关联资源")
                return@launch
            }
            val names = targets.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setItems(names) { _, idx ->
                    val (tid, tname) = targets[idx]
                    libRepo.linkComponent(comp.id, targetType, tid, tname)
                    toast("已关联到「$tname」")
                    refreshActiveWorkspace()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun confirmDeleteComponent(c: ComponentRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除组件")
            .setMessage("确定删除「${c.name}」？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { libRepo.deleteComponent(c.id) }
                    toast("已删除组件")
                    refreshActiveWorkspace()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
