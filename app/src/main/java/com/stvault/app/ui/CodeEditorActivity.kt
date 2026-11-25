package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.stvault.app.R
import com.stvault.app.core.CharacterCardParser
import com.stvault.app.core.ComponentParser
import com.stvault.app.core.PresetParser
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VaultRepository
import com.stvault.app.core.WorldBookParser
import com.stvault.app.databinding.ActivityCodeEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lazy, crash-resistant editor:
 * - JSON is parsed once off the main thread and cached; visual cards only hold lightweight previews.
 * - EditText widgets are created lazily when a card is expanded (never for hundreds of fields at once).
 * - Very large single fields (scripts, giant CSS) are shown as read-only previews, never loaded into an EditText.
 * - Long lists are paged; raw source mode is refused for huge payloads.
 */
class CodeEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeEditorBinding
    private lateinit var vaultRepo: VaultRepository
    private lateinit var libRepo: ResourceLibraryRepository

    private var module: String = ""
    private var recordId: String = ""
    private var itemType: String = ""
    private var itemKey: String = ""
    private var itemIndex: Int = -1

    private var parsedRoot: JSONObject? = null
    private var plainText: String = ""
    private var totalChars: Int = 0

    private var dirty: Boolean = false
    private var showingRawMode: Boolean = false
    private var suppressRawWatcher: Boolean = false
    private var filterJob: Job? = null

    private var matchIndices: List<Int> = emptyList()
    private var currentMatchPos: Int = 0

    private val visualCommitCallbacks = mutableListOf<() -> Unit>()
    private var lastFocusedMultiEdit: EditText? = null

    private class LazyField(
        val hint: String,
        val get: () -> String,
        val set: (String) -> Unit,
        val single: Boolean = false,
        val mono: Boolean = false,
        val minLines: Int = 5
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        module = intent.getStringExtra(EXTRA_MODULE).orEmpty()
        recordId = intent.getStringExtra(EXTRA_ID).orEmpty()
        itemType = intent.getStringExtra(EXTRA_ITEM_TYPE).orEmpty()
        itemKey = intent.getStringExtra(EXTRA_ITEM_KEY).orEmpty()
        itemIndex = intent.getIntExtra(EXTRA_ITEM_INDEX, -1)
        binding.tvEditorTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "编辑" }

        if (itemType.isNotEmpty()) {
            binding.btnEditorModeToggle.visibility = View.GONE
            binding.btnEditorSearchToggle.visibility = View.GONE
            binding.btnEditorFormat.visibility = View.GONE
        }

        binding.btnEditorClose.setOnClickListener { confirmExit() }
        binding.btnEditorModeToggle.setOnClickListener { toggleVisualRawMode() }
        binding.btnEditorSearchToggle.setOnClickListener {
            val show = binding.layoutEditorFind.visibility != View.VISIBLE
            binding.layoutEditorFind.visibility = if (show) View.VISIBLE else View.GONE
            if (show) binding.etEditorFind.requestFocus()
        }
        binding.btnEditorFormat.setOnClickListener { formatCode() }
        binding.btnEditorSave.setOnClickListener { saveCode() }

        binding.btnEditorScrollTop.setOnClickListener {
            if (showingRawMode) {
                binding.scrollRawEditor.smoothScrollTo(0, 0)
                scrollEditTextToTop(binding.etCodeContent)
            } else {
                val targetEt = findActiveMultiEditText()
                if (targetEt != null && (itemType.isNotEmpty() || targetEt.canScrollVertically(-1))) {
                    scrollEditTextToTop(targetEt)
                }
                binding.scrollVisualEditor.smoothScrollTo(0, 0)
            }
        }
        binding.btnEditorScrollBottom.setOnClickListener {
            if (showingRawMode) {
                binding.scrollRawEditor.post { binding.scrollRawEditor.fullScroll(ScrollView.FOCUS_DOWN) }
                scrollEditTextToBottom(binding.etCodeContent)
            } else {
                val targetEt = findActiveMultiEditText()
                if (targetEt != null && (itemType.isNotEmpty() || targetEt.canScrollVertically(1))) {
                    scrollEditTextToBottom(targetEt)
                }
                binding.scrollVisualEditor.post { binding.scrollVisualEditor.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        binding.etEditorFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (showingRawMode) {
                    recomputeMatches()
                } else {
                    val q = s?.toString()?.trim().orEmpty()
                    filterJob?.cancel()
                    filterJob = lifecycleScope.launch {
                        delay(350)
                        flushVisualEdits()
                        buildVisualSections(q)
                    }
                }
            }
        })
        binding.btnFindPrev.setOnClickListener { if (showingRawMode) jumpMatch(-1) }
        binding.btnFindNext.setOnClickListener { if (showingRawMode) jumpMatch(1) }

        binding.etCodeContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressRawWatcher) return
                dirty = true
            }
        })

        binding.tvEditorMeta.text = "正在读取..."
        loadCode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        VaultSheets.confirm(
            ctx = this,
            title = "放弃未保存的修改？",
            message = "当前有尚未保存的修改，退出将丢失。",
            confirmText = "放弃修改",
            cancelText = "继续编辑",
            destructive = true
        ) { finish() }
    }

    // ==================== LOAD (off main thread, parse once) ====================

    private fun readRawText(): String? {
        return when (module) {
            MODULE_THEME -> vaultRepo.readSourceRawText(recordId).second
            MODULE_CHARACTER -> {
                val rec = libRepo.getCharacterById(recordId) ?: return null
                val f = libRepo.getCharacterFile(rec)
                if (!f.exists()) return null
                if (rec.isPng) CharacterCardParser.extractJsonFromPng(f.readBytes()) else f.readText(Charsets.UTF_8)
            }
            MODULE_WORLDBOOK -> {
                val rec = libRepo.getWorldBookById(recordId) ?: return null
                val f = libRepo.getWorldBookFile(rec)
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            }
            MODULE_PRESET -> {
                val rec = libRepo.getPresetById(recordId) ?: return null
                val f = libRepo.getPresetFile(rec)
                if (f.exists()) f.readText(Charsets.UTF_8) else null
            }
            MODULE_COMPONENT -> {
                val rec = libRepo.getComponentById(recordId) ?: return null
                libRepo.readComponentText(rec)
            }
            else -> null
        }
    }

    private fun loadCode() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val raw = readRawText() ?: return@withContext null
                    val t = raw.trim()
                    val root = if (t.startsWith("{")) {
                        try { JSONObject(t) } catch (_: Exception) { null }
                    } else null
                    Triple(raw, root, raw.length)
                } catch (_: Throwable) {
                    null
                }
            }
            if (result == null) {
                toast("读取失败")
                finish()
                return@launch
            }
            plainText = result.first
            parsedRoot = result.second
            totalChars = result.third
            if (parsedRoot != null) plainText = ""
            dirty = false
            buildVisualSections("")
            updateMetaBar()
        }
    }

    private fun serializeCurrent(): String {
        val root = parsedRoot
        return if (root != null) root.toString(2) else plainText
    }

    // ==================== MODE TOGGLE ====================

    private fun toggleVisualRawMode() {
        if (!showingRawMode) {
            flushVisualEdits()
            if (totalChars > RAW_LIMIT) {
                toast("内容过大，源码模式已停用，请使用分段编辑")
                return
            }
            binding.tvEditorMeta.text = "正在生成源码..."
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { serializeCurrent() }
                showingRawMode = true
                suppressRawWatcher = true
                binding.etCodeContent.setText(text)
                suppressRawWatcher = false
                binding.scrollVisualEditor.visibility = View.GONE
                binding.scrollRawEditor.visibility = View.VISIBLE
                binding.btnEditorModeToggle.text = "分段"
                binding.btnEditorFormat.visibility = View.VISIBLE
                updateMetaBar()
            }
        } else {
            val text = binding.etCodeContent.text?.toString().orEmpty()
            lifecycleScope.launch {
                val parsed = withContext(Dispatchers.IO) {
                    val t = text.trim()
                    if (t.startsWith("{")) {
                        try { JSONObject(t) } catch (_: Exception) { null }
                    } else null
                }
                val t = text.trim()
                if (t.startsWith("{") && parsed == null) {
                    toast("JSON 语法有误，请修正后再切回分段")
                    return@launch
                }
                parsedRoot = parsed
                plainText = if (parsed == null) text else ""
                totalChars = text.length
                showingRawMode = false
                binding.scrollRawEditor.visibility = View.GONE
                binding.scrollVisualEditor.visibility = View.VISIBLE
                binding.btnEditorModeToggle.text = "源码"
                binding.btnEditorFormat.visibility = View.GONE
                buildVisualSections(binding.etEditorFind.text?.toString()?.trim().orEmpty())
                updateMetaBar()
            }
        }
    }

    private fun flushVisualEdits() {
        if (showingRawMode) return
        for (cb in visualCommitCallbacks) {
            try { cb() } catch (_: Exception) {}
        }
    }

    private fun updateMetaBar() {
        val modLabel = when (module) {
            MODULE_THEME -> "美化"
            MODULE_CHARACTER -> "角色卡"
            MODULE_WORLDBOOK -> "世界书"
            MODULE_PRESET -> "预设"
            MODULE_COMPONENT -> "组件"
            else -> "编辑"
        }
        val size = if (totalChars >= 10_000) "${totalChars / 1000}K 字符" else "$totalChars 字符"
        val mode = if (showingRawMode) "源码" else "分段"
        binding.tvEditorMeta.text = "$modLabel · $mode · $size · ${if (dirty) "未保存" else "已同步"}"
    }

    // ==================== VISUAL BUILDERS ====================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int): Int = ContextCompat.getColor(this, res)

    private fun buildVisualSections(q0: String) {
        visualCommitCallbacks.clear()
        val box = binding.containerVisualSections
        box.removeAllViews()
        val q = q0.lowercase()
        val root = parsedRoot
        try {
            if (itemType.isNotEmpty()) {
                buildSingleItemVisual(box, root)
                return
            }
            when (module) {
                MODULE_THEME -> if (root != null) buildThemeVisual(box, root, q) else buildPlainVisual(box, "CSS 样式代码")
                MODULE_CHARACTER -> if (root != null) buildCharacterVisual(box, root, q) else buildPlainVisual(box, "角色卡内容")
                MODULE_PRESET -> if (root != null) buildPresetVisual(box, root, q) else buildPlainVisual(box, "预设内容")
                MODULE_WORLDBOOK -> if (root != null) buildWorldEntries(box, root, q, null) else buildPlainVisual(box, "世界书内容")
                MODULE_COMPONENT -> buildComponentVisual(box, root)
                else -> buildPlainVisual(box, "内容")
            }
        } catch (_: Throwable) {
            box.removeAllViews()
            buildPlainVisual(box, "内容")
        }
    }

    private fun buildSingleItemVisual(box: LinearLayout, root: JSONObject?) {
        when (itemType) {
            ITEM_CHAR_FIELD -> {
                if (root == null) { buildPlainVisual(box, binding.tvEditorTitle.text.toString()); return }
                val target = root.optJSONObject("data") ?: root
                val key = itemKey
                val label = binding.tvEditorTitle.text?.toString().orEmpty().ifBlank { key }
                val isSingle = key == "name"
                addEntryCard(
                    box, label, "${target.optString(key, root.optString(key, "")).length} 字", null,
                    listOf(LazyField(
                        label,
                        { target.optString(key, root.optString(key, "")) },
                        { v -> target.put(key, v); if (root !== target && root.has(key)) root.put(key, v) },
                        single = isSingle,
                        minLines = if (isSingle) 1 else 18
                    )),
                    expanded = true
                )
            }
            ITEM_CHAR_ALT_GREETING -> {
                if (root == null) { buildPlainVisual(box, "备用开场白"); return }
                val target = root.optJSONObject("data") ?: root
                val alt = target.optJSONArray("alternate_greetings")
                if (alt == null || itemIndex !in 0 until alt.length()) {
                    buildPlainRawNotice(box); return
                }
                val idx = itemIndex
                addEntryCard(
                    box, "备用开场白 #${idx + 1}", "${alt.optString(idx, "").length} 字", null,
                    listOf(LazyField("开场白", { alt.optString(idx, "") }, { alt.put(idx, it) }, minLines = 18)),
                    expanded = true
                )
            }
            ITEM_CHAR_WB_ENTRY, ITEM_WB_ENTRY -> {
                if (root == null) { buildPlainVisual(box, "条目"); return }
                val container = if (itemType == ITEM_CHAR_WB_ENTRY) {
                    (root.optJSONObject("data") ?: root).optJSONObject("character_book")
                } else root
                val entry = container?.let { findWorldEntryObj(it, itemIndex, itemKey) }
                if (entry == null) {
                    buildPlainRawNotice(box); return
                }
                val title = entry.optString("comment", entry.optString("name", "")).ifBlank { "条目" }
                addEntryCard(
                    box, title, "${entry.optString("content", "").length} 字", null,
                    listOf(
                        LazyField("条目名称", { entry.optString("comment", entry.optString("name", "")) }, {
                            if (entry.has("comment") || !entry.has("name")) entry.put("comment", it.trim()) else entry.put("name", it.trim())
                        }, single = true),
                        LazyField("触发关键词（逗号分隔）", {
                            val a = entry.optJSONArray("key") ?: entry.optJSONArray("keys")
                            buildString {
                                if (a != null) for (k in 0 until a.length()) {
                                    if (isNotEmpty()) append(", ")
                                    append(a.optString(k, ""))
                                }
                            }
                        }, { s ->
                            val parts = s.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                            entry.put(if (entry.has("keys") && !entry.has("key")) "keys" else "key", JSONArray(parts))
                        }, single = true),
                        LazyField("正文", { entry.optString("content", "") }, { entry.put("content", it) }, minLines = 16)
                    ),
                    expanded = true
                )
            }
            ITEM_PRESET_PROMPT -> {
                val arr = root?.optJSONArray("prompts")
                if (arr == null) { buildPlainRawNotice(box); return }
                var found: JSONObject? = null
                if (itemKey.isNotEmpty()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("identifier", "") == itemKey) { found = o; break }
                    }
                }
                if (found == null && itemIndex in 0 until arr.length()) {
                    found = arr.optJSONObject(itemIndex)
                }
                val p = found ?: run { buildPlainRawNotice(box); return }
                val pName = p.optString("name", p.optString("identifier", "提示词"))
                addEntryCard(
                    box, pName, "${p.optString("role", "system")} · ${p.optString("content", "").length} 字", null,
                    listOf(
                        LazyField("标题", { p.optString("name", "") }, { p.put("name", it.trim()) }, single = true),
                        LazyField("正文", { p.optString("content", "") }, { p.put("content", it) }, minLines = 18)
                    ),
                    expanded = true
                )
            }
            ITEM_THEME_CSS -> {
                if (root == null) {
                    buildPlainVisual(box, "自定义 CSS")
                } else {
                    val css = root.optString("custom_css", "")
                    addEntryCard(
                        box, "自定义 CSS", "${css.length} 字", null,
                        listOf(LazyField("CSS", { root.optString("custom_css", "") }, { root.put("custom_css", it) }, mono = true, minLines = 20)),
                        expanded = true
                    )
                }
            }
            else -> buildPlainVisual(box, "内容")
        }
    }

    private fun findWorldEntryObj(container: JSONObject, uid: Int, fallbackTitle: String): JSONObject? {
        val list = mutableListOf<JSONObject>()
        container.optJSONObject("entries")?.let { o ->
            val it = o.keys()
            while (it.hasNext()) o.optJSONObject(it.next())?.let { e -> list.add(e) }
        } ?: container.optJSONArray("entries")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { e -> list.add(e) }
        }
        if (uid >= 0) {
            list.firstOrNull { it.optInt("uid", it.optInt("id", -1)) == uid }?.let { return it }
        }
        if (fallbackTitle.isNotEmpty()) {
            list.firstOrNull {
                it.optString("comment", it.optString("name", "")) == fallbackTitle
            }?.let { return it }
        }
        return if (uid in list.indices) list[uid] else null
    }

    private fun buildPlainVisual(box: LinearLayout, title: String) {
        addEntryCard(
            box, title, "${plainText.length} 字", null,
            listOf(LazyField("", { plainText }, { plainText = it }, mono = true, minLines = 16)),
            expanded = true
        )
    }

    /** Theme: CSS first (unescaped), colours collapsed below. */
    private fun buildThemeVisual(box: LinearLayout, root: JSONObject, q: String) {
        addEntryCard(
            box, "美化名称", null, null,
            listOf(LazyField("名称", { root.optString("name", "") }, { root.put("name", it.trim()) }, single = true)),
            expanded = true
        )
        val css = root.optString("custom_css", "")
        addEntryCard(
            box, "自定义 CSS", "${css.length} 字", null,
            listOf(LazyField("CSS", { root.optString("custom_css", "") }, { root.put("custom_css", it) }, mono = true, minLines = 14)),
            expanded = css.length in 1..MAX_EAGER_CHARS,
            preview = css
        )

        val keys = listOf(
            "main_text_color" to "主文字颜色", "italics_text_color" to "斜体颜色",
            "quote_text_color" to "引号颜色", "underline_text_color" to "下划线颜色",
            "blur_tint_color" to "毛玻璃底色", "chat_tint_color" to "聊天区底色",
            "user_mes_blur_tint_color" to "用户气泡色", "bot_mes_blur_tint_color" to "角色气泡色",
            "border_color" to "边框颜色", "shadow_color" to "阴影颜色",
            "blur_strength" to "模糊强度", "font_scale" to "字体缩放", "chat_width" to "聊天宽度"
        )
        val items = mutableListOf<() -> Unit>()
        for ((key, label) in keys) {
            if (!root.has(key)) continue
            val v = root.opt(key)?.toString().orEmpty()
            if (q.isNotEmpty() && !label.contains(q) && !v.lowercase().contains(q)) continue
            items.add {
                addEntryCard(
                    box, label, v.take(24), null,
                    listOf(LazyField(key, { root.opt(key)?.toString().orEmpty() }, { s ->
                        val orig = root.opt(key)
                        if (orig is Number) {
                            val n = s.trim().toDoubleOrNull()
                            if (n != null) { if (s.contains('.')) root.put(key, n) else root.put(key, n.toInt()) }
                        } else root.put(key, s.trim())
                    }, single = true, mono = true))
                )
            }
        }
        addPaged(box, items)
    }

    private fun buildCharacterVisual(box: LinearLayout, root: JSONObject, q: String) {
        val target = root.optJSONObject("data") ?: root
        fun f(key: String, hint: String, single: Boolean = false) = LazyField(
            hint,
            { target.optString(key, root.optString(key, "")) },
            { v -> target.put(key, v); if (root !== target && root.has(key)) root.put(key, v) },
            single = single, minLines = if (single) 1 else 6
        )
        fun matches(label: String, v: String) =
            q.isEmpty() || label.lowercase().contains(q) || v.lowercase().contains(q)

        val name = target.optString("name", root.optString("name", ""))
        addEntryCard(box, "角色名称", null, null, listOf(f("name", "名称", true)), expanded = true)

        val firstMes = target.optString("first_mes", "")
        if (matches("默认开场白", firstMes)) {
            addEntryCard(box, "默认开场白", "${firstMes.length} 字", null, listOf(f("first_mes", "开场白")),
                expanded = firstMes.length in 1..MAX_EAGER_CHARS, preview = firstMes)
        }

        val alt = target.optJSONArray("alternate_greetings")
        if (alt != null && alt.length() > 0) {
            addSectionTitle(box, "备用开场白 (${alt.length()})")
            val items = mutableListOf<() -> Unit>()
            for (i in 0 until alt.length()) {
                val g = alt.optString(i, "")
                if (!matches("备用开场白", g)) continue
                items.add {
                    addEntryCard(box, "备用开场白 #${i + 1}", "${g.length} 字", null,
                        listOf(LazyField("开场白", { alt.optString(i, "") }, { alt.put(i, it) })), preview = g)
                }
            }
            addPaged(box, items)
        }

        val book = target.optJSONObject("character_book")
        if (book != null) {
            val n = (book.optJSONArray("entries")?.length() ?: book.optJSONObject("entries")?.length() ?: 0)
            if (n > 0) {
                addSectionTitle(box, "内置世界书 ($n)")
                buildWorldEntries(box, book, q, null)
            }
        }

        addSectionTitle(box, "其他设定")
        listOf(
            Triple("description", "角色设定", 0), Triple("personality", "性格摘要", 0),
            Triple("scenario", "情景设定", 0), Triple("system_prompt", "系统提示词", 0),
            Triple("mes_example", "对话示例", 0), Triple("creator_notes", "作者备注", 0)
        ).forEach { (k, label, _) ->
            val v = target.optString(k, root.optString(k, ""))
            if (v.isEmpty() && q.isNotEmpty()) return@forEach
            if (!matches(label, v)) return@forEach
            addEntryCard(box, label, "${v.length} 字", null, listOf(f(k, label)), preview = v)
        }
        if (name.isEmpty()) updateMetaBar()
    }

    private fun buildPresetVisual(box: LinearLayout, root: JSONObject, q: String) {
        val arr = root.optJSONArray("prompts")
        if (arr == null || arr.length() == 0) {
            buildPlainRawNotice(box)
            return
        }
        val items = mutableListOf<() -> Unit>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val pName = p.optString("name", p.optString("identifier", "提示词 #${i + 1}"))
            val content = p.optString("content", "")
            if (q.isNotEmpty() && !pName.lowercase().contains(q) && !content.lowercase().contains(q)) continue
            val enabled = p.optBoolean("enabled", true)
            items.add {
                addEntryCard(
                    box, pName.ifBlank { "提示词 #${i + 1}" },
                    "#${i + 1} · ${p.optString("role", "system")} · ${content.length} 字",
                    if (enabled) Color.parseColor("#16A34A") else Color.parseColor("#8E8E98"),
                    listOf(
                        LazyField("标题", { p.optString("name", "") }, { p.put("name", it.trim()) }, single = true),
                        LazyField("正文", { p.optString("content", "") }, { p.put("content", it) }, minLines = 6)
                    ),
                    preview = content
                )
            }
        }
        addPaged(box, items)
    }

    /** Works for both a worldbook root and a character's character_book (entries as object or array). */
    private fun buildWorldEntries(box: LinearLayout, container: JSONObject, q: String, @Suppress("UNUSED_PARAMETER") unused: String?) {
        val list = mutableListOf<JSONObject>()
        container.optJSONObject("entries")?.let { o ->
            val it = o.keys()
            while (it.hasNext()) o.optJSONObject(it.next())?.let { e -> list.add(e) }
        } ?: container.optJSONArray("entries")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { e -> list.add(e) }
        }
        if (list.isEmpty()) {
            buildPlainRawNotice(box)
            return
        }
        val items = mutableListOf<() -> Unit>()
        list.forEachIndexed { idx, e ->
            val comment = e.optString("comment", e.optString("name", ""))
            val keyArr = e.optJSONArray("key") ?: e.optJSONArray("keys")
            val keysJoined = buildString {
                if (keyArr != null) for (k in 0 until minOf(keyArr.length(), 8)) {
                    if (isNotEmpty()) append(", ")
                    append(keyArr.optString(k, ""))
                }
            }
            val content = e.optString("content", "")
            if (q.isNotEmpty() && !comment.lowercase().contains(q) && !keysJoined.lowercase().contains(q) &&
                !content.lowercase().contains(q)) return@forEachIndexed
            val enabled = if (e.has("disable")) !e.optBoolean("disable", false) else e.optBoolean("enabled", true)
            val dot = when {
                !enabled -> Color.parseColor("#8E8E98")
                e.optBoolean("constant", false) -> Color.parseColor("#3B82F6")
                else -> Color.parseColor("#16A34A")
            }
            items.add {
                addEntryCard(
                    box, comment.ifBlank { keysJoined.ifBlank { "条目 #${idx + 1}" } },
                    "${if (keysJoined.isNotEmpty()) "$keysJoined · " else ""}${content.length} 字", dot,
                    listOf(
                        LazyField("条目名称", { e.optString("comment", "") }, { e.put("comment", it.trim()) }, single = true),
                        LazyField("触发关键词（逗号分隔）", {
                            val a = e.optJSONArray("key") ?: e.optJSONArray("keys")
                            buildString {
                                if (a != null) for (k in 0 until a.length()) {
                                    if (isNotEmpty()) append(", ")
                                    append(a.optString(k, ""))
                                }
                            }
                        }, { s ->
                            val parts = s.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                            e.put(if (e.has("keys") && !e.has("key")) "keys" else "key", JSONArray(parts))
                        }, single = true),
                        LazyField("正文", { e.optString("content", "") }, { e.put("content", it) }, minLines = 6)
                    ),
                    preview = content
                )
            }
        }
        addPaged(box, items)
    }

    private fun buildComponentVisual(box: LinearLayout, root: JSONObject?) {
        if (root != null) {
            if (root.has("findRegex") && root.has("replaceString")) {
                addPreviewActionButton(box, "渲染预览") {
                    flushVisualEdits()
                    val title = root.optString("scriptName", binding.tvEditorTitle.text?.toString().orEmpty()).ifBlank { "正则预览" }
                    ComponentPreviewActivity.openRegex(
                        this,
                        title,
                        root.optString("findRegex", ""),
                        root.optString("replaceString", ""),
                        ""
                    )
                }
                addEntryCard(box, "正则名称", null, null,
                    listOf(LazyField("名称", { root.optString("scriptName", "") }, { root.put("scriptName", it.trim()) }, single = true)),
                    expanded = true)
                addEntryCard(box, "查找正则", null, null,
                    listOf(LazyField("正则", { root.optString("findRegex", "") }, { root.put("findRegex", it) }, mono = true, minLines = 3)),
                    expanded = true)
                val rep = root.optString("replaceString", "")
                addEntryCard(box, "替换内容", "${rep.length} 字", null,
                    listOf(LazyField("替换内容", { root.optString("replaceString", "") }, { root.put("replaceString", it) }, mono = true, minLines = 12)),
                    preview = rep)
                return
            }
            if (root.has("content") || root.has("script")) {
                val key = if (root.has("content")) "content" else "script"
                addPreviewActionButton(box, "沙盒预览") {
                    flushVisualEdits()
                    val title = root.optString("name", binding.tvEditorTitle.text?.toString().orEmpty()).ifBlank { "脚本预览" }
                    ComponentPreviewActivity.openHelper(this, title, root.optString(key, ""), "")
                }
                addEntryCard(box, "脚本名称", null, null,
                    listOf(LazyField("名称", { root.optString("name", "") }, { root.put("name", it.trim()) }, single = true)),
                    expanded = true)
                val code = root.optString(key, "")
                addEntryCard(box, "脚本代码", "${code.length} 字", null,
                    listOf(LazyField("代码", { root.optString(key, "") }, { root.put(key, it) }, mono = true, minLines = 15)),
                    preview = code)
                return
            }
            buildPlainRawNotice(box)
            return
        }
        buildPlainVisual(box, "组件代码")
    }

    private fun addPreviewActionButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(col(R.color.on_accent))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(col(R.color.accent))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { onClick() }
        })
    }

    private fun buildPlainRawNotice(box: LinearLayout) {
        box.addView(TextView(this).apply {
            text = "该内容结构较特殊，请点右上角「源码」编辑"
            setTextColor(col(R.color.text_muted))
            textSize = 12.5f
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
    }

    // ==================== LAZY CARD UI ====================

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(col(R.color.text_primary))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(10), dp(2), dp(6))
        })
    }

    /** Adds items in chunks; "show more" appends the next chunk instead of inflating thousands of views at once. */
    private fun addPaged(parent: LinearLayout, items: List<() -> Unit>) {
        var shown = 0
        lateinit var moreBtn: TextView
        fun showNext() {
            val end = minOf(shown + PAGE_SIZE, items.size)
            if (moreBtn.parent != null) parent.removeView(moreBtn)
            for (i in shown until end) items[i]()
            shown = end
            if (shown < items.size) {
                moreBtn.text = "显示更多（剩余 ${items.size - shown}）"
                parent.addView(moreBtn)
            }
        }
        moreBtn = TextView(this).apply {
            setTextColor(col(R.color.text_primary))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pill_accent)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
            setOnClickListener { showNext() }
        }
        showNext()
    }

    private fun addEntryCard(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        dotColor: Int?,
        fields: List<LazyField>,
        expanded: Boolean = false,
        preview: String? = null
    ) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(col(R.color.surface))
            strokeWidth = dp(1)
            strokeColor = col(R.color.border)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        card.addView(inner)
        parent.addView(card)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (dotColor != null) {
            header.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            })
        }
        header.addView(TextView(this).apply {
            text = title
            setTextColor(col(R.color.text_primary))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnInTop = TextView(this).apply {
            text = "顶"
            setTextColor(col(R.color.text_muted))
            textSize = 11f
            setBackgroundResource(R.drawable.bg_pill_accent)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5) }
            visibility = View.GONE
        }
        val btnInBottom = TextView(this).apply {
            text = "底"
            setTextColor(col(R.color.text_muted))
            textSize = 11f
            setBackgroundResource(R.drawable.bg_pill_accent)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            visibility = View.GONE
        }
        val tvToggle = TextView(this).apply {
            setTextColor(col(R.color.text_muted))
            textSize = 11f
        }
        header.addView(btnInTop)
        header.addView(btnInBottom)
        header.addView(tvToggle)
        inner.addView(header)

        if (!subtitle.isNullOrBlank()) {
            inner.addView(TextView(this).apply {
                text = subtitle
                setTextColor(col(R.color.text_muted))
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
        }

        val tvPreview = if (!preview.isNullOrBlank()) TextView(this).apply {
            text = preview.take(PREVIEW_CHARS).replace('\n', ' ')
            setTextColor(col(R.color.text_muted))
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }.also { inner.addView(it) } else null

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        inner.addView(body)

        val built = mutableListOf<Pair<LazyField, EditText>>()
        val touched = HashSet<EditText>()
        var inflated = false

        fun inflateBody() {
            if (inflated) return
            inflated = true
            for (fld in fields) {
                val value = fld.get()
                if (value.length > MAX_EDIT_CHARS) {
                    body.addView(TextView(this).apply {
                        text = "内容过大（${value.length / 1000}K 字），仅预览前 3000 字，保持原样不会改动。\n\n" + value.take(3000)
                        setTextColor(col(R.color.text_muted))
                        textSize = 12f
                        typeface = if (fld.mono) Typeface.MONOSPACE else Typeface.DEFAULT
                        setPadding(dp(2), dp(8), dp(2), dp(2))
                    })
                    continue
                }
                val et = EditText(this).apply {
                    setText(value)
                    if (fld.hint.isNotEmpty()) hint = fld.hint
                    setBackgroundResource(R.drawable.bg_input)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setTextColor(col(R.color.text_primary))
                    setHintTextColor(col(R.color.text_muted))
                    textSize = if (fld.mono) 12f else 13f
                    if (fld.mono) typeface = Typeface.MONOSPACE
                    if (fld.single) {
                        isSingleLine = true
                    } else {
                        isSingleLine = false
                        minLines = fld.minLines
                        maxLines = if (itemType.isNotEmpty()) 40 else 24
                        gravity = Gravity.TOP or Gravity.START
                        isVerticalScrollBarEnabled = true
                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) lastFocusedMultiEdit = this
                        }
                        setOnTouchListener { v, event ->
                            lastFocusedMultiEdit = this
                            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                if ((event.action and android.view.MotionEvent.ACTION_MASK) == android.view.MotionEvent.ACTION_UP) {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                et.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        touched.add(et)
                        if (!dirty) {
                            dirty = true
                            updateMetaBar()
                        }
                    }
                })
                body.addView(et)
                built.add(fld to et)
            }
        }

        fun primaryMultiEdit(): EditText? = built.lastOrNull { !it.first.single }?.second

        btnInTop.setOnClickListener {
            primaryMultiEdit()?.let { scrollEditTextToTop(it) }
        }
        btnInBottom.setOnClickListener {
            primaryMultiEdit()?.let { scrollEditTextToBottom(it) }
        }

        fun setExpanded(show: Boolean) {
            if (show) inflateBody()
            body.visibility = if (show) View.VISIBLE else View.GONE
            tvPreview?.visibility = if (show) View.GONE else View.VISIBLE
            tvToggle.text = if (show) "收起 ▴" else "编辑 ▾"
            val hasMulti = show && primaryMultiEdit() != null
            btnInTop.visibility = if (hasMulti) View.VISIBLE else View.GONE
            btnInBottom.visibility = if (hasMulti) View.VISIBLE else View.GONE
            if (hasMulti && itemType.isNotEmpty()) {
                lastFocusedMultiEdit = primaryMultiEdit()
            }
        }
        header.setOnClickListener { setExpanded(body.visibility != View.VISIBLE) }
        tvPreview?.setOnClickListener { setExpanded(true) }
        setExpanded(expanded)

        visualCommitCallbacks.add {
            for ((fld, et) in built) {
                if (et in touched) fld.set(et.text?.toString().orEmpty())
            }
            touched.clear()
        }
    }

    // ==================== RAW HELPERS & SAVE ====================

    private fun formatCode() {
        val cur = binding.etCodeContent.text?.toString().orEmpty()
        val t = cur.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) {
            toast("当前不是 JSON 格式")
            return
        }
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    if (t.startsWith("{")) JSONObject(t).toString(2) else JSONArray(t).toString(2)
                } catch (e: Exception) { null }
            }
            if (out == null) {
                toast("JSON 语法有误")
            } else {
                binding.etCodeContent.setText(out)
                toast("已格式化")
            }
        }
    }

    private fun recomputeMatches() {
        val query = binding.etEditorFind.text?.toString().orEmpty()
        val text = binding.etCodeContent.text?.toString().orEmpty()
        if (query.isEmpty() || text.isEmpty()) {
            matchIndices = emptyList()
            currentMatchPos = 0
            binding.tvFindCount.text = "0/0"
            return
        }
        val list = mutableListOf<Int>()
        var idx = text.indexOf(query, 0, ignoreCase = true)
        while (idx >= 0 && list.size < 500) {
            list.add(idx)
            idx = text.indexOf(query, idx + query.length.coerceAtLeast(1), ignoreCase = true)
        }
        matchIndices = list
        currentMatchPos = 0
        if (list.isEmpty()) binding.tvFindCount.text = "0/0" else focusMatch(0)
    }

    private fun jumpMatch(delta: Int) {
        if (matchIndices.isEmpty()) return
        focusMatch((currentMatchPos + delta + matchIndices.size) % matchIndices.size)
    }

    private fun focusMatch(pos: Int) {
        if (matchIndices.isEmpty()) return
        currentMatchPos = pos.coerceIn(0, matchIndices.lastIndex)
        val start = matchIndices[currentMatchPos]
        val len = binding.etEditorFind.text?.length ?: 0
        binding.tvFindCount.text = "${currentMatchPos + 1}/${matchIndices.size}"
        binding.etCodeContent.requestFocus()
        binding.etCodeContent.setSelection(start, (start + len).coerceAtMost(binding.etCodeContent.text?.length ?: start))
    }

    private fun saveCode() {
        val rawText: String? = if (showingRawMode) binding.etCodeContent.text?.toString().orEmpty() else null
        if (!showingRawMode) flushVisualEdits()
        binding.btnEditorSave.isEnabled = false

        lifecycleScope.launch {
            val (ok, msg, saved) = withContext(Dispatchers.IO) {
                try {
                    val newCode = rawText ?: serializeCurrent()
                    if (newCode.isBlank()) return@withContext Triple(false, "内容不能为空", null as String?)
                    val t = newCode.trim()
                    val needsJson = module == MODULE_CHARACTER || module == MODULE_WORLDBOOK ||
                        module == MODULE_PRESET || (module == MODULE_THEME && t.startsWith("{"))
                    if (needsJson) {
                        try {
                            if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
                        } catch (e: Exception) {
                            return@withContext Triple(false, "JSON 语法有误：${e.message}", null)
                        }
                    }
                    val res: Pair<Boolean, String> = when (module) {
                        MODULE_THEME -> vaultRepo.overwriteSourceContent(recordId, newCode).let { it to "保存美化失败" }
                        MODULE_CHARACTER ->
                            if (!CharacterCardParser.isCharacterCardJson(newCode)) false to "缺少角色卡必要字段"
                            else libRepo.updateCharacterRawJson(recordId, newCode) to "保存角色卡失败"
                        MODULE_WORLDBOOK ->
                            if (!WorldBookParser.isWorldBookJson(newCode)) false to "缺少世界书 entries"
                            else libRepo.updateWorldBookRawJson(recordId, newCode) to "保存世界书失败"
                        MODULE_PRESET ->
                            if (!PresetParser.isPresetJson(newCode)) false to "缺少预设 prompts"
                            else libRepo.updatePresetRawJson(recordId, newCode) to "保存预设失败"
                        MODULE_COMPONENT -> {
                            val rec = libRepo.getComponentById(recordId)
                            if (rec == null) false to "找不到组件"
                            else if (ComponentParser.parse(newCode, rec.fileName) == null) false to "组件代码格式无效"
                            else libRepo.updateComponentRawText(recordId, newCode) to "保存组件失败"
                        }
                        else -> false to "未知模块"
                    }
                    Triple(res.first, if (res.first) "" else res.second, if (res.first) newCode else null)
                } catch (e: Throwable) {
                    Triple(false, e.message ?: "保存异常", null as String?)
                }
            }
            binding.btnEditorSave.isEnabled = true
            if (ok) {
                if (saved != null) totalChars = saved.length
                dirty = false
                updateMetaBar()
                com.stvault.app.core.AppLogManager.record(
                    this@CodeEditorActivity,
                    com.stvault.app.core.AppLogManager.CAT_LIBRARY,
                    "编辑",
                    binding.tvEditorTitle.text?.toString().orEmpty().ifBlank { "编辑内容" },
                    detail = if (itemType.isNotEmpty()) "单条目编辑已保存" else "整文件编辑已保存（$totalChars 字）",
                    source = "代码编辑"
                )
                toast("已保存")
                setResult(RESULT_OK)
            } else {
                toast(msg)
            }
        }
    }

    private fun findActiveMultiEditText(): EditText? {
        val focused = currentFocus as? EditText
        if (focused != null && focused !== binding.etEditorFind && focused.isAttachedToWindow) {
            return focused
        }
        val last = lastFocusedMultiEdit
        if (last != null && last.isAttachedToWindow && last.isShown) {
            return last
        }
        return null
    }

    private fun scrollEditTextToTop(et: EditText) {
        et.post {
            try {
                et.setSelection(0)
            } catch (_: Exception) {
            }
            et.scrollTo(0, 0)
        }
    }

    private fun scrollEditTextToBottom(et: EditText) {
        et.post {
            val len = et.text?.length ?: 0
            try {
                et.setSelection(len)
            } catch (_: Exception) {
            }
            val layout = et.layout
            if (layout != null) {
                val contentHeight = layout.height + et.totalPaddingTop + et.totalPaddingBottom
                val maxScroll = (contentHeight - et.height).coerceAtLeast(0)
                et.scrollTo(0, maxScroll)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MODULE = "module"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ITEM_TYPE = "item_type"
        const val EXTRA_ITEM_KEY = "item_key"
        const val EXTRA_ITEM_INDEX = "item_index"

        const val MODULE_THEME = "theme"
        const val MODULE_CHARACTER = "character"
        const val MODULE_WORLDBOOK = "worldbook"
        const val MODULE_PRESET = "preset"
        const val MODULE_COMPONENT = "component"

        const val ITEM_CHAR_FIELD = "char_field"
        const val ITEM_CHAR_ALT_GREETING = "char_alt_greeting"
        const val ITEM_CHAR_WB_ENTRY = "char_wb_entry"
        const val ITEM_WB_ENTRY = "wb_entry"
        const val ITEM_PRESET_PROMPT = "preset_prompt"
        const val ITEM_THEME_CSS = "theme_css"

        private const val PAGE_SIZE = 30
        private const val PREVIEW_CHARS = 140
        private const val MAX_EAGER_CHARS = 6_000     // auto-expand only small fields
        private const val MAX_EDIT_CHARS = 150_000    // above this a field is preview-only
        private const val RAW_LIMIT = 400_000         // raw source mode disabled beyond this

        fun createIntent(ctx: Context, module: String, id: String, title: String): Intent {
            return Intent(ctx, CodeEditorActivity::class.java).apply {
                putExtra(EXTRA_MODULE, module)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
            }
        }

        fun createSingleItemIntent(
            ctx: Context,
            module: String,
            id: String,
            title: String,
            itemType: String,
            itemKey: String = "",
            itemIndex: Int = -1
        ): Intent {
            return Intent(ctx, CodeEditorActivity::class.java).apply {
                putExtra(EXTRA_MODULE, module)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ITEM_TYPE, itemType)
                putExtra(EXTRA_ITEM_KEY, itemKey)
                putExtra(EXTRA_ITEM_INDEX, itemIndex)
            }
        }

        fun open(ctx: Context, module: String, id: String, title: String) {
            ctx.startActivity(createIntent(ctx, module, id, title))
        }
    }
}
