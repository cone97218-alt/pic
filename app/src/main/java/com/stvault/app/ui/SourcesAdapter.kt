package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.R
import com.stvault.app.databinding.ItemSourceBinding
import com.stvault.app.model.SourceRecord
import com.stvault.app.model.SourceRowUi

class SourcesAdapter(
    private val onClick: (SourceRecord) -> Unit,
    private val onPreviewClick: (SourceRecord) -> Unit,
    private val onDeleteClick: (SourceRecord) -> Unit,
    private val onLongClick: (SourceRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.VH>() {

    private var items: List<SourceRowUi> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<SourceRowUi>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateSelection(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.source.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val s = row.source
        val ctx = holder.binding.root.context
        val d = ctx.resources.displayMetrics.density
        val cleanName = s.name
        val isSelected = selectionMode && s.id in selectedIds

        holder.binding.tvSourceName.text = if (isSelected) "✓  $cleanName" else cleanName

        val catLabel = if (s.categories.isNotEmpty()) {
            s.categories.joinToString(" · ") { it.substringAfterLast('/') }
        } else ""

        val installed = com.stvault.app.core.TavernBridgeManager.getInstance(ctx)
            .isThemeInstalled(s.name, s.fileName)

        val meta = buildString {
            if (installed) append("已装 · ")
            if (s.urls.isEmpty()) {
                append("纯样式")
            } else {
                append("已缓存 ${row.okCount}/${s.urls.size}")
                if (row.errCount > 0) append(" · 失败 ${row.errCount}")
                if (row.pendingCount > 0) append(" · 待缓存 ${row.pendingCount}")
            }
            if (catLabel.isNotEmpty()) append(" · $catLabel")
        }
        holder.binding.tvSourceMeta.text = meta

        holder.binding.root.setStrokeColor(ContextCompat.getColor(ctx, if (isSelected) R.color.accent else R.color.border))
        holder.binding.root.strokeWidth = ((if (isSelected) 2 else 1) * d).toInt()
        holder.binding.btnPreviewSource.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.binding.btnDeleteSource.visibility = if (selectionMode) View.GONE else View.VISIBLE

        holder.binding.root.setOnClickListener {
            if (selectionMode) onToggleSelect(s.id) else onClick(s)
        }
        holder.binding.root.setOnLongClickListener {
            if (selectionMode) onToggleSelect(s.id) else onLongClick(s)
            true
        }
        holder.binding.btnPreviewSource.setOnClickListener { onPreviewClick(s) }
        holder.binding.btnDeleteSource.setOnClickListener { onDeleteClick(s) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
