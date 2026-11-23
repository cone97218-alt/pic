package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    private var dirty: Boolean = false
    private var matchIndices: List<Int> = emptyList()
    private var currentMatchPos: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        module = intent.getStringExtra(EXTRA_MODULE).orEmpty()
        recordId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvEditorTitle.text = title.ifBlank { "编辑代码" }

        binding.btnEditorClose.setOnClickListener { confirmExit() }
        binding.btnEditorSearchToggle.setOnClickListener {
            val show = binding.layoutEditorFind.visibility != View.VISIBLE
            binding.layoutEditorFind.visibility = if (show) View.VISIBLE else View.GONE
            if (show) binding.etEditorFind.requestFocus()
        }
        binding.btnEditorFormat.setOnClickListener { formatCode() }
        binding.btnEditorSave.setOnClickListener { saveCode() }

        binding.etEditorFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                recomputeMatches()
            }
        })
        binding.btnFindPrev.setOnClickListener { jumpMatch(-1) }
        binding.btnFindNext.setOnClickListener { jumpMatch(1) }

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
            message = "当前代码有尚未保存的改动，退出将丢失这些修改。",
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
                        MODULE_THEME -> vaultRepo.readSourceRawText(recordId).second
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
                toast("读取代码失败")
                finish()
                return@launch
            }
            initialCode = loaded
            binding.etCodeContent.setText(loaded)
            dirty = false
            updateMetaBar(loaded)

            binding.etCodeContent.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val cur = s?.toString().orEmpty()
                    dirty = (cur != initialCode)
                    updateMetaBar(cur)
                }
            })
        }
    }

    private fun updateMetaBar(text: String) {
        val lines = text.count { it == '\n' } + 1
        val modLabel = when (module) {
            MODULE_THEME -> "美化"
            MODULE_CHARACTER -> "角色卡 JSON"
            MODULE_WORLDBOOK -> "世界书 JSON"
            MODULE_PRESET -> "预设 JSON"
            MODULE_COMPONENT -> "组件代码"
            else -> "代码"
        }
        val stateLabel = if (dirty) "未保存" else "已同步"
        binding.tvEditorMeta.text = "$modLabel · $lines 行 · ${text.length} 字符 · $stateLabel"
    }

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
            toast("当前内容不是 JSON 格式")
            return
        }
        try {
            val formatted = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).toString(2)
            } else {
                JSONArray(trimmed).toString(2)
            }
            binding.etCodeContent.setText(formatted)
            toast("JSON 已格式化")
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
        val newCode = binding.etCodeContent.text?.toString().orEmpty()
        if (newCode.isBlank()) {
            toast("代码内容不能为空")
            return
        }

        // Validate JSON syntax before saving for JSON-based modules
        val trimmed = newCode.trim()
        val needsJson = module == MODULE_CHARACTER || module == MODULE_WORLDBOOK || module == MODULE_PRESET ||
            (module == MODULE_THEME && trimmed.startsWith("{"))
        if (needsJson) {
            try {
                if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
            } catch (e: Exception) {
                toast("JSON 语法有误，请修正后再保存：${e.message}")
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
                                return@withContext false to "JSON 缺少角色卡必要字段 (name / first_mes / description)"
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
                updateMetaBar(newCode)
                toast("已保存代码修改")
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
