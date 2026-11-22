package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.databinding.ItemResourceSimpleBinding
import com.stvault.app.model.ComponentRecord
import com.stvault.app.model.PresetRecord
import com.stvault.app.model.WorldBookRecord

class WorldBooksAdapter(
    private val onClick: (WorldBookRecord) -> Unit,
    private val onDeleteClick: (WorldBookRecord) -> Unit
) : RecyclerView.Adapter<WorldBooksAdapter.VH>() {

    private var items: List<WorldBookRecord> = emptyList()

    fun submitList(list: List<WorldBookRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val w = items[position]
        val b = holder.binding
        b.tvResTitle.text = w.name
        if (w.originCardName.isNotEmpty()) {
            b.tvResBadge.visibility = View.VISIBLE
            b.tvResBadge.text = "来自角色卡"
        } else {
            b.tvResBadge.visibility = View.GONE
        }
        val approxTokens = (w.totalChars / 2.2).toInt()
        val meta = buildString {
            append("${w.entryCount} 条目")
            if (w.constantCount > 0) append(" · 常驻 ${w.constantCount}")
            append(" · 约 $approxTokens tokens")
            if (w.urls.isNotEmpty()) append(" · 图 ${w.urls.size}")
            if (w.originCardName.isNotEmpty()) append(" · ${w.originCardName}")
        }
        b.tvResMeta.text = meta
        b.root.setOnClickListener { onClick(w) }
        b.btnResDelete.setOnClickListener { onDeleteClick(w) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}

class PresetsAdapter(
    private val onClick: (PresetRecord) -> Unit,
    private val onDeleteClick: (PresetRecord) -> Unit
) : RecyclerView.Adapter<PresetsAdapter.VH>() {

    private var items: List<PresetRecord> = emptyList()

    fun submitList(list: List<PresetRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val b = holder.binding
        b.tvResBadge.visibility = View.GONE
        b.tvResTitle.text = p.name
        val meta = buildString {
            append("启用 ${p.enabledPromptCount}/${p.promptCount} 条目")
            p.temperature?.let { append(" · Temp ${String.format("%.2f", it)}") }
            p.topP?.let { append(" · TopP ${String.format("%.2f", it)}") }
        }
        b.tvResMeta.text = meta
        b.root.setOnClickListener { onClick(p) }
        b.btnResDelete.setOnClickListener { onDeleteClick(p) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}

class ComponentsAdapter(
    private val onClick: (ComponentRecord) -> Unit,
    private val onDeleteClick: (ComponentRecord) -> Unit
) : RecyclerView.Adapter<ComponentsAdapter.VH>() {

    private var items: List<ComponentRecord> = emptyList()

    fun submitList(list: List<ComponentRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val b = holder.binding
        b.tvResBadge.visibility = View.VISIBLE
        b.tvResBadge.text = when (c.type) {
            "helper" -> "助手"
            "qr" -> "QR"
            else -> "正则"
        }
        b.tvResTitle.text = c.name
        val linkText = if (c.linkedTargetName.isNotEmpty()) {
            val typeLabel = when (c.linkedTargetType) {
                "character" -> "角色卡"
                "preset" -> "预设"
                "worldbook" -> "世界书"
                "theme" -> "美化"
                else -> "关联"
            }
            "已关联 $typeLabel · ${c.linkedTargetName}"
        } else {
            "全局独立"
        }
        b.tvResMeta.text = "$linkText · ${c.summary}"
        b.root.setOnClickListener { onClick(c) }
        b.btnResDelete.setOnClickListener { onDeleteClick(c) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}
