package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ItemSourceBinding
import com.stvault.app.model.SourceRecord

class SourcesAdapter(
    private val repo: VaultRepository,
    private val onClick: (SourceRecord) -> Unit,
    private val onPreviewClick: (SourceRecord) -> Unit
) : RecyclerView.Adapter<SourcesAdapter.VH>() {

    private var items: List<SourceRecord> = emptyList()

    fun submitList(list: List<SourceRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val (ok, err, pending) = repo.getSourceCounts(s)
        holder.binding.tvSourceName.text = s.name
        val meta = buildString {
            append("已缓存 $ok / ${s.urls.size}")
            if (err > 0) append(" · 失败 $err")
            if (pending > 0) append(" · 待缓存 $pending")
        }
        holder.binding.tvSourceMeta.text = meta
        holder.binding.root.setOnClickListener { onClick(s) }
        holder.binding.btnOpenSource.setOnClickListener { onClick(s) }
        holder.binding.btnPreviewSource.setOnClickListener { onPreviewClick(s) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
