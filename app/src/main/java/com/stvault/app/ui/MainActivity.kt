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
    private lateinit var bgRepo: com.stvault.app.core.BackgroundRepository
    private lateinit var catManager: CategoryManager
    private lateinit var bgGridAdapter: BackgroundGridAdapter
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
    private var activeBgCategory = ""
    private var activeCharCategory = ""
    private var activeWbCategory = ""
    private var activeCompCategory = ""
    private var activeThemeTab = "cache"
    private var searchDebounceJob: Job? = null

    private var batchMode = false
    private val batchSelectedIds = LinkedHashSet<String>()

    private var savedTreeUri: Uri? = null
    private var savedLocalDirPath: String? = null
    private var vaultSearchModeSources = true
    private var vaultModeBgs = false
    private var activeSourceFilter = "all"
    private var activeBgFilter = "all"
    private var activeUrlFilter = "all"
    private var activeThemeSort = "newest"
    private var activeCharSort = "newest"
    private var activeWbSort = "newest"
    private var activePresetSort = "newest"
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
    private val pickBgImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val (added, dups) = withContext(Dispatchers.IO) {
                    var a = 0; var d = 0
                    for (u in uris) {
                        val (rec, isDup) = bgRepo.importUri(u)
                        if (isDup) d++ else if (rec != null) a++
                    }
                    a to d
                }
                toast(if (added > 0) "已导入 $added 张背景" + if (dups > 0) "（重复 $dups）" else "" else "所选图片已在背景库中")
                refreshVaultList()
            }
        }

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
        bgRepo = com.stvault.app.core.BackgroundRepository.getInstance(this)
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
        maybeAutoStartTavernBridge()
    }

    private fun maybeAutoStartTavernBridge() {
        if (!prefs.getBoolean("bridge_auto_start", false)) return
        val bridge = com.stvault.app.core.TavernBridgeManager.getInstance(this)
        if (bridge.isRunning()) return
        lifecycleScope.launch(Dispatchers.IO) {
            bridge.startServer()
            try {
                com.stvault.app.service.TavernBridgeService.start(this@MainActivity)
            } catch (_: Exception) {
            }
        }
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
        binding.drawer.tvDrawerInboxStatus.text = if (com.stvault.app.core.InboxManager.isDiscordConfigured(this)) {
            if (com.stvault.app.core.InboxManager.isDiscordAutoSyncEnabled(this)) "DC 自动" else "DC 已配"
        } else {
            "DC · 本地"
        }
        maybeAutoSyncDiscordInbox()
    }

    private fun maybeAutoSyncDiscordInbox() {
        if (!com.stvault.app.core.InboxManager.shouldRunAutoSync(this)) return
        lifecycleScope.launch(Dispatchers.IO) {
            var islandShown = false
            val res = com.stvault.app.core.InboxManager.syncDiscordChannel(
                context = this@MainActivity,
                fullResync = false,
                onDownloadStart = { total ->
                    islandShown = true
                    runOnUiThread { showIsland("收件箱同步中（共 $total 个附件）...") }
                },
                onProgress = { msg ->
                    if (islandShown) {
                        runOnUiThread { updateIsland(msg) }
                    }
                }
            )
            withContext(Dispatchers.Main) {
                if (islandShown) {
                    if (res.error != null) {
                        finishIsland("收件箱同步中断")
                    } else if (res.summary.totalImported > 0) {
                        finishIsland("收件箱${res.summary.formatMessage()}")
                        refreshActiveWorkspace()
                        libRepo.refreshCounts()
                    } else {
                        finishIsland("收件箱：${res.summary.formatMessage()}")
                    }
                }
            }
        }
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
        bgGridAdapter = BackgroundGridAdapter(
            bgRepo = bgRepo,
            isInstalled = { bg ->
                com.stvault.app.core.TavernBridgeManager.getInstance(this).isBackgroundInstalled(bg.name, bg.fileName)
            },
            onClick = { bg -> showBgActionMenu(bg) },
            onLongClick = { bg -> showBgActionMenu(bg) },
            onToggleSelect = { id -> toggleBatchSelection(id) }
        )
        binding.rvBackgrounds.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        binding.rvBackgrounds.adapter = bgGridAdapter
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
        activeThemeSort = prefs.getString("sort_themes", "newest") ?: "newest"
        activeCharSort = prefs.getString("sort_characters", "newest") ?: "newest"
        activeWbSort = prefs.getString("sort_worldbooks", "newest") ?: "newest"
        activePresetSort = prefs.getString("sort_presets", "newest") ?: "newest"
        binding.btnThemeSort.text = sortPillLabel(activeThemeSort)
        binding.btnThemeSort.setOnClickListener {
            showSortSheet(
                title = "美化排序方式",
                currentKey = activeThemeSort,
                options = listOf(
                    "newest" to ("最新入库" to "按导入时间从新到旧"),
                    "oldest" to ("最早入库" to "按导入时间从旧到新"),
                    "name_asc" to ("名称 A → Z" to "按系列名 / 标题拼音正序"),
                    "name_desc" to ("名称 Z → A" to "按系列名 / 标题拼音倒序"),
                    "most_imgs" to ("图床最多" to "按外链图片数量从多到少"),
                    "most_variants" to ("变体最多" to "多变体系列优先排前")
                )
            ) { key ->
                activeThemeSort = key
                prefs.edit().putString("sort_themes", key).apply()
                binding.btnThemeSort.text = sortPillLabel(key)
                refreshVaultList()
            }
        }
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
        binding.wsCharacters.btnCharSort.text = sortPillLabel(activeCharSort)
        binding.wsCharacters.btnCharSort.setOnClickListener {
            showSortSheet(
                title = "角色卡排序方式",
                currentKey = activeCharSort,
                options = listOf(
                    "newest" to ("最新入库" to "按导入时间从新到旧"),
                    "oldest" to ("最早入库" to "按导入时间从旧到新"),
                    "name_asc" to ("名称 A → Z" to "按标题拼音正序"),
                    "name_desc" to ("名称 Z → A" to "按标题拼音倒序"),
                    "largest" to ("体积最大" to "按文件大小从大到小")
                )
            ) { key ->
                activeCharSort = key
                prefs.edit().putString("sort_characters", key).apply()
                binding.wsCharacters.btnCharSort.text = sortPillLabel(key)
                refreshCharactersList()
            }
        }
        buildSimpleChips(
            binding.wsCharacters.chipGroupCharFilter,
            listOf("all" to "全部", "multi_version" to "多版本", "has_book" to "带世界书", "has_script" to "带脚本", "has_urls" to "含外链图"),
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
        binding.wsWorldBooks.btnWbSort.text = sortPillLabel(activeWbSort)
        binding.wsWorldBooks.btnWbSort.setOnClickListener {
            showSortSheet(
                title = "世界书排序方式",
                currentKey = activeWbSort,
                options = listOf(
                    "newest" to ("最新入库" to "按导入时间从新到旧"),
                    "oldest" to ("最早入库" to "按导入时间从旧到新"),
                    "name_asc" to ("名称 A → Z" to "按标题拼音正序"),
                    "name_desc" to ("名称 Z → A" to "按标题拼音倒序"),
                    "most_entries" to ("条目最多" to "按世界书条目数从多到少")
                )
            ) { key ->
                activeWbSort = key
                prefs.edit().putString("sort_worldbooks", key).apply()
                binding.wsWorldBooks.btnWbSort.text = sortPillLabel(key)
                refreshWorldBooksList()
            }
        }
        refreshWbFilterChips()

        // 4. Presets
        binding.wsPresets.rvPresets.layoutManager = LinearLayoutManager(this)
        presetsAdapter = PresetsAdapter(
            onClick = { p -> ResourceDetailActivity.open(this, ResourceDetailActivity.TYPE_PRESET, p.id) },
            onDeleteClick = { p -> confirmDeletePreset(p) },
            onToggleSelect = { id -> toggleBatchSelection(id) },
            onLongClick = { p -> showPresetActionMenu(p) }
        )
        binding.wsPresets.rvPresets.adapter = presetsAdapter
        binding.wsPresets.btnPresetSort.text = sortPillLabel(activePresetSort)
        binding.wsPresets.btnPresetSort.setOnClickListener {
            showSortSheet(
                title = "预设排序方式",
                currentKey = activePresetSort,
                options = listOf(
                    "newest" to ("最新入库" to "按导入时间从新到旧"),
                    "oldest" to ("最早入库" to "按导入时间从旧到新"),
                    "name_asc" to ("名称 A → Z" to "按标题拼音正序"),
                    "name_desc" to ("名称 Z → A" to "按标题拼音倒序"),
                    "most_prompts" to ("提示词最多" to "按预设条目数从多到少")
                )
            ) { key ->
                activePresetSort = key
                prefs.edit().putString("sort_presets", key).apply()
                binding.wsPresets.btnPresetSort.text = sortPillLabel(key)
                refreshPresetsList()
            }
        }

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
            listOf("all" to "全部", "regex" to "正则", "helper" to "酒馆助手", "qr" to "快速回复", "doc" to "文档", "global" to "全局", "linked" to "已关联"),
            activeCompFilter
        ) { key ->
            activeCompFilter = key
            refreshComponentsList()
        }

        // 6. Quick Top / Bottom Scroll Jump Buttons across all 5 workspaces
        bindQuickScrollJump(binding.rvVault, binding.boxVaultScrollJump, binding.btnVaultScrollTop, binding.btnVaultScrollBottom)
        bindQuickScrollJump(binding.wsCharacters.rvCharacters, binding.wsCharacters.boxCharScrollJump, binding.wsCharacters.btnCharScrollTop, binding.wsCharacters.btnCharScrollBottom)
        bindQuickScrollJump(binding.wsWorldBooks.rvWorldBooks, binding.wsWorldBooks.boxWbScrollJump, binding.wsWorldBooks.btnWbScrollTop, binding.wsWorldBooks.btnWbScrollBottom)
        bindQuickScrollJump(binding.wsPresets.rvPresets, binding.wsPresets.boxPresetScrollJump, binding.wsPresets.btnPresetScrollTop, binding.wsPresets.btnPresetScrollBottom)
        bindQuickScrollJump(binding.wsComponents.rvComponents, binding.wsComponents.boxCompScrollJump, binding.wsComponents.btnCompScrollTop, binding.wsComponents.btnCompScrollBottom)
    }

    private fun bindQuickScrollJump(
        rv: androidx.recyclerview.widget.RecyclerView,
        box: View,
        btnTop: View,
        btnBottom: View
    ) {
        fun updateVisibility() {
            val count = rv.adapter?.itemCount ?: 0
            box.visibility = if (count >= 6) View.VISIBLE else View.GONE
        }
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                updateVisibility()
            }
        })
        rv.adapter?.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateVisibility()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateVisibility()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateVisibility()
        })
        btnTop.setOnClickListener {
            rv.scrollToPosition(0)
        }
        btnBottom.setOnClickListener {
            val last = (rv.adapter?.itemCount ?: 0) - 1
            if (last >= 0) rv.scrollToPosition(last)
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
        binding.drawer.drawerItemHoard.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(
                Intent(this, DiscordBrowserActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        }
        binding.drawer.drawerItemInbox.setOnClickListener {
            startActivity(Intent(this, InboxActivity::class.java))
        }
        binding.drawer.drawerItemBridge.setOnClickListener {
            TavernBridgeActivity.open(this)
        }
        binding.drawer.drawerItemLog.setOnClickListener {
            ActivityLogActivity.open(this)
        }
        binding.drawer.drawerItemDedup.setOnClickListener {
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

    private fun sortPillLabel(key: String): String = when (key) {
        "oldest" -> "最早 ▾"
        "name_asc" -> "名称↑ ▾"
        "name_desc" -> "名称↓ ▾"
        "most_imgs" -> "图最多 ▾"
        "most_variants" -> "变体多 ▾"
        "largest" -> "体积大 ▾"
        "most_entries" -> "条目多 ▾"
        "most_prompts" -> "提示词多 ▾"
        else -> "最新 ▾"
    }

    private fun showSortSheet(
        title: String,
        currentKey: String,
        options: List<Pair<String, Pair<String, String>>>,
        onPicked: (String) -> Unit
    ) {
        val actions = options.map { (key, labelAndSub) ->
            SheetAction(
                label = labelAndSub.first,
                subtitle = labelAndSub.second,
                checked = (key == currentKey)
            ) {
                onPicked(key)
            }
        }
        VaultSheets.actionList(ctx = this, title = title, actions = actions)
    }

    private fun <T> sortGroupedItems(
        groups: List<com.stvault.app.model.GroupedItem<T>>,
        sortBy: String,
        getDisplayName: (com.stvault.app.model.GroupedItem<T>) -> String,
        getTime: (T) -> Long,
        getMetric: (T) -> Long = { 0L }
    ): List<com.stvault.app.model.GroupedItem<T>> {
        val zhCollator = java.text.Collator.getInstance(Locale.CHINA)
        return when (sortBy) {
            "oldest" -> groups.sortedBy { getTime(it.primary) }
            "name_asc" -> groups.sortedWith { a, b -> zhCollator.compare(getDisplayName(a), getDisplayName(b)) }
            "name_desc" -> groups.sortedWith { a, b -> zhCollator.compare(getDisplayName(b), getDisplayName(a)) }
            "largest", "most_entries", "most_prompts" -> groups.sortedWith(
                compareByDescending<com.stvault.app.model.GroupedItem<T>> { getMetric(it.primary) }
                    .thenByDescending { getTime(it.primary) }
            )
            else -> groups.sortedByDescending { getTime(it.primary) }
        }
    }

    private fun rebuildFilterChips() {
        val sortVis = if (vaultSearchModeSources && !vaultModeBgs) View.VISIBLE else View.GONE
        binding.btnThemeSort.visibility = sortVis
        binding.divThemeSort.visibility = sortVis
        val group = binding.chipGroupFilter
        group.removeAllViews()
        val options = when {
            vaultModeBgs -> listOf(
                "all" to "全部",
                "bound" to "已绑定",
                "unbound" to "未绑定",
                "installed" to "已装",
                "not_installed" to "未装"
            )
            vaultSearchModeSources -> listOf(
                "all" to "全部",
                "multi_version" to "多版本",
                "installed" to "已装",
                "not_installed" to "未装",
                "incomplete" to "未下完"
            )
            else -> listOf(
                "all" to "全部",
                "ok" to "已缓存",
                "fonts" to "字体",
                "error" to "失败",
                "ignored" to "已忽略",
                "pending" to "待缓存",
                "largest" to "大图优先"
            )
        }
        val current = when {
            vaultModeBgs -> activeBgFilter
            vaultSearchModeSources -> activeSourceFilter
            else -> activeUrlFilter
        }
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
                    when {
                        vaultModeBgs -> activeBgFilter = key
                        vaultSearchModeSources -> activeSourceFilter = key
                        else -> activeUrlFilter = key
                    }
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

        fun styleBtn(btn: com.google.android.material.button.MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (active) accent else transparent)
            btn.setTextColor(if (active) onAccent else muted)
        }
        styleBtn(binding.btnModeSources, vaultSearchModeSources && !vaultModeBgs)
        styleBtn(binding.btnModeBgs, vaultModeBgs)
        styleBtn(binding.btnModeUrls, !vaultSearchModeSources && !vaultModeBgs)

        binding.rvVault.visibility = if (vaultModeBgs) View.GONE else View.VISIBLE
        binding.rvBackgrounds.visibility = if (vaultModeBgs) View.VISIBLE else View.GONE

        if (vaultModeBgs) {
            binding.btnImportInVault.visibility = View.VISIBLE
            binding.btnImportInVault.text = "+ 添加"
            binding.btnThemeCatFilter.visibility = View.VISIBLE
            binding.btnThemeCatFilter.text = if (activeBgCategory.isEmpty()) "分类 ▾" else "${activeBgCategory.substringAfterLast('/')} ▾"
            binding.btnThemeBatch.visibility = View.VISIBLE
            if (batchMode) exitBatchMode()
        } else if (vaultSearchModeSources) {
            binding.btnImportInVault.visibility = View.VISIBLE
            binding.btnImportInVault.text = "+ 添加"
            binding.btnThemeCatFilter.visibility = View.VISIBLE
            binding.btnThemeCatFilter.text = if (activeThemeCategory.isEmpty()) "分类 ▾" else "${activeThemeCategory.substringAfterLast('/')} ▾"
            binding.btnThemeBatch.visibility = View.VISIBLE
            if (batchMode) exitBatchMode()
        } else {
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
        binding.wsWorldBooks.btnWbCatFilter.setOnClickListener {
            showCategoryFilterPicker(CategoryManager.SCOPE_WORLDBOOKS, activeWbCategory) { picked ->
                activeWbCategory = picked
                binding.wsWorldBooks.btnWbCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                refreshWorldBooksList()
            }
        }
        binding.wsComponents.btnCompCatFilter.setOnClickListener {
            showCategoryFilterPicker(CategoryManager.SCOPE_COMPONENTS, activeCompCategory) { picked ->
                activeCompCategory = picked
                binding.wsComponents.btnCompCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                refreshComponentsList()
            }
        }
        binding.btnThemeCatFilter.setOnClickListener {
            if (vaultModeBgs) {
                showCategoryFilterPicker(CategoryManager.SCOPE_BACKGROUNDS, activeBgCategory) { picked ->
                    activeBgCategory = picked
                    binding.btnThemeCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                    refreshVaultList()
                }
            } else {
                showCategoryFilterPicker(CategoryManager.SCOPE_THEMES, activeThemeCategory) { picked ->
                    activeThemeCategory = picked
                    binding.btnThemeCatFilter.text = if (picked.isEmpty()) "分类 ▾" else "${picked.substringAfterLast('/')} ▾"
                    refreshVaultList()
                }
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
            VaultSheets.actionList(
                ctx = this,
                title = "添加角色卡",
                actions = listOf(
                    SheetAction("导入文件", "从本地选择 PNG / JSON 角色卡") {
                        openFileBrowser("characters", FileBrowserActivity.MODE_FILES, "导入角色卡")
                    },
                    SheetAction("扫描目录", "批量扫描文件夹中的角色卡") {
                        openFileBrowser("characters", FileBrowserActivity.MODE_FOLDER, "扫描角色卡目录")
                    },
                    SheetAction("链接导入", "粘贴直链导入角色卡") {
                        showImportCharacterUrlDialog()
                    },
                    SheetAction("新建角色卡", "创建标准 V2 空白角色卡并直接编辑") {
                        showCreateNewCharacterDialog()
                    }
                )
            )
        }
        // Per-item image caching lives inside each card's action menu and detail view (no permanent top bar clutter)

        // Workspace 3: World Books Listeners
        binding.wsWorldBooks.btnWbSearch.setOnClickListener { refreshWorldBooksList() }
        binding.wsWorldBooks.etWbSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshWorldBooksList(); true } else false
        }
        binding.wsWorldBooks.btnWbQuickImport.setOnClickListener {
            VaultSheets.actionList(
                ctx = this,
                title = "添加世界书",
                actions = listOf(
                    SheetAction("导入文件", "从本地选择 JSON 世界书") {
                        openFileBrowser("worldbooks", FileBrowserActivity.MODE_FILES, "导入世界书")
                    },
                    SheetAction("扫描目录", "批量扫描文件夹中的世界书") {
                        openFileBrowser("worldbooks", FileBrowserActivity.MODE_FOLDER, "扫描世界书目录")
                    },
                    SheetAction("新建世界书", "创建空白世界书并直接编辑") {
                        showCreateNewWorldBookDialog()
                    }
                )
            )
        }
        // Per-item image caching lives inside each worldbook's action menu and detail view

        // Workspace 4: Presets Listeners
        binding.wsPresets.btnPresetSearch.setOnClickListener { refreshPresetsList() }
        binding.wsPresets.etPresetSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshPresetsList(); true } else false
        }
        binding.wsPresets.btnPresetImportFile.setOnClickListener {
            VaultSheets.actionList(
                ctx = this,
                title = "添加预设",
                actions = listOf(
                    SheetAction("导入文件", "从本地选择 JSON 预设") {
                        openFileBrowser("presets", FileBrowserActivity.MODE_FILES, "导入预设")
                    },
                    SheetAction("扫描目录", "批量扫描文件夹中的预设") {
                        openFileBrowser("presets", FileBrowserActivity.MODE_FOLDER, "扫描预设目录")
                    },
                    SheetAction("新建预设", "创建基础预设模板并直接编辑") {
                        showCreateNewPresetDialog()
                    }
                )
            )
        }

        // Workspace 5: Components Listeners
        binding.wsComponents.btnCompSearch.setOnClickListener { refreshComponentsList() }
        binding.wsComponents.etCompSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { refreshComponentsList(); true } else false
        }
        binding.wsComponents.btnCompImportFile.setOnClickListener {
            VaultSheets.actionList(
                ctx = this,
                title = "添加组件",
                actions = listOf(
                    SheetAction("导入文件", "正则 / 酒馆助手 / 快速回复 / 文档") {
                        openFileBrowser("components", FileBrowserActivity.MODE_FILES, "导入组件")
                    },
                    SheetAction("扫描目录", "批量扫描文件夹中的组件") {
                        openFileBrowser("components", FileBrowserActivity.MODE_FOLDER, "扫描组件目录")
                    },
                    SheetAction("新建文档 / 笔记", "创建纯文本笔记并直接编辑") {
                        showCreateNewComponentDialog("doc")
                    },
                    SheetAction("新建正则脚本", "创建 SillyTavern 正则模板") {
                        showCreateNewComponentDialog("regex")
                    },
                    SheetAction("新建酒馆助手脚本", "创建 JS / HTML 助手脚本模板") {
                        showCreateNewComponentDialog("helper")
                    },
                    SheetAction("新建快速回复", "创建 Quick Reply 模板") {
                        showCreateNewComponentDialog("qr")
                    }
                )
            )
        }

        // Tab 1: Folder Pick, Single File Import & Scan
        binding.btnPickFolder.setOnClickListener {
            openFileBrowser("themes", FileBrowserActivity.MODE_FOLDER, "选择美化目录")
        }
        binding.btnImportSingleFile.setOnClickListener {
            openFileBrowser("themes", FileBrowserActivity.MODE_FILES, "导入美化文件")
        }
        binding.btnImportInVault.setOnClickListener {
            if (vaultModeBgs) {
                pickBgImagesLauncher.launch("image/*")
            } else {
                VaultSheets.actionList(
                    ctx = this,
                    title = "添加美化",
                    actions = listOf(
                        SheetAction("导入文件", "从本地选择 JSON / CSS 美化") {
                            openFileBrowser("themes", FileBrowserActivity.MODE_FILES, "导入美化文件")
                        },
                        SheetAction("扫描目录", "批量扫描文件夹中的美化") {
                            openFileBrowser("themes", FileBrowserActivity.MODE_FOLDER, "扫描美化目录")
                        },
                        SheetAction("新建美化", "创建标准酒馆美化模板并直接编辑") {
                            showCreateNewThemeDialog()
                        }
                    )
                )
            }
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
            vaultModeBgs = false
            vaultSearchModeSources = true
            binding.rvVault.adapter = sourcesAdapter
            updateSegmentedStyle()
            rebuildFilterChips()
            refreshVaultList()
        }
        binding.btnModeBgs.setOnClickListener {
            vaultModeBgs = true
            updateSegmentedStyle()
            rebuildFilterChips()
            refreshVaultList()
        }
        binding.btnModeUrls.setOnClickListener {
            vaultModeBgs = false
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
            if (vaultModeBgs) {
                val bgs = withContext(Dispatchers.IO) {
                    val bridge = com.stvault.app.core.TavernBridgeManager.getInstance(this@MainActivity)
                    bgRepo.getAll(q, activeBgCategory, catManager).filter { rec ->
                        when (activeBgFilter) {
                            "bound" -> bgRepo.countThemesBoundTo(rec.id) > 0
                            "unbound" -> bgRepo.countThemesBoundTo(rec.id) == 0
                            "installed" -> bridge.isBackgroundInstalled(rec.name, rec.fileName)
                            "not_installed" -> !bridge.isBackgroundInstalled(rec.name, rec.fileName)
                            else -> true
                        }
                    }
                }
                bgGridAdapter.submitList(bgs)
                binding.tvVaultCount.text = "共 ${bgs.size} 张背景"
                return@launch
            }
            if (vaultSearchModeSources) {
                val groupVars = prefs.getBoolean("group_theme_variants", true)
                val list = withContext(Dispatchers.IO) {
                    repo.searchSourceRows(q, activeSourceFilter, activeThemeCategory, groupVars, activeThemeSort)
                }
                sourcesAdapter.submitList(list)
                val totalExpanded = list.sumOf { it.variantCount }
                binding.tvVaultCount.text = if (groupVars && totalExpanded > list.size) {
                    "共 ${list.size} 组（$totalExpanded 个美化）"
                } else {
                    "共 ${list.size} 个美化"
                }
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

        val variants = repo.getVariantsForTheme(initialSource.seriesKey)
        fun bindActiveSource(target: SourceRecord) {
            activeSource = target
            if (variants.size > 1) {
                val sTitle = target.seriesName.ifBlank { target.name }
                val vTag = target.variantTag.trim()
                dBinding.tvDialogSourceTitle.text = if (vTag.isNotEmpty() && !sTitle.equals(vTag, ignoreCase = true)) {
                    "$sTitle · $vTag"
                } else sTitle
            } else {
                dBinding.tvDialogSourceTitle.text = target.name
            }
            detailAdapter.submitList(repo.getUrlRecordsForSource(target))
        }

        bindActiveSource(initialSource)

        // Populate Sibling Variant Pills when >1 variant exists (updates in-place with zero flicker!)
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
                val filtered = if (activeCharFilter == "multi_version") rawG.filter { it.versionCount > 1 } else rawG
                val g = sortGroupedItems(
                    filtered,
                    activeCharSort,
                    getDisplayName = { if (it.versionCount > 1) it.primary.seriesName.ifBlank { it.primary.name } else it.primary.name },
                    getTime = { it.addedAt },
                    getMetric = { it.fileSize }
                )
                val urls = libRepo.getAllCharacterUrls()
                val counts = repo.getCountsForUrls(urls)
                Triple(g, chars.size, counts.third)
            }
            charactersAdapter.submitList(grouped)
            binding.wsCharacters.tvCharCount.text =
                if (groupVersions && grouped.size < totalRaw) "共 ${grouped.size} 作品 ($totalRaw 版)" else "共 ${grouped.size} 张"
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

    private fun refreshWbFilterChips() {
        buildSimpleChips(
            binding.wsWorldBooks.chipGroupWbFilter,
            listOf(
                "all" to "全部",
                "multi_version" to "多版本",
                "standalone" to "独立世界书",
                "from_card" to "来自角色卡",
                "has_urls" to "含外链图"
            ),
            activeWbFilter
        ) { key ->
            activeWbFilter = key
            refreshWorldBooksList()
        }
    }

    private fun refreshWorldBooksList() {
        val q = binding.wsWorldBooks.etWbSearch.text?.toString().orEmpty()
        val groupVersions = prefs.getBoolean("group_versions", true)
        lifecycleScope.launch {
            val (grouped, totalRaw, pendingCount) = withContext(Dispatchers.IO) {
                val baseFilter = if (activeWbFilter == "multi_version") "all" else activeWbFilter
                val books = libRepo.searchWorldBooks(q, baseFilter, activeWbCategory)
                val rawG = VersionGrouper.groupBySeries(
                    books, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, ResourceDetailActivity.TYPE_WORLDBOOK, key) }
                )
                val filtered = if (activeWbFilter == "multi_version") rawG.filter { it.versionCount > 1 } else rawG
                val g = sortGroupedItems(
                    filtered,
                    activeWbSort,
                    getDisplayName = { if (it.versionCount > 1) it.primary.seriesName.ifBlank { it.primary.name } else it.primary.name },
                    getTime = { it.addedAt },
                    getMetric = { it.entryCount.toLong() }
                )
                val urls = libRepo.getAllWorldBookUrls()
                val counts = repo.getCountsForUrls(urls)
                Triple(g, books.size, counts.third)
            }
            worldBooksAdapter.submitList(grouped)
            binding.wsWorldBooks.tvWbCount.text =
                if (groupVersions && grouped.size < totalRaw) "共 ${grouped.size} 部 ($totalRaw 版)" else "共 ${grouped.size} 本"
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
                val rawGp = VersionGrouper.groupBySeries(
                    p, groupVersions, { it.seriesKey }, { it.addedAt }, { it.id },
                    { key -> VersionGrouper.getPreferredVersion(this@MainActivity, ResourceDetailActivity.TYPE_PRESET, key) }
                )
                val gp = sortGroupedItems(
                    rawGp,
                    activePresetSort,
                    getDisplayName = { if (it.versionCount > 1) it.primary.seriesName.ifBlank { it.primary.name } else it.primary.name },
                    getTime = { it.addedAt },
                    getMetric = { it.promptCount.toLong() }
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
                val list = libRepo.searchComponents(q, activeCompFilter, activeCompCategory, catManager)
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
            headerAction = "管理分类" to {
                showInlineCategoryManager(scope, current, onPicked)
            }
        ) { picked ->
            onPicked(picked.id)
        }
    }

    private fun showInlineCategoryManager(scope: String, currentFilter: String, onFilterChanged: (String) -> Unit) {
        val cats = catManager.getCategories(scope)
        if (cats.isEmpty()) {
            showQuickAddCategoryDialog(scope) {
                showCategoryFilterPicker(scope, currentFilter, onFilterChanged)
            }
            return
        }
        val items = cats.map { PickerItem(it, catManager.formatIndentedLabel(it), "点击编辑或删除") }
        VaultSheets.searchablePicker(
            ctx = this,
            title = "管理分类",
            items = items,
            searchHint = "搜索分类...",
            headerAction = "+ 新建" to {
                showQuickAddCategoryDialog(scope) {
                    showInlineCategoryManager(scope, currentFilter, onFilterChanged)
                }
            }
        ) { picked ->
            VaultSheets.actionList(
                ctx = this,
                title = picked.id,
                actions = listOf(
                    SheetAction("重命名 / 移动层级", "移入其他目录或改名，资源自动迁移") {
                        VaultSheets.input(
                            ctx = this,
                            title = "重命名 / 移动分类",
                            message = "用 / 分隔层级（如 作者/某某），输入已有路径自动合并。",
                            initialValue = picked.id,
                            hint = "例如：作者/某某",
                            confirmText = "保存"
                        ) { raw ->
                            val newPath = catManager.renameOrMoveCategory(scope, picked.id, raw)
                            if (newPath != null) {
                                val migrated = when (scope) {
                                    CategoryManager.SCOPE_THEMES -> repo.renameSourceCategoryPrefix(picked.id, newPath)
                                    CategoryManager.SCOPE_WORLDBOOKS -> libRepo.renameWorldBookCategoryPrefix(picked.id, newPath)
                                    CategoryManager.SCOPE_BACKGROUNDS -> bgRepo.renameCategoryPrefix(picked.id, newPath)
                                    CategoryManager.SCOPE_COMPONENTS -> libRepo.renameComponentCategoryPrefix(picked.id, newPath)
                                    else -> libRepo.renameCharacterCategoryPrefix(picked.id, newPath)
                                }
                                if (currentFilter == picked.id || currentFilter.startsWith("${picked.id}/")) {
                                    onFilterChanged(newPath)
                                } else {
                                    refreshActiveWorkspace()
                                }
                                toast("已更新为「$newPath」（迁移 $migrated 项）")
                            } else {
                                toast("路径无效或未变化")
                            }
                        }
                    },
                    SheetAction("删除分类", "同时清理所有资源上的该标签", destructive = true) {
                        VaultSheets.confirm(
                            ctx = this,
                            title = "删除分类",
                            message = "确定删除「${picked.id}」及其子分类？所有资源上的该标签也会一并移除。",
                            confirmText = "删除",
                            destructive = true
                        ) {
                            catManager.deleteCategory(scope, picked.id)
                            val cleaned = when (scope) {
                                CategoryManager.SCOPE_THEMES -> repo.removeSourceCategoryPrefix(picked.id)
                                CategoryManager.SCOPE_WORLDBOOKS -> libRepo.removeWorldBookCategoryPrefix(picked.id)
                                CategoryManager.SCOPE_BACKGROUNDS -> bgRepo.removeCategoryPrefix(picked.id)
                                CategoryManager.SCOPE_COMPONENTS -> libRepo.removeComponentCategoryPrefix(picked.id)
                                else -> libRepo.removeCharacterCategoryPrefix(picked.id)
                            }
                            if (currentFilter == picked.id || currentFilter.startsWith("${picked.id}/")) {
                                onFilterChanged("")
                            } else {
                                refreshActiveWorkspace()
                            }
                            toast("已删除分类（清理 $cleaned 项标签）")
                        }
                    }
                )
            )
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
        val beforeSet = source.categories.toSet()
        val variants = repo.getVariantsForTheme(source.seriesKey)
        if (variants.size > 1) {
            VaultSheets.multiSelect(
                ctx = this,
                title = "设置分类",
                items = items,
                preselectedIds = beforeSet,
                headerAction = "+ 新建" to {
                    showQuickAddCategoryDialog(CategoryManager.SCOPE_THEMES) {
                        showThemeCategoryAssignDialog(source)
                    }
                },
                primaryConfirmText = "全系列 (${variants.size})",
                secondaryConfirm = "仅当前" to { picked ->
                    repo.setSourceCategories(source.id, picked)
                    toast("已更新当前变体分类")
                    refreshVaultList()
                }
            ) { picked ->
                val afterSet = picked.toSet()
                val added = afterSet - beforeSet
                val removed = beforeSet - afterSet
                val cnt = repo.deltaUpdateThemeSeriesCategories(source.seriesKey, added, removed)
                toast("已同步分类至全系列 ($cnt)")
                refreshVaultList()
            }
        } else {
            VaultSheets.multiSelect(
                ctx = this,
                title = "设置分类",
                items = items,
                preselectedIds = beforeSet,
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
            actions.add(SheetAction("重命名当前美化", "修改当前这一条美化的原名") {
                showRenameDialog("重命名美化", source.name) { newName ->
                    if (repo.renameSource(source.id, newName)) {
                        toast("已重命名")
                        refreshVaultList()
                    }
                }
            })
            actions.add(SheetAction("修改系列名", "统一修改整套系列的显示名称，并自动刷新变体标签") {
                showRenameDialog("修改系列名", source.seriesName.ifBlank { source.name }) { newName ->
                    if (repo.renameThemeSeries(source.seriesKey, newName)) {
                        toast("系列名已更新")
                        refreshVaultList()
                    }
                }
            })
        } else {
            actions.add(SheetAction("重命名", "修改显示名称") {
                showRenameDialog("重命名美化", source.name) { newName ->
                    if (repo.renameSource(source.id, newName)) {
                        toast("已重命名")
                        refreshVaultList()
                    }
                }
            })
        }
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
        actions.add(SheetAction("归入同系列", "按名称匹配度排序，自动排除已在同系列的美化") {
            lifecycleScope.launch {
                val others = withContext(Dispatchers.Default) {
                    val all = repo.getAllSources()
                    val matcher = com.stvault.app.core.SimilarityDeduplicator.createNameMatcher(source.name)
                    all.asSequence()
                        .filter { other ->
                            other.id != source.id &&
                                (source.seriesKey.isBlank() || other.seriesKey != source.seriesKey)
                        }
                        .map { other -> other to matcher.score(other.name) }
                        .sortedByDescending { it.second }
                        .map { (other, sim) ->
                            val pct = (sim * 100).toInt()
                            val sub = if (pct >= 45) "匹配度 $pct%" else other.variantTag
                            PickerItem(other.id, other.name, sub)
                        }
                        .toList()
                }
                if (others.isEmpty()) {
                    toast("暂无其他可归入的美化")
                } else {
                    VaultSheets.searchablePicker(this@MainActivity, "选择同系列美化", others) { picked ->
                        val defaultSeries = source.seriesName.ifBlank { source.name }
                        VaultSheets.input(this@MainActivity, "设置系列名称", hint = "输入系列名", initialValue = defaultSeries, confirmText = "归入") { sName ->
                            if (sName.trim().isNotEmpty()) {
                                val idsToMerge = (variants.map { it.id } + source.id + picked.id).distinct()
                                repo.mergeThemeSeries(idsToMerge, sName.trim())
                                toast("已归入「${sName.trim()}」系列")
                                refreshVaultList()
                            }
                        }
                    }
                }
            }
        })
        if (variants.size > 1) {
            actions.add(SheetAction("移出当前系列", "将当前变体从系列中拆出，恢复为独立美化") {
                if (repo.detachThemeFromSeries(source.id)) {
                    toast("已从系列中移出")
                    refreshVaultList()
                }
            })
        }
        actions.add(SheetAction("装入酒馆", "推送到 TT 酒馆，支持原版或图床修复版") {
            pushThemeToTavern(source)
        })
        actions.add(SheetAction("编辑代码", "可视化调节样式与 CSS，或切换 JSON 源码") {
            CodeEditorActivity.open(this, CodeEditorActivity.MODULE_THEME, source.id, source.name)
        })
        val boundBgs = bgRepo.getBoundBackgrounds(source.id)
        val bgSub = when {
            boundBgs.isEmpty() -> "关联推荐背景图，支持绑定多张"
            boundBgs.size == 1 -> "默认：${boundBgs.first().name}"
            else -> "默认：${boundBgs.first().name}（共 ${boundBgs.size} 张）"
        }
        actions.add(SheetAction("绑定背景", bgSub) {
            if (boundBgs.isNotEmpty()) {
                showThemeBindingDetails(source)
            } else {
                showBindBgToThemePicker(source)
            }
        })
        actions.add(SheetAction("设置分类标签", "多选 1~3 级分类") { showThemeCategoryAssignDialog(source) })
        actions.add(SheetAction("删除美化", destructive = true) { confirmDeleteSource(source) })
        VaultSheets.actionList(ctx = this, title = source.name, actions = actions)
    }

    private fun showThemeBindingDetails(source: SourceRecord) {
        val boundBgs = bgRepo.getBoundBackgrounds(source.id)
        val actions = mutableListOf<SheetAction>()
        boundBgs.forEachIndexed { idx, bg ->
            val tag = if (idx == 0) "默认背景 · 点击预览" else "候选 #${idx + 1} · 点击设为默认"
            actions.add(SheetAction(bg.name, tag, checked = idx == 0) {
                if (idx == 0) {
                    ThemePreviewActivity.open(this, source.id)
                } else {
                    bgRepo.setDefaultThemeBinding(source.id, bg.id)
                    toast("已将「${bg.name}」设为默认背景")
                    refreshVaultList()
                    showThemeBindingDetails(source)
                }
            })
        }
        actions.add(SheetAction("管理 / 增减绑定背景", "支持多选绑定多张背景") {
            showBindBgToThemePicker(source)
        })
        if (boundBgs.isNotEmpty()) {
            actions.add(SheetAction("清空全部绑定", destructive = true) {
                bgRepo.setThemeBindings(source.id, emptyList())
                toast("已清空背景绑定")
                refreshVaultList()
            })
        }
        VaultSheets.actionList(ctx = this, title = "${source.name} · 绑定背景 (${boundBgs.size})", actions = actions)
    }

    private fun showBindBgToThemePicker(source: SourceRecord) {
        val allBgs = bgRepo.getAll()
        if (allBgs.isEmpty()) {
            VaultSheets.confirm(
                ctx = this,
                title = "背景库暂无图片",
                message = "先从相册导入一张背景图，或用囤囤从帖子导入纯图片即可绑定。",
                confirmText = "去导入"
            ) {
                pickBgImagesLauncher.launch("image/*")
            }
            return
        }
        val curIds = bgRepo.getBoundBgIds(source.id)
        val items = allBgs.map { bg ->
            val idx = curIds.indexOf(bg.id)
            val sub = when {
                idx == 0 -> "默认绑定 · ${bg.fileName}"
                idx > 0 -> "已绑 #${idx + 1} · ${bg.fileName}"
                else -> bg.fileName
            }
            PickerItem(bg.id, bg.name, sub)
        }
        VaultSheets.multiSelect(
            ctx = this,
            title = "绑定背景（可多选，首张为默认）",
            items = items,
            preselectedIds = curIds.toSet(),
            primaryConfirmText = "保存绑定",
            searchHint = "搜索背景名称..."
        ) { pickedIds ->
            // Keep existing order for retained items so default stays first, append newly selected items
            val retained = curIds.filter { it in pickedIds }
            val added = pickedIds.filter { it !in curIds }
            val finalIds = retained + added
            bgRepo.setThemeBindings(source.id, finalIds)
            toast(if (finalIds.isEmpty()) "已取消全部背景绑定" else "已绑定 ${finalIds.size} 张背景")
            refreshVaultList()
        }
    }

    private fun showBgBindingDetails(bg: com.stvault.app.core.BackgroundRecord) {
        val boundThemeIds = bgRepo.getThemesBoundTo(bg.id)
        val allSources = repo.getAllSources().associateBy { it.id }
        val boundSources = boundThemeIds.mapNotNull { allSources[it] }
        val actions = mutableListOf<SheetAction>()
        for (src in boundSources) {
            val isDefault = bgRepo.getBoundBgId(src.id) == bg.id
            val sub = if (isDefault) "默认背景 · 点击操作" else "候选背景 · 点击操作"
            actions.add(SheetAction(src.name, sub, checked = isDefault) {
                VaultSheets.actionList(
                    ctx = this,
                    title = "${src.name} ↔ ${bg.name}",
                    actions = listOfNotNull(
                        SheetAction("打开美化预览") { ThemePreviewActivity.open(this, src.id) },
                        if (!isDefault) SheetAction("设为该美化的默认背景") {
                            bgRepo.setDefaultThemeBinding(src.id, bg.id)
                            toast("已设为「${src.name}」的默认背景")
                            refreshVaultList()
                        } else null,
                        SheetAction("解除绑定", destructive = true) {
                            bgRepo.removeThemeBinding(src.id, bg.id)
                            toast("已解除与「${src.name}」的绑定")
                            refreshVaultList()
                        }
                    )
                )
            })
        }
        actions.add(SheetAction("+ 绑定到更多美化", "可同时绑定多个美化") {
            showBindBgToMultipleThemesPicker(bg)
        })
        VaultSheets.actionList(ctx = this, title = "${bg.name} · 已绑美化 (${boundSources.size})", actions = actions)
    }

    private fun showBindBgToMultipleThemesPicker(bg: com.stvault.app.core.BackgroundRecord) {
        val themes = repo.getAllSources().sortedByDescending { it.scannedAt }
        if (themes.isEmpty()) {
            toast("库中暂无美化")
            return
        }
        val preBound = bgRepo.getThemesBoundTo(bg.id).toSet()
        VaultSheets.multiSelect(
            ctx = this,
            title = "绑定到美化（可多选）",
            items = themes.map { PickerItem(it.id, it.name, it.variantTag.ifBlank { it.fileName }) },
            preselectedIds = preBound,
            primaryConfirmText = "保存",
            searchHint = "搜索美化..."
        ) { picked ->
            val pickedSet = picked.toSet()
            for (tid in pickedSet - preBound) bgRepo.addThemeBinding(tid, bg.id)
            for (tid in preBound - pickedSet) bgRepo.removeThemeBinding(tid, bg.id)
            toast("已更新绑定（当前关联 ${pickedSet.size} 个美化）")
            refreshVaultList()
        }
    }

    private fun showBgCategoryAssignDialog(bg: com.stvault.app.core.BackgroundRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_BACKGROUNDS)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(CategoryManager.SCOPE_BACKGROUNDS) { showBgCategoryAssignDialog(bg) }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "背景分类标签",
            items = items,
            preselectedIds = bg.categories.toSet(),
            headerAction = "+ 新建" to { showQuickAddCategoryDialog(CategoryManager.SCOPE_BACKGROUNDS) { showBgCategoryAssignDialog(bg) } },
            primaryConfirmText = "保存"
        ) { picked ->
            bgRepo.setCategories(bg.id, picked)
            toast("已更新背景分类")
            refreshVaultList()
        }
    }

    private fun showBgActionMenu(bg: com.stvault.app.core.BackgroundRecord) {
        val boundCount = bgRepo.countThemesBoundTo(bg.id)
        val installed = com.stvault.app.core.TavernBridgeManager.getInstance(this).isBackgroundInstalled(bg.name, bg.fileName)
        val actions = mutableListOf<SheetAction>()
        if (boundCount > 0) {
            actions.add(SheetAction("查看绑定详情 ($boundCount)", "查看已绑美化、设默认或解绑") {
                showBgBindingDetails(bg)
            })
        }
        actions.add(SheetAction("绑定到美化", if (boundCount > 0) "增减关联的美化（可多选）" else "选择要使用此背景的美化") {
            showBindBgToMultipleThemesPicker(bg)
        })
        actions.add(SheetAction("设置分类标签", if (bg.categories.isNotEmpty()) bg.categories.joinToString(" · ") else "归类背景风格") {
            showBgCategoryAssignDialog(bg)
        })
        actions.add(SheetAction("装入酒馆", if (installed) "酒馆已装 · 再次推送将覆盖" else "将背景图推送到酒馆背景库") {
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    val f = bgRepo.getFile(bg)
                    if (f.exists()) f.readBytes() else null
                }
                if (bytes != null) {
                    val topTags = bg.categories.map { it.substringBefore('/').trim() }.filter { it.isNotBlank() && it != "待整理" }.distinct()
                    enqueueToTavernBridge("background", bg.name, bg.fileName, bytes, topTags)
                } else toast("找不到背景文件")
            }
        })
        actions.add(SheetAction("重命名", "修改背景显示名称") {
            showRenameDialog("重命名背景", bg.name) { newName ->
                if (bgRepo.rename(bg.id, newName)) {
                    toast("已重命名")
                    refreshVaultList()
                }
            }
        })
        actions.add(SheetAction("删除背景", destructive = true) {
            VaultSheets.confirm(
                ctx = this,
                title = "删除背景",
                message = "确定删除背景「${bg.name}」？已绑定此背景的美化将自动解绑。",
                confirmText = "删除",
                destructive = true
            ) {
                bgRepo.delete(bg.id)
                toast("已删除背景")
                refreshVaultList()
            }
        })
        VaultSheets.actionList(ctx = this, title = bg.name, actions = actions)
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
                    enqueueToTavernBridge("theme", source.name, fileName, bytes, source.categories, hostId = source.id)
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

    private fun enqueueToTavernBridge(
        kind: String,
        name: String,
        fileName: String,
        bytes: ByteArray,
        tags: List<String> = emptyList(),
        hostId: String = ""
    ) {
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
            bridge.enqueueOutbox(kind, name, fileName, bytes, tags)
            toast("已加入待装入队列，切回 TT 酒馆即可自动装入")
            if (hostId.isNotEmpty()) {
                LinkedComponentPushHelper.promptIfLinked(this, kind, hostId, bridge, libRepo)
            }
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
                        if (bytes != null) enqueueToTavernBridge("character", c.name, c.fileName, bytes, hostId = c.id)
                        else toast("找不到角色卡文件")
                    }
                },
                SheetAction("编辑内容", "分段编辑设定、开场白或 JSON 源码") {
                    CodeEditorActivity.open(this, CodeEditorActivity.MODULE_CHARACTER, c.id, c.name)
                },
                SheetAction("缓存图床", "下载外链图片到本地缓存") {
                    if (c.urls.isEmpty()) toast("该角色卡没有外链图床")
                    else {
                        VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
                        toast("开始后台缓存图床")
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
                        if (bytes != null) enqueueToTavernBridge("worldbook", w.name, w.fileName, bytes, hostId = w.id)
                        else toast("找不到世界书文件")
                    }
                },
                SheetAction("编辑条目", "逐条编辑世界书条目或 JSON 源码") {
                    CodeEditorActivity.open(this, CodeEditorActivity.MODULE_WORLDBOOK, w.id, w.name)
                },
                SheetAction("分类标签", "多选 1~3 级分类") {
                    showWorldBookCategoryAssignDialog(w)
                },
                SheetAction("缓存图床", "下载外链图片到本地缓存") {
                    if (w.urls.isEmpty()) toast("该世界书没有外链图床")
                    else {
                        VaultDownloadService.start(this, mode = "pending", concurrency = downloadConcurrency)
                        toast("开始后台缓存图床")
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

    private fun showWorldBookCategoryAssignDialog(w: WorldBookRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_WORLDBOOKS)
        if (allCats.isEmpty()) {
            VaultSheets.input(
                ctx = this,
                title = "新建世界书分类（1~3级）",
                message = "用斜杠 / 分隔层级，例如 世界观/奇幻",
                hint = "例如：世界观/奇幻",
                confirmText = "添加"
            ) { raw ->
                if (catManager.addCategory(CategoryManager.SCOPE_WORLDBOOKS, raw) != null) {
                    refreshWbFilterChips()
                    showWorldBookCategoryAssignDialog(w)
                }
            }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "世界书分类标签",
            items = items,
            preselectedIds = w.categories.toSet(),
            headerAction = "+ 新建" to {
                VaultSheets.input(this, "新建世界书分类", "用 / 分隔 1~3 级", "例如：世界观/奇幻", confirmText = "添加") { raw ->
                    if (catManager.addCategory(CategoryManager.SCOPE_WORLDBOOKS, raw) != null) {
                        refreshWbFilterChips()
                        showWorldBookCategoryAssignDialog(w)
                    }
                }
            }
        ) { picked ->
            libRepo.setWorldBookCategories(w.id, picked)
            refreshWbFilterChips()
            refreshWorldBooksList()
            toast("分类已更新")
        }
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
                        if (bytes != null) enqueueToTavernBridge("preset", p.name, p.fileName, bytes, hostId = p.id)
                        else toast("找不到预设文件")
                    }
                },
                SheetAction("编辑提示词", "逐条编辑预设提示词或 JSON 源码") {
                    CodeEditorActivity.open(this, CodeEditorActivity.MODULE_PRESET, p.id, p.name)
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

    private fun showComponentCategoryAssignDialog(comp: ComponentRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_COMPONENTS)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(CategoryManager.SCOPE_COMPONENTS) { showComponentCategoryAssignDialog(comp) }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "组件分类标签",
            items = items,
            preselectedIds = comp.categories.toSet(),
            headerAction = "+ 新建" to { showQuickAddCategoryDialog(CategoryManager.SCOPE_COMPONENTS) { showComponentCategoryAssignDialog(comp) } },
            primaryConfirmText = "保存"
        ) { picked ->
            libRepo.setComponentCategories(comp.id, picked)
            toast("已更新组件分类")
            refreshComponentsList()
        }
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
        actions.add(SheetAction("编辑代码", "可视化编辑正则与脚本，或切换源码") {
            CodeEditorActivity.open(this, CodeEditorActivity.MODULE_COMPONENT, comp.id, comp.name)
        })
        actions.add(SheetAction("设置分类标签", if (comp.categories.isNotEmpty()) comp.categories.joinToString(" · ") else "多选 1~3 级分类") {
            showComponentCategoryAssignDialog(comp)
        })
        actions.add(SheetAction("重命名", "修改组件显示名称") {
            showRenameDialog("重命名组件", comp.name) { newName ->
                if (libRepo.renameComponent(comp.id, newName)) {
                    toast("已重命名")
                    refreshComponentsList()
                }
            }
        })
        actions.add(SheetAction("关联宿主", "关联到角色卡 / 预设 / 世界书 / 美化") { showLinkComponentDialog(comp) })
        actions.add(SheetAction("导出文件", "导出组件原始文件") {
            pendingExportCompFile = libRepo.getComponentFile(comp)
            exportCompFileLauncher.launch(comp.fileName)
        })
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
                val matcher = com.stvault.app.core.SimilarityDeduplicator.createNameMatcher(firstName)
                when (module) {
                    "characters" -> {
                        val curSeries = libRepo.getCharacterById(firstId)?.seriesKey.orEmpty()
                        libRepo.getAllCharacters()
                            .asSequence()
                            .filter { it.id != firstId && (curSeries.isBlank() || it.seriesKey != curSeries) }
                            .map { it to matcher.score(it.name) }
                            .sortedByDescending { it.second }
                            .map { (it, _) -> PickerItem(it.id, it.name, it.fileName) }
                            .toList()
                    }
                    "worldbooks" -> {
                        val curSeries = libRepo.getWorldBookById(firstId)?.seriesKey.orEmpty()
                        libRepo.getAllWorldBooks()
                            .asSequence()
                            .filter { it.id != firstId && (curSeries.isBlank() || it.seriesKey != curSeries) }
                            .map { it to matcher.score(it.name) }
                            .sortedByDescending { it.second }
                            .map { (it, _) -> PickerItem(it.id, it.name, "${it.entryCount} 条目") }
                            .toList()
                    }
                    "presets" -> {
                        val curSeries = libRepo.getPresetById(firstId)?.seriesKey.orEmpty()
                        libRepo.getAllPresets()
                            .asSequence()
                            .filter { it.id != firstId && (curSeries.isBlank() || it.seriesKey != curSeries) }
                            .map { it to matcher.score(it.name) }
                            .sortedByDescending { it.second }
                            .map { (it, _) -> PickerItem(it.id, it.name, "启用 ${it.enabledPromptCount}/${it.promptCount}") }
                            .toList()
                    }
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
        val beforeSet = c.categories.toSet()
        val seriesMembers = libRepo.getCharactersInSeries(c.seriesKey)
        if (seriesMembers.size > 1) {
            VaultSheets.multiSelect(
                ctx = this,
                title = "设置分类",
                items = items,
                preselectedIds = beforeSet,
                headerAction = "+ 新建" to {
                    showQuickAddCategoryDialog(CategoryManager.SCOPE_CHARACTERS) {
                        showCharacterCategoryAssignDialog(c)
                    }
                },
                primaryConfirmText = "全系列 (${seriesMembers.size})",
                secondaryConfirm = "仅当前" to { picked ->
                    libRepo.setCharacterCategories(c.id, picked)
                    toast("已更新当前版本分类")
                    refreshCharactersList()
                }
            ) { picked ->
                val afterSet = picked.toSet()
                val added = afterSet - beforeSet
                val removed = beforeSet - afterSet
                val cnt = libRepo.deltaUpdateCharacterSeriesCategories(c.seriesKey, added, removed)
                toast("已同步分类至全系列 ($cnt)")
                refreshCharactersList()
            }
        } else {
            VaultSheets.multiSelect(
                ctx = this,
                title = "设置分类",
                items = items,
                preselectedIds = beforeSet,
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
    }

    private fun showComponentDetailDialog(comp: ComponentRecord) {
        if (comp.type == "regex" || comp.type == "helper") {
            launchComponentPreview(comp)
            return
        }
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { libRepo.readComponentText(comp) }
            val linkInfo = if (comp.linkedTargetName.isNotEmpty()) {
                "当前关联：${comp.linkedTargetName}"
            } else {
                "当前状态：全局独立"
            }
            val preview = "$linkInfo\n\n${content.take(2000)}" + if (content.length > 2000) "\n..." else ""
            VaultSheets.info(
                ctx = this@MainActivity,
                title = comp.name,
                message = preview,
                primaryText = "关联宿主",
                onPrimary = { showLinkComponentDialog(comp) },
                secondaryText = "关闭",
                onSecondary = {},
                tertiaryText = "导出",
                onTertiary = {
                    pendingExportCompFile = libRepo.getComponentFile(comp)
                    exportCompFileLauncher.launch(comp.fileName)
                },
                copyText = content
            )
        }
    }

    private fun launchComponentPreview(comp: ComponentRecord) {
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val rawContent = libRepo.readComponentText(comp)
                    val trimmed = rawContent.trim()
                    if (comp.type == "regex") {
                        val linkedGreeting = if (comp.linkedTargetType == "character" && comp.linkedTargetId.isNotEmpty()) {
                            libRepo.getCharacterById(comp.linkedTargetId)?.let { libRepo.parseCharacterDetail(it)?.firstMes }.orEmpty()
                        } else ""
                        val obj = if (trimmed.startsWith("[")) {
                            org.json.JSONArray(trimmed).optJSONObject(0)
                        } else {
                            org.json.JSONObject(trimmed)
                        }
                        val find = obj?.optString("findRegex", "").orEmpty()
                        val rep = obj?.optString("replaceString", "").orEmpty()
                        listOf("regex", find, rep, rawContent, linkedGreeting)
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
                        listOf("helper", code, rawContent)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (parsed == null) {
                toast("组件解析失败")
                return@launch
            }
            if (parsed[0] == "regex") {
                ComponentPreviewActivity.openRegex(
                    this@MainActivity, comp.name, parsed[1], parsed[2], parsed[3], parsed[4]
                )
            } else {
                ComponentPreviewActivity.openHelper(this@MainActivity, comp.name, parsed[1], parsed[2])
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
            if (activeWorkspace == "themes" || activeWorkspace == "characters" || activeWorkspace == "components") View.VISIBLE else View.GONE
        binding.btnBatchMergeVersion.visibility = View.VISIBLE
        binding.btnBatchMergeVersion.text = when {
            activeWorkspace == "themes" && vaultModeBgs -> "推酒馆"
            activeWorkspace == "themes" -> "合并系列"
            else -> "合并版本"
        }
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
        "themes" -> if (vaultModeBgs) bgGridAdapter.getVisibleIds() else sourcesAdapter.getVisibleIds()
        "characters" -> charactersAdapter.getVisibleIds()
        "worldbooks" -> worldBooksAdapter.getVisibleIds()
        "presets" -> presetsAdapter.getVisibleIds()
        "components" -> componentsAdapter.getVisibleIds()
        else -> emptyList()
    }

    private fun syncBatchUi() {
        binding.tvBatchCount.text = "已选 ${batchSelectedIds.size} 项"
        val snap = batchSelectedIds.toSet()
        sourcesAdapter.updateSelection(batchMode && activeWorkspace == "themes" && !vaultModeBgs, snap)
        bgGridAdapter.updateSelection(batchMode && activeWorkspace == "themes" && vaultModeBgs, snap)
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
        val isBgBatch = activeWorkspace == "themes" && vaultModeBgs
        val scope = when {
            isBgBatch -> CategoryManager.SCOPE_BACKGROUNDS
            activeWorkspace == "themes" -> CategoryManager.SCOPE_THEMES
            activeWorkspace == "components" -> CategoryManager.SCOPE_COMPONENTS
            else -> CategoryManager.SCOPE_CHARACTERS
        }
        val allCats = catManager.getCategories(scope)
        if (allCats.isEmpty()) {
            showQuickAddCategoryDialog(scope) { runBatchCategorize() }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        // Pre-select tags shared by ALL currently selected items (e.g. "待整理") so unchecking removes them in 1 tap
        val selectedCatLists: List<Set<String>> = when {
            isBgBatch -> {
                val map = bgRepo.getAll().associateBy { it.id }
                ids.mapNotNull { map[it]?.categories?.toSet() }
            }
            activeWorkspace == "themes" -> {
                val map = repo.getAllSources().associateBy { it.id }
                ids.mapNotNull { map[it]?.categories?.toSet() }
            }
            activeWorkspace == "components" -> {
                val map = libRepo.getAllComponents().associateBy { it.id }
                ids.mapNotNull { map[it]?.categories?.toSet() }
            }
            else -> {
                val map = libRepo.getAllCharacters().associateBy { it.id }
                ids.mapNotNull { map[it]?.categories?.toSet() }
            }
        }
        val commonCats: Set<String> = if (selectedCatLists.isEmpty()) {
            emptySet()
        } else {
            selectedCatLists.reduce { acc, set -> acc intersect set }
        }

        VaultSheets.multiSelect(
            ctx = this,
            title = "批量分类（已选 ${ids.size} 项）",
            items = items,
            preselectedIds = commonCats,
            headerAction = "+ 新建" to { showQuickAddCategoryDialog(scope) { runBatchCategorize() } },
            primaryConfirmText = "应用",
            secondaryConfirm = "移除所选" to { picked ->
                if (picked.isEmpty()) {
                    toast("请先勾选要移除的分类")
                } else {
                    applyBatchCategoryDelta(ids, emptySet(), picked.toSet())
                }
            }
        ) { picked ->
            val pickedSet = picked.toSet()
            val added = pickedSet - commonCats
            val removed = commonCats - pickedSet
            if (added.isEmpty() && removed.isEmpty()) {
                toast("分类无变更")
                return@multiSelect
            }
            applyBatchCategoryDelta(ids, added, removed)
        }
    }

    private fun applyBatchCategoryDelta(ids: List<String>, added: Set<String>, removed: Set<String>) {
        lifecycleScope.launch {
            val targetCount = withContext(Dispatchers.IO) {
                if (activeWorkspace == "themes" && vaultModeBgs) {
                    var cnt = 0
                    for (id in ids) {
                        val cur = bgRepo.getById(id) ?: continue
                        val next = (cur.categories.toSet() + added - removed).toList()
                        bgRepo.setCategories(id, next)
                        cnt++
                    }
                    cnt
                } else if (activeWorkspace == "components") {
                    libRepo.batchDeltaUpdateComponentCategories(ids, added, removed)
                    ids.size
                } else if (activeWorkspace == "themes") {
                    val fold = prefs.getBoolean("fold_theme_variants", true)
                    val expanded = if (fold) {
                        ids.flatMap { id ->
                            val s = repo.getSourceById(id)
                            if (s != null && s.seriesKey.isNotBlank()) {
                                repo.getVariantsForTheme(s.seriesKey).map { it.id }
                            } else listOf(id)
                        }.distinct()
                    } else ids
                    repo.batchDeltaUpdateSourceCategories(expanded, added, removed)
                    expanded.size
                } else {
                    val fold = prefs.getBoolean("fold_versions", true)
                    val expanded = if (fold) {
                        ids.flatMap { id ->
                            val c = libRepo.getCharacterById(id)
                            if (c != null && c.seriesKey.isNotBlank()) {
                                libRepo.getCharactersInSeries(c.seriesKey).map { it.id }
                            } else listOf(id)
                        }.distinct()
                    } else ids
                    libRepo.batchDeltaUpdateCharacterCategories(expanded, added, removed)
                    expanded.size
                }
            }
            val msg = when {
                added.isEmpty() && removed.isNotEmpty() -> "已从 $targetCount 项移除 ${removed.size} 个分类"
                added.isNotEmpty() && removed.isEmpty() -> "已为 $targetCount 项添加 ${added.size} 个分类"
                else -> "已为 $targetCount 项更新分类"
            }
            toast(msg)
            batchSelectedIds.clear()
            syncBatchUi()
            refreshActiveWorkspace()
        }
    }

    private fun runBatchMergeVersions() {
        val ids = batchSelectedIds.toList()
        if (activeWorkspace == "themes" && vaultModeBgs) {
            if (ids.isEmpty()) {
                toast("请先勾选要推送的背景")
                return
            }
            lifecycleScope.launch {
                val bridge = com.stvault.app.core.TavernBridgeManager.getInstance(this@MainActivity)
                if (!bridge.stateFlow.value.running) {
                    bridge.startServer()
                }
                val pushed = withContext(Dispatchers.IO) {
                    var cnt = 0
                    for (id in ids) {
                        val bg = bgRepo.getById(id) ?: continue
                        val f = bgRepo.getFile(bg)
                        if (f.exists()) {
                            val topTags = bg.categories.map { it.substringBefore('/').trim() }.filter { it.isNotBlank() && it != "待整理" }.distinct()
                            bridge.enqueueOutbox("background", bg.name, bg.fileName, f.readBytes(), topTags)
                            cnt++
                        }
                    }
                    cnt
                }
                toast(if (pushed > 0) "已将 $pushed 张背景加入酒馆推送队列" else "未找到可推送的背景文件")
                batchSelectedIds.clear()
                syncBatchUi()
            }
            return
        }
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
                        "themes" -> {
                            val expandedIds = ids.flatMap { id ->
                                val s = repo.getSourceById(id)
                                if (s != null && s.seriesKey.isNotBlank()) {
                                    repo.getVariantsForTheme(s.seriesKey).map { it.id }
                                } else listOf(id)
                            }.distinct()
                            repo.mergeThemeSeries(expandedIds, clean)
                        }
                        "characters" -> libRepo.mergeCharacterVersions(ids, clean)
                        "worldbooks" -> libRepo.mergeWorldBookVersions(ids, clean)
                        "presets" -> libRepo.mergePresetVersions(ids, clean)
                        "components" -> libRepo.mergeComponentVersions(ids, clean)
                        else -> Unit
                    }
                }
                toast("已合并 ${ids.size} 项到「$clean」")
                batchSelectedIds.clear()
                syncBatchUi()
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
                        "themes" -> if (vaultModeBgs) ids.forEach { bgRepo.delete(it) } else repo.batchDeleteSources(ids)
                        "characters" -> ids.forEach { libRepo.deleteCharacter(it) }
                        "worldbooks" -> ids.forEach { libRepo.deleteWorldBook(it) }
                        "presets" -> ids.forEach { libRepo.deletePreset(it) }
                        "components" -> ids.forEach { libRepo.deleteComponent(it) }
                        else -> Unit
                    }
                }
                toast("已批量删除 ${ids.size} 项")
                batchSelectedIds.clear()
                syncBatchUi()
                refreshActiveWorkspace()
            }
        }
    }

    // ================== Create New Blank Resources ==================

    private fun safeCreateFileName(name: String, ext: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifBlank { "新建_${System.currentTimeMillis() % 10000}" }
        return "$base.$ext"
    }

    private fun showCreateNewThemeDialog() {
        VaultSheets.input(
            ctx = this,
            title = "新建空白美化",
            hint = "输入美化名称",
            confirmText = "创建并编辑"
        ) { raw ->
            val name = raw.trim()
            if (name.isEmpty()) { toast("请输入名称"); return@input }
            val json = org.json.JSONObject().apply {
                put("name", name)
                put("main_text_color", "rgba(220, 220, 210, 1)")
                put("italics_text_color", "rgba(145, 145, 145, 1)")
                put("quote_text_color", "rgba(225, 138, 36, 1)")
                put("blur_tint_color", "rgba(23, 23, 23, 0.85)")
                put("chat_tint_color", "rgba(23, 23, 23, 0.8)")
                put("user_mes_blur_tint_color", "rgba(0, 0, 0, 0.3)")
                put("bot_mes_blur_tint_color", "rgba(0, 0, 0, 0.3)")
                put("border_color", "rgba(255, 255, 255, 0.15)")
                put("chat_display", 0)
                put("custom_css", "/* 在此编写自定义 CSS */\n")
            }.toString(2)
            val fn = safeCreateFileName(name, "json")
            lifecycleScope.launch {
                val created = withContext(Dispatchers.IO) {
                    repo.importRawThemeText(json, fn, "新建")
                    repo.getAllSources().firstOrNull { it.fileName == fn } ?: repo.getAllSources().maxByOrNull { it.scannedAt }
                }
                refreshVaultList()
                if (created != null) {
                    toast("已新建美化：$name")
                    CodeEditorActivity.open(this@MainActivity, CodeEditorActivity.MODULE_THEME, created.id, created.name)
                }
            }
        }
    }

    private fun showCreateNewCharacterDialog() {
        VaultSheets.input(
            ctx = this,
            title = "新建空白角色卡",
            hint = "输入角色名称",
            confirmText = "创建并编辑"
        ) { raw ->
            val name = raw.trim()
            if (name.isEmpty()) { toast("请输入角色名称"); return@input }
            val json = org.json.JSONObject().apply {
                put("spec", "chara_card_v2")
                put("spec_version", "2.0")
                put("data", org.json.JSONObject().apply {
                    put("name", name)
                    put("description", "")
                    put("personality", "")
                    put("scenario", "")
                    put("first_mes", "")
                    put("mes_example", "")
                    put("creator_notes", "")
                    put("system_prompt", "")
                    put("alternate_greetings", org.json.JSONArray())
                    put("tags", org.json.JSONArray())
                    put("creator", "")
                    put("character_version", "1.0")
                })
            }.toString(2)
            val fn = safeCreateFileName(name, "json")
            lifecycleScope.launch {
                val rec = withContext(Dispatchers.IO) {
                    libRepo.importCharacterFromBytes(json.toByteArray(Charsets.UTF_8), fn, "新建").first
                }
                libRepo.refreshCounts()
                refreshCharactersList()
                if (rec != null) {
                    toast("已新建角色卡：$name")
                    CodeEditorActivity.open(this@MainActivity, CodeEditorActivity.MODULE_CHARACTER, rec.id, rec.name)
                }
            }
        }
    }

    private fun showCreateNewWorldBookDialog() {
        VaultSheets.input(
            ctx = this,
            title = "新建空白世界书",
            hint = "输入世界书名称",
            confirmText = "创建并编辑"
        ) { raw ->
            val name = raw.trim()
            if (name.isEmpty()) { toast("请输入世界书名称"); return@input }
            val json = org.json.JSONObject().apply {
                put("entries", org.json.JSONObject().apply {
                    put("0", org.json.JSONObject().apply {
                        put("uid", 0)
                        put("key", org.json.JSONArray().put("关键词"))
                        put("keysecondary", org.json.JSONArray())
                        put("comment", "条目 1")
                        put("content", "")
                        put("constant", false)
                        put("selective", true)
                        put("order", 100)
                        put("position", 0)
                        put("disable", false)
                    })
                })
            }.toString(2)
            val fn = safeCreateFileName(name, "json")
            lifecycleScope.launch {
                val rec = withContext(Dispatchers.IO) {
                    libRepo.importWorldBookFromText(json, fn).first
                }
                libRepo.refreshCounts()
                refreshWorldBooksList()
                if (rec != null) {
                    toast("已新建世界书：$name")
                    CodeEditorActivity.open(this@MainActivity, CodeEditorActivity.MODULE_WORLDBOOK, rec.id, rec.name)
                }
            }
        }
    }

    private fun showCreateNewPresetDialog() {
        VaultSheets.input(
            ctx = this,
            title = "新建空白预设",
            hint = "输入预设名称",
            confirmText = "创建并编辑"
        ) { raw ->
            val name = raw.trim()
            if (name.isEmpty()) { toast("请输入预设名称"); return@input }
            val json = org.json.JSONObject().apply {
                put("temperature", 1.0)
                put("top_p", 0.95)
                put("prompts", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("identifier", "main")
                    put("name", "主提示词")
                    put("role", "system")
                    put("content", "")
                    put("system_prompt", true)
                    put("enabled", true)
                }))
                put("prompt_order", org.json.JSONArray().put(org.json.JSONObject().apply {
                    put("character_id", 100001)
                    put("order", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("identifier", "main")
                        put("enabled", true)
                    }))
                }))
            }.toString(2)
            val fn = safeCreateFileName(name, "json")
            lifecycleScope.launch {
                val rec = withContext(Dispatchers.IO) {
                    libRepo.importPresetFromText(json, fn).first
                }
                libRepo.refreshCounts()
                refreshPresetsList()
                if (rec != null) {
                    toast("已新建预设：$name")
                    CodeEditorActivity.open(this@MainActivity, CodeEditorActivity.MODULE_PRESET, rec.id, rec.name)
                }
            }
        }
    }

    private fun showCreateNewComponentDialog(type: String) {
        val label = when (type) {
            "doc" -> "新建文档 / 笔记"
            "regex" -> "新建正则脚本"
            "helper" -> "新建酒馆助手脚本"
            else -> "新建快速回复"
        }
        VaultSheets.input(
            ctx = this,
            title = label,
            hint = "输入名称",
            confirmText = "创建并编辑"
        ) { raw ->
            val name = raw.trim()
            if (name.isEmpty()) { toast("请输入名称"); return@input }
            lifecycleScope.launch {
                val rec = withContext(Dispatchers.IO) {
                    if (type == "doc") {
                        val fn = safeCreateFileName(name, "txt")
                        val initText = "# $name\n\n"
                        libRepo.importDocumentBytes(initText.toByteArray(Charsets.UTF_8), fn, name).first
                    } else {
                        val uid = java.util.UUID.randomUUID().toString()
                        val json = when (type) {
                            "regex" -> org.json.JSONObject().apply {
                                put("id", uid)
                                put("scriptName", name)
                                put("findRegex", "/<example>([\\s\\S]*?)<\\/example>/g")
                                put("replaceString", "<div>$1</div>")
                                put("trimStrings", org.json.JSONArray())
                                put("placement", org.json.JSONArray().put(2))
                                put("disabled", false)
                                put("markdownOnly", true)
                                put("promptOnly", false)
                                put("runOnEdit", true)
                            }.toString(2)
                            "helper" -> org.json.JSONObject().apply {
                                put("type", "script")
                                put("id", uid)
                                put("name", name)
                                put("content", "// 酒馆助手脚本\n")
                                put("info", "")
                                put("enabled", true)
                            }.toString(2)
                            else -> org.json.JSONObject().apply {
                                put("version", 2)
                                put("name", name)
                                put("disableSend", false)
                                put("placeBeforeInput", false)
                                put("injectInput", false)
                                put("color", "rgba(0, 0, 0, 0)")
                                put("qrList", org.json.JSONArray().put(org.json.JSONObject().apply {
                                    put("id", 1)
                                    put("label", "示例回复")
                                    put("title", "")
                                    put("message", "")
                                    put("contextList", org.json.JSONArray())
                                    put("preventAutoExecute", true)
                                    put("isHidden", false)
                                }))
                            }.toString(2)
                        }
                        val fn = safeCreateFileName(name, "json")
                        libRepo.importComponentFromText(json, fn).first
                    }
                }
                libRepo.refreshCounts()
                refreshComponentsList()
                if (rec != null) {
                    toast("已新建：$name")
                    CodeEditorActivity.open(this@MainActivity, CodeEditorActivity.MODULE_COMPONENT, rec.id, rec.name)
                }
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
