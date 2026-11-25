package com.stvault.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.stvault.app.R
import com.stvault.app.core.BackgroundRecord
import com.stvault.app.core.BackgroundRepository

/**
 * High-performance 2-column Glide thumbnail grid for Theme Background Images.
 * Supports multi-select mode, Tavern installed badge, category display, and bound count.
 */
class BackgroundGridAdapter(
    private val bgRepo: BackgroundRepository,
    private val isInstalled: (BackgroundRecord) -> Boolean,
    private val onClick: (BackgroundRecord) -> Unit,
    private val onLongClick: (BackgroundRecord) -> Unit,
    private val onToggleSelect: (String) -> Unit
) : RecyclerView.Adapter<BackgroundGridAdapter.VH>() {

    private var items: List<BackgroundRecord> = emptyList()
    private var selectionMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()

    fun submitList(list: List<BackgroundRecord>) {
        items = list
        notifyDataSetChanged()
    }

    fun getVisibleIds(): List<String> = items.map { it.id }

    fun updateSelection(mode: Boolean, selected: Set<String>) {
        val changed = selectionMode != mode || selectedIds != selected
        selectionMode = mode
        selectedIds = selected
        if (changed) notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun col(id: Int) = ContextCompat.getColor(ctx, id)

        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(col(R.color.surface))
            strokeWidth = dp(1)
            strokeColor = col(R.color.border)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val imgWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
        }
        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(col(R.color.surface_2))
        }
        val tvInstalled = TextView(ctx).apply {
            text = "已装"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F2F2F2"))
            background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(Color.parseColor("#A618181B"))
            }
            setPadding(dp(7), dp(2), dp(7), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            visibility = View.GONE
        }
        val tvSelectCheck = TextView(ctx).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(7), dp(7), dp(7), dp(7))
            }
            visibility = View.GONE
        }
        imgWrap.addView(iv)
        imgWrap.addView(tvInstalled)
        imgWrap.addView(tvSelectCheck)

        val metaBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val tvName = TextView(ctx).apply {
            setTextColor(col(R.color.text_primary))
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val tvSub = TextView(ctx).apply {
            setTextColor(col(R.color.text_muted))
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        metaBox.addView(tvName)
        metaBox.addView(tvSub)
        box.addView(imgWrap)
        box.addView(metaBox)
        card.addView(box)
        return VH(card, iv, tvInstalled, tvSelectCheck, tvName, tvSub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.card.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val rec = items[position]
        val f = bgRepo.getFile(rec)
        Glide.with(holder.iv)
            .load(f)
            .override(420, 420)
            .centerCrop()
            .into(holder.iv)

        holder.tvName.text = rec.name
        val boundCount = bgRepo.countThemesBoundTo(rec.id)
        val sizeStr = if (rec.fileSize >= 1024 * 1024) {
            String.format("%.1f MB", rec.fileSize / (1024.0 * 1024.0))
        } else {
            "${(rec.fileSize / 1024).coerceAtLeast(1)} KB"
        }
        val parts = mutableListOf<String>()
        if (boundCount > 0) parts.add("已绑 $boundCount")
        if (rec.categories.isNotEmpty()) parts.add(rec.categories.first().substringAfterLast('/'))
        parts.add(sizeStr)
        holder.tvSub.text = parts.joinToString(" · ")

        holder.tvInstalled.visibility = if (isInstalled(rec)) View.VISIBLE else View.GONE

        val selected = selectionMode && rec.id in selectedIds
        holder.card.strokeWidth = dp(if (selected) 2 else 1)
        holder.card.strokeColor = ContextCompat.getColor(ctx, if (selected) R.color.accent else R.color.border)
        if (selectionMode) {
            holder.tvSelectCheck.visibility = View.VISIBLE
            holder.tvSelectCheck.text = if (selected) "✓" else ""
            holder.tvSelectCheck.setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
            holder.tvSelectCheck.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) {
                    setColor(ContextCompat.getColor(ctx, R.color.accent))
                } else {
                    setColor(Color.parseColor("#88000000"))
                    setStroke(dp(1), Color.WHITE)
                }
            }
        } else {
            holder.tvSelectCheck.visibility = View.GONE
        }

        holder.card.setOnClickListener {
            if (selectionMode) onToggleSelect(rec.id) else onClick(rec)
        }
        holder.card.setOnLongClickListener {
            if (selectionMode) {
                onToggleSelect(rec.id)
            } else {
                onLongClick(rec)
            }
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        val card: MaterialCardView,
        val iv: ImageView,
        val tvInstalled: TextView,
        val tvSelectCheck: TextView,
        val tvName: TextView,
        val tvSub: TextView
    ) : RecyclerView.ViewHolder(card)
}
