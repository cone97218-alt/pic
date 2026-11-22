package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stvault.app.R
import com.stvault.app.core.CategoryManager
import com.stvault.app.core.InboxManager
import com.stvault.app.core.SmartImportSummary
import com.stvault.app.databinding.ActivityDiscordBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

/**
 * Built-in Discord Web hoarding browser (「囤囤」).
 * Opens official Discord Web inside Android's WebView with persistent login cookies,
 * and provides a 1-tap floating「囤囤」button that collects all currently visible
 * attachments (normal posts, forum threads, Bot DMs, AND ephemeral messages) along
 * with the visible author username — without ever leaving the current page.
 */
class DiscordBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscordBrowserBinding
    private var busy = false
    private var sidebarHidden = false
    private val zoomSteps = intArrayOf(85, 100, 115, 130)
    private var zoomIndex = 1 // default 100%

    private data class PageAttachment(
        val id: String,
        val url: String,
        val fileName: String,
        val author: String,
        val authorUid: String = ""
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscordBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
        zoomIndex = prefs.getInt("dc_web_zoom_idx", 1).coerceIn(0, zoomSteps.lastIndex)

        binding.btnWebClose.setOnClickListener { finishAndRemoveTask() }
        binding.btnWebOrganize.setOnClickListener {
            startActivity(
                android.content.Intent(this, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        }
        binding.btnWebBack.setOnClickListener { handleSmartBack() }
        binding.btnWebReload.setOnClickListener { binding.webDiscord.reload() }
        binding.btnWebCommands.setOnClickListener { showQuickCommandsSheet() }
        binding.btnWebSidebar.setOnClickListener {
            binding.webDiscord.evaluateJavascript(
                "(function(){ if(!window.__stSetSidebarHidden) return false; var next = document.documentElement.getAttribute('data-st-hide-sidebar') !== '1'; window.__stSetSidebarHidden(next); return next; })();"
            ) { res ->
                sidebarHidden = res == "true"
                binding.btnWebSidebar.text = if (sidebarHidden) "频道" else "看消息"
            }
        }
        binding.btnWebZoom.setOnClickListener {
            zoomIndex = (zoomIndex + 1) % zoomSteps.size
            prefs.edit().putInt("dc_web_zoom_idx", zoomIndex).apply()
            applyZoom()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSmartBack()
            }
        })

        val web = binding.webDiscord
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Desktop Chrome UA ensures discord.com/app opens the real Discord Web client
            // instead of redirecting to the "Get Discord Mobile App" splash screen.
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }
        applyZoom()

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.pbWebLoading.visibility = if (newProgress >= 85) View.GONE else View.VISIBLE
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                // Intercept direct attachment clicks straight into Vault instead of leaving Discord
                if (url.contains("cdn.discordapp.com/attachments/") || url.contains("media.discordapp.net/attachments/")) {
                    hoardSingleDirectUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pbWebLoading.visibility = View.GONE
                CookieManager.getInstance().flush()
                injectMobileLayoutHelper()
            }
        }

        // Also intercept WebView download callbacks straight into Vault!
        web.setDownloadListener { url, _, _, _, _ ->
            if (!url.isNullOrBlank()) {
                hoardSingleDirectUrl(url)
            }
        }

        // Make the floating [⚡ 指令 | 囤囤] pill freely draggable anywhere on screen
        setupDraggableHoardDock()
        refreshActiveQuickCmdLabel()

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onSidebarStateChanged(hidden: Boolean) {
                runOnUiThread {
                    sidebarHidden = hidden
                    binding.btnWebSidebar.text = if (hidden) "频道" else "看消息"
                }
            }

            @JavascriptInterface
            fun requestNativeEnter(delayMs: Long) {
                runOnUiThread {
                    binding.webDiscord.postDelayed({
                        dispatchNativeKey(KeyEvent.KEYCODE_ENTER)
                    }, delayMs.coerceIn(80L, 1200L))
                }
            }

            @JavascriptInterface
            fun requestNativeSlashType(restCmd: String) {
                runOnUiThread {
                    dispatchNativeKey(KeyEvent.KEYCODE_SLASH)
                    binding.webDiscord.postDelayed({
                        val escapedRest = JSONObject.quote(restCmd)
                        val bare = JSONObject.quote(restCmd.trim().lowercase())
                        binding.webDiscord.evaluateJavascript(
                            "if(window.__stContinueSlashCmd) window.__stContinueSlashCmd($escapedRest, $bare);",
                            null
                        )
                    }, 260L)
                }
            }

            @JavascriptInterface
            fun requestNativeTabThenEnter(tabDelayMs: Long, enterDelayMs: Long) {
                runOnUiThread {
                    binding.webDiscord.postDelayed({
                        dispatchNativeKey(KeyEvent.KEYCODE_TAB)
                        binding.webDiscord.postDelayed({
                            dispatchNativeKey(KeyEvent.KEYCODE_ENTER)
                        }, enterDelayMs.coerceIn(150L, 1200L))
                    }, tabDelayMs.coerceIn(60L, 1000L))
                }
            }
        }, "STHoardBridge")

        web.loadUrl("https://discord.com/app")
    }

    private fun dispatchNativeKey(keyCode: Int) {
        try {
            binding.webDiscord.requestFocus()
            hideSoftKeyboard()
            binding.webDiscord.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            binding.webDiscord.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableHoardDock() {
        val dock = binding.fabHoardDock
        val touchSlop = 8f * resources.displayMetrics.density

        fun attachDragAndTap(target: View, onTap: () -> Unit) {
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0f
            var startY = 0f
            var isDragging = false

            target.setOnTouchListener { _, event ->
                val parent = dock.parent as? View ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = dock.x
                        startY = dock.y
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val maxX = (parent.width - dock.width).coerceAtLeast(0).toFloat()
                            val maxY = (parent.height - dock.height).coerceAtLeast(0).toFloat()
                            dock.x = (startX + dx).coerceIn(0f, maxX)
                            dock.y = (startY + dy).coerceIn(0f, maxY)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) onTap()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        true
                    }
                    else -> false
                }
            }
        }

        attachDragAndTap(binding.fabHoard) {
            if (busy) {
                toast("正在囤入中，请稍候...")
            } else {
                scanVisibleAttachments()
            }
        }

        attachDragAndTap(binding.btnQuickCmd) {
            val active = getActiveQuickCommand()
            if (active.isBlank()) {
                showQuickCommandsSheet()
            } else {
                sendDiscordCommand(active)
            }
        }
    }

    private fun handleSmartBack() {
        val js = """
            (function() {
              try {
                var layer = document.querySelector('div[class*="chatLayerWrapper_"]');
                if (layer && layer.getBoundingClientRect().width > 40) {
                  var closeBtn = layer.querySelector('[aria-label="关闭"], [aria-label="Close"], [aria-label="关闭帖子"], [aria-label="Close Thread"], [class*="closeButton_"]');
                  if (!closeBtn) {
                    var btns = layer.querySelectorAll('section[class*="title_"] div[class*="toolbar_"] [role="button"], section[class*="title_"] div[class*="toolbar_"] button');
                    if (btns && btns.length > 0) closeBtn = btns[btns.length - 1];
                  }
                  if (closeBtn) {
                    closeBtn.click();
                    return 'CLOSED_THREAD';
                  }
                  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
                  return 'CLOSED_THREAD';
                }
                return 'NO_THREAD';
              } catch (e) {
                return 'NO_THREAD';
              }
            })();
        """.trimIndent()
        binding.webDiscord.evaluateJavascript(js) { res ->
            if (res?.contains("CLOSED_THREAD") == true) return@evaluateJavascript
            if (binding.webDiscord.canGoBack()) {
                binding.webDiscord.goBack()
            } else {
                finish()
            }
        }
    }

    private fun hideSoftKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.webDiscord.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun injectMobileLayoutHelper() {
        // Never overwrite current DOM sidebar state on sub-frame / message onPageFinished!
        binding.webDiscord.evaluateJavascript(MOBILE_LAYOUT_JS) {
            // Prime the scroll-accumulation collector immediately so scrolling from the very top is captured
            binding.webDiscord.evaluateJavascript(DOM_SCAN_JS, null)
        }
    }

    private fun applyZoom() {
        val pct = zoomSteps[zoomIndex]
        binding.btnWebZoom.text = "$pct%"
        binding.webDiscord.settings.textZoom = pct
    }

    private fun scanVisibleAttachments() {
        binding.webDiscord.evaluateJavascript(DOM_SCAN_JS) { rawResult ->
            val parsed = parseScanResult(rawResult)
            if (parsed.isEmpty()) {
                toast("当前屏幕未发现可囤的酒馆附件（滑到附件位置再点囤囤）")
                return@evaluateJavascript
            }
            showHoardPicker(parsed)
        }
    }

    private fun parseScanResult(raw: String?): List<PageAttachment> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            // evaluateJavascript wraps string returns in JSON quotes, so unwrap once
            val unwrapped = JSONTokener(raw).nextValue() as? String ?: return emptyList()
            val arr = JSONArray(unwrapped)
            val list = mutableListOf<PageAttachment>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                val fn = obj.optString("fileName").trim()
                val author = obj.optString("author").trim()
                val authorUid = obj.optString("authorUid").trim()
                if (url.isNotBlank() && fn.isNotBlank()) {
                    list.add(PageAttachment(id = "att_$i", url = url, fileName = fn, author = author, authorUid = authorUid))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun showHoardPicker(found: List<PageAttachment>) {
        val byId = found.associateBy { it.id }
        val pickerItems = found.map { att ->
            val sub = if (att.author.isNotBlank()) "作者：${att.author}" else ""
            PickerItem(id = att.id, title = att.fileName, subtitle = sub)
        }
        val allIds = found.map { it.id }.toSet()
        val firstWithAuthor = found.firstOrNull { it.author.isNotBlank() || it.authorUid.isNotBlank() }

        val extChips = listOf<Pair<String, (PickerItem) -> Boolean>>(
            "全部" to { true },
            "PNG" to { it.title.endsWith(".png", ignoreCase = true) },
            "JSON" to { it.title.endsWith(".json", ignoreCase = true) },
            "ZIP" to { it.title.endsWith(".zip", ignoreCase = true) },
            "其他" to {
                !it.title.endsWith(".png", ignoreCase = true) &&
                    !it.title.endsWith(".json", ignoreCase = true) &&
                    !it.title.endsWith(".zip", ignoreCase = true)
            }
        )

        VaultSheets.multiSelect(
            ctx = this,
            title = "发现 ${found.size} 个附件",
            items = pickerItems,
            preselectedIds = emptySet(),
            primaryConfirmText = "带标签囤入",
            secondaryConfirm = "仅囤入" to { picked ->
                val chosen = picked.mapNotNull { byId[it] }
                if (chosen.isEmpty()) {
                    toast("请先勾选要囤入的附件")
                } else {
                    runBatchHoard(chosen, overrideTags = emptyList())
                }
            },
            showSelectionTools = true,
            searchHint = "搜索文件名...",
            filterChips = extChips
        ) { picked ->
            val chosen = picked.mapNotNull { byId[it] }
            if (chosen.isEmpty()) {
                toast("未勾选任何附件")
                return@multiSelect
            }
            val refAtt = chosen.firstOrNull { it.author.isNotBlank() || it.authorUid.isNotBlank() } ?: firstWithAuthor
            val refAuthor = refAtt?.author.orEmpty()
            val refUid = refAtt?.authorUid.orEmpty()
            val suggested = suggestAuthorTagPath(refAuthor, refUid)
            VaultSheets.input(
                ctx = this,
                title = "确认分类标签",
                message = "已自动记住楼主对应分类（即使楼主改名也能识别）；多个标签用空格隔开。",
                initialValue = suggested,
                hint = "例如：作者/某某 古风",
                confirmText = "确认囤入"
            ) { raw ->
                val tags = raw.trim()
                    .split(Regex("""[\s,，、;；]+"""))
                    .map { it.trim().trimStart('#').trim('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (tags.isNotEmpty() && (refUid.isNotBlank() || refAuthor.isNotBlank())) {
                    saveAuthorAlias(refUid, refAuthor, tags.first())
                }
                runBatchHoard(chosen, overrideTags = tags)
            }
        }
    }

    private fun loadAuthorAliasMap(): JSONObject {
        val raw = getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
            .getString("dc_author_aliases", "").orEmpty()
        return try {
            if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveAuthorAlias(uid: String, name: String, mappedTag: String) {
        if (mappedTag.isBlank()) return
        val map = loadAuthorAliasMap()
        if (uid.isNotBlank()) map.put("uid:$uid", mappedTag)
        if (name.isNotBlank()) map.put("name:${name.lowercase()}", mappedTag)
        getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("dc_author_aliases", map.toString())
            .apply()
    }

    private fun suggestAuthorTagPath(rawAuthor: String, rawUid: String): String {
        val clean = rawAuthor.trim()
        val uid = rawUid.trim()
        val map = loadAuthorAliasMap()
        // 1. Permanent Discord User ID match (survives nickname changes!)
        if (uid.isNotBlank()) {
            val hit = map.optString("uid:$uid", "").trim()
            if (hit.isNotBlank()) return hit
        }
        // 2. Saved nickname alias match
        if (clean.isNotBlank()) {
            val hit = map.optString("name:${clean.lowercase()}", "").trim()
            if (hit.isNotBlank()) return hit
        }
        if (clean.isEmpty()) return ""
        // 3. Existing hierarchical category shorthand match
        val cm = CategoryManager.getInstance(this)
        val themeMatch = cm.resolveTagPath(CategoryManager.SCOPE_THEMES, clean)
        if (themeMatch.contains('/')) return themeMatch
        val charMatch = cm.resolveTagPath(CategoryManager.SCOPE_CHARACTERS, clean)
        if (charMatch.contains('/')) return charMatch
        return "作者/$clean"
    }

    private fun runBatchHoard(items: List<PageAttachment>, overrideTags: List<String>) {
        setBusy(true, "正在囤入 (1/${items.size})：${items.first().fileName}")
        lifecycleScope.launch(Dispatchers.IO) {
            val summary = SmartImportSummary()
            for ((idx, item) in items.withIndex()) {
                runOnUiThread {
                    binding.tvHoardProgress.text = "正在囤入 (${idx + 1}/${items.size})：${item.fileName}"
                }
                InboxManager.downloadAndImportSingleUrl(
                    context = this@DiscordBrowserActivity,
                    url = item.url,
                    fallbackName = item.fileName,
                    extraTags = overrideTags,
                    summary = summary
                )
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                toast(summary.formatMessage())
            }
        }
    }

    // ================== Custom Quick Bot Commands ==================

    private fun loadQuickCommands(): MutableList<String> {
        val prefs = getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("dc_quick_commands", "").orEmpty()
        val list = mutableListOf<String>()
        if (raw.isNotBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty() && s !in list) list.add(s)
                }
            } catch (_: Exception) {
            }
        }
        return list
    }

    private fun saveQuickCommands(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("dc_quick_commands", arr.toString())
            .apply()
    }

    private fun getActiveQuickCommand(): String {
        val saved = getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
            .getString("dc_active_command", "").orEmpty().trim()
        if (saved.isNotEmpty()) return saved
        return loadQuickCommands().firstOrNull().orEmpty()
    }

    private fun setActiveQuickCommand(cmd: String) {
        getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("dc_active_command", cmd.trim())
            .apply()
        refreshActiveQuickCmdLabel()
    }

    private fun refreshActiveQuickCmdLabel() {
        val active = getActiveQuickCommand()
        binding.btnQuickCmd.text = if (active.isNotBlank()) active else "指令"
    }

    private fun showQuickCommandsSheet() {
        val cmds = loadQuickCommands()
        val active = getActiveQuickCommand()
        val items = mutableListOf(
            PickerItem(id = "__PRESS_ENTER__", title = "发送输入框", subtitle = "发送当前输入框里的已选指令或内容"),
            PickerItem(id = "__SEND_CUSTOM_TEXT__", title = "发送口令", subtitle = "用原生输入框安全发送口令")
        )
        cmds.forEach {
            val sub = if (it == active) "当前悬浮快捷键 · 点击立即触发" else "点击立即触发并设为悬浮快捷键"
            items.add(PickerItem(id = it, title = it, subtitle = sub))
        }
        VaultSheets.searchablePicker(
            ctx = this,
            title = "指令与口令",
            items = items,
            selectedId = active,
            searchHint = "搜索指令...",
            headerAction = "+ 添加 / 管理" to {
                VaultSheets.actionList(
                    ctx = this,
                    title = "管理快捷指令",
                    actions = listOf(
                        SheetAction("添加新指令", "例如 /下载、/get、/领卡 等") {
                            showAddQuickCommandDialog()
                        },
                        SheetAction("删除已有指令", "移除不需要的快捷指令", destructive = true) {
                            if (cmds.isEmpty()) {
                                toast("暂无可删除的快捷指令")
                            } else {
                                val delItems = cmds.map { PickerItem(it, it, "点击删除") }
                                VaultSheets.searchablePicker(
                                    ctx = this,
                                    title = "选择要删除的指令",
                                    items = delItems
                                ) { toDel ->
                                    cmds.remove(toDel.id)
                                    saveQuickCommands(cmds)
                                    if (getActiveQuickCommand() == toDel.id) {
                                        setActiveQuickCommand(cmds.firstOrNull().orEmpty())
                                    }
                                    toast("已删除：${toDel.id}")
                                }
                            }
                        }
                    )
                )
            }
        ) { picked ->
            when (picked.id) {
                "__PRESS_ENTER__" -> {
                    binding.webDiscord.evaluateJavascript(
                        "(function(){ var l=document.querySelector('div[class*=\"chatLayerWrapper_\"]'); var b=(l&&l.querySelector('div[role=\"textbox\"][contenteditable=\"true\"]'))||document.querySelector('div[role=\"textbox\"][contenteditable=\"true\"]'); if(b) b.focus(); })();"
                    ) {
                        binding.webDiscord.postDelayed({ dispatchNativeKey(KeyEvent.KEYCODE_ENTER) }, 120L)
                    }
                }
                "__SEND_CUSTOM_TEXT__" -> showSendPasscodeDialog()
                else -> {
                    setActiveQuickCommand(picked.id)
                    sendDiscordCommand(picked.id)
                }
            }
        }
    }

    private fun showSendPasscodeDialog() {
        VaultSheets.input(
            ctx = this,
            title = "发送口令 / 文字",
            message = "安全填入当前频道并发送，避免网页键盘触发闪退。",
            hint = "输入口令或要发送的文字...",
            confirmText = "发送"
        ) { raw ->
            val clean = raw.trim()
            if (clean.isNotEmpty()) {
                sendDiscordCommand(clean)
            }
        }
    }

    private fun showAddQuickCommandDialog() {
        VaultSheets.input(
            ctx = this,
            title = "添加快捷指令",
            message = "输入社区 Bot 使用的指令（如 /下载、/get），支持一键触发真实斜杠指令。",
            hint = "例如：/下载",
            confirmText = "添加"
        ) { raw ->
            val clean = raw.trim()
            if (clean.isEmpty()) {
                toast("指令不能为空")
                return@input
            }
            val cmds = loadQuickCommands()
            if (clean !in cmds) {
                cmds.add(0, clean)
                saveQuickCommands(cmds)
            }
            setActiveQuickCommand(clean)
            toast("已添加并设为快捷键：$clean")
            showQuickCommandsSheet()
        }
    }

    private fun sendDiscordCommand(cmd: String) {
        hideSoftKeyboard()
        val escaped = JSONObject.quote(cmd)
        val bareName = JSONObject.quote(cmd.trimStart('/').trim().lowercase())
        val js = """
            (function() {
              try {
                function findVisibleBox() {
                  var layer = document.querySelector('div[class*="chatLayerWrapper_"]');
                  if (layer) {
                    var lb = layer.querySelector('div[role="textbox"][contenteditable="true"]');
                    if (lb && lb.getBoundingClientRect().width > 40) return lb;
                  }
                  var all = document.querySelectorAll('div[role="textbox"][contenteditable="true"]');
                  for (var i = all.length - 1; i >= 0; i--) {
                    var r = all[i].getBoundingClientRect();
                    if (r.width > 40 && r.height > 10) return all[i];
                  }
                  return null;
                }
                var box = findVisibleBox();
                if (!box) return 'NO_BOX';
                box.setAttribute('inputmode', 'none');
                box.focus();
                var sel = window.getSelection();
                if (sel) {
                  var range = document.createRange();
                  range.selectNodeContents(box);
                  sel.removeAllRanges();
                  sel.addRange(range);
                }
                try {
                  box.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true }));
                } catch (e0) {}
                document.execCommand('delete', false, null);

                function insertSlateText(txt) {
                  var before = (box.textContent || '').trim();
                  try {
                    box.dispatchEvent(new InputEvent('beforeinput', { inputType: 'insertText', data: txt, bubbles: true, cancelable: true }));
                  } catch (e1) {}
                  if ((box.textContent || '').trim() === before) {
                    document.execCommand('insertText', false, txt);
                  }
                }

                var textToInsert = $escaped;
                var isSlash = textToInsert.indexOf('/') === 0;
                if (isSlash) {
                  window.__stContinueSlashCmd = function(rest, targetName) {
                    var b = findVisibleBox();
                    if (!b) return;
                    b.focus();
                    if (rest) {
                      var bef = (b.textContent || '').trim();
                      try {
                        b.dispatchEvent(new InputEvent('beforeinput', { inputType: 'insertText', data: rest, bubbles: true, cancelable: true }));
                      } catch (e2) {}
                      if ((b.textContent || '').trim() === bef) {
                        document.execCommand('insertText', false, rest);
                      }
                    }
                    var tries = 0;
                    var appFallbackTried = false;
                    function clearBoxText() {
                      var bb = findVisibleBox();
                      if (!bb) return;
                      bb.focus();
                      var s = window.getSelection();
                      if (s) {
                        var r = document.createRange();
                        r.selectNodeContents(bb);
                        s.removeAllRanges();
                        s.addRange(r);
                      }
                      try { bb.dispatchEvent(new InputEvent('beforeinput', { inputType: 'deleteContentBackward', bubbles: true, cancelable: true })); } catch (e) {}
                      document.execCommand('delete', false, null);
                    }
                    function tryAppLauncherFlow() {
                      var scope = document.querySelector('div[class*="chatLayerWrapper_"]') || document;
                      var appBtn = scope.querySelector('[aria-label*="应用"], [aria-label*="App"], [class*="appLauncher"] button, button[class*="appLauncher"], [class*="entryPointButton"]');
                      if (!appBtn) return;
                      clearBoxText();
                      appBtn.click();
                      var appTries = 0;
                      var appTimer = setInterval(function() {
                        appTries++;
                        var dlg = document.querySelector('div[role="dialog"], div[class*="drawerSizingWrapper_"], div[class*="appLauncher_"], div[class*="contentWrapper_"]');
                        if (!dlg) {
                          if (appTries >= 20) clearInterval(appTimer);
                          return;
                        }
                        // If a command row matching targetName is already visible, click it directly!
                        var clickables = dlg.querySelectorAll('button, [role="button"], [role="option"], [class*="command_"], [class*="clickable_"]');
                        var cmdHit = null;
                        for (var k = 0; k < clickables.length; k++) {
                          var ct = (clickables[k].textContent || '').trim().toLowerCase();
                          if (targetName && ct && ct.indexOf(targetName) !== -1 && ct.length < 60) {
                            cmdHit = clickables[k];
                            break;
                          }
                        }
                        if (cmdHit) {
                          clearInterval(appTimer);
                          cmdHit.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                          cmdHit.click();
                          setTimeout(function() {
                            var b3 = findVisibleBox();
                            var r3 = b3 ? (b3.textContent || '').trim() : '';
                            var hasPill3 = !!document.querySelector('[class*="optionPill_"], [class*="commandName_"], [class*="pill_"], [data-slate-void="true"]');
                            if (hasPill3 || (r3 && r3.indexOf('/') !== 0)) {
                              if (window.STHoardBridge && window.STHoardBridge.requestNativeEnter) {
                                window.STHoardBridge.requestNativeEnter(200);
                              }
                            }
                          }, 450);
                          return;
                        }
                        // Otherwise, if App Launcher has a search input, type targetName once to filter commands
                        if (appTries === 3 && targetName) {
                          var sInp = dlg.querySelector('input[type="text"], input[type="search"], input');
                          if (sInp) {
                            sInp.focus();
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                            if (setter) setter.call(sInp, targetName); else sInp.value = targetName;
                            sInp.dispatchEvent(new Event('input', { bubbles: true }));
                          }
                        }
                        if (appTries >= 24) clearInterval(appTimer);
                      }, 150);
                    }
                    var timer = setInterval(function() {
                      tries++;
                      // Strictly match Discord's slash-command autocomplete popup
                      var popup = document.querySelector('div[class*="autocomplete_"]');
                      if (!popup || popup.getBoundingClientRect().height < 20) {
                        if (tries >= 9 && !appFallbackTried) {
                          appFallbackTried = true;
                          clearInterval(timer);
                          tryAppLauncherFlow();
                        }
                        return;
                      }
                      var rows = popup.querySelectorAll('[role="option"], div[class*="autocompleteRow_"]');
                      if (!rows || rows.length === 0) {
                        if (tries >= 9 && !appFallbackTried) {
                          appFallbackTried = true;
                          clearInterval(timer);
                          tryAppLauncherFlow();
                        }
                        return;
                      }
                      var matched = null;
                      for (var i = 0; i < rows.length; i++) {
                        var t = (rows[i].textContent || '').toLowerCase();
                        if (targetName && t.indexOf(targetName) !== -1) {
                          matched = rows[i];
                          break;
                        }
                      }
                      if (!matched) {
                        if (tries >= 9 && !appFallbackTried) {
                          appFallbackTried = true;
                          clearInterval(timer);
                          tryAppLauncherFlow();
                        }
                        return;
                      }
                      clearInterval(timer);
                      matched.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                      matched.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
                      matched.click();
                      setTimeout(function() {
                        var b2 = findVisibleBox();
                        var rawTxt = b2 ? (b2.textContent || '').trim() : '';
                        var hasSlashPill = !!document.querySelector('[class*="optionPill_"], [class*="commandName_"], [class*="pill_"], [data-slate-void="true"]');
                        if (hasSlashPill || (rawTxt.indexOf('/') !== 0)) {
                          if (window.STHoardBridge && window.STHoardBridge.requestNativeEnter) {
                            window.STHoardBridge.requestNativeEnter(180);
                          }
                        }
                      }, 420);
                    }, 150);
                  };
                  // Trigger '/' via real native KEYCODE_SLASH so Discord's slash command UI opens
                  if (window.STHoardBridge && window.STHoardBridge.requestNativeSlashType) {
                    window.STHoardBridge.requestNativeSlashType(textToInsert.slice(1));
                  }
                } else {
                  insertSlateText(textToInsert);
                  if (window.STHoardBridge && window.STHoardBridge.requestNativeEnter) {
                    window.STHoardBridge.requestNativeEnter(220);
                  }
                }
                return 'OK';
              } catch (e) {
                return 'ERR:' + e.message;
              }
            })();
        """.trimIndent()
        binding.webDiscord.evaluateJavascript(js) { res ->
            hideSoftKeyboard()
            if (res?.contains("NO_BOX") == true) {
                toast("当前页面未找到消息输入框，请先进入频道或帖子")
            } else {
                toast("已触发指令：$cmd")
            }
        }
    }

    private fun hoardSingleDirectUrl(url: String) {
        if (busy) {
            toast("正在囤入中，请稍候...")
            return
        }
        val fn = url.substringBefore('?').substringAfterLast('/')
        setBusy(true, "正在囤入：$fn")
        lifecycleScope.launch(Dispatchers.IO) {
            val summary = SmartImportSummary()
            InboxManager.downloadAndImportSingleUrl(
                context = this@DiscordBrowserActivity,
                url = url,
                fallbackName = fn,
                summary = summary
            )
            withContext(Dispatchers.Main) {
                setBusy(false)
                toast(summary.formatMessage())
            }
        }
    }

    private fun setBusy(isBusy: Boolean, text: String = "") {
        busy = isBusy
        binding.hoardProgressStrip.visibility = if (isBusy) View.VISIBLE else View.GONE
        if (text.isNotBlank()) binding.tvHoardProgress.text = text
        binding.fabHoard.isEnabled = !isBusy
    }

    override fun onResume() {
        super.onResume()
        binding.webDiscord.onResume()
        binding.webDiscord.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        binding.webDiscord.onPause()
        binding.webDiscord.pauseTimers()
    }

    override fun onDestroy() {
        try {
            val web = binding.webDiscord
            web.stopLoading()
            web.loadUrl("about:blank")
            web.onPause()
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Injects a lightweight mobile-adaptation CSS layer into Discord Web:
         * - Hides the right-hand online members list to free screen space
         * - Toggles the left server rail + channel sidebar via `html.st-hide-sidebar` so chat/forum gets 100% width
         * - Auto-hides the sidebar when the user clicks a channel or DM link, and lets the top-bar button bring it back
         */
        private val MOBILE_LAYOUT_JS = """
            (function() {
              try {
                var vp = document.querySelector('meta[name="viewport"]');
                if (!vp) {
                  vp = document.createElement('meta');
                  vp.name = 'viewport';
                  document.head.appendChild(vp);
                }
                vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes';
              } catch (e) {}

              if (!document.getElementById('st-mobile-dc-style')) {
                var s = document.createElement('style');
                s.id = 'st-mobile-dc-style';
                s.textContent = [
                  'html, body, #app-mount { width: 100% !important; min-width: 0 !important; max-width: 100% !important; overflow-x: hidden !important; margin: 0 !important; padding: 0 !important; }',
                  'div[class*="membersWrap_"], aside[class*="membersWrap_"] { display: none !important; }',
                  'html[data-st-hide-sidebar="1"] nav[class*="guilds_"], html[data-st-hide-sidebar="1"] div[class*="guilds_"] { display: none !important; width: 0 !important; min-width: 0 !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="base_"] > div[class*="content_"] > div[class*="sidebar_"] { display: none !important; width: 0 !important; min-width: 0 !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="sidebarList_"], html[data-st-hide-sidebar="1"] div[class*="resizeHandle_"] { display: none !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="base_"] { position: relative !important; left: 0 !important; right: 0 !important; margin-left: 0 !important; width: 100% !important; min-width: 0 !important; max-width: 100% !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="base_"] > div[class*="content_"] { width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; left: 0 !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="chat_"], html[data-st-hide-sidebar="1"] main[class*="chatContent_"], html[data-st-hide-sidebar="1"] div[class*="container_"] { min-width: 0 !important; max-width: 100% !important; margin-left: 0 !important; left: 0 !important; box-sizing: border-box !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="chatLayerWrapper_"] { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; padding: 0 !important; transform: none !important; z-index: 200 !important; background: var(--background-primary, #313338) !important; box-sizing: border-box !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="chatLayerWrapper_"] > * { width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; left: 0 !important; }',
                  'html[data-st-hide-sidebar="1"] section[class*="title_"] { position: relative !important; z-index: 20 !important; flex-shrink: 0 !important; }',
                  'html[data-st-hide-sidebar="1"] section[class*="title_"] div[class*="children_"] { min-width: 0 !important; flex: 1 1 auto !important; overflow: hidden !important; }',
                  'html[data-st-hide-sidebar="1"] section[class*="title_"] h1, html[data-st-hide-sidebar="1"] section[class*="title_"] div[class*="titleWrapper_"] { min-width: 0 !important; overflow: hidden !important; text-overflow: ellipsis !important; white-space: nowrap !important; }',
                  'html[data-st-hide-sidebar="1"] section[class*="title_"] div[class*="toolbar_"] { flex-shrink: 0 !important; position: relative !important; z-index: 25 !important; }',
                  'html[data-st-hide-sidebar="1"] div[class*="headerRow_"], html[data-st-hide-sidebar="1"] div[class*="buttons_"] { position: relative !important; z-index: 15 !important; }',
                  'html[data-st-hide-sidebar="1"] ol[class*="scrollerInner_"], html[data-st-hide-sidebar="1"] li[class*="messageListItem_"] { max-width: 100% !important; box-sizing: border-box !important; }',
                  'html:not([data-st-hide-sidebar="1"]) div[class*="base_"] > div[class*="content_"] > div[class*="sidebar_"] { width: calc(100vw - 72px) !important; min-width: 240px !important; max-width: 100vw !important; }',
                  'html:not([data-st-hide-sidebar="1"]) div[class*="sidebarList_"] { width: calc(100vw - 72px) !important; }'
                ].join('\n');
                document.head.appendChild(s);
              }
              window.__stSetSidebarHidden = function(hidden) {
                if (hidden) {
                  document.documentElement.setAttribute('data-st-hide-sidebar', '1');
                } else {
                  document.documentElement.removeAttribute('data-st-hide-sidebar');
                }
                if (window.STHoardBridge && window.STHoardBridge.onSidebarStateChanged) {
                  window.STHoardBridge.onSidebarStateChanged(!!hidden);
                }
              };
              if (!window.__stSidebarAutoBound) {
                window.__stSidebarAutoBound = true;
                // Suppress Android soft keyboard on Discord chat Slate textboxes (never affects /login <input> fields)
                function lockChatKeyboard() {
                  if (location.pathname.indexOf('/channels/') !== 0) return;
                  var boxes = document.querySelectorAll('div[role="textbox"][contenteditable="true"]');
                  for (var i = 0; i < boxes.length; i++) {
                    if (boxes[i].getAttribute('inputmode') !== 'none') {
                      boxes[i].setAttribute('inputmode', 'none');
                    }
                  }
                }
                lockChatKeyboard();
                setInterval(lockChatKeyboard, 1200);
                // Scroll-accumulation pool: remembers all attachments scrolled past in the current channel/thread
                setInterval(function() {
                  if (window.__stCollectIntoPool) window.__stCollectIntoPool();
                }, 900);
                document.addEventListener('click', function(e) {
                  var a = e.target && e.target.closest ? e.target.closest('a[href*="/channels/"]') : null;
                  if (!a) return;
                  var inSidebar = a.closest('div[class*="sidebar_"], div[class*="sidebarList_"]');
                  if (inSidebar && document.documentElement.getAttribute('data-st-hide-sidebar') !== '1') {
                    setTimeout(function() {
                      window.__stSetSidebarHidden(true);
                    }, 180);
                  }
                }, true);
              }
            })();
        """.trimIndent()

        /**
         * Read-only DOM scanner: collects attachment URLs already rendered on the user's screen
         * (including normal messages, forum threads, Bot DMs, and ephemeral messages),
         * converts media.discordapp.net resized preview URLs back to original cdn.discordapp.com
         * lossless URLs so PNG character card metadata is preserved, and reads the visible author name.
         */
        private val DOM_SCAN_JS = """
            (function() {
              try {
                function runCollector() {
                  var exts = /\.(png|json|zip|css|js|html|htm|txt)(\?|$)/i;
                  var layer = document.querySelector('div[class*="chatLayerWrapper_"]');
                  var layerTitle = '';
                  if (layer) {
                    var h = layer.querySelector('section[class*="title_"] h1, section[class*="title_"] [class*="title_"]');
                    if (h && h.textContent) layerTitle = h.textContent.trim();
                  }
                  var ctxKey = location.pathname + '|' + layerTitle;
                  if (!window.__stHoardPool || window.__stHoardPool.ctxKey !== ctxKey) {
                    window.__stHoardPool = { ctxKey: ctxKey, order: [], map: {} };
                  }
                  var pool = window.__stHoardPool;
                  function cleanCdnUrl(raw) {
                    if (!raw) return '';
                    var u = raw.trim();
                    if (u.indexOf('media.discordapp.net/attachments/') !== -1) {
                      try {
                        var parsed = new URL(u);
                        parsed.hostname = 'cdn.discordapp.com';
                        parsed.searchParams.delete('format');
                        parsed.searchParams.delete('quality');
                        parsed.searchParams.delete('width');
                        parsed.searchParams.delete('height');
                        u = parsed.toString();
                      } catch (e) {}
                    }
                    return u;
                  }
                  function readMsgMeta(msgEl) {
                    if (!msgEl) return { name: '', uid: '', isBot: false };
                    var cur = msgEl;
                    for (var steps = 0; steps < 10 && cur; steps++) {
                      var u = cur.querySelector('[id^="message-username-"], span[class*="username_"], h3 [class*="username"]');
                      if (u && u.textContent && u.textContent.trim()) {
                        var name = u.textContent.trim().slice(0, 28);
                        var isBot = !!cur.querySelector('span[class*="botTag"], [class*="botText"]');
                        var uid = '';
                        var av = cur.querySelector('img[class*="avatar_"], img[src*="/avatars/"]');
                        if (av && av.src) {
                          var m = av.src.match(/\/avatars\/(\d+)\//);
                          if (m && m[1]) uid = m[1];
                        }
                        return { name: name, uid: uid, isBot: isBot };
                      }
                      cur = cur.previousElementSibling;
                    }
                    return { name: '', uid: '', isBot: false };
                  }
                  function findThreadOp() {
                    var root = layer || document;
                    var msgs = root.querySelectorAll('li[id^="chat-messages-"], div[id^="chat-messages-"]');
                    for (var i = 0; i < msgs.length; i++) {
                      var opBadge = msgs[i].querySelector('[class*="opTag"], [class*="ownerIcon"]');
                      if (!opBadge) {
                        var tags = msgs[i].querySelectorAll('span[class*="botText"], span[class*="tagText"]');
                        for (var t = 0; t < tags.length; t++) {
                          var tt = (tags[t].textContent || '').trim().toUpperCase();
                          if (tt === 'OP' || tt === '楼主') { opBadge = tags[t]; break; }
                        }
                      }
                      if (opBadge) {
                        var opMeta = readMsgMeta(msgs[i]);
                        if (opMeta.name && !opMeta.isBot) return opMeta;
                      }
                    }
                    var activeCard = document.querySelector('li[class*="card_"][class*="isOpen_"], div[class*="mainCard_"][class*="isOpen_"], [class*="card_"][aria-selected="true"]');
                    if (activeCard) {
                      var cardAuthor = activeCard.querySelector('[class*="author_"] [class*="username"], [class*="userName"], span[class*="username_"]');
                      if (cardAuthor && cardAuthor.textContent && cardAuthor.textContent.trim()) {
                        var cUid = '';
                        var cAv = activeCard.querySelector('img[src*="/avatars/"]');
                        if (cAv && cAv.src) {
                          var cm = cAv.src.match(/\/avatars\/(\d+)\//);
                          if (cm && cm[1]) cUid = cm[1];
                        }
                        return { name: cardAuthor.textContent.trim().slice(0, 28), uid: cUid, isBot: false };
                      }
                    }
                    var scroller = root.querySelector('div[class*="scroller_"][class*="managedReactiveScroller_"], div[class*="scroller_"]');
                    var nearTop = !scroller || scroller.scrollTop < 350;
                    if (nearTop && msgs.length > 0) {
                      var firstMeta = readMsgMeta(msgs[0]);
                      if (firstMeta.name && !firstMeta.isBot) return firstMeta;
                    }
                    return { name: '', uid: '', isBot: false };
                  }
                  var threadOp = findThreadOp();
                  if (threadOp.name) pool.op = threadOp;
                  else if (pool.op) threadOp = pool.op;
                  function findAuthorInfo(el) {
                    var msg = el.closest('li[id^="chat-messages-"], div[id^="chat-messages-"], [class*="messageListItem"]');
                    var meta = readMsgMeta(msg);
                    if ((meta.isBot || !meta.name) && threadOp.name) {
                      return threadOp;
                    }
                    return meta;
                  }
                  function extractDomFileName(el, urlFallback) {
                    try {
                      var candidates = [];
                      if (el) {
                        var txt = (el.textContent || '').trim();
                        if (txt && exts.test(txt) && txt.length <= 140) candidates.push(txt);
                        var ttl = (el.getAttribute('title') || '').trim();
                        if (ttl && exts.test(ttl) && ttl.length <= 140) candidates.push(ttl);
                        var alt = (el.getAttribute('alt') || '').trim();
                        if (alt && exts.test(alt) && alt.length <= 140) candidates.push(alt);
                        var box = el.closest('[class*="attachment_"], [class*="nonMediaAttachment_"], [class*="file_"], [class*="mosaicItem_"]');
                        if (box) {
                          var fnEl = box.querySelector('a[class*="fileNameLink_"], div[class*="filename_"], span[class*="filename_"], [class*="fileName_"]');
                          if (fnEl && fnEl.textContent) {
                            var btxt = fnEl.textContent.trim();
                            if (btxt && exts.test(btxt) && btxt.length <= 140) candidates.push(btxt);
                          }
                        }
                      }
                      for (var k = 0; k < candidates.length; k++) {
                        var c = candidates[k].replace(/[\r\n]+/g, ' ').trim();
                        if (c) return c;
                      }
                    } catch (e) {}
                    return urlFallback;
                  }
                  function pushUrl(rawUrl, el) {
                    var u = cleanCdnUrl(rawUrl);
                    if (!u) return;
                    var isDcAtt = u.indexOf('cdn.discordapp.com/attachments/') !== -1;
                    if (!isDcAtt && !exts.test(u)) return;
                    var path = u.split('?')[0].split('#')[0];
                    if (!exts.test(path)) return;
                    var key = path.toLowerCase();
                    var urlFn = path.substring(path.lastIndexOf('/') + 1);
                    try { urlFn = decodeURIComponent(urlFn); } catch (e) {}
                    var fn = extractDomFileName(el, urlFn);
                    var aInfo = findAuthorInfo(el);
                    if (!pool.map[key]) {
                      pool.order.push(key);
                      pool.map[key] = { url: u, fileName: fn, author: aInfo.name, authorUid: aInfo.uid };
                    } else {
                      // Refresh signed URL / better author if newly discovered
                      pool.map[key].url = u;
                      if (!pool.map[key].author && aInfo.name) {
                        pool.map[key].author = aInfo.name;
                        pool.map[key].authorUid = aInfo.uid;
                      }
                    }
                  }
                  var scanRoot = layer || document;
                  var anchors = scanRoot.querySelectorAll('a[href]');
                  for (var i = 0; i < anchors.length; i++) {
                    pushUrl(anchors[i].href, anchors[i]);
                  }
                  var imgs = scanRoot.querySelectorAll('img[src]');
                  for (var j = 0; j < imgs.length; j++) {
                    var src = imgs[j].src || '';
                    if (src.indexOf('/attachments/') !== -1) {
                      pushUrl(src, imgs[j]);
                    }
                  }
                  return pool.order.map(function(k) { return pool.map[k]; });
                }
                window.__stCollectIntoPool = runCollector;
                return JSON.stringify(runCollector());
              } catch (e) {
                return '[]';
              }
            })();
        """.trimIndent()
    }
}
