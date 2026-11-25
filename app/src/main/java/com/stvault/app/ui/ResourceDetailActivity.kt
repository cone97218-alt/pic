package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.stvault.app.R
import com.stvault.app.core.CategoryManager
import com.stvault.app.core.ParsedCharacterCard
import com.stvault.app.core.ParsedPreset
import com.stvault.app.core.ParsedWorldBook
import com.stvault.app.core.ParsedWorldEntry
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityResourceDetailBinding
import com.stvault.app.model.CacheStatus
import com.stvault.app.model.CharacterRecord
import com.stvault.app.model.PresetRecord
import com.stvault.app.model.WorldBookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResourceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResourceDetailBinding
    private lateinit var libRepo: ResourceLibraryRepository
    private lateinit var vaultRepo: VaultRepository
    private lateinit var catManager: CategoryManager
    private lateinit var detailAdapter: DetailItemsAdapter

    private val prefs by lazy { getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE) }
    private var editorialStyle: Boolean = true

    private var resType: String = ""
    private var resId: String = ""

    private var pendingExportFile: File? = null
    private var pendingExportBytes: ByteArray? = null

    private var currentCharacter: CharacterRecord? = null
    private var currentParsedChar: ParsedCharacterCard? = null
    private var currentWorldBook: WorldBookRecord? = null
    private var currentParsedWb: ParsedWorldBook? = null
    private var currentPreset: PresetRecord? = null
    private var currentParsedPreset: ParsedPreset? = null

    private var activeTabKey: String = ""
    private var detailSearchQuery: String = ""
    private var searchDebounceJob: Job? = null
    private val collapsedPresetGroups = HashSet<String>()

    private val codeEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                reloadById()
            }
        }

    private val exportDocLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri == null) return@registerForActivityResult
            val bytes = pendingExportBytes
            val file = pendingExportFile
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            if (bytes != null) {
                                out.write(bytes)
                            } else if (file != null && file.exists()) {
                                file.inputStream().use { inp -> inp.copyTo(out) }
                            } else {
                                return@withContext false
                            }
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                pendingExportBytes = null
                pendingExportFile = null
                toast(if (ok) "导出成功" else "导出失败")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResourceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libRepo = ResourceLibraryRepository.getInstance(this)
        vaultRepo = VaultRepository.getInstance(this)
        catManager = CategoryManager.getInstance(this)
        editorialStyle = (prefs.getString("detail_ui_style", "editorial") ?: "editorial") == "editorial"

        resType = intent.getStringExtra(EXTRA_TYPE) ?: ""
        resId = intent.getStringExtra(EXTRA_ID) ?: ""

        detailAdapter = DetailItemsAdapter(editorialStyle) { raw, onDone ->
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    com.stvault.app.core.TextTranslator.translateToZh(raw)
                }
                res.onSuccess { translated ->
                    onDone(translated)
                }.onFailure { err ->
                    toast("翻译失败：${err.message ?: "请稍后重试"}")
                    onDone(null)
                }
            }
        }
        binding.rvDetail.layoutManager = LinearLayoutManager(this)
        binding.rvDetail.setHasFixedSize(false)
        binding.rvDetail.adapter = detailAdapter

        binding.btnDetailClose.setOnClickListener { finish() }
        binding.btnDetailExport.setOnClickListener { exportCurrentResource() }
        binding.btnDetailMore.setOnClickListener { showMoreActionsMenu() }

        fun updateScrollJumpVisibility() {
            val count = binding.rvDetail.adapter?.itemCount ?: 0
            binding.boxDetailScrollJump.visibility = if (count >= 4) View.VISIBLE else View.GONE
        }
        binding.rvDetail.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollJumpVisibility()
            }
        })
        detailAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateScrollJumpVisibility()
        })
        binding.btnDetailScrollTop.setOnClickListener {
            binding.rvDetail.scrollToPosition(0)
        }
        binding.btnDetailScrollBottom.setOnClickListener {
            val last = (binding.rvDetail.adapter?.itemCount ?: 0) - 1
            if (last >= 0) binding.rvDetail.scrollToPosition(last)
        }

        binding.etDetailSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                detailSearchQuery = s?.toString()?.trim().orEmpty()
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(150L)
                    rebuildCurrentList()
                }
            }
        })

        reloadById()
    }

    private fun reloadById() {
        when (resType) {
            TYPE_CHARACTER -> loadCharacter()
            TYPE_WORLDBOOK -> loadWorldBook()
            TYPE_PRESET -> loadPreset()
            else -> finish()
        }
    }

    private fun exportCurrentResource() {
        pendingExportBytes = null
        when (resType) {
            TYPE_CHARACTER -> currentCharacter?.let {
                pendingExportFile = libRepo.getCharacterFile(it)
                exportDocLauncher.launch(it.fileName)
            }
            TYPE_WORLDBOOK -> currentWorldBook?.let {
                pendingExportFile = libRepo.getWorldBookFile(it)
                exportDocLauncher.launch(it.fileName)
            }
            TYPE_PRESET -> currentPreset?.let {
                pendingExportFile = libRepo.getPresetFile(it)
                exportDocLauncher.launch(it.fileName)
            }
        }
    }

    private fun showMoreActionsMenu() {
        val actions = mutableListOf<SheetAction>()
        val editLabel = when (resType) {
            TYPE_CHARACTER -> "编辑内容"
            TYPE_WORLDBOOK -> "编辑条目"
            TYPE_PRESET -> "编辑提示词"
            else -> "编辑代码"
        }
        actions.add(SheetAction(editLabel, "支持可视化分段编辑或切换 JSON 源码") { openCodeEditor() })
        if (resType == TYPE_CHARACTER) {
            currentCharacter?.let { c ->
                actions.add(SheetAction("分类标签", "多选 1~3 级分类") { showCharacterCategoryDialog(c) })
                if (c.urls.isNotEmpty()) {
                    actions.add(SheetAction("图床换链复活", "保留原 PNG 无损替换失效链接") { runCharacterRepair(c) })
                }
            }
        } else if (resType == TYPE_WORLDBOOK) {
            currentWorldBook?.let { w ->
                actions.add(SheetAction("分类标签", "多选 1~3 级分类") { showWorldBookCategoryDialog(w) })
            }
        }
        actions.add(SheetAction("修改版本号", "自定义当前版本的版本标签") { showEditVersionTagDialog() })
        actions.add(SheetAction("删除当前版本", destructive = true) { confirmDelete() })
        VaultSheets.actionList(this, actions = actions)
    }

    private fun openCodeEditor() {
        val (mod, title) = when (resType) {
            TYPE_CHARACTER -> CodeEditorActivity.MODULE_CHARACTER to (currentCharacter?.name ?: "角色卡")
            TYPE_WORLDBOOK -> CodeEditorActivity.MODULE_WORLDBOOK to (currentWorldBook?.name ?: "世界书")
            TYPE_PRESET -> CodeEditorActivity.MODULE_PRESET to (currentPreset?.name ?: "预设")
            else -> return
        }
        codeEditorLauncher.launch(CodeEditorActivity.createIntent(this, mod, resId, title))
    }

    private fun openSingleItemEditor(
        module: String,
        title: String,
        itemType: String,
        itemKey: String = "",
        itemIndex: Int = -1
    ) {
        codeEditorLauncher.launch(
            CodeEditorActivity.createSingleItemIntent(this, module, resId, title, itemType, itemKey, itemIndex)
        )
    }

    private fun showEditVersionTagDialog() {
        val currentTag = when (resType) {
            TYPE_CHARACTER -> currentCharacter?.versionTag.orEmpty()
            TYPE_WORLDBOOK -> currentWorldBook?.versionTag.orEmpty()
            TYPE_PRESET -> currentPreset?.versionTag.orEmpty()
            else -> ""
        }
        VaultSheets.input(
            ctx = this,
            title = "自定义版本号",
            message = "例如 v2.0、精修版、自改版（留空则按默认序号显示）",
            hint = "输入版本号",
            initialValue = currentTag,
            confirmText = "保存"
        ) { newTag ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    when (resType) {
                        TYPE_CHARACTER -> libRepo.updateCharacterVersionTag(resId, newTag)
                        TYPE_WORLDBOOK -> libRepo.updateWorldBookVersionTag(resId, newTag)
                        TYPE_PRESET -> libRepo.updatePresetVersionTag(resId, newTag)
                        else -> Unit
                    }
                }
                toast("版本号已更新")
                reloadById()
            }
        }
    }

    private fun showCharacterCategoryDialog(c: CharacterRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_CHARACTERS)
        if (allCats.isEmpty()) {
            VaultSheets.input(
                ctx = this,
                title = "新建角色卡分类（1~3级）",
                message = "用斜杠 / 分隔层级，例如 古风/仙侠/师尊",
                hint = "例如：古风/仙侠",
                confirmText = "添加"
            ) { raw ->
                val added = catManager.addCategory(CategoryManager.SCOPE_CHARACTERS, raw)
                if (added != null) showCharacterCategoryDialog(c)
            }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "选择分类（可多选）",
            items = items,
            preselectedIds = c.categories.toSet(),
            headerAction = "+ 新建" to {
                VaultSheets.input(this, "新建角色卡分类", "用 / 分隔 1~3 级", "例如：古风/仙侠", confirmText = "添加") { raw ->
                    if (catManager.addCategory(CategoryManager.SCOPE_CHARACTERS, raw) != null) {
                        showCharacterCategoryDialog(c)
                    }
                }
            }
        ) { picked ->
            libRepo.setCharacterCategories(c.id, picked)
            currentCharacter = c.copy(categories = picked)
            toast("分类已更新")
            rebuildCurrentList()
        }
    }

    private fun showWorldBookCategoryDialog(w: WorldBookRecord) {
        val allCats = catManager.getCategories(CategoryManager.SCOPE_WORLDBOOKS)
        if (allCats.isEmpty()) {
            VaultSheets.input(
                ctx = this,
                title = "新建世界书分类（1~3级）",
                message = "用斜杠 / 分隔层级，例如 世界观/奇幻",
                hint = "例如：世界观/奇幻",
                confirmText = "添加"
            ) { raw ->
                val added = catManager.addCategory(CategoryManager.SCOPE_WORLDBOOKS, raw)
                if (added != null) showWorldBookCategoryDialog(w)
            }
            return
        }
        val items = allCats.map { PickerItem(it, catManager.formatIndentedLabel(it)) }
        VaultSheets.multiSelect(
            ctx = this,
            title = "选择分类（可多选）",
            items = items,
            preselectedIds = w.categories.toSet(),
            headerAction = "+ 新建" to {
                VaultSheets.input(this, "新建世界书分类", "用 / 分隔 1~3 级", "例如：世界观/奇幻", confirmText = "添加") { raw ->
                    if (catManager.addCategory(CategoryManager.SCOPE_WORLDBOOKS, raw) != null) {
                        showWorldBookCategoryDialog(w)
                    }
                }
            }
        ) { picked ->
            libRepo.setWorldBookCategories(w.id, picked)
            currentWorldBook = w.copy(categories = picked)
            setResult(RESULT_OK)
            toast("分类已更新")
            rebuildCurrentList()
        }
    }

    private fun confirmDelete() {
        VaultSheets.confirm(
            ctx = this,
            title = "删除当前版本",
            message = "确定从库中删除当前版本？将同时清理仅由其引用的独享图床缓存。",
            confirmText = "删除",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    when (resType) {
                        TYPE_CHARACTER -> libRepo.deleteCharacter(resId)
                        TYPE_WORLDBOOK -> libRepo.deleteWorldBook(resId)
                        TYPE_PRESET -> libRepo.deletePreset(resId)
                        else -> Unit
                    }
                }
                toast("已删除")
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    // ==================== VERSION SWITCHER ====================

    private fun setupVersionSwitcher(
        currentId: String,
        versions: List<Triple<String, String, String>> // (id, versionLabel, fileName)
    ) {
        if (versions.size <= 1) {
            binding.btnDetailVersion.visibility = View.GONE
            binding.btnDetailDiff.visibility = View.GONE
            return
        }
        val cur = versions.firstOrNull { it.first == currentId } ?: versions.first()
        binding.btnDetailVersion.visibility = View.VISIBLE
        binding.btnDetailVersion.text = "${cur.second} ▾"
        binding.btnDetailDiff.visibility = View.VISIBLE
        binding.btnDetailDiff.setOnClickListener {
            val sKey = when (resType) {
                TYPE_CHARACTER -> currentCharacter?.seriesKey.orEmpty()
                TYPE_WORLDBOOK -> currentWorldBook?.seriesKey.orEmpty()
                TYPE_PRESET -> currentPreset?.seriesKey.orEmpty()
                else -> ""
            }
            if (sKey.isNotEmpty()) {
                val otherId = versions.firstOrNull { it.first != currentId }?.first.orEmpty()
                VersionDiffActivity.open(this, resType, sKey, currentId, otherId)
            }
        }
        binding.btnDetailVersion.setOnClickListener {
            val sKey = when (resType) {
                TYPE_CHARACTER -> currentCharacter?.seriesKey.orEmpty()
                TYPE_WORLDBOOK -> currentWorldBook?.seriesKey.orEmpty()
                TYPE_PRESET -> currentPreset?.seriesKey.orEmpty()
                else -> ""
            }
            val actions = versions.map { (id, ver, fname) ->
                SheetAction(
                    label = ver,
                    subtitle = fname,
                    checked = (id == currentId)
                ) {
                    if (sKey.isNotEmpty()) {
                        com.stvault.app.core.VersionGrouper.setPreferredVersion(this, resType, sKey, id)
                    }
                    if (id != resId) {
                        resId = id
                        reloadById()
                    }
                }
            } + SheetAction("修改当前版本号", "自定义当前版本标签") {
                showEditVersionTagDialog()
            }
            VaultSheets.actionList(this, title = "切换版本 (${versions.size})", actions = actions)
        }
    }

    // ==================== CHARACTER DETAIL ====================

    private fun loadCharacter() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val rec = libRepo.getCharacterById(resId) ?: return@withContext null
                val parsed = try { libRepo.parseCharacterDetail(rec) } catch (_: Throwable) { null }
                val versions = libRepo.getVersionsForCharacter(rec.seriesKey)
                Triple(rec, parsed, versions)
            }
            if (data == null) {
                toast("找不到该角色卡")
                finish()
                return@launch
            }
            val (rec, parsed, versions) = data
            currentCharacter = rec
            currentParsedChar = parsed
            binding.tvDetailTitle.text = if (versions.size > 1) {
                rec.seriesName.ifBlank { com.stvault.app.core.VersionGrouper.extractDisplaySeriesName(rec.name) }
            } else rec.name

            setupVersionSwitcher(
                rec.id,
                versions.mapIndexed { i, v ->
                    val label = v.versionTag.ifBlank { "版本 ${versions.size - i}" }
                    Triple(v.id, label, v.fileName)
                }
            )

            val tabs = mutableListOf(
                "overview" to "概览",
                "greetings" to "开场白 (${1 + (parsed?.alternateGreetings?.size ?: 0)})"
            )
            if (parsed?.embeddedWorldBook != null) {
                tabs.add("book" to "世界书 (${parsed.embeddedWorldBook.entries.size})")
            }
            val linkedComps = withContext(Dispatchers.IO) { libRepo.getComponentsLinkedTo("character", rec.id) }
            val totalScripts = (parsed?.embeddedRegexScripts?.size ?: 0) + (parsed?.embeddedHelperScripts?.size ?: 0) + linkedComps.size
            if (totalScripts > 0) tabs.add("scripts" to "脚本 ($totalScripts)")
            if (rec.urls.isNotEmpty()) tabs.add("urls" to "图床 (${rec.urls.size})")

            activeTabKey = "overview"
            setupHeaderTabs(tabs, activeTabKey) { key ->
                activeTabKey = key
                binding.detailSearchBox.visibility = if (key == "book") View.VISIBLE else View.GONE
                rebuildCurrentList()
            }
            binding.detailSearchBox.visibility = View.GONE
            rebuildCurrentList()
        }
    }

    private fun runCharacterRepair(rec: CharacterRecord) {
        fun exec(mode: String) {
            toast("正在处理角色卡图床...")
            lifecycleScope.launch {
                try {
                    val (fileName, bytes, res) = withContext(Dispatchers.IO) {
                        libRepo.repairCharacterCard(rec.id, mode)
                    }
                    toast("已替换 ${res.matchedCount}/${res.totalUrls} 张，请选择保存位置")
                    pendingExportFile = null
                    pendingExportBytes = bytes
                    exportDocLauncher.launch(fileName)
                } catch (e: Exception) {
                    toast("复活失败：${e.message}")
                }
            }
        }
        VaultSheets.actionList(
            ctx = this,
            title = "角色卡图床复活",
            actions = listOf(
                SheetAction("上传图床换链", "保留原 PNG 封面无损替换链接") { exec("catbox") },
                SheetAction("WebP 压缩内嵌 Base64", "离线内嵌，不依赖外部图床") { exec("base64_webp") }
            )
        )
    }

    // ==================== WORLD BOOK DETAIL ====================

    private fun loadWorldBook() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val rec = libRepo.getWorldBookById(resId) ?: return@withContext null
                val parsed = try { libRepo.parseWorldBookDetail(rec) } catch (_: Throwable) { null }
                val versions = libRepo.getVersionsForWorldBook(rec.seriesKey)
                Triple(rec, parsed, versions)
            }
            if (data == null) {
                toast("找不到该世界书")
                finish()
                return@launch
            }
            val (rec, parsed, versions) = data
            currentWorldBook = rec
            currentParsedWb = parsed
            binding.tvDetailTitle.text = if (versions.size > 1) {
                rec.seriesName.ifBlank { com.stvault.app.core.VersionGrouper.extractDisplaySeriesName(rec.name) }
            } else rec.name

            setupVersionSwitcher(
                rec.id,
                versions.mapIndexed { i, v ->
                    val label = v.versionTag.ifBlank { "版本 ${versions.size - i}" }
                    Triple(v.id, label, v.fileName)
                }
            )

            val entries = parsed?.entries ?: emptyList()
            val tabs = listOf(
                "all" to "全部 (${entries.size})",
                "constant" to "常驻 (${entries.count { it.constant }})",
                "selective" to "触发 (${entries.count { !it.constant && it.enabled }})",
                "disabled" to "禁用 (${entries.count { !it.enabled }})"
            )
            activeTabKey = "all"
            setupHeaderTabs(tabs, activeTabKey) { key ->
                activeTabKey = key
                rebuildCurrentList()
            }
            binding.detailSearchBox.visibility = View.VISIBLE
            binding.etDetailSearch.hint = "搜索条目名称、关键词或正文..."
            rebuildCurrentList()
        }
    }

    // ==================== PRESET DETAIL ====================

    private fun loadPreset() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val rec = libRepo.getPresetById(resId) ?: return@withContext null
                val parsed = try { libRepo.parsePresetDetail(rec) } catch (_: Throwable) { null }
                val versions = libRepo.getVersionsForPreset(rec.seriesKey)
                val linked = libRepo.getComponentsLinkedTo("preset", rec.id)
                listOf(rec, parsed, versions, linked)
            }
            if (data == null) {
                toast("找不到该预设")
                finish()
                return@launch
            }
            val rec = data[0] as PresetRecord
            val parsed = data[1] as? ParsedPreset
            @Suppress("UNCHECKED_CAST")
            val versions = data[2] as List<PresetRecord>
            @Suppress("UNCHECKED_CAST")
            val linked = data[3] as List<com.stvault.app.model.ComponentRecord>

            currentPreset = rec
            currentParsedPreset = parsed
            collapsedPresetGroups.clear()
            parsed?.promptGroups?.forEach { g ->
                if (g.collapsed) collapsedPresetGroups.add(g.id)
            }
            binding.tvDetailTitle.text = if (versions.size > 1) {
                rec.seriesName.ifBlank { com.stvault.app.core.VersionGrouper.extractDisplaySeriesName(rec.name) }
            } else rec.name

            setupVersionSwitcher(
                rec.id,
                versions.mapIndexed { i, v ->
                    val label = v.versionTag.ifBlank { "版本 ${versions.size - i}" }
                    Triple(v.id, label, v.fileName)
                }
            )

            val linkedCount = parsed?.prompts?.count { it.linked } ?: 0
            val unlinkedCount = parsed?.prompts?.count { !it.linked } ?: 0
            val sentCount = parsed?.let { computeSentPrompts(it).size } ?: 0
            val scriptCount = (parsed?.embeddedRegexScripts?.size ?: 0) +
                (parsed?.embeddedHelperScripts?.size ?: 0) + linked.size
            val tabs = mutableListOf(
                "prompts" to "提示词 ($linkedCount)",
                "sent" to "发送 ($sentCount)"
            )
            if (unlinkedCount > 0) {
                tabs.add("unlinked" to "未连接 ($unlinkedCount)")
            }
            if (scriptCount > 0) {
                tabs.add("scripts" to "脚本 ($scriptCount)")
            }
            activeTabKey = "prompts"
            setupHeaderTabs(tabs, activeTabKey) { key ->
                activeTabKey = key
                binding.detailSearchBox.visibility = if (key == "prompts" || key == "sent" || key == "unlinked") View.VISIBLE else View.GONE
                rebuildCurrentList()
            }
            binding.detailSearchBox.visibility = View.VISIBLE
            binding.etDetailSearch.hint = "搜索提示词名称或正文..."
            rebuildCurrentList()
        }
    }

    // ==================== VIRTUALIZED LIST BUILDER (OFF MAIN THREAD) ====================

    private fun rebuildCurrentList() {
        val q = detailSearchQuery.lowercase()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                when (resType) {
                    TYPE_CHARACTER -> buildCharacterItems(q)
                    TYPE_WORLDBOOK -> buildWorldBookItems(q)
                    TYPE_PRESET -> buildPresetItems(q)
                    else -> emptyList()
                }
            }
            detailAdapter.submitList(items)
        }
    }

    private fun buildCharacterItems(q: String): List<DetailItem> {
        val rec = currentCharacter ?: return emptyList()
        val parsed = currentParsedChar
        val out = mutableListOf<DetailItem>()

        if (activeTabKey == "overview") {
            val coverFile = if (rec.isPng) libRepo.getCharacterFile(rec).takeIf { it.exists() } else null
            val sub = buildString {
                append(rec.spec.uppercase())
                if (rec.versionTag.isNotEmpty()) append(" · ${rec.versionTag}")
                append(" · ${formatBytes(rec.fileSize)}")
                if (rec.categories.isNotEmpty()) {
                    append("\n分类：${rec.categories.joinToString(" / ")}")
                }
            }
            out.add(DetailItem.CharHeader(rec.name, sub, rec.tags.take(6), coverFile))
            if (parsed != null) {
                fun editCharField(label: String, key: String) = {
                    openSingleItemEditor(CodeEditorActivity.MODULE_CHARACTER, label, CodeEditorActivity.ITEM_CHAR_FIELD, itemKey = key)
                }
                if (parsed.description.isNotBlank()) out.add(DetailItem.TextBlock("角色设定", parsed.description, false, onEdit = editCharField("角色设定", "description")))
                if (parsed.personality.isNotBlank()) out.add(DetailItem.TextBlock("性格摘要", parsed.personality, false, onEdit = editCharField("性格摘要", "personality")))
                if (parsed.scenario.isNotBlank()) out.add(DetailItem.TextBlock("情景设定", parsed.scenario, false, onEdit = editCharField("情景设定", "scenario")))
                if (parsed.creatorNotes.isNotBlank()) out.add(DetailItem.TextBlock("说明备注", parsed.creatorNotes, false, onEdit = editCharField("说明备注", "creator_notes")))
                if (parsed.systemPrompt.isNotBlank()) out.add(DetailItem.TextBlock("系统提示词", parsed.systemPrompt, false, onEdit = editCharField("系统提示词", "system_prompt")))
                if (parsed.mesExample.isNotBlank()) out.add(DetailItem.TextBlock("对话示例", parsed.mesExample, false, onEdit = editCharField("对话示例", "mes_example")))
            }
        } else if (activeTabKey == "greetings" && parsed != null) {
            val allGreetings = listOf("默认开场白" to parsed.firstMes) +
                parsed.alternateGreetings.mapIndexed { i, g -> "备用开场白 #${i + 1}" to g }
            val rules = parsed.embeddedRegexScripts.map {
                Triple(it.findRegex, it.replaceString, it.disabled)
            }
            out.add(
                DetailItem.ActionStrip(
                    leftText = "共 ${allGreetings.size} 条开场白",
                    actionLabel = "酒馆渲染预览"
                ) {
                    ComponentPreviewActivity.openCharacterGreeting(
                        this, rec.id, rec.name, parsed.firstMes, rules, allGreetings, 0
                    )
                }
            )
            out.add(
                DetailItem.TextBlock(
                    title = "默认开场白",
                    body = parsed.firstMes.ifBlank { "（空）" },
                    compact = false,
                    defaultPreviewChars = GREETING_PREVIEW_CHARS,
                    onPreview = {
                        ComponentPreviewActivity.openCharacterGreeting(
                            this, rec.id, rec.name, parsed.firstMes, rules, allGreetings, 0
                        )
                    },
                    onEdit = {
                        openSingleItemEditor(
                            CodeEditorActivity.MODULE_CHARACTER, "默认开场白",
                            CodeEditorActivity.ITEM_CHAR_FIELD, itemKey = "first_mes"
                        )
                    }
                )
            )
            parsed.alternateGreetings.forEachIndexed { idx, g ->
                out.add(
                    DetailItem.TextBlock(
                        title = "备用开场白 #${idx + 1}",
                        body = g,
                        compact = false,
                        defaultPreviewChars = GREETING_PREVIEW_CHARS,
                        onPreview = {
                            ComponentPreviewActivity.openCharacterGreeting(
                                this, rec.id, rec.name, g, rules, allGreetings, idx + 1
                            )
                        },
                        onEdit = {
                            openSingleItemEditor(
                                CodeEditorActivity.MODULE_CHARACTER, "备用开场白 #${idx + 1}",
                                CodeEditorActivity.ITEM_CHAR_ALT_GREETING, itemIndex = idx
                            )
                        }
                    )
                )
            }
        } else if (activeTabKey == "book" && parsed?.embeddedWorldBook != null) {
            val book = parsed.embeddedWorldBook
            out.add(
                DetailItem.ActionStrip(
                    leftText = "${book.name} · ${book.entries.size} 条目",
                    actionLabel = "抽离为独立世界书"
                ) {
                    lifecycleScope.launch {
                        val (_, msg) = withContext(Dispatchers.IO) {
                            libRepo.extractEmbeddedWorldBook(rec.id)
                        }
                        toast(msg)
                    }
                }
            )
            val filtered = filterWorldEntries(book.entries, "all", q)
            out.addAll(filtered.map { e ->
                toEntryItem(e) {
                    openSingleItemEditor(
                        CodeEditorActivity.MODULE_CHARACTER,
                        e.title.ifBlank { "内置世界书条目" },
                        CodeEditorActivity.ITEM_CHAR_WB_ENTRY,
                        itemKey = e.title,
                        itemIndex = e.uid
                    )
                }
            })
        } else if (activeTabKey == "scripts" && parsed != null) {
            if (parsed.embeddedRegexScripts.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("内嵌正则 (${parsed.embeddedRegexScripts.size})"))
                for (r in parsed.embeddedRegexScripts) {
                    val dot = if (r.disabled) DOT_GRAY else DOT_GREEN
                    out.add(
                        DetailItem.CollapsibleEntry(
                            title = r.name,
                            subtitle = "正则 · ${r.findRegex.take(60)}",
                            body = "查找正则：\n${r.findRegex}\n\n替换内容：\n${r.replaceString}",
                            dotColor = dot,
                            onPreview = {
                                ComponentPreviewActivity.openRegex(
                                    this, r.name, r.findRegex, r.replaceString, r.rawJson, parsed.firstMes
                                )
                            }
                        )
                    )
                }
            }
            if (parsed.embeddedHelperScripts.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("内嵌酒馆助手脚本 (${parsed.embeddedHelperScripts.size})"))
                for (h in parsed.embeddedHelperScripts) {
                    val dot = if (h.enabled) DOT_GREEN else DOT_GRAY
                    val body = buildString {
                        if (h.info.isNotBlank()) append("${h.info}\n\n")
                        append(h.content)
                    }
                    out.add(
                        DetailItem.CollapsibleEntry(
                            title = h.name,
                            subtitle = h.info.ifBlank { "酒馆助手脚本" },
                            body = body,
                            dotColor = dot,
                            onPreview = {
                                ComponentPreviewActivity.openHelper(this, h.name, h.content, h.rawJson)
                            }
                        )
                    )
                }
            }
            val linked = libRepo.getComponentsLinkedTo("character", rec.id)
            if (linked.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("已关联外部组件 (${linked.size})"))
                for (c in linked) {
                    out.add(DetailItem.TextBlock(c.name, c.summary, true))
                }
            }
        } else if (activeTabKey == "urls") {
            val records = vaultRepo.getUrlRecordsForUrls(rec.urls)
            for (u in records) {
                val status = when (u.status) {
                    CacheStatus.OK -> "已缓存 · ${formatBytes(u.size)}"
                    CacheStatus.ERROR -> "失败 · ${u.error}"
                    CacheStatus.IGNORED -> "已忽略"
                    CacheStatus.PENDING -> "待缓存"
                }
                out.add(DetailItem.TextBlock(status, u.url, true))
            }
        }
        return out
    }

    private fun buildWorldBookItems(q: String): List<DetailItem> {
        val rec = currentWorldBook ?: return emptyList()
        val parsed = currentParsedWb ?: return emptyList()
        val out = mutableListOf<DetailItem>()

        val approxTokens = (rec.totalChars / 2.2).toInt()
        val tokStr = if (approxTokens >= 1000) String.format("%.1fk tokens", approxTokens / 1000.0) else "$approxTokens tokens"
        val meta = buildString {
            append("${rec.entryCount} 条目 · 常驻 ${rec.constantCount} · 约 $tokStr")
            if (rec.originCardName.isNotEmpty()) append(" · 来自 ${rec.originCardName}")
            if (rec.categories.isNotEmpty()) append("\n分类：${rec.categories.joinToString(" / ")}")
        }
        out.add(DetailItem.CompactSummary(meta))

        val filtered = filterWorldEntries(parsed.entries, activeTabKey, q)
        if (filtered.isEmpty()) {
            out.add(DetailItem.CompactSummary("没有匹配的条目"))
        } else {
            out.addAll(filtered.map { e ->
                toEntryItem(e) {
                    openSingleItemEditor(
                        CodeEditorActivity.MODULE_WORLDBOOK,
                        e.title.ifBlank { "世界书条目" },
                        CodeEditorActivity.ITEM_WB_ENTRY,
                        itemKey = e.title,
                        itemIndex = e.uid
                    )
                }
            })
        }
        return out
    }

    private fun buildPresetItems(q: String): List<DetailItem> {
        val rec = currentPreset ?: return emptyList()
        val parsed = currentParsedPreset ?: return emptyList()
        val out = mutableListOf<DetailItem>()

        val params = listOfNotNull(
            parsed.temperature?.let { "Temp" to String.format("%.2f", it) },
            parsed.topP?.let { "Top-P" to String.format("%.2f", it) },
            parsed.maxTokens?.let { "MaxTok" to it.toString() },
            parsed.maxContext?.let { "Context" to it.toString() }
        )
        if (params.isNotEmpty()) {
            out.add(DetailItem.ParamRow(params))
        }

        if (activeTabKey == "prompts") {
            val linkedPrompts = parsed.prompts.filter { it.linked }
            val groupById = parsed.promptGroups.associateBy { it.id }
            val hasGroups = groupById.isNotEmpty() && parsed.promptGroupMap.isNotEmpty()
            if (hasGroups) {
                out.add(DetailItem.CompactSummary("已连接 ${linkedPrompts.size} 条 · 分组 ${parsed.promptGroups.size} 组"))
                val membersByGroup = LinkedHashMap<String, MutableList<Pair<Int, com.stvault.app.core.ParsedPresetPrompt>>>()
                linkedPrompts.forEachIndexed { idx, p ->
                    val gid = parsed.promptGroupMap[p.identifier]
                    if (gid != null && gid in groupById) {
                        membersByGroup.getOrPut(gid) { mutableListOf() }.add(idx to p)
                    }
                }
                val emittedGroups = HashSet<String>()
                linkedPrompts.forEachIndexed { idx, p ->
                    val gid = parsed.promptGroupMap[p.identifier]
                    if (gid != null && gid in groupById) {
                        if (!emittedGroups.add(gid)) return@forEachIndexed
                        val grp = groupById[gid]!!
                        val allMembers = membersByGroup[gid].orEmpty()
                        val matchedMembers = if (q.isEmpty()) allMembers else allMembers.filter { (_, mp) ->
                            grp.name.lowercase().contains(q) ||
                                mp.name.lowercase().contains(q) ||
                                mp.content.lowercase().contains(q)
                        }
                        if (matchedMembers.isEmpty()) return@forEachIndexed
                        val collapsed = q.isEmpty() && (gid in collapsedPresetGroups)
                        val enabledCount = allMembers.count { it.second.enabled }
                        out.add(
                            DetailItem.GroupHeader(
                                title = grp.name,
                                subtitle = "${enabledCount}/${allMembers.size} 启用" + if (!grp.enabled) " · 组已禁用" else "",
                                enabled = grp.enabled,
                                collapsed = collapsed
                            ) {
                                if (gid in collapsedPresetGroups) collapsedPresetGroups.remove(gid)
                                else collapsedPresetGroups.add(gid)
                                rebuildCurrentList()
                            }
                        )
                        if (!collapsed) {
                            for ((mIdx, mp) in matchedMembers) {
                                out.add(buildPresetPromptItem(mIdx, mp, groupEnabled = grp.enabled))
                            }
                        }
                    } else {
                        if (q.isNotEmpty() && !p.name.lowercase().contains(q) && !p.content.lowercase().contains(q)) {
                            return@forEachIndexed
                        }
                        out.add(buildPresetPromptItem(idx, p, groupEnabled = true))
                    }
                }
            } else {
                val list = if (q.isEmpty()) linkedPrompts else linkedPrompts.filter {
                    it.name.lowercase().contains(q) || it.content.lowercase().contains(q)
                }
                list.forEachIndexed { idx, p ->
                    out.add(buildPresetPromptItem(idx, p, groupEnabled = true))
                }
            }
        } else if (activeTabKey == "unlinked") {
            val unlinkedPrompts = parsed.prompts.filter { !it.linked }
            out.add(DetailItem.CompactSummary("未连接条目（${unlinkedPrompts.size} 条）不在酒馆发送队列中，不会随对话发送"))
            val list = if (q.isEmpty()) unlinkedPrompts else unlinkedPrompts.filter {
                it.name.lowercase().contains(q) || it.content.lowercase().contains(q)
            }
            if (list.isEmpty()) {
                out.add(DetailItem.CompactSummary("没有匹配的未连接条目"))
            } else {
                list.forEachIndexed { idx, p ->
                    val editCb: (() -> Unit)? = if (!p.isMarker) {
                        {
                            openSingleItemEditor(
                                CodeEditorActivity.MODULE_PRESET,
                                p.name.ifBlank { "未连接条目 #${idx + 1}" },
                                CodeEditorActivity.ITEM_PRESET_PROMPT,
                                itemKey = p.identifier,
                                itemIndex = idx
                            )
                        }
                    } else null
                    out.add(
                        DetailItem.CollapsibleEntry(
                            title = p.name.ifBlank { p.identifier },
                            subtitle = "未连接 · ${p.role} · ${p.content.length} 字",
                            body = p.content.ifBlank { "（空内容）" },
                            dotColor = DOT_GRAY,
                            dimmed = true,
                            onEdit = editCb,
                            entryKey = p.identifier
                        )
                    )
                }
            }
        } else if (activeTabKey == "sent") {
            val sent = computeSentPrompts(parsed)
            val localVars = HashMap<String, String>()
            val globalVars = HashMap<String, String>()
            val renderedList = mutableListOf<Triple<com.stvault.app.core.ParsedPresetPrompt, String, Boolean>>()
            var silentVarEntries = 0
            for (p in sent) {
                if (p.isMarker) {
                    renderedList.add(Triple(p, markerLabel(p.identifier), true))
                } else {
                    val evaluated = evaluateStMacros(p.content, localVars, globalVars)
                    if (evaluated.isBlank()) {
                        silentVarEntries++
                    } else {
                        renderedList.add(Triple(p, evaluated, false))
                    }
                }
            }
            val totalChars = renderedList.filter { !it.third }.sumOf { it.second.length }
            val approxTokens = (totalChars / 2.2).toInt()
            val tokStr = if (approxTokens >= 1000) String.format("%.1fk", approxTokens / 1000.0) else "$approxTokens"
            val summaryNote = buildString {
                append("模拟实际发送 · 有效 ${renderedList.size} 段 · 约 $totalChars 字（≈ $tokStr tokens）")
                if (silentVarEntries > 0) append(" · 已求值 $silentVarEntries 个变量/注释条目")
            }
            out.add(DetailItem.CompactSummary(summaryNote))

            val filtered = if (q.isEmpty()) renderedList else renderedList.filter { (p, txt, _) ->
                p.name.lowercase().contains(q) || txt.lowercase().contains(q)
            }
            if (filtered.isEmpty()) {
                out.add(DetailItem.CompactSummary("没有匹配的发送内容"))
            } else {
                filtered.forEachIndexed { idx, (p, evaluatedText, isMarker) ->
                    out.add(
                        DetailItem.SentSegment(
                            index = idx + 1,
                            identifier = p.identifier,
                            role = if (isMarker) "INJECT" else p.role.uppercase(),
                            sourceName = if (isMarker) markerLabel(p.identifier) else p.name.ifBlank { p.identifier },
                            content = evaluatedText,
                            isMarker = isMarker,
                            onJumpToEntry = { jumpToPresetPrompt(p.identifier) },
                            onEdit = if (!isMarker) {
                                {
                                    openSingleItemEditor(
                                        CodeEditorActivity.MODULE_PRESET,
                                        p.name.ifBlank { "提示词" },
                                        CodeEditorActivity.ITEM_PRESET_PROMPT,
                                        itemKey = p.identifier,
                                        itemIndex = idx
                                    )
                                }
                            } else null
                        )
                    )
                }
            }
        } else {
            if (parsed.embeddedRegexScripts.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("预设自带正则 (${parsed.embeddedRegexScripts.size})"))
                for (r in parsed.embeddedRegexScripts) {
                    val dot = if (r.disabled) DOT_GRAY else DOT_GREEN
                    out.add(
                        DetailItem.CollapsibleEntry(
                            title = r.name,
                            subtitle = r.findRegex.take(70),
                            body = "查找正则：\n${r.findRegex}\n\n替换内容：\n${r.replaceString}",
                            dotColor = dot,
                            onPreview = {
                                ComponentPreviewActivity.openRegex(this, r.name, r.findRegex, r.replaceString, r.rawJson)
                            }
                        )
                    )
                }
            }
            if (parsed.embeddedHelperScripts.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("预设自带酒馆助手脚本 (${parsed.embeddedHelperScripts.size})"))
                for (h in parsed.embeddedHelperScripts) {
                    val dot = if (h.enabled) DOT_GREEN else DOT_GRAY
                    val body = buildString {
                        if (h.info.isNotBlank()) append("${h.info}\n\n")
                        append(h.content)
                    }
                    out.add(
                        DetailItem.CollapsibleEntry(
                            title = h.name,
                            subtitle = h.info.ifBlank { "酒馆助手脚本" },
                            body = body,
                            dotColor = dot,
                            onPreview = {
                                ComponentPreviewActivity.openHelper(this, h.name, h.content, h.rawJson)
                            }
                        )
                    )
                }
            }
            val linked = libRepo.getComponentsLinkedTo("preset", rec.id)
            if (linked.isNotEmpty()) {
                out.add(DetailItem.SectionTitle("外部关联组件 (${linked.size})"))
                for (c in linked) {
                    out.add(DetailItem.TextBlock(c.name, c.summary, true))
                }
            }
        }
        return out
    }

    private fun buildPresetPromptItem(
        idx: Int,
        p: com.stvault.app.core.ParsedPresetPrompt,
        groupEnabled: Boolean
    ): DetailItem.CollapsibleEntry {
        val active = p.enabled && groupEnabled
        val dot = if (active) DOT_GREEN else DOT_GRAY
        val sub = buildString {
            append("#${idx + 1} · ${p.role}")
            if (p.isMarker) append(" · 系统占位")
            if (!groupEnabled) append(" · 组已禁用")
        }
        val body = if (p.isMarker && p.content.isBlank()) markerLabel(p.identifier) else p.content.ifBlank { "（空内容）" }
        val editCb: (() -> Unit)? = if (!p.isMarker) {
            {
                openSingleItemEditor(
                    CodeEditorActivity.MODULE_PRESET,
                    p.name.ifBlank { "提示词 #${idx + 1}" },
                    CodeEditorActivity.ITEM_PRESET_PROMPT,
                    itemKey = p.identifier,
                    itemIndex = idx
                )
            }
        } else null
        return DetailItem.CollapsibleEntry(
            title = p.name,
            subtitle = sub,
            body = body,
            dotColor = dot,
            dimmed = !active || p.isMarker,
            onEdit = editCb,
            entryKey = p.identifier
        )
    }

    private fun evaluateStMacros(
        raw: String,
        localVars: MutableMap<String, String>,
        globalVars: MutableMap<String, String>
    ): String {
        var s = raw
        // Strip {{// comments}}
        s = Regex("""\{\{//[\s\S]*?\}\}""").replace(s, "")
        // Evaluate {{setvar::k::v}} & {{setglobalvar::k::v}}
        s = Regex("""\{\{setvar::([^:}]+)::([\s\S]*?)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            localVars[m.groupValues[1].trim()] = m.groupValues[2]
            ""
        }
        s = Regex("""\{\{setglobalvar::([^:}]+)::([\s\S]*?)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            globalVars[m.groupValues[1].trim()] = m.groupValues[2]
            ""
        }
        s = Regex("""\{\{addvar::([^:}]+)::([\s\S]*?)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            val k = m.groupValues[1].trim()
            val v = m.groupValues[2]
            val prev = localVars[k].orEmpty()
            val pNum = prev.toDoubleOrNull()
            val vNum = v.toDoubleOrNull()
            localVars[k] = if (pNum != null && vNum != null) {
                val sum = pNum + vNum
                if (sum % 1.0 == 0.0) sum.toLong().toString() else sum.toString()
            } else prev + v
            ""
        }
        // Evaluate {{getvar::k}} & {{getglobalvar::k}}
        s = Regex("""\{\{getvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            localVars[m.groupValues[1].trim()].orEmpty()
        }
        s = Regex("""\{\{getglobalvar::([^}]+)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            globalVars[m.groupValues[1].trim()].orEmpty()
        }
        // Basic built-in macros
        s = Regex("""\{\{random::([^}]+)\}\}""", RegexOption.IGNORE_CASE).replace(s) { m ->
            m.groupValues[1].split("::").firstOrNull().orEmpty()
        }
        s = s.replace("{{newline}}", "\n", ignoreCase = true)
            .replace("{{user}}", "User", ignoreCase = true)
            .replace("{{char}}", "Char", ignoreCase = true)
        s = Regex("""[ \t]*\r?\n?\{\{trim\}\}\r?\n?[ \t]*""", RegexOption.IGNORE_CASE).replace(s, "")
        return s.trim()
    }

    private fun jumpToPresetPrompt(identifier: String) {
        val parsed = currentParsedPreset ?: return
        val gid = parsed.promptGroupMap[identifier]
        if (gid != null) collapsedPresetGroups.remove(gid)
        if (detailSearchQuery.isNotEmpty()) {
            detailSearchQuery = ""
            binding.etDetailSearch.setText("")
        }
        activeTabKey = "prompts"
        // Visually check the first chip ("提示词")
        val group = binding.detailTabGroup
        for (i in 0 until group.childCount) {
            val ch = group.getChildAt(i) as? Chip ?: continue
            ch.isChecked = (i == 0)
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildPresetItems("") }
            val targetPos = items.indexOfFirst { it is DetailItem.CollapsibleEntry && it.entryKey == identifier }
            detailAdapter.submitList(items, preExpandedPos = if (targetPos >= 0) targetPos else null)
            if (targetPos >= 0) {
                binding.rvDetail.post {
                    (binding.rvDetail.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetPos, 48)
                }
            }
        }
    }

    private fun computeSentPrompts(parsed: ParsedPreset): List<com.stvault.app.core.ParsedPresetPrompt> {
        val groupEnabledMap = parsed.promptGroups.associate { it.id to it.enabled }
        return parsed.prompts.filter { p ->
            if (!p.linked || !p.enabled) return@filter false
            val gid = parsed.promptGroupMap[p.identifier]
            if (gid != null && groupEnabledMap[gid] == false) return@filter false
            p.isMarker || p.content.isNotBlank()
        }
    }

    private fun markerLabel(identifier: String): String = when (identifier) {
        "worldInfoBefore" -> "【世界书 · 角色前】"
        "worldInfoAfter" -> "【世界书 · 角色后】"
        "personaDescription" -> "【用户人设】"
        "charDescription" -> "【角色设定】"
        "charPersonality" -> "【角色性格】"
        "scenario" -> "【情景设定】"
        "dialogueExamples" -> "【对话示例】"
        "chatHistory" -> "【聊天记录】"
        "enhanceDefinitions" -> "【增强定义】"
        else -> "【系统占位 · $identifier】"
    }

    private fun filterWorldEntries(entries: List<ParsedWorldEntry>, filter: String, q: String): List<ParsedWorldEntry> {
        return entries.filter { e ->
            val modeOk = when (filter) {
                "constant" -> e.constant
                "selective" -> !e.constant && e.enabled
                "disabled" -> !e.enabled
                else -> true
            }
            if (!modeOk) return@filter false
            if (q.isEmpty()) return@filter true
            e.title.lowercase().contains(q) ||
                e.content.lowercase().contains(q) ||
                e.keys.any { it.lowercase().contains(q) }
        }
    }

    private fun toEntryItem(e: ParsedWorldEntry, onEdit: (() -> Unit)? = null): DetailItem.CollapsibleEntry {
        val dot = when {
            !e.enabled -> DOT_GRAY
            e.constant -> DOT_BLUE
            else -> DOT_GREEN
        }
        val keyLine = if (e.keys.isNotEmpty()) e.keys.take(5).joinToString(" · ") else "深度 ${e.depth} · 顺序 ${e.order}"
        return DetailItem.CollapsibleEntry(
            title = e.title,
            subtitle = keyLine,
            body = e.content.ifBlank { "（空条目）" },
            dotColor = dot,
            dimmed = !e.enabled,
            onEdit = onEdit
        )
    }

    private fun setupHeaderTabs(
        tabs: List<Pair<String, String>>,
        initialKey: String,
        onPick: (String) -> Unit
    ) {
        binding.detailHeaderControls.visibility = View.VISIBLE
        binding.detailTabsScroll.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        val group = binding.detailTabGroup
        group.removeAllViews()

        val accent = ContextCompat.getColor(this, R.color.accent)
        val onAccent = ContextCompat.getColor(this, R.color.on_accent)
        val surface2 = ContextCompat.getColor(this, R.color.surface_2)
        val border = ContextCompat.getColor(this, R.color.border)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        val bgState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, surface2))
        val textState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(onAccent, muted))
        val strokeState = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, border))

        for ((key, label) in tabs) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = (key == initialKey)
                isCheckedIconVisible = false
                chipBackgroundColor = bgState
                setTextColor(textState)
                chipStrokeWidth = 2f
                chipStrokeColor = strokeState
                chipCornerRadius = 20f
                textSize = 12f
                setOnClickListener { onPick(key) }
            }
            group.addView(chip)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }

    // ==================== VIRTUALIZED ADAPTER (CARD & EDITORIAL STYLES) ====================

    private sealed class DetailItem {
        data class CharHeader(val name: String, val subtitle: String, val tags: List<String>, val coverFile: File?) : DetailItem()
        data class ParamRow(val params: List<Pair<String, String>>) : DetailItem()
        data class CompactSummary(val text: String) : DetailItem()
        data class SectionTitle(val title: String) : DetailItem()
        data class ActionStrip(val leftText: String, val actionLabel: String, val onAction: () -> Unit) : DetailItem()
        data class TextBlock(
            val title: String,
            val body: String,
            val compact: Boolean,
            val defaultPreviewChars: Int? = null,
            val onPreview: (() -> Unit)? = null,
            val onEdit: (() -> Unit)? = null
        ) : DetailItem()
        data class GroupHeader(
            val title: String,
            val subtitle: String,
            val enabled: Boolean,
            val collapsed: Boolean,
            val onToggle: () -> Unit
        ) : DetailItem()
        data class SentSegment(
            val index: Int,
            val identifier: String,
            val role: String,
            val sourceName: String,
            val content: String,
            val isMarker: Boolean,
            val onJumpToEntry: () -> Unit,
            val onEdit: (() -> Unit)? = null
        ) : DetailItem()
        data class CollapsibleEntry(
            val title: String,
            val subtitle: String,
            val body: String,
            val dotColor: Int,
            val dimmed: Boolean = false,
            val onPreview: (() -> Unit)? = null,
            val onEdit: (() -> Unit)? = null,
            val entryKey: String = ""
        ) : DetailItem()
    }

    private class DetailItemsAdapter(
        private val editorial: Boolean,
        private val onTranslate: (String, (String?) -> Unit) -> Unit
    ) : RecyclerView.Adapter<DetailItemsAdapter.VH>() {

        private var items: List<DetailItem> = emptyList()
        private val expandedPositions = HashSet<Int>()
        private val translatedBodies = HashMap<Int, String>()
        private val showingTranslated = HashSet<Int>()
        private val translatingPos = HashSet<Int>()

        fun submitList(list: List<DetailItem>, preExpandedPos: Int? = null) {
            items = list
            expandedPositions.clear()
            translatedBodies.clear()
            showingTranslated.clear()
            translatingPos.clear()
            if (preExpandedPos != null && preExpandedPos in list.indices) {
                expandedPositions.add(preExpandedPos)
            }
            notifyDataSetChanged()
        }

        private fun triggerTranslate(pos: Int, rawText: String) {
            if (pos in translatingPos) return
            if (pos in showingTranslated) {
                showingTranslated.remove(pos)
                notifyItemChanged(pos)
                return
            }
            val cached = translatedBodies[pos] ?: com.stvault.app.core.TextTranslator.getCached(rawText)
            if (cached != null) {
                translatedBodies[pos] = cached
                showingTranslated.add(pos)
                notifyItemChanged(pos)
                return
            }
            translatingPos.add(pos)
            notifyItemChanged(pos)
            onTranslate(rawText) { res ->
                translatingPos.remove(pos)
                if (!res.isNullOrBlank()) {
                    translatedBodies[pos] = res
                    showingTranslated.add(pos)
                }
                if (pos in items.indices) notifyItemChanged(pos)
            }
        }

        private fun addHeaderIcon(
            ctx: Context,
            row: LinearLayout,
            iconRes: Int,
            tintColor: Int,
            desc: String,
            onClick: () -> Unit
        ) {
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            row.addView(ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(tintColor)
                contentDescription = desc
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginStart = dp(2)
                }
                setOnClickListener { onClick() }
            })
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DetailItem.CharHeader -> 0
            is DetailItem.ParamRow -> 1
            is DetailItem.CompactSummary -> 2
            is DetailItem.SectionTitle -> 3
            is DetailItem.ActionStrip -> 4
            is DetailItem.TextBlock -> 5
            is DetailItem.CollapsibleEntry -> 6
            is DetailItem.GroupHeader -> 7
            is DetailItem.SentSegment -> 8
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.container.context
            holder.container.removeAllViews()
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            fun col(id: Int) = ContextCompat.getColor(ctx, id)

            when (val item = items[position]) {
                is DetailItem.CharHeader -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(if (editorial) 4 else 14), dp(if (editorial) 8 else 14), dp(if (editorial) 4 else 14), dp(if (editorial) 14 else 14))
                    }
                    if (item.coverFile != null) {
                        val iv = ImageView(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(82), dp(110)).apply { marginEnd = dp(14) }
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(col(R.color.surface_2))
                        }
                        Glide.with(iv).load(item.coverFile).centerCrop().into(iv)
                        row.addView(iv)
                    }
                    val info = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    info.addView(TextView(ctx).apply {
                        text = item.name
                        setTextColor(col(R.color.text_primary))
                        textSize = 18f
                        setTypeface(null, Typeface.BOLD)
                    })
                    info.addView(TextView(ctx).apply {
                        text = item.subtitle
                        setTextColor(col(R.color.text_muted))
                        textSize = 12f
                        setLineSpacing(dp(2).toFloat(), 1f)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                        }
                    })
                    if (item.tags.isNotEmpty()) {
                        info.addView(TextView(ctx).apply {
                            text = item.tags.joinToString("  # ", prefix = "# ")
                            setTextColor(col(R.color.text_muted))
                            textSize = 11.5f
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(6)
                            }
                        })
                    }
                    row.addView(info)
                    holder.container.addView(wrapStyle(ctx, row, editorial, dp(10)))
                }

                is DetailItem.ParamRow -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(10)
                        }
                    }
                    item.params.forEachIndexed { i, (k, v) ->
                        val cell = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundResource(R.drawable.bg_input)
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (i < item.params.lastIndex) marginEnd = dp(6)
                            }
                        }
                        cell.addView(TextView(ctx).apply {
                            text = v
                            setTextColor(col(R.color.text_primary))
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                        })
                        cell.addView(TextView(ctx).apply {
                            text = k
                            setTextColor(col(R.color.text_muted))
                            textSize = 11f
                        })
                        row.addView(cell)
                    }
                    holder.container.addView(row)
                }

                is DetailItem.CompactSummary -> {
                    holder.container.addView(TextView(ctx).apply {
                        text = item.text
                        setTextColor(col(R.color.text_muted))
                        textSize = 12f
                        setPadding(dp(2), dp(2), dp(2), dp(10))
                    })
                }

                is DetailItem.SectionTitle -> {
                    holder.container.addView(TextView(ctx).apply {
                        text = item.title
                        setTextColor(col(R.color.text_primary))
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(dp(2), dp(10), dp(2), dp(6))
                    })
                }

                is DetailItem.ActionStrip -> {
                    val bar = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), dp(2), dp(2), dp(10))
                    }
                    bar.addView(TextView(ctx).apply {
                        text = item.leftText
                        setTextColor(col(R.color.text_muted))
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    bar.addView(TextView(ctx).apply {
                        text = item.actionLabel
                        setTextColor(col(R.color.text_muted))
                        textSize = 11.5f
                        setBackgroundResource(R.drawable.bg_pill_accent)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        setOnClickListener { item.onAction() }
                    })
                    holder.container.addView(bar)
                }

                is DetailItem.TextBlock -> {
                    val isExpanded = position in expandedPositions
                    val isTrans = position in showingTranslated && translatedBodies.containsKey(position)
                    val activeRaw = if (isTrans) translatedBodies[position]!! else item.body
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(if (editorial) 4 else 14), dp(if (editorial) 10 else 12), dp(if (editorial) 4 else 14), dp(if (editorial) 14 else 12))
                    }
                    val titleRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    titleRow.addView(TextView(ctx).apply {
                        text = if (isTrans) "${item.title} · 译" else item.title
                        setTextColor(col(R.color.text_primary))
                        textSize = if (editorial && !item.compact) 14f else 13f
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (!item.compact && item.body.length >= 6 && item.body != "（空）") {
                        val tint = when {
                            position in translatingPos -> DOT_BLUE
                            isTrans -> DOT_GREEN
                            else -> col(R.color.text_muted)
                        }
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_translate, tint, "翻译") {
                            triggerTranslate(position, item.body)
                        }
                    }
                    if (item.onPreview != null) {
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_preview, col(R.color.text_muted), "预览") {
                            item.onPreview.invoke()
                        }
                    }
                    if (item.onEdit != null) {
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_edit, col(R.color.text_muted), "编辑") {
                            item.onEdit.invoke()
                        }
                    }
                    box.addView(titleRow)

                    val previewCap = item.defaultPreviewChars
                    val canCollapse = previewCap != null && activeRaw.length > previewCap
                    val displayBody = when {
                        canCollapse && !isExpanded -> activeRaw.take(previewCap!!) + "……"
                        activeRaw.length > MAX_DETAIL_TEXT_CHARS ->
                            activeRaw.take(MAX_DETAIL_TEXT_CHARS) + "\n\n……（内容较长，共 ${activeRaw.length} 字，仅预览前 20,000 字）"
                        else -> activeRaw
                    }
                    box.addView(TextView(ctx).apply {
                        text = displayBody
                        setTextColor(col(if (editorial && !item.compact) R.color.text_primary else R.color.text_muted))
                        alpha = if (editorial && !item.compact) 0.86f else 1f
                        textSize = if (editorial && !item.compact) 14f else 12.5f
                        setLineSpacing(dp(if (editorial && !item.compact) 5 else 2).toFloat(), 1f)
                        if (displayBody.length <= 12_000 && !canCollapse) setTextIsSelectable(true)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(6)
                        }
                    })
                    if (canCollapse) {
                        box.addView(TextView(ctx).apply {
                            text = if (isExpanded) "收起 ▴" else "展开全文（共 ${activeRaw.length} 字）▾"
                            setTextColor(col(R.color.text_muted))
                            textSize = 11.5f
                            setPadding(0, dp(6), 0, dp(2))
                            setOnClickListener {
                                if (position in expandedPositions) expandedPositions.remove(position)
                                else expandedPositions.add(position)
                                notifyItemChanged(position)
                            }
                        })
                    }
                    holder.container.addView(wrapStyle(ctx, box, editorial, dp(10)))
                }

                is DetailItem.SentSegment -> {
                    val isTrans = position in showingTranslated && translatedBodies.containsKey(position)
                    val activeRaw = if (isTrans) translatedBodies[position]!! else item.content
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(2), dp(8), dp(2), dp(10))
                    }
                    val headerRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    headerRow.addView(TextView(ctx).apply {
                        text = item.role
                        setTextColor(if (item.isMarker) DOT_BLUE else DOT_GREEN)
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_pill_accent)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(6) }
                    })
                    headerRow.addView(TextView(ctx).apply {
                        text = if (isTrans) "${item.sourceName} · 译" else item.sourceName
                        setTextColor(col(R.color.text_muted))
                        textSize = 11.5f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener { item.onJumpToEntry() }
                    })
                    if (!item.isMarker) {
                        val tint = when {
                            position in translatingPos -> DOT_BLUE
                            isTrans -> DOT_GREEN
                            else -> col(R.color.text_muted)
                        }
                        addHeaderIcon(ctx, headerRow, R.drawable.ic_action_translate, tint, "翻译") {
                            triggerTranslate(position, item.content)
                        }
                    }
                    addHeaderIcon(ctx, headerRow, R.drawable.ic_action_jump, col(R.color.text_muted), "定位条目") {
                        item.onJumpToEntry()
                    }
                    if (item.onEdit != null) {
                        addHeaderIcon(ctx, headerRow, R.drawable.ic_action_edit, col(R.color.text_muted), "编辑") {
                            item.onEdit.invoke()
                        }
                    }
                    box.addView(headerRow)

                    if (!item.isMarker) {
                        val displayBody = if (activeRaw.length > MAX_DETAIL_TEXT_CHARS) {
                            activeRaw.take(MAX_DETAIL_TEXT_CHARS) + "\n\n……（内容较长，仅预览前 20,000 字）"
                        } else activeRaw
                        box.addView(TextView(ctx).apply {
                            text = displayBody
                            setTextColor(col(R.color.text_primary))
                            alpha = 0.9f
                            textSize = 13.5f
                            setLineSpacing(dp(4).toFloat(), 1f)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(6) }
                            setOnClickListener { item.onJumpToEntry() }
                        })
                    }
                    box.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            topMargin = dp(10)
                        }
                        setBackgroundColor(col(R.color.border))
                        alpha = 0.55f
                    })
                    holder.container.addView(box)
                }

                is DetailItem.GroupHeader -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.bg_input)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        alpha = if (item.enabled) 1f else 0.58f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6); bottomMargin = dp(4) }
                        setOnClickListener { item.onToggle() }
                    }
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (item.enabled) DOT_GREEN else DOT_GRAY)
                        }
                    })
                    val colText = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    colText.addView(TextView(ctx).apply {
                        text = item.title
                        setTextColor(col(R.color.text_primary))
                        textSize = 13.5f
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    colText.addView(TextView(ctx).apply {
                        text = item.subtitle
                        setTextColor(col(R.color.text_muted))
                        textSize = 11f
                    })
                    row.addView(colText)
                    row.addView(TextView(ctx).apply {
                        text = if (item.collapsed) "展开 ▾" else "收起 ▴"
                        setTextColor(col(R.color.text_muted))
                        textSize = 11.5f
                    })
                    holder.container.addView(row)
                }

                is DetailItem.CollapsibleEntry -> {
                    val isExpanded = position in expandedPositions
                    val isTrans = position in showingTranslated && translatedBodies.containsKey(position)
                    val activeRaw = if (isTrans) translatedBodies[position]!! else item.body
                    val box = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(if (editorial) 4 else 14), dp(11), dp(if (editorial) 4 else 14), dp(11))
                        alpha = if (item.dimmed) 0.6f else 1f
                    }
                    val titleRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val dot = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(item.dotColor)
                        }
                    }
                    titleRow.addView(dot)
                    titleRow.addView(TextView(ctx).apply {
                        text = if (isTrans) "${item.title} · 译" else item.title
                        setTextColor(col(R.color.text_primary))
                        textSize = 13.5f
                        setTypeface(null, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (item.body.length >= 6 && item.body != "（空条目）" && item.body != "（空内容）") {
                        val tint = when {
                            position in translatingPos -> DOT_BLUE
                            isTrans -> DOT_GREEN
                            else -> col(R.color.text_muted)
                        }
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_translate, tint, "翻译") {
                            if (position !in expandedPositions) expandedPositions.add(position)
                            triggerTranslate(position, item.body)
                        }
                    }
                    if (item.onPreview != null) {
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_preview, col(R.color.text_muted), "预览") {
                            item.onPreview.invoke()
                        }
                    }
                    if (item.onEdit != null) {
                        addHeaderIcon(ctx, titleRow, R.drawable.ic_action_edit, col(R.color.text_muted), "编辑") {
                            item.onEdit.invoke()
                        }
                    }
                    box.addView(titleRow)

                    if (item.subtitle.isNotBlank()) {
                        box.addView(TextView(ctx).apply {
                            text = item.subtitle
                            setTextColor(col(R.color.text_muted))
                            textSize = 11.5f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(3)
                                marginStart = dp(15)
                            }
                        })
                    }

                    val displayBody = if (!isExpanded) {
                        activeRaw.take(240).replace('\n', ' ')
                    } else if (activeRaw.length > MAX_DETAIL_TEXT_CHARS) {
                        activeRaw.take(MAX_DETAIL_TEXT_CHARS) + "\n\n……（内容较长，共 ${activeRaw.length} 字，仅预览前 20,000 字）"
                    } else {
                        activeRaw
                    }
                    val tvBody = TextView(ctx).apply {
                        text = displayBody
                        setTextColor(col(R.color.text_muted))
                        textSize = if (editorial) 13f else 12.5f
                        maxLines = if (isExpanded) Int.MAX_VALUE else 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setLineSpacing(dp(3).toFloat(), 1f)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(5)
                            marginStart = dp(15)
                        }
                    }
                    box.addView(tvBody)
                    box.setOnClickListener {
                        if (position in expandedPositions) expandedPositions.remove(position)
                        else expandedPositions.add(position)
                        notifyItemChanged(position)
                    }
                    holder.container.addView(wrapStyle(ctx, box, editorial, dp(8)))
                }
            }
        }

        private fun wrapStyle(ctx: Context, child: View, editorial: Boolean, bottomMarginPx: Int): View {
            val d = ctx.resources.displayMetrics.density
            if (editorial) {
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(child)
                    addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt())
                        setBackgroundColor(ContextCompat.getColor(ctx, R.color.border))
                    })
                }
            }
            return MaterialCardView(ctx).apply {
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
                radius = 14f * d
                cardElevation = 0f
                setStrokeColor(ContextCompat.getColor(ctx, R.color.border))
                strokeWidth = (1 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = bottomMarginPx
                }
                addView(child)
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(val container: LinearLayout) : RecyclerView.ViewHolder(container)
    }

    companion object {
        const val EXTRA_TYPE = "res_type"
        const val EXTRA_ID = "res_id"
        const val TYPE_CHARACTER = "character"
        const val TYPE_WORLDBOOK = "worldbook"
        const val TYPE_PRESET = "preset"

        private val DOT_BLUE = Color.parseColor("#3B82F6")
        private val DOT_GREEN = Color.parseColor("#2F7D57")
        private val DOT_GRAY = Color.parseColor("#8E8E98")
        private const val MAX_DETAIL_TEXT_CHARS = 20_000
        private const val GREETING_PREVIEW_CHARS = 500

        fun open(context: Context, type: String, id: String) {
            context.startActivity(Intent(context, ResourceDetailActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_ID, id)
            })
        }
    }
}
