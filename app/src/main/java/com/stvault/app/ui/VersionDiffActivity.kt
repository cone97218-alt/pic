package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.stvault.app.R
import com.stvault.app.core.DiffEntry
import com.stvault.app.core.DiffKind
import com.stvault.app.core.DiffReport
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VersionDiffEngine
import com.stvault.app.databinding.ActivityVersionDiffBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VersionDiffActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVersionDiffBinding
    private lateinit var libRepo: ResourceLibraryRepository
    private lateinit var adapter: DiffAdapter

    private var resType: String = ""
    private var seriesKey: String = ""
    private var versions: List<VerChoice> = emptyList()
    private var oldId: String = ""
    private var newId: String = ""
    private var fullReport: DiffReport? = null
    private var activeFilter: String = "all" // "all" | "mod" | "add" | "rem"

    data class VerChoice(val id: String, val label: String, val sub: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVersionDiffBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libRepo = ResourceLibraryRepository.getInstance(this)
        resType = intent.getStringExtra(EXTRA_TYPE) ?: ""
        seriesKey = intent.getStringExtra(EXTRA_SERIES_KEY) ?: ""
        val initialNewId = intent.getStringExtra(EXTRA_NEW_ID) ?: ""
        val initialOldId = intent.getStringExtra(EXTRA_OLD_ID) ?: ""

        adapter = DiffAdapter()
        binding.rvDiff.layoutManager = LinearLayoutManager(this)
        binding.rvDiff.adapter = adapter

        binding.btnDiffClose.setOnClickListener { finish() }
        binding.btnDiffSwap.setOnClickListener {
            val tmp = oldId
            oldId = newId
            newId = tmp
            updateVersionPills()
            runDiff()
        }
        binding.btnPickOldVer.setOnClickListener { pickVersion(isOld = true) }
        binding.btnPickNewVer.setOnClickListener { pickVersion(isOld = false) }

        binding.btnFilterAll.setOnClickListener { setFilter("all") }
        binding.btnFilterMod.setOnClickListener { setFilter("mod") }
        binding.btnFilterAdd.setOnClickListener { setFilter("add") }
        binding.btnFilterRem.setOnClickListener { setFilter("rem") }

        loadVersions(initialOldId, initialNewId)
    }

    private fun loadVersions(initialOldId: String, initialNewId: String) {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                when (resType) {
                    ResourceDetailActivity.TYPE_PRESET -> libRepo.getVersionsForPreset(seriesKey).map {
                        VerChoice(it.id, it.versionTag.ifBlank { it.name }, it.name)
                    }
                    ResourceDetailActivity.TYPE_WORLDBOOK -> libRepo.getVersionsForWorldBook(seriesKey).map {
                        VerChoice(it.id, it.versionTag.ifBlank { it.name }, it.name)
                    }
                    ResourceDetailActivity.TYPE_CHARACTER -> libRepo.getVersionsForCharacter(seriesKey).map {
                        VerChoice(it.id, it.versionTag.ifBlank { it.name }, it.name)
                    }
                    else -> emptyList()
                }
            }
            versions = list
            if (list.size < 2) {
                binding.tvDiffSummary.text = "当前作品不足 2 个版本，无法对比"
                binding.tvDiffEmpty.visibility = View.VISIBLE
                return@launch
            }
            newId = if (initialNewId.isNotEmpty() && list.any { it.id == initialNewId }) initialNewId else list[0].id
            oldId = if (initialOldId.isNotEmpty() && list.any { it.id == initialOldId }) {
                initialOldId
            } else {
                list.firstOrNull { it.id != newId }?.id ?: list[1].id
            }
            updateVersionPills()
            runDiff()
        }
    }

    private fun updateVersionPills() {
        val oldLabel = versions.firstOrNull { it.id == oldId }?.label ?: "旧版"
        val newLabel = versions.firstOrNull { it.id == newId }?.label ?: "新版"
        binding.btnPickOldVer.text = "旧：$oldLabel ▾"
        binding.btnPickNewVer.text = "新：$newLabel ▾"
    }

    private fun pickVersion(isOld: Boolean) {
        if (versions.isEmpty()) return
        val pickerItems = versions.map { PickerItem(it.id, it.label, it.sub) }
        VaultSheets.searchablePicker(
            ctx = this,
            title = if (isOld) "选择旧版本（基准）" else "选择新版本（对比目标）",
            items = pickerItems
        ) { picked ->
            if (isOld) oldId = picked.id else newId = picked.id
            updateVersionPills()
            runDiff()
        }
    }

    private fun runDiff() {
        binding.tvDiffSummary.text = "正在分析差异..."
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                when (resType) {
                    ResourceDetailActivity.TYPE_PRESET -> {
                        val op = libRepo.getPresetById(oldId)?.let { libRepo.parsePresetDetail(it) }
                        val np = libRepo.getPresetById(newId)?.let { libRepo.parsePresetDetail(it) }
                        if (op != null && np != null) VersionDiffEngine.diffPresets(op, np) else null
                    }
                    ResourceDetailActivity.TYPE_WORLDBOOK -> {
                        val ow = libRepo.getWorldBookById(oldId)?.let { libRepo.parseWorldBookDetail(it) }
                        val nw = libRepo.getWorldBookById(newId)?.let { libRepo.parseWorldBookDetail(it) }
                        if (ow != null && nw != null) VersionDiffEngine.diffWorldBooks(ow, nw) else null
                    }
                    ResourceDetailActivity.TYPE_CHARACTER -> {
                        val oc = libRepo.getCharacterById(oldId)?.let { libRepo.parseCharacterDetail(it) }
                        val nc = libRepo.getCharacterById(newId)?.let { libRepo.parseCharacterDetail(it) }
                        if (oc != null && nc != null) VersionDiffEngine.diffCharacters(oc, nc) else null
                    }
                    else -> null
                }
            }
            fullReport = report
            if (report == null) {
                binding.tvDiffSummary.text = "解析版本失败"
                binding.tvDiffEmpty.visibility = View.VISIBLE
                adapter.submit(emptyList())
                return@launch
            }
            binding.tvDiffSummary.text =
                "+${report.addedCount} 新增 · ~${report.modifiedCount} 修改 · -${report.removedCount} 删除"
            applyFilter()
        }
    }

    private fun setFilter(f: String) {
        activeFilter = f
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val map = listOf(
            "all" to binding.btnFilterAll,
            "mod" to binding.btnFilterMod,
            "add" to binding.btnFilterAdd,
            "rem" to binding.btnFilterRem
        )
        for ((k, tv) in map) {
            val active = (k == f)
            tv.setTextColor(if (active) primary else muted)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
        applyFilter()
    }

    private fun applyFilter() {
        val list = fullReport?.entries.orEmpty().filter { e ->
            when (activeFilter) {
                "add" -> e.kind == DiffKind.ADDED
                "mod" -> e.kind == DiffKind.MODIFIED
                "rem" -> e.kind == DiffKind.REMOVED
                else -> true
            }
        }
        binding.tvDiffEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(list)
    }

    private inner class DiffAdapter : RecyclerView.Adapter<DiffAdapter.VH>() {
        private var items: List<DiffEntry> = emptyList()
        private val expanded = HashSet<Int>()

        fun submit(list: List<DiffEntry>) {
            items = list
            expanded.clear()
            notifyDataSetChanged()
        }

        private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        private fun col(res: Int): Int = ContextCompat.getColor(this@VersionDiffActivity, res)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(this@VersionDiffActivity).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                radius = dp(12).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(col(R.color.surface))
                strokeWidth = dp(1)
                strokeColor = col(R.color.border)
            }
            val box = LinearLayout(this@VersionDiffActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(box)
            return VH(card, box)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val box = holder.box
            box.removeAllViews()

            val (badgeText, badgeColor) = when (e.kind) {
                DiffKind.ADDED -> "+" to Color.parseColor("#16A34A")
                DiffKind.MODIFIED -> "~" to Color.parseColor("#D97706")
                DiffKind.REMOVED -> "-" to Color.parseColor("#DC2626")
            }

            val topRow = LinearLayout(this@VersionDiffActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this@VersionDiffActivity).apply {
                text = badgeText
                setTextColor(badgeColor)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            topRow.addView(TextView(this@VersionDiffActivity).apply {
                text = "${e.section} · ${e.title}"
                setTextColor(col(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            box.addView(topRow)

            box.addView(TextView(this@VersionDiffActivity).apply {
                text = e.summary
                setTextColor(col(R.color.text_muted))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); marginStart = dp(20) }
            })

            val hasDetail = e.oldText.isNotEmpty() || e.newText.isNotEmpty()
            val isExp = position in expanded

            if (hasDetail && isExp) {
                if (e.oldText.isNotEmpty()) {
                    box.addView(buildCodeBlock("旧版本内容", e.oldText.take(6000), Color.parseColor("#DC2626")))
                }
                if (e.newText.isNotEmpty()) {
                    box.addView(buildCodeBlock("新版本内容", e.newText.take(6000), Color.parseColor("#16A34A")))
                }
            } else if (hasDetail) {
                box.addView(TextView(this@VersionDiffActivity).apply {
                    text = "点击展开查看前后文本"
                    setTextColor(col(R.color.text_muted))
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6); marginStart = dp(20) }
                })
            }

            holder.card.setOnClickListener {
                if (!hasDetail) return@setOnClickListener
                if (position in expanded) expanded.remove(position) else expanded.add(position)
                notifyItemChanged(position)
            }
        }

        private fun buildCodeBlock(label: String, content: String, labelColor: Int): LinearLayout {
            return LinearLayout(this@VersionDiffActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }

                addView(TextView(this@VersionDiffActivity).apply {
                    text = label
                    setTextColor(labelColor)
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(this@VersionDiffActivity).apply {
                    text = content
                    setTextColor(col(R.color.text_primary))
                    textSize = 12f
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }
        }

        override fun getItemCount(): Int = items.size
        inner class VH(val card: MaterialCardView, val box: LinearLayout) : RecyclerView.ViewHolder(card)
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_SERIES_KEY = "series_key"
        const val EXTRA_OLD_ID = "old_id"
        const val EXTRA_NEW_ID = "new_id"

        fun open(ctx: Context, type: String, seriesKey: String, newId: String, oldId: String = "") {
            ctx.startActivity(Intent(ctx, VersionDiffActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_SERIES_KEY, seriesKey)
                putExtra(EXTRA_NEW_ID, newId)
                putExtra(EXTRA_OLD_ID, oldId)
            })
        }
    }
}
