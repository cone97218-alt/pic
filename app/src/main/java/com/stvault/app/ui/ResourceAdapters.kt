package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.R
import com.stvault.app.databinding.ItemResourceSimpleBinding
import com.stvault.app.model.ComponentRecord
import com.stvault.app.model.GroupedItem
import com.stvault.app.model.PresetRecord
import com.stvault.app.model.WorldBookRecord

class WorldBooksAdapter(
    private val onClick: (WorldBookRecord) -> Unit,
    private val onDeleteClick: (WorldBookRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit,
    private val onLongClick: (WorldBookRecord) -> Unit = {}
) : RecyclerView.Adapter<WorldBooksAdapter.VH>() {

    private var items: List<GroupedItem<WorldBookRecord>> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<GroupedItem<WorldBookRecord>>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateSelection(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.primary.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val grouped = items[position]
        val w = grouped.primary
        val b = holder.binding
        val ctx = b.root.context
        val d = ctx.resources.displayMetrics.density
        val isSelected = selectionMode && w.id in selectedIds

        b.tvResTitle.text = if (isSelected) "✓  ${w.name}" else w.name
        b.root.setStrokeColor(ContextCompat.getColor(ctx, if (isSelected) R.color.accent else R.color.border))
        b.root.strokeWidth = ((if (isSelected) 2 else 1) * d).toInt()
        b.btnResDelete.visibility = if (selectionMode) View.GONE else View.VISIBLE

        val wbInstalled = com.stvault.app.core.TavernBridgeManager.getInstance(ctx)
            .isWorldBookInstalled(w.name, w.fileName)
        val badge = when {
            wbInstalled && grouped.versionCount > 1 -> "已装 · ${grouped.versionCount} 版"
            wbInstalled -> "已装"
            grouped.versionCount > 1 -> "${grouped.versionCount} 版本"
            w.originCardName.isNotEmpty() -> "来自角色卡"
            else -> ""
        }
        if (badge.isNotEmpty()) {
            b.tvResBadge.visibility = View.VISIBLE
            b.tvResBadge.text = badge
        } else {
            b.tvResBadge.visibility = View.GONE
        }

        val approxTokens = (w.totalChars / 2.2).toInt()
        val tokenStr = if (approxTokens >= 1000) String.format("%.1fk tok", approxTokens / 1000.0) else "$approxTokens tok"
        val meta = buildString {
            append("${w.entryCount} 条目")
            if (w.constantCount > 0) append(" · 常驻 ${w.constantCount}")
            append(" · $tokenStr")
            if (w.urls.isNotEmpty()) append(" · 图 ${w.urls.size}")
        }
        b.tvResMeta.text = meta
        b.root.setOnClickListener { if (selectionMode) onToggleSelect(w.id) else onClick(w) }
        b.root.setOnLongClickListener {
            if (selectionMode) onToggleSelect(w.id) else onLongClick(w)
            true
        }
        b.btnResDelete.setOnClickListener { onDeleteClick(w) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}

class PresetsAdapter(
    private val onClick: (PresetRecord) -> Unit,
    private val onDeleteClick: (PresetRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit,
    private val onLongClick: (PresetRecord) -> Unit = {}
) : RecyclerView.Adapter<PresetsAdapter.VH>() {

    private var items: List<GroupedItem<PresetRecord>> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<GroupedItem<PresetRecord>>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateSelection(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.primary.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val grouped = items[position]
        val p = grouped.primary
        val b = holder.binding
        val ctx = b.root.context
        val d = ctx.resources.displayMetrics.density
        val isSelected = selectionMode && p.id in selectedIds

        b.tvResTitle.text = if (isSelected) "✓  ${p.name}" else p.name
        b.root.setStrokeColor(ContextCompat.getColor(ctx, if (isSelected) R.color.accent else R.color.border))
        b.root.strokeWidth = ((if (isSelected) 2 else 1) * d).toInt()
        b.btnResDelete.visibility = if (selectionMode) View.GONE else View.VISIBLE

        val pInstalled = com.stvault.app.core.TavernBridgeManager.getInstance(ctx)
            .isPresetInstalled(p.name, p.fileName)
        val pBadge = when {
            pInstalled && grouped.versionCount > 1 -> "已装 · ${grouped.versionCount} 版"
            pInstalled -> "已装"
            grouped.versionCount > 1 -> "${grouped.versionCount} 版本"
            else -> ""
        }
        if (pBadge.isNotEmpty()) {
            b.tvResBadge.visibility = View.VISIBLE
            b.tvResBadge.text = pBadge
        } else {
            b.tvResBadge.visibility = View.GONE
        }
        val scripts = p.embeddedRegexCount + p.embeddedHelperCount
        val meta = buildString {
            append("启用 ${p.enabledPromptCount}/${p.promptCount}")
            if (scripts > 0) append(" · 脚本 $scripts")
            p.temperature?.let { append(" · Temp ${String.format("%.2f", it)}") }
            p.topP?.let { append(" · TopP ${String.format("%.2f", it)}") }
        }
        b.tvResMeta.text = meta
        b.root.setOnClickListener { if (selectionMode) onToggleSelect(p.id) else onClick(p) }
        b.root.setOnLongClickListener {
            if (selectionMode) onToggleSelect(p.id) else onLongClick(p)
            true
        }
        b.btnResDelete.setOnClickListener { onDeleteClick(p) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}

class ComponentsAdapter(
    private val onClick: (ComponentRecord) -> Unit,
    private val onDeleteClick: (ComponentRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit,
    private val onLongClick: (ComponentRecord) -> Unit = {}
) : RecyclerView.Adapter<ComponentsAdapter.VH>() {

    private var items: List<GroupedItem<ComponentRecord>> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<GroupedItem<ComponentRecord>>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateSelection(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.primary.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResourceSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val grouped = items[position]
        val c = grouped.primary
        val b = holder.binding
        val ctx = b.root.context
        val d = ctx.resources.displayMetrics.density
        val isSelected = selectionMode && c.id in selectedIds

        b.tvResTitle.text = if (isSelected) "✓  ${c.name}" else c.name
        b.root.setStrokeColor(ContextCompat.getColor(ctx, if (isSelected) R.color.accent else R.color.border))
        b.root.strokeWidth = ((if (isSelected) 2 else 1) * d).toInt()
        b.btnResDelete.visibility = if (selectionMode) View.GONE else View.VISIBLE

        b.tvResBadge.visibility = View.VISIBLE
        val typeLabel = when (c.type) {
            "helper" -> "酒馆助手"
            "qr" -> "QR"
            else -> "正则"
        }
        b.tvResBadge.text = if (grouped.versionCount > 1) "$typeLabel · ${grouped.versionCount}版" else typeLabel
        val linkText = if (c.linkedTargetName.isNotEmpty()) {
            val hostType = when (c.linkedTargetType) {
                "character" -> "角色卡"
                "preset" -> "预设"
                "worldbook" -> "世界书"
                "theme" -> "美化"
                else -> "关联"
            }
            "已关联 $hostType · ${c.linkedTargetName}"
        } else {
            "全局独立"
        }
        b.tvResMeta.text = "$linkText · ${c.summary}"
        b.root.setOnClickListener { if (selectionMode) onToggleSelect(c.id) else onClick(c) }
        b.root.setOnLongClickListener {
            if (selectionMode) onToggleSelect(c.id) else onLongClick(c)
            true
        }
        b.btnResDelete.setOnClickListener { onDeleteClick(c) }
    }

    override fun getItemCount(): Int = items.size
    class VH(val binding: ItemResourceSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}
