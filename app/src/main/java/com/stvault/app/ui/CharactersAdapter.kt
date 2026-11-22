package com.stvault.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stvault.app.R
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.databinding.ItemCharacterBinding
import com.stvault.app.model.CharacterRecord
import com.stvault.app.model.GroupedItem

class CharactersAdapter(
    private val libRepo: ResourceLibraryRepository,
    private val onClick: (CharacterRecord) -> Unit,
    private val onLongClick: (CharacterRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit
) : RecyclerView.Adapter<CharactersAdapter.VH>() {

    private var items: List<GroupedItem<CharacterRecord>> = emptyList()
    private var selectionMode = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<GroupedItem<CharacterRecord>>) {
        items = list
        notifyDataSetChanged()
    }

    fun updateSelection(enabled: Boolean, selected: Set<String>) {
        selectionMode = enabled
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.primary.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val grouped = items[position]
        val c = grouped.primary
        val b = holder.binding
        val ctx = b.root.context
        val d = ctx.resources.displayMetrics.density
        val isSelected = selectionMode && c.id in selectedIds

        b.tvCharName.text = if (isSelected) "✓ ${c.name}" else c.name

        if (isSelected) {
            b.tvCharSpecBadge.visibility = View.VISIBLE
            b.tvCharSpecBadge.text = "已选"
        } else if (grouped.versionCount > 1) {
            b.tvCharSpecBadge.visibility = View.VISIBLE
            b.tvCharSpecBadge.text = "${grouped.versionCount} 版本"
        } else if (c.versionTag.isNotEmpty()) {
            b.tvCharSpecBadge.visibility = View.VISIBLE
            b.tvCharSpecBadge.text = c.versionTag
        } else {
            b.tvCharSpecBadge.visibility = View.GONE
        }

        b.root.setStrokeColor(ContextCompat.getColor(ctx, if (isSelected) R.color.accent else R.color.border))
        b.root.strokeWidth = ((if (isSelected) 2 else 1) * d).toInt()

        val metaParts = mutableListOf<String>()
        if (com.stvault.app.core.TavernBridgeManager.getInstance(ctx).isCharacterInstalled(c.name, c.fileName)) {
            metaParts.add("已装")
        }
        if (c.categories.isNotEmpty()) {
            metaParts.add(c.categories.first().substringAfterLast('/'))
        }
        if (c.hasEmbeddedBook) metaParts.add("世界书 ${c.embeddedBookEntryCount}")
        val scripts = c.embeddedRegexCount + c.embeddedHelperCount
        if (scripts > 0) metaParts.add("脚本 $scripts")
        if (c.urls.isNotEmpty()) metaParts.add("图 ${c.urls.size}")
        b.tvCharMeta.text = metaParts.joinToString(" · ").ifBlank { c.spec.uppercase() }

        val thumb = libRepo.getCharacterThumbFile(c) ?: libRepo.getCharacterFile(c).takeIf { c.isPng && it.exists() }
        if (thumb != null) {
            Glide.with(b.ivCharCover)
                .load(thumb)
                .centerCrop()
                .into(b.ivCharCover)
        } else {
            Glide.with(b.ivCharCover).clear(b.ivCharCover)
            b.ivCharCover.setImageResource(R.drawable.ic_module_character)
        }

        b.root.setOnClickListener {
            if (selectionMode) onToggleSelect(c.id) else onClick(c)
        }
        b.root.setOnLongClickListener {
            if (selectionMode) onToggleSelect(c.id) else onLongClick(c)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)
}
