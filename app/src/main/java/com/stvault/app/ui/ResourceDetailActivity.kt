package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.stvault.app.R
import com.stvault.app.core.ParsedCharacterCard
import com.stvault.app.core.ParsedWorldEntry
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityResourceDetailBinding
import com.stvault.app.model.CacheStatus
import com.stvault.app.model.CharacterRecord
import com.stvault.app.model.PresetRecord
import com.stvault.app.model.WorldBookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ResourceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResourceDetailBinding
    private lateinit var libRepo: ResourceLibraryRepository
    private lateinit var vaultRepo: VaultRepository

    private var resType: String = ""
    private var resId: String = ""

    private var pendingExportFile: File? = null
    private var pendingExportBytes: ByteArray? = null

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

        resType = intent.getStringExtra(EXTRA_TYPE) ?: ""
        resId = intent.getStringExtra(EXTRA_ID) ?: ""

        binding.btnDetailClose.setOnClickListener { finish() }
        binding.btnDetailDelete.setOnClickListener { confirmDelete() }

        when (resType) {
            TYPE_CHARACTER -> loadCharacter()
            TYPE_WORLDBOOK -> loadWorldBook()
            TYPE_PRESET -> loadPreset()
            else -> finish()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除资源")
            .setMessage("确定从库中删除该资源？将同时清理仅由其引用的独享图床缓存。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        when (resType) {
                            TYPE_CHARACTER -> libRepo.deleteCharacter(resId)
                            TYPE_WORLDBOOK -> libRepo.deleteWorldBook(resId)
                            TYPE_PRESET -> libRepo.deletePreset(resId)
                        }
                    }
                    toast("已删除")
                    setResult(RESULT_OK)
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== CHARACTER DETAIL ====================

    private fun loadCharacter() {
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val rec = libRepo.getCharacterById(resId) ?: return@withContext null
                val parsed = libRepo.parseCharacterDetail(rec)
                rec to parsed
            }
            if (pair == null) {
                toast("找不到该角色卡")
                finish()
                return@launch
            }
            val (rec, parsed) = pair
            binding.tvDetailTitle.text = rec.name
            renderCharacterUI(rec, parsed)
        }
    }

    private fun renderCharacterUI(rec: CharacterRecord, parsed: ParsedCharacterCard?) {
        val root = binding.detailContainer
        root.removeAllViews()

        // 1. Header Card (Cover + Info + Actions)
        val headerCard = makeCard()
        val headerInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (rec.isPng) {
            val cover = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(112)).apply { marginEnd = dp(14) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(color(R.color.surface_2))
            }
            val file = libRepo.getCharacterFile(rec)
            if (file.exists()) Glide.with(cover).load(file).centerCrop().into(cover)
            topRow.addView(cover)
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(makeTitleText(rec.name, 17f))
        val sub = buildString {
            append(rec.spec.uppercase())
            if (rec.creator.isNotEmpty()) append(" · ${rec.creator}")
            append(" · ${formatBytes(rec.fileSize)}")
        }
        infoCol.addView(makeMutedText(sub, 12f, topMarginDp = 4))
        if (rec.tags.isNotEmpty()) {
            infoCol.addView(makeMutedText(rec.tags.take(6).joinToString("  # ", prefix = "# "), 11.5f, topMarginDp = 6))
        }
        topRow.addView(infoCol)
        headerInner.addView(topRow)

        // Action Buttons Row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            }
        }
        val btnExport = makePrimaryButton("导出原卡") {
            pendingExportBytes = null
            pendingExportFile = libRepo.getCharacterFile(rec)
            exportDocLauncher.launch(rec.fileName)
        }
        actionRow.addView(btnExport, LinearLayout.LayoutParams(0, dp(40), 1f))

        if (rec.urls.isNotEmpty()) {
            val btnRepair = makeSecondaryButton("换链复活") {
                runCharacterRepair(rec)
            }
            actionRow.addView(btnRepair, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        }
        headerInner.addView(actionRow)
        headerCard.addView(headerInner)
        root.addView(headerCard)

        if (parsed == null) {
            root.addView(makeMutedText("角色卡元数据解析失败", 13f, topMarginDp = 12))
            return
        }

        // 2. Section Tabs
        val sectionContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }

        val tabs = mutableListOf(
            "overview" to "概览",
            "greetings" to "开场白 (${1 + parsed.alternateGreetings.size})"
        )
        if (parsed.embeddedWorldBook != null) {
            tabs.add("book" to "世界书 (${parsed.embeddedWorldBook.entries.size})")
        }
        val linkedComps = libRepo.getComponentsLinkedTo("character", rec.id)
        val totalScripts = parsed.embeddedRegexScripts.size + parsed.embeddedHelperScripts.size + linkedComps.size
        if (totalScripts > 0) {
            tabs.add("scripts" to "脚本 ($totalScripts)")
        }
        if (rec.urls.isNotEmpty()) {
            tabs.add("urls" to "图床 (${rec.urls.size})")
        }

        fun renderTab(key: String) {
            sectionContent.removeAllViews()
            when (key) {
                "overview" -> {
                    if (parsed.description.isNotBlank()) sectionContent.addView(makeTextSectionCard("角色设定", parsed.description))
                    if (parsed.personality.isNotBlank()) sectionContent.addView(makeTextSectionCard("性格摘要", parsed.personality))
                    if (parsed.scenario.isNotBlank()) sectionContent.addView(makeTextSectionCard("情景设定", parsed.scenario))
                    if (parsed.creatorNotes.isNotBlank()) sectionContent.addView(makeTextSectionCard("作者说明", parsed.creatorNotes))
                    if (parsed.systemPrompt.isNotBlank()) sectionContent.addView(makeTextSectionCard("系统提示词", parsed.systemPrompt))
                    if (parsed.mesExample.isNotBlank()) sectionContent.addView(makeTextSectionCard("对话示例", parsed.mesExample))
                }
                "greetings" -> {
                    sectionContent.addView(makeTextSectionCard("默认开场白", parsed.firstMes.ifBlank { "（空）" }))
                    parsed.alternateGreetings.forEachIndexed { idx, g ->
                        sectionContent.addView(makeTextSectionCard("备用开场白 #${idx + 1}", g))
                    }
                }
                "book" -> {
                    val book = parsed.embeddedWorldBook ?: return
                    val extractBtn = makePrimaryButton("抽离为独立世界书") {
                        lifecycleScope.launch {
                            val (_, msg) = withContext(Dispatchers.IO) {
                                libRepo.extractEmbeddedWorldBook(rec.id)
                            }
                            toast(msg)
                        }
                    }
                    sectionContent.addView(
                        extractBtn,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(10) }
                    )
                    renderWorldEntriesList(sectionContent, book.entries)
                }
                "scripts" -> {
                    if (parsed.embeddedRegexScripts.isNotEmpty()) {
                        sectionContent.addView(makeSectionHeader("内嵌正则 (${parsed.embeddedRegexScripts.size})"))
                        for (r in parsed.embeddedRegexScripts) {
                            val status = if (r.disabled) "已禁用" else "已启用"
                            sectionContent.addView(makeTextSectionCard("${r.name} · $status", "查找：${r.findRegex}\n\n替换：${r.replaceString.take(400)}"))
                        }
                    }
                    if (parsed.embeddedHelperScripts.isNotEmpty()) {
                        sectionContent.addView(makeSectionHeader("内嵌酒馆助手脚本 (${parsed.embeddedHelperScripts.size})"))
                        for (h in parsed.embeddedHelperScripts) {
                            val status = if (h.enabled) "已启用" else "已禁用"
                            val body = buildString {
                                if (h.info.isNotBlank()) append("${h.info}\n\n")
                                append(h.content.take(600))
                                if (h.content.length > 600) append("\n...")
                            }
                            sectionContent.addView(makeTextSectionCard("${h.name} · $status", body))
                        }
                    }
                    if (linkedComps.isNotEmpty()) {
                        sectionContent.addView(makeSectionHeader("已关联外部组件 (${linkedComps.size})"))
                        for (c in linkedComps) {
                            sectionContent.addView(makeTextSectionCard(c.name, c.summary))
                        }
                    }
                }
                "urls" -> {
                    val records = vaultRepo.getUrlRecordsForUrls(rec.urls)
                    for (u in records) {
                        val status = when (u.status) {
                            CacheStatus.OK -> "已缓存 · ${formatBytes(u.size)}"
                            CacheStatus.ERROR -> "失败 · ${u.error}"
                            CacheStatus.IGNORED -> "已忽略"
                            CacheStatus.PENDING -> "待缓存"
                        }
                        sectionContent.addView(makeTextSectionCard(status, u.url))
                    }
                }
            }
        }

        root.addView(makeTabChipsBar(tabs, "overview") { renderTab(it) })
        root.addView(sectionContent)
        renderTab("overview")
    }

    private fun runCharacterRepair(rec: CharacterRecord) {
        val modes = arrayOf("上传图床换链（保留原 PNG）", "WebP 压缩内嵌 Base64")
        AlertDialog.Builder(this)
            .setTitle("角色卡图床复活")
            .setItems(modes) { _, which ->
                val mode = if (which == 0) "catbox" else "base64_webp"
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
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== WORLD BOOK DETAIL ====================

    private fun loadWorldBook() {
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val rec = libRepo.getWorldBookById(resId) ?: return@withContext null
                val parsed = libRepo.parseWorldBookDetail(rec)
                rec to parsed
            }
            if (pair == null) {
                toast("找不到该世界书")
                finish()
                return@launch
            }
            val (rec, parsed) = pair
            binding.tvDetailTitle.text = rec.name
            val root = binding.detailContainer
            root.removeAllViews()

            val headerCard = makeCard()
            val inner = LinearLayout(this@ResourceDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            inner.addView(makeTitleText(rec.name, 17f))
            val approxTokens = (rec.totalChars / 2.2).toInt()
            val meta = buildString {
                append("${rec.entryCount} 条目 · 常驻 ${rec.constantCount} · 约 $approxTokens tokens")
                if (rec.originCardName.isNotEmpty()) append("\n来源角色卡：${rec.originCardName}")
            }
            inner.addView(makeMutedText(meta, 12.5f, topMarginDp = 6))

            val btnExport = makePrimaryButton("导出世界书 JSON") {
                pendingExportBytes = null
                pendingExportFile = libRepo.getWorldBookFile(rec)
                exportDocLauncher.launch(rec.fileName)
            }
            inner.addView(btnExport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(12) })
            headerCard.addView(inner)
            root.addView(headerCard)

            if (parsed != null) {
                val entriesContainer = LinearLayout(this@ResourceDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                    }
                }
                val filters = listOf(
                    "all" to "全部 (${parsed.entries.size})",
                    "constant" to "蓝灯常驻 (${parsed.entries.count { it.constant }})",
                    "selective" to "绿灯触发 (${parsed.entries.count { !it.constant && it.enabled }})",
                    "disabled" to "已禁用 (${parsed.entries.count { !it.enabled }})"
                )
                fun showFiltered(f: String) {
                    entriesContainer.removeAllViews()
                    val list = when (f) {
                        "constant" -> parsed.entries.filter { it.constant }
                        "selective" -> parsed.entries.filter { !it.constant && it.enabled }
                        "disabled" -> parsed.entries.filter { !it.enabled }
                        else -> parsed.entries
                    }
                    renderWorldEntriesList(entriesContainer, list)
                }
                root.addView(makeTabChipsBar(filters, "all") { showFiltered(it) })
                root.addView(entriesContainer)
                showFiltered("all")
            }
        }
    }

    private fun renderWorldEntriesList(container: LinearLayout, entries: List<ParsedWorldEntry>) {
        if (entries.isEmpty()) {
            container.addView(makeMutedText("无条目", 13f, topMarginDp = 8))
            return
        }
        for (e in entries) {
            val statusLabel = when {
                !e.enabled -> "已禁用"
                e.constant -> "蓝灯常驻"
                else -> "绿灯触发"
            }
            val keyInfo = if (e.keys.isNotEmpty()) "关键词：${e.keys.joinToString(", ")}\n\n" else ""
            val title = "${e.title}  ·  $statusLabel"
            val previewBody = "$keyInfo${e.content}"
            container.addView(makeCollapsibleCard(title, previewBody))
        }
    }

    // ==================== PRESET DETAIL ====================

    private fun loadPreset() {
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val rec = libRepo.getPresetById(resId) ?: return@withContext null
                val parsed = libRepo.parsePresetDetail(rec)
                rec to parsed
            }
            if (pair == null) {
                toast("找不到该预设")
                finish()
                return@launch
            }
            val (rec, parsed) = pair
            binding.tvDetailTitle.text = rec.name
            val root = binding.detailContainer
            root.removeAllViews()

            val headerCard = makeCard()
            val inner = LinearLayout(this@ResourceDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            inner.addView(makeTitleText(rec.name, 17f))
            val paramText = buildString {
                append("启用 ${rec.enabledPromptCount} / ${rec.promptCount} 条目")
                parsed?.temperature?.let { append("  ·  Temp ${String.format("%.2f", it)}") }
                parsed?.topP?.let { append("  ·  TopP ${String.format("%.2f", it)}") }
                parsed?.maxTokens?.let { append("  ·  MaxTokens $it") }
                parsed?.maxContext?.let { append("  ·  Context $it") }
            }
            inner.addView(makeMutedText(paramText, 12.5f, topMarginDp = 6))

            val btnExport = makePrimaryButton("导出预设 JSON") {
                pendingExportBytes = null
                pendingExportFile = libRepo.getPresetFile(rec)
                exportDocLauncher.launch(rec.fileName)
            }
            inner.addView(btnExport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(12) })
            headerCard.addView(inner)
            root.addView(headerCard)

            // Linked external components
            val linked = libRepo.getComponentsLinkedTo("preset", rec.id)
            if (linked.isNotEmpty()) {
                root.addView(makeSectionHeader("关联组件 (${linked.size})"))
                for (c in linked) {
                    root.addView(makeTextSectionCard(c.name, c.summary))
                }
            }

            if (parsed != null) {
                root.addView(makeSectionHeader("Prompt 顺序 (${parsed.prompts.size})"))
                for (p in parsed.prompts) {
                    val state = if (p.enabled) "● 开启" else "○ 关闭"
                    val title = "$state  ${p.name} (${p.role})"
                    val body = if (p.isMarker && p.content.isBlank()) "系统占位符标记" else p.content.ifBlank { "（空内容）" }
                    root.addView(makeCollapsibleCard(title, body))
                }
            }
        }
    }

    // ==================== MINIMALIST UI BUILDERS ====================

    private fun makeCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.surface))
            radius = dp(14).toFloat()
            cardElevation = 0f
            setStrokeColor(color(R.color.border))
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun makeTextSectionCard(title: String, body: String): View {
        val card = makeCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        box.addView(makeTitleText(title, 13.5f))
        val tvBody = TextView(this).apply {
            text = body
            setTextColor(color(R.color.text_muted))
            textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        box.addView(tvBody)
        card.addView(box)
        return card
    }

    private fun makeCollapsibleCard(title: String, body: String): View {
        val card = makeCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        box.addView(makeTitleText(title, 13.5f))
        val tvBody = TextView(this).apply {
            text = body
            setTextColor(color(R.color.text_muted))
            textSize = 12.5f
            maxLines = 3
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            }
        }
        var expanded = false
        box.setOnClickListener {
            expanded = !expanded
            tvBody.maxLines = if (expanded) Int.MAX_VALUE else 3
        }
        box.addView(tvBody)
        card.addView(box)
        return card
    }

    private fun makeTabChipsBar(tabs: List<Pair<String, String>>, initialKey: String, onPick: (String) -> Unit): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        val group = ChipGroup(this).apply {
            setSingleLine(true)
            setSingleSelection(true)
            setSelectionRequired(true)
        }
        val accent = color(R.color.accent)
        val onAccent = color(R.color.on_accent)
        val surface2 = color(R.color.surface_2)
        val border = color(R.color.border)
        val muted = color(R.color.text_muted)

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
        scroll.addView(group)
        return scroll
    }

    private fun makeSectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(color(R.color.text_primary))
        textSize = 13.5f
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
            bottomMargin = dp(8)
        }
    }

    private fun makeTitleText(text: String, sp: Float): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_primary))
        textSize = sp
        setTypeface(null, Typeface.BOLD)
    }

    private fun makeMutedText(text: String, sp: Float, topMarginDp: Int = 0): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_muted))
        textSize = sp
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topMarginDp)
        }
    }

    private fun makePrimaryButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(color(R.color.accent))
            cornerRadius = dp(10)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun makeSecondaryButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(color(R.color.border))
            strokeWidth = dp(1)
            cornerRadius = dp(10)
            insetTop = 0
            insetBottom = 0
            elevation = 0f
            setOnClickListener { onClick() }
        }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }

    companion object {
        const val EXTRA_TYPE = "res_type"
        const val EXTRA_ID = "res_id"
        const val TYPE_CHARACTER = "character"
        const val TYPE_WORLDBOOK = "worldbook"
        const val TYPE_PRESET = "preset"

        fun open(context: Context, type: String, id: String) {
            context.startActivity(Intent(context, ResourceDetailActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_ID, id)
            })
        }
    }
}
