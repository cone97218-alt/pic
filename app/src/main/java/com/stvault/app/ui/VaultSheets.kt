package com.stvault.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.stvault.app.R

data class SheetAction(
    val label: String,
    val subtitle: String = "",
    val destructive: Boolean = false,
    val checked: Boolean = false,
    val onClick: () -> Unit
)

data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String = ""
)

/**
 * Unified modern minimalist BottomSheet components replacing all clunky native AlertDialogs.
 */
object VaultSheets {

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun col(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    private fun createSheet(ctx: Context): Pair<BottomSheetDialog, LinearLayout> {
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 22))
        }
        // Drag handle pill
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, 16)
            }
            setBackgroundResource(R.drawable.bg_pill_accent)
        })
        sheet.setContentView(root)
        sheet.setOnShowListener {
            val bs = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.setBackgroundColor(Color.TRANSPARENT)
            if (bs != null) {
                BottomSheetBehavior.from(bs).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return sheet to root
    }

    private fun makePrimaryBtn(ctx: Context, text: String, destructive: Boolean = false, onClick: () -> Unit): MaterialButton {
        val bg = col(ctx, if (destructive) R.color.red else R.color.accent)
        val fg = if (destructive) Color.WHITE else col(ctx, R.color.on_accent)
        return MaterialButton(ctx).apply {
            this.text = text
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(bg)
            cornerRadius = dp(ctx, 11)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }
    }

    private fun makeOutlinedBtn(ctx: Context, text: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(ctx).apply {
            this.text = text
            textSize = 13.5f
            setTextColor(col(ctx, R.color.text_muted))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(col(ctx, R.color.border))
            strokeWidth = dp(ctx, 1)
            cornerRadius = dp(ctx, 11)
            insetTop = 0
            insetBottom = 0
            elevation = 0f
            setOnClickListener { onClick() }
        }
    }

    private fun makeTitle(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title
        setTextColor(col(ctx, R.color.text_primary))
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
    }

    fun confirm(
        ctx: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val (sheet, root) = createSheet(ctx)
        root.addView(makeTitle(ctx, title))
        if (message.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = message
                setTextColor(col(ctx, R.color.text_muted))
                textSize = 13f
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 8)
                }
            })
        }
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 18)
            }
        }
        btnRow.addView(makeOutlinedBtn(ctx, cancelText) { sheet.dismiss() }, LinearLayout.LayoutParams(0, dp(ctx, 44), 0.9f).apply { marginEnd = dp(ctx, 10) })
        btnRow.addView(makePrimaryBtn(ctx, confirmText, destructive) {
            sheet.dismiss()
            onConfirm()
        }, LinearLayout.LayoutParams(0, dp(ctx, 44), 1.2f))
        root.addView(btnRow)
        sheet.show()
    }

    fun info(
        ctx: Context,
        title: String,
        message: String,
        primaryText: String = "知道了",
        onPrimary: (() -> Unit)? = null,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
        tertiaryText: String? = null,
        onTertiary: (() -> Unit)? = null
    ) {
        val (sheet, root) = createSheet(ctx)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeTitle(ctx, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (tertiaryText != null && onTertiary != null) {
            header.addView(TextView(ctx).apply {
                text = tertiaryText
                setTextColor(col(ctx, R.color.text_muted))
                textSize = 12.5f
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                setOnClickListener {
                    sheet.dismiss()
                    onTertiary()
                }
            })
        }
        root.addView(header)

        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.48f).toInt()
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 10)
            }
        }
        val msgTv = TextView(ctx).apply {
            text = message
            setTextColor(col(ctx, R.color.text_muted))
            textSize = 13f
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        scroll.addView(msgTv)
        if (message.length > 500) {
            scroll.layoutParams.height = maxH
        }
        root.addView(scroll)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 18)
            }
        }
        if (secondaryText != null) {
            btnRow.addView(makeOutlinedBtn(ctx, secondaryText) {
                sheet.dismiss()
                onSecondary?.invoke()
            }, LinearLayout.LayoutParams(0, dp(ctx, 44), 0.9f).apply { marginEnd = dp(ctx, 10) })
        }
        btnRow.addView(makePrimaryBtn(ctx, primaryText) {
            sheet.dismiss()
            onPrimary?.invoke()
        }, LinearLayout.LayoutParams(0, dp(ctx, 44), 1.2f))
        root.addView(btnRow)
        sheet.show()
    }

    fun actionList(
        ctx: Context,
        title: String? = null,
        actions: List<SheetAction>
    ) {
        val (sheet, root) = createSheet(ctx)
        if (!title.isNullOrBlank()) {
            root.addView(makeTitle(ctx, title).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(ctx, 10)
                }
            })
        }

        val listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        actions.forEachIndexed { idx, act ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 13), dp(ctx, 12), dp(ctx, 13))
                if (act.checked) setBackgroundResource(R.drawable.bg_input)
                setOnClickListener {
                    sheet.dismiss()
                    act.onClick()
                }
            }
            row.addView(TextView(ctx).apply {
                text = if (act.checked) "✓  ${act.label}" else act.label
                setTextColor(col(ctx, if (act.destructive) R.color.red else R.color.text_primary))
                textSize = 14.5f
                if (act.checked) setTypeface(null, Typeface.BOLD)
            })
            if (act.subtitle.isNotBlank()) {
                row.addView(TextView(ctx).apply {
                    text = act.subtitle
                    setTextColor(col(ctx, R.color.text_muted))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(ctx, 2)
                    }
                })
            }
            listBox.addView(row)
            if (idx < actions.lastIndex && !act.checked) {
                listBox.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
                    setBackgroundColor(col(ctx, R.color.border))
                })
            }
        }

        val maxScrollH = (ctx.resources.displayMetrics.heightPixels * 0.58f).toInt()
        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            isFillViewport = false
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (actions.size > 7) maxScrollH else ViewGroup.LayoutParams.WRAP_CONTENT
        )
        root.addView(scroll, lp)
        sheet.show()
    }

    fun input(
        ctx: Context,
        title: String,
        message: String = "",
        hint: String = "",
        initialValue: String = "",
        confirmText: String = "确定",
        onConfirm: (String) -> Unit
    ) {
        val (sheet, root) = createSheet(ctx)
        root.addView(makeTitle(ctx, title))
        if (message.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = message
                setTextColor(col(ctx, R.color.text_muted))
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 5)
                }
            })
        }
        val et = EditText(ctx).apply {
            this.hint = hint
            setText(initialValue)
            isSingleLine = true
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            setTextColor(col(ctx, R.color.text_primary))
            setHintTextColor(col(ctx, R.color.text_muted))
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 14)
            }
        }
        root.addView(et)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 16)
            }
        }
        btnRow.addView(makeOutlinedBtn(ctx, "取消") { sheet.dismiss() }, LinearLayout.LayoutParams(0, dp(ctx, 44), 0.85f).apply { marginEnd = dp(ctx, 10) })
        btnRow.addView(makePrimaryBtn(ctx, confirmText) {
            sheet.dismiss()
            onConfirm(et.text?.toString().orEmpty())
        }, LinearLayout.LayoutParams(0, dp(ctx, 44), 1.3f))
        root.addView(btnRow)
        sheet.show()
    }

    /**
     * Searchable virtualized BottomSheet picker — ideal for linking resources when there are hundreds/thousands of items!
     */
    fun searchablePicker(
        ctx: Context,
        title: String,
        items: List<PickerItem>,
        selectedId: String? = null,
        searchHint: String = "搜索名称...",
        headerAction: Pair<String, () -> Unit>? = null,
        onPicked: (PickerItem) -> Unit
    ) {
        val (sheet, root) = createSheet(ctx)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeTitle(ctx, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (headerAction != null) {
            header.addView(TextView(ctx).apply {
                text = headerAction.first
                setTextColor(col(ctx, R.color.text_primary))
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                setOnClickListener {
                    sheet.dismiss()
                    headerAction.second()
                }
            })
        }
        root.addView(header)

        // Search Bar
        val etSearch = EditText(ctx).apply {
            hint = searchHint
            isSingleLine = true
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            setTextColor(col(ctx, R.color.text_primary))
            setHintTextColor(col(ctx, R.color.text_muted))
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 42)).apply {
                topMargin = dp(ctx, 12)
                bottomMargin = dp(ctx, 8)
            }
        }
        root.addView(etSearch)

        val filtered = items.toMutableList()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
                }
                return object : RecyclerView.ViewHolder(box) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val box = holder.itemView as LinearLayout
                box.removeAllViews()
                val item = filtered[position]
                val isSelected = (selectedId != null && item.id == selectedId)
                if (isSelected) box.setBackgroundResource(R.drawable.bg_input) else box.background = null

                box.addView(TextView(ctx).apply {
                    text = if (isSelected) "✓  ${item.title}" else item.title
                    setTextColor(col(ctx, R.color.text_primary))
                    textSize = 14f
                    if (isSelected) setTypeface(null, Typeface.BOLD)
                    maxLines = 2
                })
                if (item.subtitle.isNotBlank()) {
                    box.addView(TextView(ctx).apply {
                        text = item.subtitle
                        setTextColor(col(ctx, R.color.text_muted))
                        textSize = 11.5f
                        maxLines = 1
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(ctx, 2)
                        }
                    })
                }
                box.setOnClickListener {
                    sheet.dismiss()
                    onPicked(item)
                }
            }

            override fun getItemCount(): Int = filtered.size
        }

        val maxListH = (ctx.resources.displayMetrics.heightPixels * 0.48f).toInt()
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (items.size > 6) maxListH else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(rv)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                filtered.clear()
                if (q.isEmpty()) filtered.addAll(items)
                else filtered.addAll(items.filter {
                    it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
                })
                adapter.notifyDataSetChanged()
            }
        })

        sheet.show()
    }

    /**
     * Searchable multi-select BottomSheet (ideal for 1~3 level categories!).
     */
    fun multiSelect(
        ctx: Context,
        title: String,
        items: List<PickerItem>,
        preselectedIds: Set<String>,
        headerAction: Pair<String, () -> Unit>? = null,
        onConfirm: (List<String>) -> Unit
    ) {
        val (sheet, root) = createSheet(ctx)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeTitle(ctx, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (headerAction != null) {
            header.addView(TextView(ctx).apply {
                text = headerAction.first
                setTextColor(col(ctx, R.color.text_primary))
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                setOnClickListener {
                    sheet.dismiss()
                    headerAction.second()
                }
            })
        }
        root.addView(header)

        val selected = preselectedIds.toMutableSet()
        val filtered = items.toMutableList()

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                }
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as LinearLayout
                row.removeAllViews()
                val item = filtered[position]
                val cb = CheckBox(ctx).apply {
                    isChecked = item.id in selected
                    buttonTintList = ColorStateList.valueOf(col(ctx, R.color.accent))
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selected.add(item.id) else selected.remove(item.id)
                    }
                }
                val tv = TextView(ctx).apply {
                    text = item.title
                    setTextColor(col(ctx, R.color.text_primary))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(ctx, 6)
                    }
                }
                row.addView(cb)
                row.addView(tv)
                row.setOnClickListener { cb.toggle() }
            }

            override fun getItemCount(): Int = filtered.size
        }

        if (items.size > 6) {
            val etSearch = EditText(ctx).apply {
                hint = "搜索分类..."
                isSingleLine = true
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
                setTextColor(col(ctx, R.color.text_primary))
                setHintTextColor(col(ctx, R.color.text_muted))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40)).apply {
                    topMargin = dp(ctx, 12)
                    bottomMargin = dp(ctx, 6)
                }
            }
            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim()?.lowercase().orEmpty()
                    filtered.clear()
                    if (q.isEmpty()) filtered.addAll(items)
                    else filtered.addAll(items.filter { it.title.lowercase().contains(q) })
                    adapter.notifyDataSetChanged()
                }
            })
            root.addView(etSearch)
        }

        val maxListH = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (items.size > 6) maxListH else ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6) }
        }
        root.addView(rv)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 14)
            }
        }
        btnRow.addView(makeOutlinedBtn(ctx, "取消") { sheet.dismiss() }, LinearLayout.LayoutParams(0, dp(ctx, 44), 0.85f).apply { marginEnd = dp(ctx, 10) })
        btnRow.addView(makePrimaryBtn(ctx, "保存") {
            sheet.dismiss()
            onConfirm(selected.toList())
        }, LinearLayout.LayoutParams(0, dp(ctx, 44), 1.3f))
        root.addView(btnRow)
        sheet.show()
    }
}
