package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.stvault.app.core.AppLogEntry
import com.stvault.app.core.AppLogManager
import com.stvault.app.databinding.ActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var adapter: LogAdapter
    private var allLogs: List<AppLogEntry> = emptyList()
    private var activeFilter: String = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = LogAdapter()
        binding.rvAppLogs.layoutManager = LinearLayoutManager(this)
        binding.rvAppLogs.adapter = adapter

        binding.btnAppLogClose.setOnClickListener { finish() }
        binding.btnAppLogClear.setOnClickListener {
            VaultSheets.confirm(
                ctx = this,
                title = "清空日志",
                message = "确定清空所有活动日志与同步日志？",
                confirmText = "清空",
                destructive = true
            ) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { AppLogManager.clearAll(this@ActivityLogActivity) }
                    Toast.makeText(this@ActivityLogActivity, "已清空日志", Toast.LENGTH_SHORT).show()
                    loadLogs()
                }
            }
        }

        binding.chipAppLogAll.setOnClickListener { setFilter("all") }
        binding.chipAppLogBridge.setOnClickListener { setFilter(AppLogManager.CAT_BRIDGE) }
        binding.chipAppLogInbox.setOnClickListener { setFilter(AppLogManager.CAT_INBOX) }
        binding.chipAppLogLibrary.setOnClickListener { setFilter(AppLogManager.CAT_LIBRARY) }

        loadLogs()
    }

    private fun setFilter(f: String) {
        activeFilter = f
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        listOf(
            "all" to binding.chipAppLogAll,
            AppLogManager.CAT_BRIDGE to binding.chipAppLogBridge,
            AppLogManager.CAT_INBOX to binding.chipAppLogInbox,
            AppLogManager.CAT_LIBRARY to binding.chipAppLogLibrary
        ).forEach { (key, tv) ->
            val on = key == activeFilter
            tv.setTextColor(if (on) primary else muted)
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        applyFilter()
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            allLogs = withContext(Dispatchers.IO) {
                AppLogManager.getAllMerged(this@ActivityLogActivity)
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val list = if (activeFilter == "all") allLogs else allLogs.filter { it.category == activeFilter }
        binding.tvAppLogEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(list)
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

        private var items: List<AppLogEntry> = emptyList()
        private val expanded = HashSet<String>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<AppLogEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(box)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.root.context
            holder.root.removeAllViews()
            val item = items[position]
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            fun col(id: Int) = ContextCompat.getColor(ctx, id)

            val card = MaterialCardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                radius = dp(12).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(col(R.color.surface))
                strokeWidth = dp(1)
                strokeColor = col(R.color.border)
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(inner)

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badgeColor = when (item.action) {
                "覆盖", "删除" -> Color.parseColor("#D97706")
                "入库", "拉取", "装入" -> Color.parseColor("#16A34A")
                "冲突" -> Color.parseColor("#DC2626")
                else -> col(R.color.text_muted)
            }
            topRow.addView(TextView(ctx).apply {
                text = item.action.ifBlank { "记录" }
                setTextColor(badgeColor)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_pill_accent)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            })
            topRow.addView(TextView(ctx).apply {
                text = item.title.ifBlank { "未命名" }
                setTextColor(col(R.color.text_primary))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(ctx).apply {
                text = if (item.timestamp > 0L) fmt.format(Date(item.timestamp)) else ""
                setTextColor(col(R.color.text_muted))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            })
            inner.addView(topRow)

            val metaLine = buildString {
                if (item.source.isNotEmpty()) append(item.source)
                if (item.detail.isNotEmpty()) {
                    val firstLine = item.detail.lineSequence().firstOrNull().orEmpty().take(72)
                    if (isNotEmpty()) append(" · ")
                    append(firstLine)
                }
            }
            if (metaLine.isNotEmpty()) {
                inner.addView(TextView(ctx).apply {
                    text = metaLine
                    setTextColor(col(R.color.text_muted))
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                })
            }

            if (item.detail.isNotEmpty() && item.id in expanded) {
                inner.addView(TextView(ctx).apply {
                    text = item.detail
                    setTextColor(col(R.color.text_primary))
                    textSize = 11.5f
                    typeface = Typeface.MONOSPACE
                    setBackgroundResource(R.drawable.bg_input)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                })
            }

            if (item.detail.isNotEmpty()) {
                card.setOnClickListener {
                    if (item.id in expanded) expanded.remove(item.id) else expanded.add(item.id)
                    notifyItemChanged(position)
                }
            }
            holder.root.addView(card)
        }

        override fun getItemCount(): Int = items.size

        class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)
    }

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, ActivityLogActivity::class.java))
        }
    }
}
