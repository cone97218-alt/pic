package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.stvault.app.R
import com.stvault.app.core.DuplicateCluster
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.SimilarityDeduplicator
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityDuplicateCleanerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DuplicateCleanerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDuplicateCleanerBinding
    private lateinit var vaultRepo: VaultRepository
    private lateinit var libRepo: ResourceLibraryRepository
    private lateinit var adapter: ClusterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDuplicateCleanerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        adapter = ClusterAdapter()
        binding.rvDedup.layoutManager = LinearLayoutManager(this)
        binding.rvDedup.adapter = adapter

        binding.btnDedupClose.setOnClickListener { finish() }
        binding.btnDedupResetIgnored.setOnClickListener {
            SimilarityDeduplicator.clearIgnored(this)
            toast("已重置忽略记录，重新扫描...")
            rescan()
        }

        rescan()
    }

    private fun rescan() {
        binding.tvDedupSummary.text = "正在扫描全库同名与近似资源..."
        lifecycleScope.launch {
            val clusters = withContext(Dispatchers.IO) {
                SimilarityDeduplicator.scanAll(this@DuplicateCleanerActivity, vaultRepo, libRepo)
            }
            if (clusters.isEmpty()) {
                binding.tvDedupSummary.text = "扫描完成"
                binding.tvDedupEmpty.visibility = View.VISIBLE
            } else {
                binding.tvDedupSummary.text = "发现 ${clusters.size} 组同名或近似资源，可逐组决定保留、合并版本或清理"
                binding.tvDedupEmpty.visibility = View.GONE
            }
            adapter.submit(clusters)
        }
    }

    private fun keepNewestOnly(cluster: DuplicateCluster) {
        val newest = cluster.items.first()
        val older = cluster.items.drop(1)
        VaultSheets.confirm(
            ctx = this,
            title = "保留最新，删除旧版",
            message = "将保留最新项「${newest.name}」，并删除其余 ${older.size} 个旧项？",
            confirmText = "保留最新",
            destructive = true
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    for (old in older) {
                        when (cluster.module) {
                            "themes" -> vaultRepo.deleteSource(old.id)
                            "characters" -> libRepo.deleteCharacter(old.id)
                            "worldbooks" -> libRepo.deleteWorldBook(old.id)
                            "presets" -> libRepo.deletePreset(old.id)
                            "components" -> libRepo.deleteComponent(old.id)
                            else -> Unit
                        }
                    }
                    Unit
                }
                toast("已保留最新项并清理 ${older.size} 个旧项")
                rescan()
            }
        }
    }

    private fun mergeClusterAsVersions(cluster: DuplicateCluster) {
        val suggested = cluster.items.first().name
        val ids = cluster.items.map { it.id }
        VaultSheets.input(
            ctx = this,
            title = "合并为同一作品的不同版本",
            message = "合并后列表仅展示最新版，进入详情页可随时切换历史版本并对比差异。",
            hint = "输入作品分组名称",
            initialValue = suggested,
            confirmText = "合并"
        ) { name ->
            val clean = name.trim()
            if (clean.isEmpty()) return@input
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    when (cluster.module) {
                        "characters" -> libRepo.mergeCharacterVersions(ids, clean)
                        "worldbooks" -> libRepo.mergeWorldBookVersions(ids, clean)
                        "presets" -> libRepo.mergePresetVersions(ids, clean)
                        "components" -> libRepo.mergeComponentVersions(ids, clean)
                        else -> Unit
                    }
                }
                toast("已合并为「$clean」的不同版本")
                rescan()
            }
        }
    }

    private fun pickItemToDelete(cluster: DuplicateCluster) {
        val items = cluster.items.map { PickerItem(it.id, it.name, it.meta) }
        VaultSheets.searchablePicker(
            ctx = this,
            title = "选择要删除的项目",
            items = items
        ) { picked ->
            VaultSheets.confirm(
                ctx = this,
                title = "确认删除",
                message = "确定删除「${picked.title}」？",
                confirmText = "删除",
                destructive = true
            ) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        when (cluster.module) {
                            "themes" -> vaultRepo.deleteSource(picked.id)
                            "characters" -> libRepo.deleteCharacter(picked.id)
                            "worldbooks" -> libRepo.deleteWorldBook(picked.id)
                            "presets" -> libRepo.deletePreset(picked.id)
                            "components" -> libRepo.deleteComponent(picked.id)
                            else -> Unit
                        }
                        Unit
                    }
                    toast("已删除「${picked.title}」")
                    rescan()
                }
            }
        }
    }

    private inner class ClusterAdapter : RecyclerView.Adapter<ClusterAdapter.VH>() {
        private var list: List<DuplicateCluster> = emptyList()
        private val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submit(items: List<DuplicateCluster>) {
            list = items
            notifyDataSetChanged()
        }

        private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        private fun col(res: Int): Int = ContextCompat.getColor(this@DuplicateCleanerActivity, res)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(this@DuplicateCleanerActivity).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(col(R.color.surface))
                strokeWidth = dp(1)
                strokeColor = col(R.color.border)
            }
            val box = LinearLayout(this@DuplicateCleanerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            card.addView(box)
            return VH(card, box)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = list[position]
            val box = holder.box
            box.removeAllViews()

            // Header row: module + reason
            val header = LinearLayout(this@DuplicateCleanerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this@DuplicateCleanerActivity).apply {
                text = c.moduleLabel
                setTextColor(col(R.color.on_accent))
                setBackgroundResource(R.drawable.bg_dock_item_active)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            })
            header.addView(TextView(this@DuplicateCleanerActivity).apply {
                text = "  ${c.reason} · ${c.items.size} 项"
                setTextColor(col(R.color.text_muted))
                textSize = 12f
            })
            box.addView(header)

            // Items list
            c.items.forEachIndexed { idx, item ->
                val row = LinearLayout(this@DuplicateCleanerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                row.addView(TextView(this@DuplicateCleanerActivity).apply {
                    text = if (idx == 0) "最新" else "旧版"
                    setTextColor(if (idx == 0) col(R.color.text_primary) else col(R.color.text_muted))
                    textSize = 11f
                    setTypeface(null, if (idx == 0) Typeface.BOLD else Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                val colBox = LinearLayout(this@DuplicateCleanerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                colBox.addView(TextView(this@DuplicateCleanerActivity).apply {
                    text = item.name
                    setTextColor(col(R.color.text_primary))
                    textSize = 13.5f
                    if (idx == 0) setTypeface(null, Typeface.BOLD)
                })
                val ts = if (item.timestamp > 0L) df.format(Date(item.timestamp)) else ""
                colBox.addView(TextView(this@DuplicateCleanerActivity).apply {
                    text = listOf(item.meta, ts).filter { it.isNotEmpty() }.joinToString(" · ")
                    setTextColor(col(R.color.text_muted))
                    textSize = 11.5f
                })
                row.addView(colBox)
                box.addView(row)
            }

            // Divider
            box.addView(View(this@DuplicateCleanerActivity).apply {
                setBackgroundColor(col(R.color.border))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(8)
                }
            })

            // Action row
            val actions = LinearLayout(this@DuplicateCleanerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            fun addBtn(label: String, bold: Boolean = false, onClick: () -> Unit) {
                actions.addView(TextView(this@DuplicateCleanerActivity).apply {
                    text = label
                    setTextColor(if (bold) col(R.color.text_primary) else col(R.color.text_muted))
                    textSize = 12.5f
                    if (bold) setTypeface(null, Typeface.BOLD)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { onClick() }
                })
            }

            addBtn("共存忽略") {
                SimilarityDeduplicator.markIgnored(this@DuplicateCleanerActivity, c.clusterKey)
                toast("已标记共存，不再提示")
                rescan()
            }
            addBtn("手动删") { pickItemToDelete(c) }
            if (c.module != "themes") {
                addBtn("合并版本", bold = true) { mergeClusterAsVersions(c) }
            }
            addBtn("保留最新", bold = true) { keepNewestOnly(c) }

            box.addView(actions)
        }

        override fun getItemCount(): Int = list.size
        inner class VH(val card: MaterialCardView, val box: LinearLayout) : RecyclerView.ViewHolder(card)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, DuplicateCleanerActivity::class.java))
        }
    }
}
