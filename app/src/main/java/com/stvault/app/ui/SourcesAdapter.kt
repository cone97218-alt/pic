package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.databinding.ItemSourceBinding
import com.stvault.app.model.SourceRecord
import com.stvault.app.model.SourceRowUi

class SourcesAdapter(
    private val onClick: (SourceRecord) -> Unit,
    private val onPreviewClick: (SourceRecord) -> Unit,
    private val onDeleteClick: (SourceRecord) -> Unit,
    private val onLongClick: (SourceRecord) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.VH>() {

    private var items: List<SourceRowUi> = emptyList()

    fun submitList(list: List<SourceRowUi>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val s = row.source
        val cleanName = s.name.substringAfterLast('/')
        holder.binding.tvSourceName.text = cleanName

        val catLabel = if (s.categories.isNotEmpty()) {
            s.categories.joinToString(" · ") { it.substringAfterLast('/') }
        } else ""

        val meta = buildString {
            append("已缓存 ${row.okCount}/${s.urls.size}")
            if (row.errCount > 0) append(" · 失败 ${row.errCount}")
            if (row.pendingCount > 0) append(" · 待缓存 ${row.pendingCount}")
            if (catLabel.isNotEmpty()) append(" · $catLabel")
        }
        holder.binding.tvSourceMeta.text = meta
        holder.binding.root.setOnClickListener { onClick(s) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(s)
            true
        }
        holder.binding.btnPreviewSource.setOnClickListener { onPreviewClick(s) }
        holder.binding.btnDeleteSource.setOnClickListener { onDeleteClick(s) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
