package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stvault.app.R
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ItemUrlBinding
import com.stvault.app.model.CacheStatus
import com.stvault.app.model.UrlRecord

class UrlsAdapter(
    private val repo: VaultRepository,
    private val onReplaceClick: (UrlRecord) -> Unit,
    private val onSaveClick: (UrlRecord) -> Unit
) : RecyclerView.Adapter<UrlsAdapter.VH>() {

    private var items: List<UrlRecord> = emptyList()

    fun submitList(list: List<UrlRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUrlBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = items[position]
        val b = holder.binding
        b.tvUrl.text = rec.url

        val imgFile = repo.getImageFile(rec)
        if (imgFile != null) {
            Glide.with(b.ivThumb)
                .load(imgFile)
                .centerCrop()
                .into(b.ivThumb)
            b.btnSavePhoto.visibility = View.VISIBLE
        } else {
            Glide.with(b.ivThumb).clear(b.ivThumb)
            b.ivThumb.setImageResource(
                if (rec.status == CacheStatus.ERROR) android.R.drawable.ic_delete
                else android.R.drawable.ic_menu_gallery
            )
            b.btnSavePhoto.visibility = View.GONE
        }

        val ctx = b.root.context
        when (rec.status) {
            CacheStatus.OK -> {
                b.tvUrlStatus.text = "已缓存 · ${formatBytes(rec.size)}"
                b.tvUrlStatus.setTextColor(ContextCompat.getColor(ctx, R.color.green))
            }
            CacheStatus.ERROR -> {
                b.tvUrlStatus.text = "拉取失败 · ${rec.error}"
                b.tvUrlStatus.setTextColor(ContextCompat.getColor(ctx, R.color.red))
            }
            CacheStatus.PENDING -> {
                b.tvUrlStatus.text = "等待缓存"
                b.tvUrlStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            }
        }

        b.btnReplacePhoto.setOnClickListener { onReplaceClick(rec) }
        b.btnSavePhoto.setOnClickListener { onSaveClick(rec) }
    }

    override fun getItemCount(): Int = items.size

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }

    class VH(val binding: ItemUrlBinding) : RecyclerView.ViewHolder(binding.root)
}
