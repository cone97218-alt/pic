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
import com.stvault.app.core.CategoryManager
import com.stvault.app.core.HostConfig
import com.stvault.app.core.ReleaseInfo
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.SmartImporter
import com.stvault.app.core.UpdateManager
import com.stvault.app.core.VaultRepository
import com.stvault.app.core.VersionGrouper
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private lateinit var catManager: CategoryManager
    private lateinit var sourcesAdapter: SourcesAdapter
    private lateinit var urlsAdapter: UrlsAdapter
    private lateinit var charactersAdapter: CharactersAdapter
    private lateinit var worldBooksAdapter: WorldBooksAdapter
    private lateinit var presetsAdapter: PresetsAdapter
    private lateinit var componentsAdapter: ComponentsAdapter

    private var activeWorkspace = "themes" // "themes" | "characters" | "worldbooks" | "presets" | "components"
    private var activeCharFilter = "all"
    private var activeWbFilter = "all"
    private var activeCompFilter = "all"
    private var activeThemeCategory = ""
    private var activeCharCategory = ""
    private var activeThemeTab = "cache"
    private var searchDebounceJob: Job? = null

    private var batchMode = false
    private val batchSelectedIds = LinkedHashSet<String>()

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
    private var pendingBrowserTarget: String = "themes"
    private var pendingBrowserMode: String = FileBrowserActivity.MODE_FILES

    // 13. Built-in Minimalist File Browser Launcher (with 1-tap SAF fallback)
    private val fileBrowserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == FileBrowserActivity.RESULT_USE_SYSTEM_PICKER) {
                launchSystemSafPicker(pendingBrowserTarget, pendingBrowserMode)
                return@registerForActivityResult
            }
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult

            if (pendingBrowserMode == FileBrowserActivity.MODE_FOLDER) {
                val folderPath = result.data?.getStringExtra(FileBrowserActivity.EXTRA_RESULT_FOLDER)
                    ?: return@registerForActivityResult
                val dir = File(folderPath)
                if (!dir.exists() || !dir.isDirectory) return@registerForActivityResult
                if (pendingBrowserTarget == "themes") {
                    savedLocalDirPath = dir.absolutePath
                    savedTreeUri = null
                    prefs.edit().putString("local_dir_path", dir.absolutePath).remove("tree_uri").apply()
                    updateFolderLabel()
                    runLocalDirScan(dir)
                } else {
                    runModuleLocalFolderScan(pendingBrowserTarget, dir)
                }
            } else {
                val paths = result.data?.getStringArrayListExtra(FileBrowserActivity.EXTRA_RESULT_FILES)
                    ?: return@registerForActivityResult
                val files = paths.map { File(it) }.filter { it.exists() && it.isFile }
                if (files.isNotEmpty()) {
                    importLocalFilesWithSmartRouter(files, pendingBrowserTarget)
                }
            }
        }

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
        catManager = CategoryManager.getInstance(this)
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
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyBottomBarVisibility()
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
            onClick = { source -> ThemePreviewActivity.open(this, source.id) },
            onPreviewClick = { source -> showSourceDetailDialog(source) },
            onDeleteClick = { source -> confirmDeleteSource(source) },
            onLongClick = { source -> showThemeActionMenu(source) },
            onToggleSelect = { id -> toggleBatchSelection(id) }
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
            onLongClick = { c -> showCharacterActionMenu(c) },
            onToggleSelect = { id -> toggleBatchSelection(id) }
        )
        binding.wsCharacters.rvCharacters.adapter = charactersAdapter
        buildSimpleChips(
            binding.wsCharacters.chipGroupCharFilter,
            listOf("all" to "最新导入", "multi_version" to "多版本", "has_book" to "带世界书", "has_script" to "带脚本", "has_urls" to "含外链图"),
            activeCharFilter
        ) { key ->
            activeCharFilter = key
            refreshCharactersList()
        }

        // 3. World Books
        binding.wsWorldBooks.rvWorldBooks.layoutManager = LinearLayoutManager(this)
        worldBooksAdapter = WorldBooksAdapter(
            onClick = { w -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_WORLDBOOK, w.id) },
            onDeleteClick = { w -> confirmDeleteWorldBook(w) },
            onToggleSelect = { id -> toggleBatchSelection(id) },
            onLongClick = { w -> showWorldBookActionMenu(w) }
        )
        binding.wsWorldBooks.rvWorldBooks.adapter = worldBooksAdapter
        buildSimpleChips(
            binding.wsWorldBooks.chipGroupWbFilter,
            listOf("all" to "全部", "multi_version" to "多版本", "standalone" to "独立世界书", "from_card" to "来自角色卡", "has_urls" to "含外链图"),
            activeWbFilter
        ) { key ->
            activeWbFilter = key
            refreshWorldBooksList()
        }

        // 4. Presets
        binding.wsPresets.rvPresets.layoutManager = LinearLayoutManager(this)
        presetsAdapter = PresetsAdapter(
            onClick = { p -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_PRESET, p.id) },
            onDeleteClick = { p -> confirmDeletePreset(p) },
            onToggleSelect = { id -> toggleBatchSelection(id) },
            onLongClick = { p -> showPresetActionMenu(p) }
        )
        binding.wsPresets.rvPresets.adapter = presetsAdapter

        // 5. Components (Regex / Tavern Helper / QR)
        binding.wsComponents.rvComponents.layoutManager = LinearLayoutManager(this)
        componentsAdapter = ComponentsAdapter(
            onClick = { c -> showComponentDetailDialog(c) },
            onDeleteClick = { c -> confirmDeleteComponent(c) },
            onToggleSelect = { id -> toggleBatchSelection(id) },
            onLongClick = { c -> showComponentActionMenu(c) }
        )
        binding.wsComponents.rvComponents.adapter = componentsAdapter
        buildSimpleChips(
            binding.wsComponents.chipGroupCompFilter,
            listOf("all" to "全部", "regex" to "正则", "helper" to "酒馆助手", "qr" to "快速回复", "global" to "全局", "linked" to "已关联"),
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

    private var drawerSearchJob: Job? = null
    private lateinit var drawerSearchAdapter: DrawerGlobalSearchAdapter

    private fun setupDrawer() {
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.drawer.drawerItemThemes.setOnClickListener { switchWorkspace("themes") }
        binding.drawer.drawerItemCharacters.setOnClickListener { switchWorkspace("characters") }
        binding.drawer.drawerItemWorldBooks.setOnClickListener { switchWorkspace("worldbooks") }
        binding.drawer.drawerItemPresets.setOnClickListener { switchWorkspace("presets") }
        binding.drawer.drawerItemComponents.setOnClickListener { switchWorkspace("components") }
        binding.drawer.drawerItemBridge.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            TavernBridgeActivity.open(this)
        }
        binding.drawer.drawerItemDedup.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            DuplicateCleanerActivity.open(this)
        }
        var lastInvTotal = -1
        lifecycleScope.launch {
            com.stvault.app.core.TavernBridgeManager.getInstance(this@MainActivity).stateFlow.collectLatest { st ->
                binding.drawer.tvDrawerBridgeStatus.text = when {
                    st.connected -> "已连接"
                    st.running -> "监听中"
                    else -> "未开启"
                }
                val invTotal = st.installedThemesCount + st.installedCharsCount + st.installedWbsCount + st.installedPresetsCount
                if (invTotal != lastInvTotal) {
                    lastInvTotal = invTotal
                    refreshActiveWorkspace()
                }
            }
        }
        binding.drawer.drawerItemSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            SettingsActivity.open(this)
        }

        // Global Cross-Module Search inside Drawer
        drawerSearchAdapter = DrawerGlobalSearchAdapter { hit ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (hit.module) {
                "characters" -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_CHARACTER, hit.id)
                "worldbooks" -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_WORLDBOOK, hit.id)
                "presets" -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_PRESET, hit.id)
                "themes" -> repo.getSourceById(hit.id)?.let { showSourceDetailDialog(it) }
                "components" -> libRepo.getComponentById(hit.id)?.let { showComponentDetailDialog(it) }
            }
        }
        binding.drawer.rvDrawerGlobalSearch.layoutManager = LinearLayoutManager(this)
        binding.drawer.rvDrawerGlobalSearch.adapter = drawerSearchAdapter

        binding.drawer.btnDrawerSearchClear.setOnClickListener {
            binding.drawer.etDrawerGlobalSearch.setText("")
        }
        binding.drawer.etDrawerGlobalSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                binding.drawer.btnDrawerSearchClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                if (q.isEmpty()) {
                    drawerSearchJob?.cancel()
                    binding.drawer.drawerNavGroup.visibility = View.VISIBLE
                    binding.drawer.rvDrawerGlobalSearch.visibility = View.GONE
                    binding.drawer.tvDrawerSearchEmpty.visibility = View.GONE
                    return
                }
                binding.drawer.drawerNavGroup.visibility = View.GONE
                drawerSearchJob?.cancel()
                drawerSearchJob = lifecycleScope.launch {
                    delay(180)
                    val hits = withContext(Dispatchers.IO) {
                        libRepo.searchGlobal(q, repo)
                    }
                    drawerSearchAdapter.submit(hits)
                    if (hits.isEmpty()) {
                        binding.drawer.tvDrawerSearchEmpty.visibility = View.VISIBLE
                        binding.drawer.rvDrawerGlobalSearch.visibility = View.GONE
                    } else {
                        binding.drawer.tvDrawerSearchEmpty.visibility = View.GONE
                        binding.drawer.rvDrawerGlobalSearch.visibility = View.VISIBLE
                    }
                }
            }
        })
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

        exitBatchMode()
        applyBottomBarVisibility()
        if (workspace == "themes") {
            selectThemeDockTab(activeThemeTab)
        }
        refreshActiveWorkspace()
    }

    private fun applyBottomBarVisibility() {
        val showThemesNav = (activeWorkspace == "themes") && !batchMode
        val style = prefs.getString("bottom_bar_style", "ios_flat") ?: "ios_flat"
        val useFloating = (style == "floating_pill")

        binding.iosFlatBottomBar.visibility = if (showThemesNav && !useFloating) View.VISIBLE else View.GONE
        binding.floatingBottomDock.visibility = if (showThemesNav && useFloating) View.VISIBLE else View.GONE
    }

    private fun selectThemeDockTab(tab: String) {
        activeThemeTab = tab
        if (tab != "vault" && batchMode) exitBatchMode()
        binding.tabCache.visibility = if (tab == "cache") View.VISIBLE else View.GONE
        binding.tabVault.visibility = if (tab == "vault") View.VISIBLE else View.GONE
        binding.tabRepair.visibility = if (tab == "repair") View.VISIBLE else View.GONE
        if (tab == "vault") refreshVaultList()

        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        // Sync Floating Pill Dock
        val floatTabs = listOf(
            Triple("cache", binding.dockTabCache, binding.ivDockCache to binding.tvDockCache),
            Triple("vault", binding.dockTabVault, binding.ivDockVault to binding.tvDockVault),
            Triple("repair", binding.dockTabRepair, binding.ivDockRepair to binding.tvDockRepair)
        )
        for ((key, pill, pair) in floatTabs) {
            val active = (key == tab)
            pill.setBackgroundResource(if (active) R.drawable.bg_dock_item_active else 0)
            pair.first.imageTintList = ColorStateList.valueOf(if (active) onAccent else muted)
            pair.second.setTextColor(if (active) onAccent else muted)
            pair.second.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        // Sync iOS Flat Bottom Bar
        val flatTabs = listOf(
            Triple("cache", binding.ivFlatCache, binding.tvFlatCache),
            Triple("vault", binding.ivFlatVault, binding.tvFlatVault),
            Triple("repair", binding.ivFlatRepair, binding.tvFlatRepair)
        )
        for ((key, iv, tv) in flatTabs) {
            val active = (key == tab)
            iv.imageTintList = ColorStateList.valueOf(if (active) primary else muted)
            tv.setTextColor(if (active) primary else muted)
            tv.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
                "multi_version" to "多版本",
                "installed" to "已装",
                "not_installed" to "未装",
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
            binding.btnThemeCatFilter.visibility = View.VISIBLE
            binding.btnThemeBatch.visibility = View.VISIBLE
        } else {
            binding.btnModeUrls.backgroundTintList = ColorStateList.valueOf(accent)
            binding.btnModeUrls.setTextColor(onAccent)
            binding.btnModeSources.backgroundTintList = ColorStateList.valueOf(transparent)
            binding.btnModeSources.setTextColor(muted)
            binding.btnImportInVault.visibility = View.GONE
            binding.btnThemeCatFilter.visibility = View.GONE
            binding.btnThemeBatch.visibility = View.GONE
            if (batchMode) exitBatchMode()
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

        // Themes Bottom Navigation (both iOS Flat & Floating Pill)
        binding.dockTabCache.setOnClickListener { selectThemeDockTab("cache") }
        binding.dockTabVault.setOnClickListener { selectThemeDockTab("vault") }
        binding.dockTabRepair.setOnClickListener { selectThemeDockTab("repair") }
        binding.flatTabCache.setOnClickListener { selectThemeDockTab("cache") }
        binding.flatTabVault.setOnClickListener { selectThemeDockTab("vault") }
        binding.flatTabRepair.setOnClickListener { selectThemeDockTab("repair") }

        // Universal Batch Multi-Select Triggers & Controls
        binding.btnThemeBatch.setOnClickListener { enterBatchMode() }
        binding.wsCharacters.btnCharBatch.setOnClickListener { enterBatchMode() }
        binding.wsWorldBooks.btnWbBatch.setOnClickListener { enterBatchMode() }
        binding.wsPresets.btnPresetBatch.setOnClickListener { enterBatchMode() }
        binding.wsComponents.btnCompBatch.setOnClickListener { enterBatchMode() }

        binding.btnBatchSelectAll.setOnClickListener { batchSelectAll() }
        binding.btnBatchRange.setOnClickListener { batchSelectRange() }
        binding.btnBatchInvert.setOnClickListener { batchInvertSelection() }
        binding.btnBatchDone.setOnClickListener { exitBatchMode() }
        binding.btnBatchCategorize.setOnClickListener { runBatchCategorize() }
        binding.btnBatchMergeVersion.setOnClickListener { runBatchMergeVersions() }
        binding.btnBatchDelete.setOnClickListener { runBatchDelete() }

        // Workspace 2: Characters Listeners
        binding.wsCharacters.btnCharCatFilter.setOnClickListener {
            showCategoryFilterPicker(CategoryManager.SCOPE_CHARACTERS, activeCharCategory) { picked ->
                activeCharCategory = picked
                binding.wsCharacters.btnCharCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                refreshCharactersList()
            }
        }
        binding.btnThemeCatFilter.setOnClickListener {
            showCategoryFilterPicker(CategoryManager.SCOPE_THEMES, activeThemeCategory) { picked ->
                activeThemeCategory = picked
                binding.btnThemeCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                refreshVaultList()
            }
        }
        bindDebouncedSearch(binding.etSearch) { refreshVaultList() }
        bindDebouncedSearch(binding.wsCharacters.etCharSearch) { refreshCharactersList() }
        bindDebouncedSearch(binding.wsWorldBooks.etWbSearch) { refreshWorldBooksList() }
        bindDebouncedSearch(binding.wsPresets.etPresetSearch) { refreshPresetsList() }
        bindDebouncedSearch(binding.wsComponents.etCompSearch) { refreshComponentsList() }

        binding.wsCharacters.btnCharSearch.setOnClickListener { refreshCharactersList() }
        binding.wsCharacters.etCharSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshCharactersList(); true } else false
        }
        binding.wsCharacters.btnCharQuickImport.setOnClickListener {
            openFileBrowser("characters", FileBrowserActivity.MODE_FILES, "导入角色卡")
        }
        binding.wsCharacters.btnCharQuickUrl.setOnClickListener { showImportCharacterUrlDialog() }
        binding.wsCharacters.btnCharScanFolder.setOnClickListener {
            openFileBrowser("characters", FileBrowserActivity.MODE_FOLDER, "扫描角色卡目录")
        }
        binding.wsCharacters.btnCharStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
            toast("开始后台缓存")
        }

        // Workspace 3: World Books Listeners
        binding.wsWorldBooks.btnWbSearch.setOnClickListener { refreshWorldBooksList() }
        binding.wsWorldBooks.etWbSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshWorldBooksList(); true } else false
        }
        binding.wsWorldBooks.btnWbQuickImport.setOnClickListener {
            openFileBrowser("worldbooks", FileBrowserActivity.MODE_FILES, "导入世界书")
        }
        binding.wsWorldBooks.btnWbScanFolder.setOnClickListener {
            openFileBrowser("worldbooks", FileBrowserActivity.MODE_FOLDER, "扫描世界书目录")
        }
        binding.wsWorldBooks.btnWbStartCache.setOnClickListener {
            VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
            toast("开始后台缓存")
        }

        // Workspace 4: Presets Listeners
        binding.wsPresets.btnPresetSearch.setOnClickListener { refreshPresetsList() }
        binding.wsPresets.etPresetSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshPresetsList(); true } else false
        }
        binding.wsPresets.btnPresetImportFile.setOnClickListener {
            openFileBrowser("presets", FileBrowserActivity.MODE_FILES, "导入预设")
        }
        binding.wsPresets.btnPresetScanFolder.setOnClickListener {
            openFileBrowser("presets", FileBrowserActivity.MODE_FOLDER, "扫描预设目录")
        }

        // Workspace 5: Components Listeners
        binding.wsComponents.btnCompSearch.setOnClickListener { refreshComponentsList() }
        binding.wsComponents.etCompSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshComponentsList(); true } else false
        }
        binding.wsComponents.btnCompImportFile.setOnClickListener {
            openFileBrowser("components", FileBrowserActivity.MODE_FILES, "导入组件")
        }
        binding.wsComponents.btnCompScanFolder.setOnClickListener {
            openFileBrowser("components", FileBrowserActivity.MODE_FOLDER, "扫描组件目录")
        }

        // Tab 1: Folder Pick, Single File Import & Scan
        binding.btnPickFolder.setOnClickListener {
            openFileBrowser("themes", FileBrowserActivity.MODE_FOLDER, "选择美化目录")
        }
        binding.btnImportSingleFile.setOnClickListener {
            openFileBrowser("themes", FileBrowserActivity.MODE_FILES, "导入美化文件")
        }
        binding.btnImportInVault.setOnClickListener {
            openFileBrowser("themes", FileBrowserActivity.MODE_FILES, "导入美化文件")
        }
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
                openFileBrowser("themes", FileBrowserActivity.MODE_FOLDER, "选择美化目录")
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
            VaultSheets.confirm(
                ctx = this,
                title = "忽略失败链接",
                message = "将当前 $errCount 个失败链接标记为不再重试？之后仍可在「图库 → 已忽略」中查看或相册替换。",
                confirmText = "忽略"
            ) {
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { repo.ignoreAllFailedUrls() }
                    toast("已忽略 $n 个失败链接")
                }
            }
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
                    VaultSheets.info(
                        ctx = this@MainActivity,
                        title = "已是最新版本",
                        message = "当前版本 v${BuildConfig.VERSION_NAME} 已与 GitHub 最新 Release (${info.tagName}) 同步。",
                        primaryText = "知道了",
                        secondaryText = "重新下载",
                        onSecondary = { downloadAndInstallUpdate(info) }
                    )
                }
            } catch (e: Exception) {
                toast("检查更新失败：${e.message}")
            }
        }
    }

    private fun showUpdateDialog(info: ReleaseInfo) {
        val sizeStr = if (info.apkSize > 0) " (${formatBytes(info.apkSize)})" else ""
        VaultSheets.info(
            ctx = this,
            title = "发现新版本 ${info.tagName}",
            message = "当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：${info.tagName}$sizeStr\n\n${info.changelog}",
            primaryText = "立即更新",
            onPrimary = { downloadAndInstallUpdate(info) },
            secondaryText = "稍后"
        )
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
                VaultSheets.info(
                    ctx = this@MainActivity,
                    title = "存档打包完成",
                    message = "已将 ${res.imageCount} 张图片（涵盖 ${res.sourceCount} 个美化）以及完整对照索引 manifest.json 整理进 ZIP 压缩包。\n\n压缩包大小：${formatBytes(res.totalBytes)}",
                    primaryText = "分享到其他软件",
                    onPrimary = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "发送图床压缩包到..."))
                    },
                    secondaryText = "完成"
                )
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
        VaultSheets.info(
            ctx = this,
            title = "访问 TT 酒馆目录",
            message = "由于安卓 13/14+ 系统限制，普通应用无法直接打开 Android/data，但你可以直接借助手机里的 MT 管理器授权：\n\n" +
                "1. 点下方「打开文件选择器」\n" +
                "2. 点左上角菜单 ≡ → 选择「MT管理器」\n" +
                "3. 进入 Android/data/com.tauritavern.client/data/default-user/themes\n" +
                "4. 点底部「使用此文件夹」即可直接扫描",
            primaryText = "打开文件选择器",
            onPrimary = {
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
            },
            secondaryText = "取消"
        )
    }

    private fun runLocalDirScan(dir: File) {
        binding.btnPickFolder.isEnabled = false
        binding.btnOpenTTFolder.isEnabled = false
        binding.btnScanFolder.isEnabled = false
        binding.tvScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "正在扫描本地目录..."

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
            binding.tvSelectedFolder.text = "已绑定目录：$localPath"
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
                val groupVars = prefs.getBoolean("group_theme_variants", true)
                val list = withContext(Dispatchers.IO) {
                    repo.searchSourceRows(q, activeSourceFilter, activeThemeCategory, groupVars)
                }
                sourcesAdapter.submitList(list)
                binding.tvVaultCount.text = "共 ${list.size} 个美化"
            } else {
                val list = withContext(Dispatchers.IO) { repo.searchUrls(q, activeUrlFilter) }
                urlsAdapter.submitList(list)
                binding.tvVaultCount.text = "共 ${list.size} 张图床"
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
        val cleanName = source.name
        VaultSheets.confirm(
            ctx = this,
            title = "删除美化",
            message = "确定删除「$cleanName」？\n将同时清理仅由该美化引用的独享图床缓存，不影响其他美化共用的图片。",
            confirmText = "删除",
            destructive = true
        ) {
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
    }

    private fun showSourceDetailDialog(initialSource: SourceRecord) {
        var activeSource = initialSource
        val dBinding = DialogSourceDetailBinding.inflate(LayoutInflater.from(this))
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

        fun bindActiveSource(target: SourceRecord) {
            activeSource = target
            dBinding.tvDialogSourceTitle.text = target.name
            detailAdapter.submitList(repo.getUrlRecordsForSource(target))
        }

        bindActiveSource(initialSource)

        // Populate Sibling Variant Pills when >1 variant exists (updates in-place with zero flicker!)
        val variants = repo.getVariantsForTheme(initialSource.seriesKey)
        if (variants.size > 1) {
            dBinding.scrollDialogVariants.visibility = View.VISIBLE
            val opts = variants.map { v ->
                val lbl = v.variantTag.ifBlank { v.name }
                v.id to lbl
            }
            fun rebuildVariantChips(selectedId: String) {
                buildSimpleChips(dBinding.chipGroupDialogVariants, opts, selectedId) { pickedId ->
                    if (pickedId != activeSource.id) {
                        com.stvault.app.core.ThemeVariantGrouper.setPreferredVariant(this, initialSource.seriesKey, pickedId)
                        val target = repo.getSourceById(pickedId)
                        if (target != null) {
                            bindActiveSource(target)
                            rebuildVariantChips(pickedId)
                            refreshVaultList()
                        }
                    }
                }
            }
            rebuildVariantChips(initialSource.id)
        } else {
            dBinding.scrollDialogVariants.visibility = View.GONE
        }

        fun runModalRepair(mode: String) {
            val labelPrefix = if (mode == "catbox") "上传换链" else "压缩内嵌"
            val curId = activeSource.id
            activeSourceDialog?.dismiss()
            showIsland("正在$labelPrefix...")
            lifecycleScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        repo.repairSourceById(curId, mode) { done, total ->
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
            confirmDeleteSource(activeSource)
        }
        dBinding.btnDialogPreview.setOnClickListener {
            activeSourceDialog?.dismiss()
            ThemePreviewActivity.open(this, activeSource.id)
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
        val groupVersions = prefs.getBoolean("group_versions", true)
        lifecycleScope.launch {
            val (grouped, totalRaw, pendingCount) = withContext(Dispatchers.IO) {
                val chars = libRepo.searchCharacters(q, if (activeCharFilter == "multi_version") "all" else activeCharFilter, activeCharCategory)
                val rawG = VersionGrouper.groupBySeries(
                    chars, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, ResourceDetailActivity.TYPE_CHARACTER, key) }
                )
                val g = if (activeCharFilter == "multi_version") rawG.filter { it.versionCount > 1 } else rawG
                val urls = libRepo.getAllCharacterUrls()
                val counts = repo.getCountsForUrls(urls)
                Triple(g, chars.size, counts.third)
            }
            charactersAdapter.submitList(grouped)
            binding.wsCharacters.tvCharCount.text =
                if (groupVersions && grouped.size < totalRaw) "共 ${grouped.size} 作品 ($totalRaw 版)" else "共 ${grouped.size} 张"
            if (pendingCount > 0) {
                binding.wsCharacters.btnCharStartCache.visibility = View.VISIBLE
                binding.wsCharacters.btnCharStartCache.text = "缓存图床 ($pendingCount)"
            } else {
                binding.wsCharacters.btnCharStartCache.visibility = View.GONE
            }
        }
    }

    private fun confirmDeleteCharacter(c: CharacterRecord) {
        VaultSheets.confirm(
            ctx = this,
            title = "删除角色卡",
            message = "确定删除「${c.name}」？将同时清理该卡独享的外链图床缓存。",
            confirmText = "删除",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { libRepo.deleteCharacter(c.id) }
                toast("已删除角色卡")
                refreshCharactersList()
            }
        }
    }

    private fun refreshWorldBooksList() {
        val q = binding.wsWorldBooks.etWbSearch.text?.toString().orEmpty()
        val groupVersions = prefs.getBoolean("group_versions", true)
        lifecycleScope.launch {
            val (grouped, totalRaw, pendingCount) = withContext(Dispatchers.IO) {
                val books = libRepo.searchWorldBooks(q, if (activeWbFilter == "multi_version") "all" else activeWbFilter)
                val rawG = VersionGrouper.groupBySeries(
                    books, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, ResourceDetailActivity.TYPE_WORLDBOOK, key) }
                )
                val g = if (activeWbFilter == "multi_version") rawG.filter { it.versionCount > 1 } else rawG
                val urls = libRepo.getAllWorldBookUrls()
                val counts = repo.getCountsForUrls(urls)
                Triple(g, books.size, counts.third)
            }
            worldBooksAdapter.submitList(grouped)
            binding.wsWorldBooks.tvWbCount.text =
                if (groupVersions && grouped.size < totalRaw) "共 ${grouped.size} 部 ($totalRaw 版)" else "共 ${grouped.size} 本"
            if (pendingCount > 0) {
                binding.wsWorldBooks.btnWbStartCache.visibility = View.VISIBLE
                binding.wsWorldBooks.btnWbStartCache.text = "缓存图床 ($pendingCount)"
            } else {
                binding.wsWorldBooks.btnWbStartCache.visibility = View.GONE
            }
        }
    }

    private fun confirmDeleteWorldBook(w: WorldBookRecord) {
        VaultSheets.confirm(
            ctx = this,
            title = "删除世界书",
            message = "确定删除「${w.name}」？",
            confirmText = "删除",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { libRepo.deleteWorldBook(w.id) }
                toast("已删除世界书")
                refreshWorldBooksList()
            }
        }
    }

    private fun refreshPresetsList() {
        val q = binding.wsPresets.etPresetSearch.text?.toString().orEmpty()
        val groupVersions = prefs.getBoolean("group_versions", true)
        lifecycleScope.launch {
            val (groupedPresets, totalRaw) = withContext(Dispatchers.IO) {
                val p = libRepo.searchPresets(q)
                val gp = VersionGrouper.groupBySeries(
                    p, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, ResourceDetailActivity.TYPE_PRESET, key) }
                )
                gp to p.size
            }
            presetsAdapter.submitList(groupedPresets)
            binding.wsPresets.tvPresetCount.text =
                if (groupVersions && groupedPresets.size < totalRaw) "共 ${groupedPresets.size} 套 ($totalRaw 版)" else "共 ${groupedPresets.size} 个"
        }
    }

    private fun confirmDeletePreset(p: PresetRecord) {
        VaultSheets.confirm(
            ctx = this,
            title = "删除预设",
            message = "确定删除「${p.name}」？",
            confirmText = "删除",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { libRepo.deletePreset(p.id) }
                toast("已删除预设")
                refreshPresetsList()
            }
        }
    }

    private fun refreshComponentsList() {
        val q = binding.wsComponents.etCompSearch.text?.toString().orEmpty()
        val groupVersions = prefs.getBoolean("group_versions", true)
        lifecycleScope.launch {
            val (grouped, totalRaw) = withContext(Dispatchers.IO) {
                val list = libRepo.searchComponents(q, activeCompFilter)
                val g = VersionGrouper.groupBySeries(
                    list, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, "component", key) }
                )
                g to list.size
            }
            componentsAdapter.submitList(grouped)
            binding.wsComponents.tvCompCount.text =
                if (groupVersions && grouped.size < totalRaw) "共 ${grouped.size} 项 ($totalRaw 版)" else "共 ${grouped.size} 个"
        }
    }

    private fun bindDebouncedSearch(et: android.widget.EditText, onTrigger: () -> Unit) {
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(180L)
                    onTrigger()
                }
            }
        })
    }

    private fun showCategoryFilterPicker(scope: String, current: String, onPicked: (String) -> Unit) {
        val cats = catManager.getCategories(scope)
        val items = mutableListOf(PickerItem("", "全部分类"))
        for (c in cats) {
            items.add(PickerItem(c, catManager.formatIndentedLabel(c)))
        }
        VaultSheets.searchablePicker(
            ctx = this,
            title = "按分类筛选",
            items = items,
            selectedId = current,
            searchHint = "搜索分类...",
            headerAction = "+ 新建" to {
                showQuickAddCategoryDialog(scope) {
                    showCategoryFilterPicker(scope, current, onPicked)
                }
            }
        ) { picked ->
            onPicked(picked.id)
        }
    }

    private fun showQuickAddCategoryDialog(scope: String, onAdded: () -> Unit) {
        VaultSheets.input(
            ctx = this,
            title = "新建分类（1~3级）",
            message = "用斜杠 / 分隔层级，例如 古风/仙侠/师尊，父级会自动创建。",
            hint = "例如：古风/仙侠",
            confirmText = "添加"
        ) { raw ->
            val added = catManager.addCategory(scope, raw)
            if (added != null) {
                toast("已添加：$added")
                onAdded()
            } else {
                toast("请输入有效名称")
            }
        }
    }

    private fun showThemeCategoryAssignDialog(source: SourceRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_THEMES)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(CategoryManager.SCOPE_THEMES) {
                showThemeCategoryAssignDialog(source)
            }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "美化分类（可多选）",
            items = items,
            preselectedIds = source.categories.toSet(),
            headerAction = "+ 新建" to {
                showQuickAddCategoryDialog(CategoryManager.SCOPE_THEMES) {
                    showThemeCategoryAssignDialog(source)
                }
            }
        ) { picked ->
            repo.setSourceCategories(source.id, picked)
            toast("分类已更新")
            refreshVaultList()
        }
    }

    private fun showThemeActionMenu(source: SourceRecord) {
        val variants = repo.getVariantsForTheme(source.seriesKey)
        val actions = mutableListOf<SheetAction>()
        if (variants.size > 1) {
            actions.add(SheetAction("切换变体 (${variants.size})", "在同系列配色 / 形态之间切换") {
                val items = variants.map { v ->
                    SheetAction(
                        label = v.variantTag.ifBlank { v.name },
                        subtitle = v.name,
                        checked = (v.id == source.id)
                    ) {
                        com.stvault.app.core.ThemeVariantGrouper.setPreferredVariant(this, source.seriesKey, v.id)
                        refreshVaultList()
                        showSourceDetailDialog(v)
                    }
                }
                VaultSheets.actionList(this, title = "切换变体 (${variants.size})", actions = items)
            })
        }
        actions.add(SheetAction("装入酒馆", "推送到 TT 酒馆，支持原版或图床修复版") {
            pushThemeToTavern(source)
        })
        actions.add(SheetAction("重命名", "修改显示名称") {
            showRenameDialog("重命名美化", source.name) { newName ->
                if (repo.renameSource(source.id, newName)) {
                    toast("已重命名")
                    refreshVaultList()
                }
            }
        })
        actions.add(SheetAction("修改变体标签", "例如 宽黑、窄白、夜间蓝") {
            VaultSheets.input(
                ctx = this,
                title = "自定义变体标签",
                hint = "例如：宽黑 / 日间粉",
                initialValue = source.variantTag,
                confirmText = "保存"
            ) { tag ->
                repo.updateThemeVariantTag(source.id, tag)
                toast("变体标签已更新")
                refreshVaultList()
            }
        })
        actions.add(SheetAction("归入同系列", "与另一款美化收纳为同系列变体") {
            val others = repo.getAllSources().filter { it.id != source.id }.map { PickerItem(it.id, it.name, it.variantTag) }
            if (others.isEmpty()) {
                toast("暂无其他美化可归入")
            } else {
                VaultSheets.searchablePicker(this, "选择同系列美化", others) { picked ->
                    VaultSheets.input(this, "设置系列名称", hint = "输入系列名", initialValue = source.name, confirmText = "归入") { sName ->
                        if (sName.trim().isNotEmpty()) {
                            repo.mergeThemeSeries(listOf(source.id, picked.id), sName.trim())
                            toast("已归入同系列")
                            refreshVaultList()
                        }
                    }
                }
            }
        })
        actions.add(SheetAction("设置分类标签", "多选 1~3 级分类") { showThemeCategoryAssignDialog(source) })
        actions.add(SheetAction("删除美化", destructive = true) { confirmDeleteSource(source) })
        VaultSheets.actionList(ctx = this, title = source.name, actions = actions)
    }

    private fun pushThemeToTavern(source: SourceRecord) {
        fun enqueueRaw(repaired: Boolean) {
            lifecycleScope.launch {
                try {
                    val (fileName, bytes) = withContext(Dispatchers.IO) {
                        val (rawName, rawText) = repo.readSourceRawText(source.id)
                        val outText = if (repaired) {
                            repo.repairRawText(rawText, rawName, "base64_webp").repairedText
                        } else rawText
                        val safeName = if (rawName.endsWith(".json", ignoreCase = true)) rawName else "${source.name}.json"
                        safeName to outText.toByteArray(Charsets.UTF_8)
                    }
                    enqueueToTavernBridge("theme", source.name, fileName, bytes)
                } catch (e: Exception) {
                    toast("读取美化失败：${e.message}")
                }
            }
        }
        VaultSheets.actionList(
            ctx = this,
            title = "装入酒馆 · ${source.name}",
            actions = listOf(
                SheetAction("推送原版", "保持原始代码直接装入 TT 酒馆") { enqueueRaw(false) },
                SheetAction("推送图床修复版", "将已缓存图床离线内嵌后再装入") { enqueueRaw(true) }
            )
        )
    }

    private fun enqueueToTavernBridge(kind: String, name: String, fileName: String, bytes: ByteArray) {
        val bridge = com.stvault.app.core.TavernBridgeManager.getInstance(this)
        val alreadyInstalled = when (kind) {
            "theme" -> bridge.isThemeInstalled(name, fileName)
            "character" -> bridge.isCharacterInstalled(name, fileName)
            "worldbook" -> bridge.isWorldBookInstalled(name, fileName)
            "preset" -> bridge.isPresetInstalled(name, fileName)
            else -> false
        }
        fun doEnqueue() {
            if (!bridge.isRunning()) {
                try { com.stvault.app.service.TavernBridgeService.start(this) } catch (_: Exception) {}
                lifecycleScope.launch(Dispatchers.IO) { bridge.startServer() }
            }
            bridge.enqueueOutbox(kind, name, fileName, bytes)
            toast("已加入待装入队列，切回 TT 酒馆即可自动装入")
        }
        if (alreadyInstalled) {
            VaultSheets.confirm(
                ctx = this,
                title = "酒馆中已有同名项",
                message = "「$name」已在酒馆中安装，继续装入将覆盖更新酒馆里的原文件，确定继续吗？",
                confirmText = "覆盖装入"
            ) { doEnqueue() }
        } else {
            doEnqueue()
        }
    }

    private fun showCharacterActionMenu(c: CharacterRecord) {
        VaultSheets.actionList(
            ctx = this,
            title = c.name,
            actions = listOf(
                SheetAction("装入酒馆", "将角色卡推送到 TT 酒馆") {
                    lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            val f = libRepo.getCharacterFile(c)
                            if (f.exists()) f.readBytes() else null
                        }
                        if (bytes != null) enqueueToTavernBridge("character", c.name, c.fileName, bytes)
                        else toast("找不到角色卡文件")
                    }
                },
                SheetAction("重命名", "修改角色卡显示名称") {
                    showRenameDialog("重命名角色卡", c.name) { newName ->
                        if (libRepo.renameCharacter(c.id, newName)) {
                            toast("已重命名")
                            refreshCharactersList()
                        }
                    }
                },
                SheetAction("设置分类标签", "多选 1~3 级分类") { showCharacterCategoryAssignDialog(c) },
                SheetAction("合并为同作品版本", "与另一张角色卡归为不同版本") {
                    showQuickMergeVersionPicker("characters", c.id, c.name)
                },
                SheetAction("删除角色卡", destructive = true) { confirmDeleteCharacter(c) }
            )
        )
    }

    private fun showWorldBookActionMenu(w: WorldBookRecord) {
        VaultSheets.actionList(
            ctx = this,
            title = w.name,
            actions = listOf(
                SheetAction("装入酒馆", "将世界书推送到 TT 酒馆") {
                    lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            val f = libRepo.getWorldBookFile(w)
                            if (f.exists()) f.readBytes() else null
                        }
                        if (bytes != null) enqueueToTavernBridge("worldbook", w.name, w.fileName, bytes)
                        else toast("找不到世界书文件")
                    }
                },
                SheetAction("重命名", "修改世界书显示名称") {
                    showRenameDialog("重命名世界书", w.name) { newName ->
                        if (libRepo.renameWorldBook(w.id, newName)) {
                            toast("已重命名")
                            refreshWorldBooksList()
                        }
                    }
                },
                SheetAction("合并为同作品版本", "与另一本世界书归为不同版本") {
                    showQuickMergeVersionPicker("worldbooks", w.id, w.name)
                },
                SheetAction("删除世界书", destructive = true) { confirmDeleteWorldBook(w) }
            )
        )
    }

    private fun showPresetActionMenu(p: PresetRecord) {
        VaultSheets.actionList(
            ctx = this,
            title = p.name,
            actions = listOf(
                SheetAction("装入酒馆", "将预设推送到 TT 酒馆") {
                    lifecycleScope.launch {
                        val bytes = withContext(Dispatchers.IO) {
                            val f = libRepo.getPresetFile(p)
                            if (f.exists()) f.readBytes() else null
                        }
                        if (bytes != null) enqueueToTavernBridge("preset", p.name, p.fileName, bytes)
                        else toast("找不到预设文件")
                    }
                },
                SheetAction("重命名", "修改预设显示名称") {
                    showRenameDialog("重命名预设", p.name) { newName ->
                        if (libRepo.renamePreset(p.id, newName)) {
                            toast("已重命名")
                            refreshPresetsList()
                        }
                    }
                },
                SheetAction("合并为同作品版本", "与另一个预设归为同一预设的不同版本") {
                    showQuickMergeVersionPicker("presets", p.id, p.name)
                },
                SheetAction("删除预设", destructive = true) { confirmDeletePreset(p) }
            )
        )
    }

    private fun showComponentActionMenu(comp: ComponentRecord) {
        val canPush = comp.type == "regex" || comp.type == "qr"
        val actions = mutableListOf<SheetAction>()
        if (canPush) {
            actions.add(SheetAction("装入酒馆", "将${if (comp.type == "qr") "快速回复" else "正则"}推送到 TT 酒馆") {
                lifecycleScope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        val f = libRepo.getComponentFile(comp)
                        if (f.exists()) f.readBytes() else null
                    }
                    if (bytes != null) enqueueToTavernBridge(comp.type, comp.name, comp.fileName, bytes)
                    else toast("找不到组件文件")
                }
            })
        }
        actions.add(SheetAction("重命名", "修改组件显示名称") {
            showRenameDialog("重命名组件", comp.name) { newName ->
                if (libRepo.renameComponent(comp.id, newName)) {
                    toast("已重命名")
                    refreshComponentsList()
                }
            }
        })
        actions.add(SheetAction("关联宿主", "关联到角色卡 / 预设 / 世界书 / 美化") { showLinkComponentDialog(comp) })
        actions.add(SheetAction("删除组件", destructive = true) { confirmDeleteComponent(comp) })

        VaultSheets.actionList(ctx = this, title = comp.name, actions = actions)
    }

    private fun showRenameDialog(title: String, currentName: String, onConfirm: (String) -> Unit) {
        VaultSheets.input(
            ctx = this,
            title = title,
            hint = "输入新名称",
            initialValue = currentName,
            confirmText = "保存"
        ) { newName ->
            val clean = newName.trim()
            if (clean.isEmpty()) {
                toast("名称不能为空")
            } else {
                onConfirm(clean)
            }
        }
    }

    private fun showQuickMergeVersionPicker(module: String, firstId: String, firstName: String) {
        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                when (module) {
                    "characters" -> libRepo.getAllCharacters().filter { it.id != firstId }.map { PickerItem(it.id, it.name, it.fileName) }
                    "worldbooks" -> libRepo.getAllWorldBooks().filter { it.id != firstId }.map { PickerItem(it.id, it.name, "${it.entryCount} 条目") }
                    "presets" -> libRepo.getAllPresets().filter { it.id != firstId }.map { PickerItem(it.id, it.name, "启用 ${it.enabledPromptCount}/${it.promptCount}") }
                    else -> emptyList()
                }
            }
            if (candidates.isEmpty()) {
                toast("当前没有其他可合并的项目")
                return@launch
            }
            VaultSheets.searchablePicker(
                ctx = this@MainActivity,
                title = "选择要与「$firstName」合并的另一版本",
                items = candidates
            ) { picked ->
                VaultSheets.input(
                    ctx = this@MainActivity,
                    title = "设置版本组名称",
                    message = "合并后列表仅显示最新版，进入详情页可随时切换版本并点「对比」查看差异。",
                    hint = "输入作品分组名称",
                    initialValue = firstName,
                    confirmText = "合并"
                ) { groupName ->
                    val clean = groupName.trim()
                    if (clean.isEmpty()) return@input
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val ids = listOf(firstId, picked.id)
                            when (module) {
                                "characters" -> libRepo.mergeCharacterVersions(ids, clean)
                                "worldbooks" -> libRepo.mergeWorldBookVersions(ids, clean)
                                "presets" -> libRepo.mergePresetVersions(ids, clean)
                                else -> Unit
                            }
                        }
                        toast("已合并为「$clean」的不同版本")
                        refreshActiveWorkspace()
                    }
                }
            }
        }
    }

    private fun showCharacterCategoryAssignDialog(c: CharacterRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_CHARACTERS)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(CategoryManager.SCOPE_CHARACTERS) {
                showCharacterCategoryAssignDialog(c)
            }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "角色卡分类（可多选）",
            items = items,
            preselectedIds = c.categories.toSet(),
            headerAction = "+ 新建" to {
                showQuickAddCategoryDialog(CategoryManager.SCOPE_CHARACTERS) {
                    showCharacterCategoryAssignDialog(c)
                }
            }
        ) { picked ->
            libRepo.setCharacterCategories(c.id, picked)
            toast("分类已更新")
            refreshCharactersList()
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
            val canPreview = comp.type == "regex" || comp.type == "helper"
            val preview = "$linkInfo\n\n${content.take(2000)}" + if (content.length > 2000) "\n..." else ""
            VaultSheets.info(
                ctx = this@MainActivity,
                title = comp.name,
                message = preview,
                primaryText = if (canPreview) "沙盒预览" else "关联宿主",
                onPrimary = {
                    if (canPreview) launchComponentPreview(comp, content)
                    else showLinkComponentDialog(comp)
                },
                secondaryText = if (canPreview) "关联宿主" else "关闭",
                onSecondary = {
                    if (canPreview) showLinkComponentDialog(comp)
                },
                tertiaryText = "导出",
                onTertiary = {
                    pendingExportCompFile = libRepo.getComponentFile(comp)
                    exportCompFileLauncher.launch(comp.fileName)
                }
            )
        }
    }

    private fun launchComponentPreview(comp: ComponentRecord, rawContent: String) {
        lifecycleScope.launch {
            val linkedGreeting = withContext(Dispatchers.IO) {
                if (comp.linkedTargetType == "character" && comp.linkedTargetId.isNotEmpty()) {
                    libRepo.getCharacterById(comp.linkedTargetId)?.let { libRepo.parseCharacterDetail(it)?.firstMes }.orEmpty()
                } else ""
            }
            val trimmed = rawContent.trim()
            if (comp.type == "regex") {
                try {
                    val obj = if (trimmed.startsWith("[")) {
                        org.json.JSONArray(trimmed).optJSONObject(0)
                    } else {
                        org.json.JSONObject(trimmed)
                    }
                    val find = obj?.optString("findRegex", "").orEmpty()
                    val rep = obj?.optString("replaceString", "").orEmpty()
                    ComponentPreviewActivity.openRegex(
                        this@MainActivity, comp.name, find, rep, rawContent, linkedGreeting
                    )
                } catch (_: Exception) {
                    toast("正则 JSON 解析失败")
                }
            } else {
                val code = try {
                    if (trimmed.startsWith("{")) {
                        val o = org.json.JSONObject(trimmed)
                        o.optString("content", o.optString("script", rawContent))
                    } else if (trimmed.startsWith("[")) {
                        val o = org.json.JSONArray(trimmed).optJSONObject(0)
                        o?.optString("content", o.optString("script", rawContent)) ?: rawContent
                    } else rawContent
                } catch (_: Exception) {
                    rawContent
                }
                ComponentPreviewActivity.openHelper(this@MainActivity, comp.name, code, rawContent)
            }
        }
    }

    private fun showLinkComponentDialog(comp: ComponentRecord) {
        VaultSheets.actionList(
            ctx = this,
            title = "关联「${comp.name}」",
            actions = listOf(
                SheetAction("设为全局独立", "不绑定到特定资源", checked = comp.linkedTargetId.isEmpty()) {
                    libRepo.linkComponent(comp.id, "", "", "")
                    toast("已设为全局独立")
                    refreshActiveWorkspace()
                },
                SheetAction("关联到角色卡", "支持搜索查找角色卡") {
                    pickLinkTarget(comp, "character", "选择角色卡") {
                        libRepo.searchCharacters("").map { PickerItem(it.id, it.name, it.fileName) }
                    }
                },
                SheetAction("关联到预设", "支持搜索查找预设") {
                    pickLinkTarget(comp, "preset", "选择预设") {
                        libRepo.searchPresets("").map { PickerItem(it.id, it.name, it.fileName) }
                    }
                },
                SheetAction("关联到世界书", "支持搜索查找世界书") {
                    pickLinkTarget(comp, "worldbook", "选择世界书") {
                        libRepo.searchWorldBooks("").map { PickerItem(it.id, it.name, it.fileName) }
                    }
                },
                SheetAction("关联到美化", "支持搜索查找美化") {
                    pickLinkTarget(comp, "theme", "选择美化") {
                        repo.searchSources("").map { PickerItem(it.id, it.name.substringAfterLast('/'), it.folderName) }
                    }
                }
            )
        )
    }

    private fun pickLinkTarget(
        comp: ComponentRecord,
        targetType: String,
        title: String,
        loader: () -> List<PickerItem>
    ) {
        lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) { loader() }
            if (targets.isEmpty()) {
                toast("该模块暂无可关联资源")
                return@launch
            }
            VaultSheets.searchablePicker(
                ctx = this@MainActivity,
                title = title,
                items = targets,
                selectedId = if (comp.linkedTargetType == targetType) comp.linkedTargetId else null,
                searchHint = "输入关键词快速查找..."
            ) { picked ->
                libRepo.linkComponent(comp.id, targetType, picked.id, picked.title)
                toast("已关联到「${picked.title}」")
                refreshActiveWorkspace()
            }
        }
    }

    private fun confirmDeleteComponent(c: ComponentRecord) {
        VaultSheets.confirm(
            ctx = this,
            title = "删除组件",
            message = "确定删除「${c.name}」？",
            confirmText = "删除",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { libRepo.deleteComponent(c.id) }
                toast("已删除组件")
                refreshActiveWorkspace()
            }
        }
    }

    // ================== Universal Batch Multi-Select ==================

    private fun enterBatchMode() {
        batchMode = true
        batchSelectedIds.clear()
        applyBottomBarVisibility()
        binding.batchActionBar.visibility = View.VISIBLE
        binding.btnBatchCategorize.visibility =
            if (activeWorkspace == "themes" || activeWorkspace == "characters") View.VISIBLE else View.GONE
        binding.btnBatchMergeVersion.visibility = View.VISIBLE
        binding.btnBatchMergeVersion.text = if (activeWorkspace == "themes") "合并系列" else "合并版本"
        syncBatchUi()
    }

    private fun exitBatchMode() {
        if (!batchMode) return
        batchMode = false
        batchSelectedIds.clear()
        binding.batchActionBar.visibility = View.GONE
        applyBottomBarVisibility()
        syncBatchUi()
    }

    private fun toggleBatchSelection(id: String) {
        if (id in batchSelectedIds) batchSelectedIds.remove(id) else batchSelectedIds.add(id)
        syncBatchUi()
    }

    private fun currentVisibleIds(): List<String> = when (activeWorkspace) {
        "themes" -> sourcesAdapter.getVisibleIds()
        "characters" -> charactersAdapter.getVisibleIds()
        "worldbooks" -> worldBooksAdapter.getVisibleIds()
        "presets" -> presetsAdapter.getVisibleIds()
        "components" -> componentsAdapter.getVisibleIds()
        else -> emptyList()
    }

    private fun syncBatchUi() {
        binding.tvBatchCount.text = "已选 ${batchSelectedIds.size} 项"
        val snap = batchSelectedIds.toSet()
        sourcesAdapter.updateSelection(batchMode && activeWorkspace == "themes", snap)
        charactersAdapter.updateSelection(batchMode && activeWorkspace == "characters", snap)
        worldBooksAdapter.updateSelection(batchMode && activeWorkspace == "worldbooks", snap)
        presetsAdapter.updateSelection(batchMode && activeWorkspace == "presets", snap)
        componentsAdapter.updateSelection(batchMode && activeWorkspace == "components", snap)
    }

    private fun batchSelectAll() {
        val ids = currentVisibleIds()
        if (ids.isEmpty()) return
        if (batchSelectedIds.size == ids.size) {
            batchSelectedIds.clear()
        } else {
            batchSelectedIds.clear()
            batchSelectedIds.addAll(ids)
        }
        syncBatchUi()
    }

    /**
     * Contiguous range select: fills all items between the first and last currently selected indices!
     */
    private fun batchSelectRange() {
        val ids = currentVisibleIds()
        if (ids.isEmpty()) return
        val selectedIndices = ids.indices.filter { ids[it] in batchSelectedIds }
        if (selectedIndices.size < 2) {
            toast("请先点选区间首尾两项，再点「连选」")
            return
        }
        val first = selectedIndices.first()
        val last = selectedIndices.last()
        for (i in first..last) {
            batchSelectedIds.add(ids[i])
        }
        syncBatchUi()
        toast("已连选 ${last - first + 1} 项")
    }

    private fun batchInvertSelection() {
        val ids = currentVisibleIds()
        if (ids.isEmpty()) return
        val next = ids.filter { it !in batchSelectedIds }
        batchSelectedIds.clear()
        batchSelectedIds.addAll(next)
        syncBatchUi()
    }

    private fun runBatchCategorize() {
        val ids = batchSelectedIds.toList()
        if (ids.isEmpty()) {
            toast("请先勾选要分类的项目")
            return
        }
        val scope = if (activeWorkspace == "themes") CategoryManager.SCOPE_THEMES else CategoryManager.SCOPE_CHARACTERS
        val allCats = catManager.getCategories(scope)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(scope) { runBatchCategorize() }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "批量分类（已选 ${ids.size} 项）",
            items = items,
            preselectedIds = emptySet(),
            headerAction = "+ 新建" to { showQuickAddCategoryDialog(scope) { runBatchCategorize() } }
        ) { picked ->
            if (picked.isEmpty()) {
                toast("未勾选任何分类")
                return@multiSelect
            }
            VaultSheets.actionList(
                ctx = this,
                title = "应用方式",
                actions = listOf(
                    SheetAction("追加分类", "保留原有分类并添加新分类") {
                        applyBatchCategory(ids, picked, append = true)
                    },
                    SheetAction("覆盖分类", "替换为当前选中的分类") {
                        applyBatchCategory(ids, picked, append = false)
                    }
                )
            )
        }
    }

    private fun applyBatchCategory(ids: List<String>, categories: List<String>, append: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (activeWorkspace == "themes") {
                    repo.batchSetSourceCategories(ids, categories, append)
                } else {
                    libRepo.batchSetCharacterCategories(ids, categories, append)
                }
            }
            toast("已为 ${ids.size} 项更新分类")
            exitBatchMode()
            refreshActiveWorkspace()
        }
    }

    private fun runBatchMergeVersions() {
        val ids = batchSelectedIds.toList()
        if (ids.size < 2) {
            toast("请至少勾选 2 项进行版本合并")
            return
        }
        val suggestedName = when (activeWorkspace) {
            "themes" -> repo.getSourceById(ids.first())?.name ?: ""
            "characters" -> libRepo.getCharacterById(ids.first())?.name ?: ""
            "worldbooks" -> libRepo.getWorldBookById(ids.first())?.name ?: ""
            "presets" -> libRepo.getPresetById(ids.first())?.name ?: ""
            else -> ""
        }
        VaultSheets.input(
            ctx = this,
            title = "合并为同一作品的不同版本",
            message = "将把选中的 ${ids.size} 项归入同一个版本组，列表只展示最新版，进入详情页可随时切换版本。",
            hint = "输入作品分组名称",
            initialValue = suggestedName,
            confirmText = "合并"
        ) { name ->
            val clean = name.trim()
            if (clean.isEmpty()) {
                toast("请输入分组名称")
                return@input
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    when (activeWorkspace) {
                        "themes" -> repo.mergeThemeSeries(ids, clean)
                        "characters" -> libRepo.mergeCharacterVersions(ids, clean)
                        "worldbooks" -> libRepo.mergeWorldBookVersions(ids, clean)
                        "presets" -> libRepo.mergePresetVersions(ids, clean)
                        "components" -> libRepo.mergeComponentVersions(ids, clean)
                        else -> Unit
                    }
                }
                toast("已合并 ${ids.size} 项到「$clean」")
                exitBatchMode()
                refreshActiveWorkspace()
            }
        }
    }

    private fun runBatchDelete() {
        val ids = batchSelectedIds.toList()
        if (ids.isEmpty()) {
            toast("请先勾选要删除的项目")
            return
        }
        VaultSheets.confirm(
            ctx = this,
            title = "批量删除 (${ids.size} 项)",
            message = "确定删除选中的 ${ids.size} 项？将同时清理仅由其引用的独享图床缓存。",
            confirmText = "删除 ${ids.size} 项",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    when (activeWorkspace) {
                        "themes" -> repo.batchDeleteSources(ids)
                        "characters" -> ids.forEach { libRepo.deleteCharacter(it) }
                        "worldbooks" -> ids.forEach { libRepo.deleteWorldBook(it) }
                        "presets" -> ids.forEach { libRepo.deletePreset(it) }
                        "components" -> ids.forEach { libRepo.deleteComponent(it) }
                        else -> Unit
                    }
                }
                toast("已批量删除 ${ids.size} 项")
                exitBatchMode()
                refreshActiveWorkspace()
            }
        }
    }

    // ================== Built-in File Browser & Cross-App Share Import ==================

    private fun openFileBrowser(target: String, mode: String, title: String) {
        pendingBrowserTarget = target
        pendingBrowserMode = mode
        fileBrowserLauncher.launch(FileBrowserActivity.buildIntent(this, mode, title))
    }

    private fun launchSystemSafPicker(target: String, mode: String) {
        if (mode == FileBrowserActivity.MODE_FOLDER) {
            when (target) {
                "themes" -> pickFolderLauncher.launch(savedTreeUri)
                "characters" -> scanCharFolderLauncher.launch(null)
                "worldbooks" -> scanWbFolderLauncher.launch(null)
                "presets" -> scanPresetFolderLauncher.launch(null)
                "components" -> scanCompFolderLauncher.launch(null)
            }
        } else {
            when (target) {
                "themes" -> importSingleFilesLauncher.launch(arrayOf("*/*"))
                "characters" -> importCharFilesLauncher.launch(arrayOf("*/*"))
                "worldbooks" -> importWbFilesLauncher.launch(arrayOf("*/*"))
                "presets" -> importPresetFilesLauncher.launch(arrayOf("*/*"))
                "components" -> importCompFilesLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    private fun importLocalFilesWithSmartRouter(files: List<File>, preferredModule: String) {
        showIsland("正在导入 ${files.size} 个文件...")
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    SmartImporter.importLocalFiles(
                        context = this@MainActivity,
                        files = files,
                        preferredModule = preferredModule
                    ) { processed, label ->
                        runOnUiThread { updateIsland("导入中 ($processed) $label") }
                    }
                }
                val msg = summary.formatMessage()
                finishIsland(msg)
                toast(msg)
                libRepo.refreshCounts()
                refreshActiveWorkspace()
            } catch (e: Exception) {
                finishIsland("导入失败")
                toast("导入失败：${e.message}")
            }
        }
    }

    private fun runModuleLocalFolderScan(target: String, dir: File) {
        val label = when (target) {
            "characters" -> "角色卡"
            "worldbooks" -> "世界书"
            "presets" -> "预设"
            "components" -> "组件"
            else -> "资源"
        }
        showIsland("正在扫描${label}目录...")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val cb: (Int, Int) -> Unit = { scanned, matched ->
                        runOnUiThread { updateIsland("已扫 $scanned 个 · 命中 $matched 个$label") }
                    }
                    when (target) {
                        "characters" -> libRepo.scanCharactersLocalDir(dir, cb)
                        "worldbooks" -> libRepo.scanWorldBooksLocalDir(dir, cb)
                        "presets" -> libRepo.scanPresetsLocalDir(dir, cb)
                        "components" -> libRepo.scanComponentsLocalDir(dir, cb)
                        else -> com.stvault.app.model.ScanResult(0, 0, 0, 0)
                    }
                }
                val dupTip = if (res.duplicateFiles > 0) "（重复 ${res.duplicateFiles}）" else ""
                val urlTip = if (res.newUrls > 0) "，提取 ${res.newUrls} 图床" else ""
                val msg = "扫描完成：新增 ${res.matchedFiles} 个$label$dupTip$urlTip"
                finishIsland(msg)
                toast(msg)
                libRepo.refreshCounts()
                refreshActiveWorkspace()
            } catch (e: Exception) {
                finishIsland("扫描失败")
                toast("扫描失败：${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(incoming: Intent?) {
        val intent = incoming ?: return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND &&
            action != Intent.ACTION_SEND_MULTIPLE &&
            action != Intent.ACTION_VIEW
        ) return

        val uris = LinkedHashSet<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (single != null) uris.add(single)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!list.isNullOrEmpty()) uris.addAll(list)
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }

        // Also inspect clipData in case the sender (e.g., cloned file manager / QQ / WeChat) passed URIs there
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }

        // Clear action so configuration changes (e.g. dark mode / rotation) do not re-trigger import
        intent.action = null
        if (uris.isEmpty()) return

        val uriList = uris.toList()
        showIsland("正在智能识别并导入 ${uriList.size} 个文件...")
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    SmartImporter.importUris(
                        context = this@MainActivity,
                        uris = uriList,
                        preferredModule = ""
                    ) { processed, label ->
                        runOnUiThread { updateIsland("正在导入 ($processed) $label") }
                    }
                }
                val msg = summary.formatMessage()
                finishIsland(msg)
                toast(msg)
                libRepo.refreshCounts()

                // Auto-navigate if all imported items belong to a single module
                val targetWs = when {
                    summary.characters > 0 && summary.themes == 0 && summary.worldBooks == 0 && summary.presets == 0 && summary.components == 0 -> "characters"
                    summary.worldBooks > 0 && summary.themes == 0 && summary.characters == 0 && summary.presets == 0 && summary.components == 0 -> "worldbooks"
                    summary.presets > 0 && summary.themes == 0 && summary.characters == 0 && summary.worldBooks == 0 && summary.components == 0 -> "presets"
                    summary.components > 0 && summary.themes == 0 && summary.characters == 0 && summary.worldBooks == 0 && summary.presets == 0 -> "components"
                    summary.themes > 0 && summary.characters == 0 && summary.worldBooks == 0 && summary.presets == 0 && summary.components == 0 -> "themes"
                    else -> null
                }
                if (targetWs != null && targetWs != activeWorkspace) {
                    switchWorkspace(targetWs)
                } else {
                    refreshActiveWorkspace()
                }
            } catch (e: Exception) {
                finishIsland("分享导入失败")
                toast("分享导入失败：${e.message}")
            }
        }
    }

    private inner class DrawerGlobalSearchAdapter(
        private val onHitClick: (ResourceLibraryRepository.GlobalSearchHit) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<DrawerGlobalSearchAdapter.VH>() {

        private var hits: List<ResourceLibraryRepository.GlobalSearchHit> = emptyList()

        fun submit(list: List<ResourceLibraryRepository.GlobalSearchHit>) {
            hits = list
            notifyDataSetChanged()
        }

        private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        private fun col(res: Int): Int = ContextCompat.getColor(this@MainActivity, res)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val row = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val h = hits[position]
            val row = holder.row
            row.removeAllViews()

            val top = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this@MainActivity).apply {
                text = h.badge
                setTextColor(col(R.color.text_muted))
                setBackgroundResource(R.drawable.bg_pill_accent)
                textSize = 10f
                setPadding(dp(6), dp(1), dp(6), dp(1))
            })
            top.addView(TextView(this@MainActivity).apply {
                text = "  ${h.title}"
                setTextColor(col(R.color.text_primary))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(top)

            if (h.subtitle.isNotEmpty()) {
                row.addView(TextView(this@MainActivity).apply {
                    text = h.subtitle
                    setTextColor(col(R.color.text_muted))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            }

            row.setOnClickListener { onHitClick(h) }
        }

        override fun getItemCount(): Int = hits.size
        inner class VH(val row: android.widget.LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(row)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
