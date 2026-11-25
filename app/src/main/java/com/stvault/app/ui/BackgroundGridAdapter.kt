package com.stvault.app.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
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
 */
class BackgroundGridAdapter(
    private val bgRepo: BackgroundRepository,
    private val onClick: (BackgroundRecord) -> Unit,
    private val onLongClick: (BackgroundRecord) -> Unit
) : RecyclerView.Adapter<BackgroundGridAdapter.VH>() {

    private var items: List<BackgroundRecord> = emptyList()

    fun submitList(list: List<BackgroundRecord>) {
        items = list
        notifyDataSetChanged()
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
        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(col(R.color.surface_2))
        }
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
        box.addView(iv)
        box.addView(metaBox)
        card.addView(box)
        return VH(card, iv, tvName, tvSub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
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
        holder.tvSub.text = if (boundCount > 0) "已绑 $boundCount 美化 · $sizeStr" else sizeStr

        holder.card.setOnClickListener { onClick(rec) }
        holder.card.setOnLongClickListener {
            onLongClick(rec)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        val card: MaterialCardView,
        val iv: ImageView,
        val tvName: TextView,
        val tvSub: TextView
    ) : RecyclerView.ViewHolder(card)
}
