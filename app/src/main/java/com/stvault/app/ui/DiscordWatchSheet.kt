package com.stvault.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.stvault.app.R
import com.stvault.app.core.DiscordWatchRepository
import com.stvault.app.core.FeedPost
import com.stvault.app.core.WatchedChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimalist modern BottomSheet for「追新」(cross-community Discord new-post sweep).
 * Uses RecyclerView for 60fps scrolling and soft Morandi pills matching the app's design system.
 */
object DiscordWatchSheet {

    interface Callbacks {
        fun onStartSweep(categoryFilter: String)
        fun onAddCurrentChannel()
        fun onOpenPost(post: FeedPost, overrideUrl: String? = null)
        fun onOpenChannel(channel: WatchedChannel)
        fun onDataChanged()
        fun isPostTitleHoarded(title: String): Boolean
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun col(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    private fun makeSoftPill(ctx: Context, text: String, bgHex: String, fgHex: String): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(ctx, 99).toFloat()
            setColor(Color.parseColor(bgHex))
        }
        return TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(fgHex))
            background = bg
            setPadding(dp(ctx, 6), dp(ctx, 1), dp(ctx, 6), dp(ctx, 1))
        }
    }

    private fun formatShortTime(ts: Long): String {
        if (ts <= 0L) return "未巡检"
        val diffMin = ((System.currentTimeMillis() - ts) / 60_000L).coerceAtLeast(0L)
        return when {
            diffMin < 1L -> "刚刚"
            diffMin < 60L -> "${diffMin}分钟前"
            diffMin < 1440L -> "${diffMin / 60}小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }

    fun show(
        ctx: Context,
        repo: DiscordWatchRepository,
        initialTab: Int = -1, // -1 auto, 0 = feed, 1 = channels
        initialCategory: String = "全部",
        callbacks: Callbacks
    ) {
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 18))
        }

        // Handle pill
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 14)
            }
            setBackgroundResource(R.drawable.bg_pill_accent)
        })

        var activeTab = when (initialTab) {
            0, 1 -> initialTab
            else -> if (repo.getChannels().isEmpty()) 1 else 0
        }
        var activeCategory = initialCategory

        // Header: Title + Segmented [新帖 | 频道]
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 12) }
        }

        val tvTitle = TextView(ctx).apply {
            text = "追新"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(col(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvTitle)

        val segWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(col(ctx, R.color.surface_2))
                setStroke(dp(ctx, 1), col(ctx, R.color.border))
            }
            setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
        }

        val tabFeedBtn = TextView(ctx).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
        }
        val tabChanBtn = TextView(ctx).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
        }
        segWrap.addView(tabFeedBtn)
        segWrap.addView(tabChanBtn)
        header.addView(segWrap)
        root.addView(header)

        // Category Filter Row
        val catScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        val catRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        catScroll.addView(catRow)
        root.addView(catScroll)

        // Empty state view
        val emptyBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 32), dp(ctx, 16), dp(ctx, 32))
            visibility = View.GONE
        }
        val tvEmptyTitle = TextView(ctx).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(col(ctx, R.color.text_primary))
            gravity = Gravity.CENTER
        }
        val tvEmptySub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(col(ctx, R.color.text_muted))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        emptyBox.addView(tvEmptyTitle)
        emptyBox.addView(tvEmptySub)
        root.addView(emptyBox)

        // RecyclerView for smooth scrolling
        val maxListHeight = (ctx.resources.displayMetrics.heightPixels * 0.48f).toInt()
        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxListHeight
            )
        }
        root.addView(recycler)

        // Footer action bar
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 12) }
        }

        val btnSecondary = MaterialButton(ctx).apply {
            textSize = 13f
            setTextColor(col(ctx, R.color.text_primary))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(col(ctx, R.color.border))
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 11)
            insetTop = 0
            insetBottom = 0
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply {
                marginEnd = dp(ctx, 8)
            }
        }
        val btnPrimary = MaterialButton(ctx).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(col(ctx, R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(col(ctx, R.color.accent))
            cornerRadius = dp(ctx, 11)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.25f)
        }
        footer.addView(btnSecondary)
        footer.addView(btnPrimary)
        root.addView(footer)

        lateinit var renderAll: () -> Unit

        // Feed Adapter
        val feedItems = mutableListOf<FeedPost>()
        val feedAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 12).toFloat()
                        setColor(col(ctx, R.color.surface_2))
                        setStroke(dp(ctx, 1), col(ctx, R.color.border))
                    }
                    setPadding(dp(ctx, 13), dp(ctx, 10), dp(ctx, 13), dp(ctx, 10))
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 7) }
                }
                val titleRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dot = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6)).apply {
                        marginEnd = dp(ctx, 7)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(col(ctx, R.color.accent))
                    }
                }
                val tvPostTitle = TextView(ctx).apply {
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val badgeWrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(dot)
                titleRow.addView(tvPostTitle)
                titleRow.addView(badgeWrap)

                val tvSub = TextView(ctx).apply {
                    textSize = 11.5f
                    setTextColor(col(ctx, R.color.text_muted))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(ctx, 4), 0, 0)
                }
                card.addView(titleRow)
                card.addView(tvSub)
                card.tag = arrayOf(dot, tvPostTitle, badgeWrap, tvSub)
                return object : RecyclerView.ViewHolder(card) {}
            }

            override fun getItemCount(): Int = feedItems.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val post = feedItems[position]
                val card = holder.itemView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                val refs = card.tag as Array<View>
                val dot = refs[0]
                val tvPostTitle = refs[1] as TextView
                val badgeWrap = refs[2] as LinearLayout
                val tvSub = refs[3] as TextView

                dot.visibility = if (post.read) View.GONE else View.VISIBLE
                tvPostTitle.text = post.title
                tvPostTitle.setTextColor(col(ctx, if (post.read) R.color.text_muted else R.color.text_primary))
                card.alpha = if (post.read) 0.68f else 1f

                badgeWrap.removeAllViews()
                if (callbacks.isPostTitleHoarded(post.title)) {
                    val pill = makeSoftPill(ctx, "已囤", "#244A8761", "#4A8761")
                    (pill.layoutParams as? LinearLayout.LayoutParams) ?: run {
                        pill.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(ctx, 6) }
                    }
                    badgeWrap.addView(pill)
                }
                if (post.mirrors.isNotEmpty()) {
                    val pill = makeSoftPill(ctx, "+${post.mirrors.size}同发", "#225174A3", "#5174A3")
                    pill.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(ctx, 5) }
                    badgeWrap.addView(pill)
                }

                val parts = mutableListOf<String>()
                if (post.author.isNotBlank()) parts.add(post.author)
                if (post.primaryChannelName.isNotBlank()) parts.add(post.primaryChannelName)
                parts.add(formatShortTime(post.discoveredAt))
                tvSub.text = parts.joinToString(" · ")

                card.setOnClickListener {
                    repo.markPostRead(post.id)
                    callbacks.onDataChanged()
                    sheet.dismiss()
                    callbacks.onOpenPost(post, null)
                }
                card.setOnLongClickListener {
                    val actions = mutableListOf<SheetAction>()
                    actions.add(
                        SheetAction("打开原帖 · ${post.primaryChannelName}", "优先社区") {
                            repo.markPostRead(post.id)
                            callbacks.onDataChanged()
                            sheet.dismiss()
                            callbacks.onOpenPost(post, post.url)
                        }
                    )
                    for (m in post.mirrors) {
                        actions.add(
                            SheetAction("切换社区 · ${m.channelName}", "同发镜像") {
                                repo.markPostRead(post.id)
                                callbacks.onDataChanged()
                                sheet.dismiss()
                                callbacks.onOpenPost(post, m.url)
                            }
                        )
                    }
                    if (!post.read) {
                        actions.add(
                            SheetAction("标为已读") {
                                repo.markPostRead(post.id)
                                callbacks.onDataChanged()
                                renderAll()
                            }
                        )
                    }
                    actions.add(
                        SheetAction("移出列表", destructive = true) {
                            repo.removeFeedPost(post.id)
                            callbacks.onDataChanged()
                            renderAll()
                        }
                    )
                    VaultSheets.actionList(ctx, post.title.take(36), actions)
                    true
                }
            }
        }

        // Channel Adapter
        val channelItems = mutableListOf<WatchedChannel>()
        val channelAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 12).toFloat()
                        setColor(col(ctx, R.color.surface_2))
                        setStroke(dp(ctx, 1), col(ctx, R.color.border))
                    }
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 7) }
                }
                val tvRank = TextView(ctx).apply {
                    textSize = 11.5f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(col(ctx, R.color.text_muted))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 7).toFloat()
                        setColor(col(ctx, R.color.surface))
                        setStroke(dp(ctx, 1), col(ctx, R.color.border))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)).apply {
                        marginEnd = dp(ctx, 10)
                    }
                }
                val info = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvName = TextView(ctx).apply {
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(col(ctx, R.color.text_primary))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val tvMeta = TextView(ctx).apply {
                    textSize = 11.5f
                    setTextColor(col(ctx, R.color.text_muted))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                info.addView(tvName)
                info.addView(tvMeta)

                val btnMore = TextView(ctx).apply {
                    text = "···"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(col(ctx, R.color.text_muted))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                }
                row.addView(tvRank)
                row.addView(info)
                row.addView(btnMore)
                row.tag = arrayOf(tvRank, tvName, tvMeta, btnMore)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun getItemCount(): Int = channelItems.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val ch = channelItems[position]
                val row = holder.itemView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                val refs = row.tag as Array<View>
                val tvRank = refs[0] as TextView
                val tvName = refs[1] as TextView
                val tvMeta = refs[2] as TextView
                val btnMore = refs[3] as TextView

                tvRank.text = (ch.priority + 1).toString()
                tvName.text = ch.name
                val seenLabel = if (ch.seenPostIds.isEmpty()) "待初始化" else formatShortTime(ch.lastCheckedAt)
                tvMeta.text = "${ch.category} · $seenLabel"

                row.setOnClickListener {
                    sheet.dismiss()
                    callbacks.onOpenChannel(ch)
                }

                val openManage = {
                    val acts = mutableListOf<SheetAction>()
                    if (ch.priority > 0) {
                        acts.add(SheetAction("置顶（最高去重优先级）", "多社区同发时优先保留此频道") {
                            repo.moveChannelToTop(ch.id)
                            callbacks.onDataChanged()
                            renderAll()
                        })
                        acts.add(SheetAction("上移一位") {
                            repo.moveChannelUp(ch.id)
                            callbacks.onDataChanged()
                            renderAll()
                        })
                    }
                    acts.add(SheetAction("重命名") {
                        VaultSheets.input(ctx, "修改频道名称", initialValue = ch.name, hint = "输入简短名称") { newName ->
                            repo.updateChannelMeta(ch.id, newName, ch.category)
                            callbacks.onDataChanged()
                            renderAll()
                        }
                    })
                    acts.add(SheetAction("切换分组（当前：${ch.category}）") {
                        VaultSheets.actionList(
                            ctx,
                            "选择分组",
                            DiscordWatchRepository.CATEGORIES.map { cat ->
                                SheetAction(cat, checked = cat == ch.category) {
                                    repo.updateChannelMeta(ch.id, ch.name, cat)
                                    callbacks.onDataChanged()
                                    renderAll()
                                }
                            }
                        )
                    })
                    acts.add(SheetAction("移除频道", destructive = true) {
                        repo.removeChannel(ch.id)
                        callbacks.onDataChanged()
                        renderAll()
                    })
                    VaultSheets.actionList(ctx, ch.name, acts)
                }
                btnMore.setOnClickListener { openManage() }
                row.setOnLongClickListener { openManage(); true }
            }
        }

        renderAll = {
            val allChannels = repo.getChannels()
            val allFeed = repo.getFeed()
            val unreadTotal = allFeed.count { !it.read }

            // Segmented tab styles
            fun styleTab(tv: TextView, selected: Boolean, label: String) {
                tv.text = label
                tv.setTextColor(col(ctx, if (selected) R.color.on_accent else R.color.text_muted))
                tv.background = if (selected) {
                    GradientDrawable().apply {
                        cornerRadius = dp(ctx, 8).toFloat()
                        setColor(col(ctx, R.color.accent))
                    }
                } else {
                    null
                }
            }
            styleTab(tabFeedBtn, activeTab == 0, if (unreadTotal > 0) "新帖 $unreadTotal" else "新帖")
            styleTab(tabChanBtn, activeTab == 1, "频道 ${allChannels.size}")

            // Category chips
            catRow.removeAllViews()
            val cats = listOf("全部") + DiscordWatchRepository.CATEGORIES
            for (cat in cats) {
                val sel = cat == activeCategory
                val chip = TextView(ctx).apply {
                    text = cat
                    textSize = 11.5f
                    setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(col(ctx, if (sel) R.color.on_accent else R.color.text_muted))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 99).toFloat()
                        setColor(col(ctx, if (sel) R.color.accent else R.color.surface_2))
                        if (!sel) setStroke(dp(ctx, 1), col(ctx, R.color.border))
                    }
                    setPadding(dp(ctx, 11), dp(ctx, 5), dp(ctx, 11), dp(ctx, 5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(ctx, 6) }
                    setOnClickListener {
                        if (activeCategory != cat) {
                            activeCategory = cat
                            renderAll()
                        }
                    }
                }
                catRow.addView(chip)
            }

            if (activeTab == 0) {
                val filtered = allFeed.filter {
                    activeCategory == "全部" || it.category == activeCategory
                }
                feedItems.clear()
                feedItems.addAll(filtered)
                if (recycler.adapter !== feedAdapter) recycler.adapter = feedAdapter
                feedAdapter.notifyDataSetChanged()

                if (filtered.isEmpty()) {
                    recycler.visibility = View.GONE
                    emptyBox.visibility = View.VISIBLE
                    if (allChannels.isEmpty()) {
                        tvEmptyTitle.text = "还没有追新频道"
                        tvEmptySub.text = "先切到「频道」加入常逛的发布区"
                    } else {
                        tvEmptyTitle.text = "暂无新帖"
                        tvEmptySub.text = "点下方「巡检」立即扫描最新帖子"
                    }
                } else {
                    recycler.visibility = View.VISIBLE
                    emptyBox.visibility = View.GONE
                }

                val hasUnreadInScope = filtered.any { !it.read }
                val hasReadInScope = filtered.any { it.read }
                btnSecondary.text = when {
                    hasUnreadInScope -> "全部已读"
                    hasReadInScope -> "清空已读"
                    else -> "+ 追当前频道"
                }
                btnSecondary.setOnClickListener {
                    when {
                        hasUnreadInScope -> {
                            repo.markAllRead(activeCategory)
                            callbacks.onDataChanged()
                            renderAll()
                        }
                        hasReadInScope -> {
                            repo.clearReadPosts()
                            callbacks.onDataChanged()
                            renderAll()
                        }
                        else -> {
                            sheet.dismiss()
                            callbacks.onAddCurrentChannel()
                        }
                    }
                }

                val sweepTargetCount = allChannels.count { activeCategory == "全部" || it.category == activeCategory }
                btnPrimary.text = if (activeCategory == "全部") {
                    if (sweepTargetCount > 0) "巡检全部 ($sweepTargetCount)" else "去添加频道"
                } else {
                    "巡检$activeCategory ($sweepTargetCount)"
                }
                btnPrimary.setOnClickListener {
                    if (sweepTargetCount == 0) {
                        activeTab = 1
                        renderAll()
                    } else {
                        sheet.dismiss()
                        callbacks.onStartSweep(activeCategory)
                    }
                }
            } else {
                val filteredCh = allChannels.filter {
                    activeCategory == "全部" || it.category == activeCategory
                }
                channelItems.clear()
                channelItems.addAll(filteredCh)
                if (recycler.adapter !== channelAdapter) recycler.adapter = channelAdapter
                channelAdapter.notifyDataSetChanged()

                if (filteredCh.isEmpty()) {
                    recycler.visibility = View.GONE
                    emptyBox.visibility = View.VISIBLE
                    tvEmptyTitle.text = if (allChannels.isEmpty()) "还没有加入任何频道" else "「$activeCategory」分组下暂无频道"
                    tvEmptySub.text = "在囤囤里打开任意发布频道，点下方「+ 追当前频道」\n序号越靠前，跨社区同发时越优先保留"
                } else {
                    recycler.visibility = View.VISIBLE
                    emptyBox.visibility = View.GONE
                }

                btnSecondary.text = "+ 追当前频道"
                btnSecondary.setOnClickListener {
                    sheet.dismiss()
                    callbacks.onAddCurrentChannel()
                }
                val sweepTargetCount = filteredCh.size
                btnPrimary.text = if (activeCategory == "全部") {
                    if (sweepTargetCount > 0) "巡检全部 ($sweepTargetCount)" else "开始巡检"
                } else {
                    "巡检$activeCategory ($sweepTargetCount)"
                }
                btnPrimary.isEnabled = sweepTargetCount > 0
                btnPrimary.alpha = if (sweepTargetCount > 0) 1f else 0.45f
                btnPrimary.setOnClickListener {
                    if (sweepTargetCount > 0) {
                        sheet.dismiss()
                        callbacks.onStartSweep(activeCategory)
                    }
                }
            }
        }

        tabFeedBtn.setOnClickListener {
            if (activeTab != 0) {
                activeTab = 0
                renderAll()
            }
        }
        tabChanBtn.setOnClickListener {
            if (activeTab != 1) {
                activeTab = 1
                renderAll()
            }
        }

        renderAll()

        sheet.setContentView(root)
        sheet.setOnShowListener {
            val bs = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.setBackgroundColor(Color.TRANSPARENT)
            if (bs != null) {
                BottomSheetBehavior.from(bs).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.show()
    }
}
