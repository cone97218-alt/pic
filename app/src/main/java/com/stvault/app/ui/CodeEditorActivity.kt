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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CodeEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeEditorBinding
    private lateinit var vaultRepo: VaultRepository
    private lateinit var libRepo: ResourceLibraryRepository

    private var module: String = ""
    private var recordId: String = ""
    private var initialCode: String = ""
    private var currentCode: String = ""
    private var dirty: Boolean = false
    private var showingRawMode: Boolean = false
    private var suppressRawWatcher: Boolean = false

    private var matchIndices: List<Int> = emptyList()
    private var currentMatchPos: Int = 0

    // Tracks commit callbacks from currently mounted visual fields so switching to Raw or pressing Save flushes them
    private val visualCommitCallbacks = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        module = intent.getStringExtra(EXTRA_MODULE).orEmpty()
        recordId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvEditorTitle.text = title.ifBlank { "编辑" }

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
            } else {
                binding.scrollVisualEditor.smoothScrollTo(0, 0)
            }
        }
        binding.btnEditorScrollBottom.setOnClickListener {
            if (showingRawMode) {
                binding.scrollRawEditor.post {
                    binding.scrollRawEditor.fullScroll(ScrollView.FOCUS_DOWN)
                }
            } else {
                binding.scrollVisualEditor.post {
                    binding.scrollVisualEditor.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        binding.etEditorFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!showingRawMode) {
                    val q = s?.toString()?.trim().orEmpty()
                    buildVisualSections(q)
                } else {
                    recomputeMatches()
                }
            }
        })
        binding.btnFindPrev.setOnClickListener {
            if (showingRawMode) jumpMatch(-1)
        }
        binding.btnFindNext.setOnClickListener {
            if (showingRawMode) jumpMatch(1)
        }

        loadCode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        flushVisualEditsToCode()
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
        ) {
            finish()
        }
    }

    private fun loadCode() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    when (module) {
                        MODULE_THEME -> prettyJsonIfPossible(vaultRepo.readSourceRawText(recordId).second)
                        MODULE_CHARACTER -> {
                            val rec = libRepo.getCharacterById(recordId) ?: return@withContext null
                            val f = libRepo.getCharacterFile(rec)
                            if (!f.exists()) return@withContext null
                            val raw = if (rec.isPng) {
                                CharacterCardParser.extractJsonFromPng(f.readBytes())
                            } else {
                                f.readText(Charsets.UTF_8)
                            } ?: return@withContext null
                            prettyJsonIfPossible(raw)
                        }
                        MODULE_WORLDBOOK -> {
                            val rec = libRepo.getWorldBookById(recordId) ?: return@withContext null
                            val f = libRepo.getWorldBookFile(rec)
                            if (f.exists()) prettyJsonIfPossible(f.readText(Charsets.UTF_8)) else null
                        }
                        MODULE_PRESET -> {
                            val rec = libRepo.getPresetById(recordId) ?: return@withContext null
                            val f = libRepo.getPresetFile(rec)
                            if (f.exists()) prettyJsonIfPossible(f.readText(Charsets.UTF_8)) else null
                        }
                        MODULE_COMPONENT -> {
                            val rec = libRepo.getComponentById(recordId) ?: return@withContext null
                            prettyJsonIfPossible(libRepo.readComponentText(rec))
                        }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded == null) {
                toast("读取失败")
                finish()
                return@launch
            }
            initialCode = loaded
            currentCode = loaded
            suppressRawWatcher = true
            binding.etCodeContent.setText(loaded)
            suppressRawWatcher = false
            dirty = false

            binding.etCodeContent.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressRawWatcher) return
                    currentCode = s?.toString().orEmpty()
                    dirty = (currentCode != initialCode)
                    updateMetaBar()
                }
            })

            buildVisualSections("")
            updateMetaBar()
        }
    }

    private fun toggleVisualRawMode() {
        if (!showingRawMode) {
            // Switching Visual -> Raw
            flushVisualEditsToCode()
            showingRawMode = true
            suppressRawWatcher = true
            binding.etCodeContent.setText(currentCode)
            suppressRawWatcher = false
            binding.scrollVisualEditor.visibility = View.GONE
            binding.scrollRawEditor.visibility = View.VISIBLE
            binding.btnEditorModeToggle.text = "分段"
            binding.btnEditorFormat.visibility = View.VISIBLE
            updateMetaBar()
        } else {
            // Switching Raw -> Visual
            currentCode = binding.etCodeContent.text?.toString().orEmpty()
            showingRawMode = false
            binding.scrollRawEditor.visibility = View.GONE
            binding.scrollVisualEditor.visibility = View.VISIBLE
            binding.btnEditorModeToggle.text = "源码"
            binding.btnEditorFormat.visibility = View.GONE
            buildVisualSections(binding.etEditorFind.text?.toString()?.trim().orEmpty())
            updateMetaBar()
        }
    }

    private fun flushVisualEditsToCode() {
        if (showingRawMode) {
            currentCode = binding.etCodeContent.text?.toString().orEmpty()
            dirty = (currentCode != initialCode)
            return
        }
        for (cb in visualCommitCallbacks) {
            try {
                cb()
            } catch (_: Exception) {
            }
        }
        dirty = (currentCode != initialCode)
    }

    private fun updateMetaBar() {
        val text = currentCode
        val lines = text.count { it == '\n' } + 1
        val modLabel = when (module) {
            MODULE_THEME -> "美化"
            MODULE_CHARACTER -> "角色卡"
            MODULE_WORLDBOOK -> "世界书"
            MODULE_PRESET -> "预设"
            MODULE_COMPONENT -> "组件"
            else -> "编辑"
        }
        val modeLabel = if (showingRawMode) "源码模式" else "分段模式"
        val stateLabel = if (dirty) "未保存" else "已同步"
        binding.tvEditorMeta.text = "$modLabel · $modeLabel · $lines 行 · ${text.length} 字符 · $stateLabel"
    }

    // ==================== VISUAL SECTION BUILDER ====================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun col(res: Int): Int = ContextCompat.getColor(this, res)

    private fun buildVisualSections(filterQuery: String) {
        visualCommitCallbacks.clear()
        val box = binding.containerVisualSections
        box.removeAllViews()
        val q = filterQuery.lowercase()
        val trimmed = currentCode.trim()

        try {
            when (module) {
                MODULE_THEME -> {
                    if (trimmed.startsWith("{")) {
                        buildThemeVisual(box, JSONObject(trimmed), q)
                    } else {
                        buildPlainCssVisual(box, currentCode)
                    }
                }
                MODULE_CHARACTER -> {
                    if (trimmed.startsWith("{")) {
                        buildCharacterVisual(box, JSONObject(trimmed), q)
                    } else {
                        buildFallbackRawNotice(box)
                    }
                }
                MODULE_PRESET -> {
                    if (trimmed.startsWith("{")) {
                        buildPresetVisual(box, JSONObject(trimmed), q)
                    } else {
                        buildFallbackRawNotice(box)
                    }
                }
                MODULE_WORLDBOOK -> {
                    if (trimmed.startsWith("{")) {
                        buildWorldBookVisual(box, JSONObject(trimmed), q)
                    } else {
                        buildFallbackRawNotice(box)
                    }
                }
                MODULE_COMPONENT -> {
                    buildComponentVisual(box, trimmed)
                }
                else -> buildFallbackRawNotice(box)
            }
        } catch (_: Exception) {
            buildFallbackRawNotice(box)
        }
    }

    /**
     * 1. Theme Visual Editor:
     *    - Prominently places `custom_css` right at the top (clean multi-line CSS editor, no JSON escaping!)
     *    - Theme name + collapsible Color & Parameter fields below
     */
    private fun buildThemeVisual(box: LinearLayout, root: JSONObject, q: String) {
        val etName = addCleanFieldCard(
            parent = box,
            title = "美化名称",
            subtitle = "name",
            initialValue = root.optString("name", ""),
            singleLine = true,
            monospace = false
        )
        val etCss = addCleanFieldCard(
            parent = box,
            title = "自定义 CSS 样式代码",
            subtitle = "custom_css（直接编辑纯净 CSS，保存时自动打包回 JSON）",
            initialValue = root.optString("custom_css", ""),
            singleLine = false,
            monospace = true,
            minLines = 14
        )

        val colorKeys = listOf(
            "main_text_color" to "主文字颜色 (main_text_color)",
            "italics_text_color" to "斜体颜色 (italics_text_color)",
            "quote_text_color" to "引号颜色 (quote_text_color)",
            "underline_text_color" to "下划线颜色 (underline_text_color)",
            "blur_tint_color" to "毛玻璃底色 (blur_tint_color)",
            "chat_tint_color" to "聊天区底色 (chat_tint_color)",
            "user_mes_blur_tint_color" to "用户气泡色 (user_mes_blur_tint_color)",
            "bot_mes_blur_tint_color" to "角色气泡色 (bot_mes_blur_tint_color)",
            "border_color" to "边框颜色 (border_color)",
            "shadow_color" to "阴影颜色 (shadow_color)",
            "blur_strength" to "模糊强度 (blur_strength)",
            "font_scale" to "字体缩放 (font_scale)",
            "chat_width" to "聊天宽度 (chat_width)"
        )

        val colorEdits = LinkedHashMap<String, EditText>()
        for ((key, label) in colorKeys) {
            if (!root.has(key)) continue
            val v = root.opt(key)?.toString().orEmpty()
            if (q.isNotEmpty() && !label.lowercase().contains(q) && !v.lowercase().contains(q)) continue
            colorEdits[key] = addCollapsibleFieldCard(
                parent = box,
                title = label.substringBefore(" ("),
                subtitle = "$key · $v",
                initialValue = v,
                singleLine = true,
                monospace = true
            )
        }

        visualCommitCallbacks.add {
            root.put("name", etName.text?.toString()?.trim().orEmpty())
            root.put("custom_css", etCss.text?.toString().orEmpty())
            for ((k, et) in colorEdits) {
                val rawVal = et.text?.toString()?.trim().orEmpty()
                val orig = root.opt(k)
                when (orig) {
                    is Number -> {
                        val num = rawVal.toDoubleOrNull()
                        if (num != null) {
                            if (rawVal.contains('.')) root.put(k, num) else root.put(k, num.toInt())
                        }
                    }
                    else -> root.put(k, rawVal)
                }
            }
            currentCode = root.toString(2)
        }
    }

    private fun buildPlainCssVisual(box: LinearLayout, rawCss: String) {
        val et = addCleanFieldCard(
            parent = box,
            title = "CSS 样式代码",
            subtitle = "直接编辑样式源码",
            initialValue = rawCss,
            singleLine = false,
            monospace = true,
            minLines = 18
        )
        visualCommitCallbacks.add {
            currentCode = et.text?.toString().orEmpty()
        }
    }

    /**
     * 2. Character Card Visual Editor:
     *    - Role Name, Description, First Greeting, Personality, Scenario, System Prompt, Creator Notes, Alternate Greetings
     */
    private fun buildCharacterVisual(box: LinearLayout, root: JSONObject, q: String) {
        val target = root.optJSONObject("data") ?: root
        val fields = listOf(
            Triple("name", "角色名称", true),
            Triple("description", "角色设定 (Description)", false),
            Triple("first_mes", "默认开场白 (First Message)", false),
            Triple("personality", "性格摘要 (Personality)", false),
            Triple("scenario", "情景设定 (Scenario)", false),
            Triple("system_prompt", "系统提示词 (System Prompt)", false),
            Triple("mes_example", "对话示例 (Mes Example)", false),
            Triple("creator_notes", "作者备注 (Creator Notes)", false)
        )
        val fieldEdits = LinkedHashMap<String, EditText>()
        for ((key, label, single) in fields) {
            val v = target.optString(key, root.optString(key, ""))
            if (q.isNotEmpty() && !label.lowercase().contains(q) && !v.lowercase().contains(q)) continue
            val expandByDefault = (key == "name" || key == "description" || key == "first_mes")
            fieldEdits[key] = if (expandByDefault) {
                addCleanFieldCard(box, label, "${v.length} 字", v, singleLine = single, monospace = false, minLines = if (single) 1 else 6)
            } else {
                addCollapsibleFieldCard(box, label, "${v.length} 字", v, singleLine = single, monospace = false)
            }
        }

        val altArr = target.optJSONArray("alternate_greetings")
        val altEdits = LinkedHashMap<Int, EditText>()
        if (altArr != null && altArr.length() > 0) {
            for (i in 0 until altArr.length()) {
                val g = altArr.optString(i, "")
                val title = "备用开场白 #${i + 1}"
                if (q.isNotEmpty() && !title.lowercase().contains(q) && !g.lowercase().contains(q)) continue
                altEdits[i] = addCollapsibleFieldCard(box, title, "${g.length} 字", g, singleLine = false, monospace = false)
            }
        }

        visualCommitCallbacks.add {
            for ((k, et) in fieldEdits) {
                val newVal = et.text?.toString().orEmpty()
                target.put(k, newVal)
                if (root.has(k)) root.put(k, newVal)
            }
            if (altArr != null) {
                for ((idx, et) in altEdits) {
                    altArr.put(idx, et.text?.toString().orEmpty())
                }
            }
            currentCode = root.toString(2)
        }
    }

    /**
     * 3. Preset Visual Editor:
     *    - Collapsible cards for each Prompt block (`name` + `content`), click to expand & edit
     */
    private fun buildPresetVisual(box: LinearLayout, root: JSONObject, q: String) {
        val promptsArr = root.optJSONArray("prompts")
        if (promptsArr == null || promptsArr.length() == 0) {
            buildFallbackRawNotice(box)
            return
        }
        val promptEdits = LinkedHashMap<Int, Pair<EditText, EditText>>()
        for (i in 0 until promptsArr.length()) {
            val pObj = promptsArr.optJSONObject(i) ?: continue
            val pName = pObj.optString("name", pObj.optString("identifier", "提示词 #${i + 1}"))
            val pRole = pObj.optString("role", "system")
            val pContent = pObj.optString("content", "")
            val enabled = pObj.optBoolean("enabled", true)
            if (q.isNotEmpty() && !pName.lowercase().contains(q) && !pContent.lowercase().contains(q)) continue

            val sub = "#${i + 1} · $pRole · ${pContent.length} 字"
            val pair = addCollapsibleDualFieldCard(
                parent = box,
                title = pName,
                subtitle = sub,
                dotColor = if (enabled) Color.parseColor("#16A34A") else Color.parseColor("#8E8E98"),
                field1Hint = "提示词标题",
                field1Value = pName,
                field2Hint = "提示词正文内容...",
                field2Value = pContent
            )
            promptEdits[i] = pair
        }

        visualCommitCallbacks.add {
            for ((idx, pair) in promptEdits) {
                val pObj = promptsArr.optJSONObject(idx) ?: continue
                pObj.put("name", pair.first.text?.toString()?.trim().orEmpty())
                pObj.put("content", pair.second.text?.toString().orEmpty())
            }
            currentCode = root.toString(2)
        }
    }

    /**
     * 4. WorldBook Visual Editor:
     *    - Collapsible cards for each World Info entry (`comment` + `key` + `content`)
     */
    private fun buildWorldBookVisual(box: LinearLayout, root: JSONObject, q: String) {
        val entriesObj = root.optJSONObject("entries")
        val entriesArr = root.optJSONArray("entries")
        val entryObjects = mutableListOf<JSONObject>()
        if (entriesObj != null) {
            val keys = entriesObj.keys().asSequence().toList()
            for (k in keys) {
                entriesObj.optJSONObject(k)?.let { entryObjects.add(it) }
            }
        } else if (entriesArr != null) {
            for (i in 0 until entriesArr.length()) {
                entriesArr.optJSONObject(i)?.let { entryObjects.add(it) }
            }
        }
        if (entryObjects.isEmpty()) {
            buildFallbackRawNotice(box)
            return
        }

        val entryEdits = LinkedHashMap<JSONObject, Triple<EditText, EditText, EditText>>()
        entryObjects.forEachIndexed { idx, eObj ->
            val comment = eObj.optString("comment", eObj.optString("name", "条目 #${idx + 1}"))
            val keyArr = eObj.optJSONArray("key") ?: eObj.optJSONArray("keys")
            val keysList = mutableListOf<String>()
            if (keyArr != null) {
                for (kIdx in 0 until keyArr.length()) {
                    val kv = keyArr.optString(kIdx, "").trim()
                    if (kv.isNotEmpty()) keysList.add(kv)
                }
            }
            val keysJoined = keysList.joinToString(", ")
            val content = eObj.optString("content", "")
            val constant = eObj.optBoolean("constant", false)
            val enabled = if (eObj.has("disable")) !eObj.optBoolean("disable", false) else eObj.optBoolean("enabled", true)

            if (q.isNotEmpty() && !comment.lowercase().contains(q) && !keysJoined.lowercase().contains(q) && !content.lowercase().contains(q)) {
                return@forEachIndexed
            }

            val dot = when {
                !enabled -> Color.parseColor("#8E8E98")
                constant -> Color.parseColor("#3B82F6")
                else -> Color.parseColor("#16A34A")
            }
            val sub = if (keysJoined.isNotEmpty()) "$keysJoined · ${content.length} 字" else "${content.length} 字"
            val triple = addCollapsibleTripleFieldCard(
                parent = box,
                title = comment.ifBlank { "条目 #${idx + 1}" },
                subtitle = sub,
                dotColor = dot,
                f1Hint = "条目名称",
                f1Val = comment,
                f2Hint = "触发关键词（用英文逗号 , 分隔）",
                f2Val = keysJoined,
                f3Hint = "世界书条目正文...",
                f3Val = content
            )
            entryEdits[eObj] = triple
        }

        visualCommitCallbacks.add {
            for ((eObj, triple) in entryEdits) {
                eObj.put("comment", triple.first.text?.toString()?.trim().orEmpty())
                val rawKeys = triple.second.text?.toString().orEmpty()
                    .split(",", "，")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                eObj.put("key", JSONArray(rawKeys))
                eObj.put("content", triple.third.text?.toString().orEmpty())
            }
            currentCode = root.toString(2)
        }
    }

    /**
     * 5. Component Visual Editor (Regex / Tavern Helper Script / QR)
     */
    private fun buildComponentVisual(box: LinearLayout, trimmed: String) {
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            if (obj.has("findRegex") && obj.has("replaceString")) {
                val etName = addCleanFieldCard(box, "正则名称", "scriptName", obj.optString("scriptName", ""), singleLine = true, monospace = false)
                val etFind = addCleanFieldCard(box, "查找正则", "findRegex", obj.optString("findRegex", ""), singleLine = false, monospace = true, minLines = 3)
                val etRep = addCleanFieldCard(box, "替换内容 (HTML / CSS / 文本)", "replaceString", obj.optString("replaceString", ""), singleLine = false, monospace = true, minLines = 12)
                visualCommitCallbacks.add {
                    obj.put("scriptName", etName.text?.toString()?.trim().orEmpty())
                    obj.put("findRegex", etFind.text?.toString().orEmpty())
                    obj.put("replaceString", etRep.text?.toString().orEmpty())
                    currentCode = obj.toString(2)
                }
                return
            }
            if (obj.has("content") || obj.has("script")) {
                val key = if (obj.has("content")) "content" else "script"
                val etName = addCleanFieldCard(box, "脚本名称", "name", obj.optString("name", ""), singleLine = true, monospace = false)
                val etCode = addCleanFieldCard(box, "脚本代码", key, obj.optString(key, ""), singleLine = false, monospace = true, minLines = 15)
                visualCommitCallbacks.add {
                    obj.put("name", etName.text?.toString()?.trim().orEmpty())
                    obj.put(key, etCode.text?.toString().orEmpty())
                    currentCode = obj.toString(2)
                }
                return
            }
        }
        val etRaw = addCleanFieldCard(box, "组件代码", "直接编辑脚本内容", currentCode, singleLine = false, monospace = true, minLines = 16)
        visualCommitCallbacks.add {
            currentCode = etRaw.text?.toString().orEmpty()
        }
    }

    private fun buildFallbackRawNotice(box: LinearLayout) {
        val et = addCleanFieldCard(
            parent = box,
            title = "代码内容",
            subtitle = "可直接编辑，或点右上角「源码」切换全屏代码视图",
            initialValue = currentCode,
            singleLine = false,
            monospace = true,
            minLines = 16
        )
        visualCommitCallbacks.add {
            currentCode = et.text?.toString().orEmpty()
        }
    }

    // ==================== VISUAL CARD UI HELPERS ====================

    private fun makeSectionCard(parent: LinearLayout): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(col(R.color.surface))
            strokeWidth = dp(1)
            strokeColor = col(R.color.border)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(inner)
        parent.addView(card)
        return card to inner
    }

    private fun makeCleanEditText(
        initialValue: String,
        singleLine: Boolean,
        monospace: Boolean,
        minLines: Int = 4,
        hintText: String = ""
    ): EditText {
        return EditText(this).apply {
            setText(initialValue)
            if (hintText.isNotEmpty()) hint = hintText
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(col(R.color.text_primary))
            setHintTextColor(col(R.color.text_muted))
            textSize = if (monospace) 12f else 13f
            if (monospace) typeface = Typeface.MONOSPACE
            if (singleLine) {
                isSingleLine = true
            } else {
                isSingleLine = false
                this.minLines = minLines
                gravity = Gravity.TOP or Gravity.START
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    dirty = true
                }
            })
        }
    }

    private fun addCleanFieldCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initialValue: String,
        singleLine: Boolean,
        monospace: Boolean,
        minLines: Int = 5
    ): EditText {
        val (_, inner) = makeSectionCard(parent)
        inner.addView(TextView(this).apply {
            text = title
            setTextColor(col(R.color.text_primary))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
        })
        if (subtitle.isNotBlank()) {
            inner.addView(TextView(this).apply {
                text = subtitle
                setTextColor(col(R.color.text_muted))
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
        }
        val et = makeCleanEditText(initialValue, singleLine, monospace, minLines)
        inner.addView(et)
        return et
    }

    private fun addCollapsibleFieldCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initialValue: String,
        singleLine: Boolean,
        monospace: Boolean
    ): EditText {
        val (_, inner) = makeSectionCard(parent)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = title
            setTextColor(col(R.color.text_primary))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvSub = TextView(this).apply {
            text = "$subtitle ▾"
            setTextColor(col(R.color.text_muted))
            textSize = 11.5f
        }
        header.addView(tvSub)
        inner.addView(header)

        val et = makeCleanEditText(initialValue, singleLine, monospace, minLines = if (singleLine) 1 else 5).apply {
            visibility = View.GONE
        }
        inner.addView(et)
        header.setOnClickListener {
            val show = et.visibility != View.VISIBLE
            et.visibility = if (show) View.VISIBLE else View.GONE
            tvSub.text = if (show) "$subtitle ▴" else "$subtitle ▾"
        }
        return et
    }

    private fun addCollapsibleDualFieldCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        dotColor: Int,
        field1Hint: String,
        field1Value: String,
        field2Hint: String,
        field2Value: String
    ): Pair<EditText, EditText> {
        val (_, inner) = makeSectionCard(parent)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        })
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(col(R.color.text_primary))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvTitle)
        val tvSub = TextView(this).apply {
            text = "$subtitle ▾"
            setTextColor(col(R.color.text_muted))
            textSize = 11f
        }
        header.addView(tvSub)
        inner.addView(header)

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val et1 = makeCleanEditText(field1Value, singleLine = true, monospace = false, minLines = 1, hintText = field1Hint)
        val et2 = makeCleanEditText(field2Value, singleLine = false, monospace = false, minLines = 6, hintText = field2Hint)
        bodyBox.addView(et1)
        bodyBox.addView(et2)
        inner.addView(bodyBox)

        header.setOnClickListener {
            val show = bodyBox.visibility != View.VISIBLE
            bodyBox.visibility = if (show) View.VISIBLE else View.GONE
            tvSub.text = if (show) "$subtitle ▴" else "$subtitle ▾"
        }
        return et1 to et2
    }

    private fun addCollapsibleTripleFieldCard(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        dotColor: Int,
        f1Hint: String,
        f1Val: String,
        f2Hint: String,
        f2Val: String,
        f3Hint: String,
        f3Val: String
    ): Triple<EditText, EditText, EditText> {
        val (_, inner) = makeSectionCard(parent)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
            }
        })
        header.addView(TextView(this).apply {
            text = title
            setTextColor(col(R.color.text_primary))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvSub = TextView(this).apply {
            text = "编辑 ▾"
            setTextColor(col(R.color.text_muted))
            textSize = 11f
        }
        header.addView(tvSub)
        inner.addView(header)

        if (subtitle.isNotBlank()) {
            inner.addView(TextView(this).apply {
                text = subtitle
                setTextColor(col(R.color.text_muted))
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); marginStart = dp(15) }
            })
        }

        val bodyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val et1 = makeCleanEditText(f1Val, singleLine = true, monospace = false, minLines = 1, hintText = f1Hint)
        val et2 = makeCleanEditText(f2Val, singleLine = true, monospace = false, minLines = 1, hintText = f2Hint)
        val et3 = makeCleanEditText(f3Val, singleLine = false, monospace = false, minLines = 6, hintText = f3Hint)
        bodyBox.addView(et1)
        bodyBox.addView(et2)
        bodyBox.addView(et3)
        inner.addView(bodyBox)

        header.setOnClickListener {
            val show = bodyBox.visibility != View.VISIBLE
            bodyBox.visibility = if (show) View.VISIBLE else View.GONE
            tvSub.text = if (show) "收起 ▴" else "编辑 ▾"
        }
        return Triple(et1, et2, et3)
    }

    // ==================== RAW MODE HELPERS & SAVE ====================

    private fun prettyJsonIfPossible(raw: String): String {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatCode() {
        val cur = binding.etCodeContent.text?.toString().orEmpty()
        val trimmed = cur.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            toast("当前不是 JSON 格式")
            return
        }
        try {
            val formatted = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).toString(2)
            } else {
                JSONArray(trimmed).toString(2)
            }
            binding.etCodeContent.setText(formatted)
            toast("已格式化")
        } catch (e: Exception) {
            toast("JSON 语法错误：${e.message}")
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
        if (list.isEmpty()) {
            binding.tvFindCount.text = "0/0"
        } else {
            focusMatch(0)
        }
    }

    private fun jumpMatch(delta: Int) {
        if (matchIndices.isEmpty()) return
        val next = (currentMatchPos + delta + matchIndices.size) % matchIndices.size
        focusMatch(next)
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
        flushVisualEditsToCode()
        val newCode = currentCode
        if (newCode.isBlank()) {
            toast("内容不能为空")
            return
        }

        val trimmed = newCode.trim()
        val needsJson = module == MODULE_CHARACTER || module == MODULE_WORLDBOOK || module == MODULE_PRESET ||
            (module == MODULE_THEME && trimmed.startsWith("{"))
        if (needsJson) {
            try {
                if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            } catch (e: Exception) {
                toast("JSON 语法有误：${e.message}")
                return
            }
        }

        lifecycleScope.launch {
            val (ok, errMsg) = withContext(Dispatchers.IO) {
                try {
                    when (module) {
                        MODULE_THEME -> {
                            val saved = vaultRepo.overwriteSourceContent(recordId, newCode)
                            saved to if (saved) "" else "保存美化失败"
                        }
                        MODULE_CHARACTER -> {
                            if (!CharacterCardParser.isCharacterCardJson(newCode)) {
                                return@withContext false to "JSON 缺少角色卡必要字段"
                            }
                            val saved = libRepo.updateCharacterRawJson(recordId, newCode)
                            saved to if (saved) "" else "保存角色卡失败"
                        }
                        MODULE_WORLDBOOK -> {
                            if (!WorldBookParser.isWorldBookJson(newCode)) {
                                return@withContext false to "JSON 缺少世界书 entries 字段"
                            }
                            val saved = libRepo.updateWorldBookRawJson(recordId, newCode)
                            saved to if (saved) "" else "保存世界书失败"
                        }
                        MODULE_PRESET -> {
                            if (!PresetParser.isPresetJson(newCode)) {
                                return@withContext false to "JSON 缺少预设 prompts 字段"
                            }
                            val saved = libRepo.updatePresetRawJson(recordId, newCode)
                            saved to if (saved) "" else "保存预设失败"
                        }
                        MODULE_COMPONENT -> {
                            val rec = libRepo.getComponentById(recordId) ?: return@withContext false to "找不到组件"
                            if (ComponentParser.parse(newCode, rec.fileName) == null) {
                                return@withContext false to "组件代码格式无效"
                            }
                            val saved = libRepo.updateComponentRawText(recordId, newCode)
                            saved to if (saved) "" else "保存组件失败"
                        }
                        else -> false to "未知模块"
                    }
                } catch (e: Exception) {
                    false to (e.message ?: "保存异常")
                }
            }
            if (ok) {
                initialCode = newCode
                dirty = false
                updateMetaBar()
                toast("已保存修改")
                setResult(RESULT_OK)
            } else {
                toast(errMsg)
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

        const val MODULE_THEME = "theme"
        const val MODULE_CHARACTER = "character"
        const val MODULE_WORLDBOOK = "worldbook"
        const val MODULE_PRESET = "preset"
        const val MODULE_COMPONENT = "component"

        fun createIntent(ctx: Context, module: String, id: String, title: String): Intent {
            return Intent(ctx, CodeEditorActivity::class.java).apply {
                putExtra(EXTRA_MODULE, module)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
            }
        }

        fun open(ctx: Context, module: String, id: String, title: String) {
            ctx.startActivity(createIntent(ctx, module, id, title))
        }
    }
}
