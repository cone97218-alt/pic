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
        val author: String
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscordBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("st_inbox_prefs", Context.MODE_PRIVATE)
        zoomIndex = prefs.getInt("dc_web_zoom_idx", 1).coerceIn(0, zoomSteps.lastIndex)

        binding.btnWebClose.setOnClickListener { finish() }
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
        binding.webDiscord.evaluateJavascript(MOBILE_LAYOUT_JS, null)
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
                if (url.isNotBlank() && fn.isNotBlank()) {
                    list.add(PageAttachment(id = "att_$i", url = url, fileName = fn, author = author))
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
        val firstAuthor = found.firstOrNull { it.author.isNotBlank() }?.author.orEmpty()

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
            searchHint = "搜索文件名..."
        ) { picked ->
            val chosen = picked.mapNotNull { byId[it] }
            if (chosen.isEmpty()) {
                toast("未勾选任何附件")
                return@multiSelect
            }
            val suggested = suggestAuthorTagPath(chosen.firstOrNull { it.author.isNotBlank() }?.author ?: firstAuthor)
            VaultSheets.input(
                ctx = this,
                title = "确认分类标签",
                message = "可改名或指定目录（如 作者/某某，多个标签用空格隔开；留空则仅囤入）。",
                initialValue = suggested,
                hint = "例如：作者/某某 古风",
                confirmText = "确认囤入"
            ) { raw ->
                val tags = raw.trim()
                    .split(Regex("""[\s,，、;；]+"""))
                    .map { it.trim().trimStart('#').trim('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                runBatchHoard(chosen, overrideTags = tags)
            }
        }
    }

    private fun suggestAuthorTagPath(rawAuthor: String): String {
        val clean = rawAuthor.trim()
        if (clean.isEmpty()) return ""
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
            PickerItem(id = "__SEND_CUSTOM_TEXT__", title = "发送口令 / 文字", subtitle = "用原生输入框安全发送口令，不弹网页键盘")
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
            if (picked.id == "__SEND_CUSTOM_TEXT__") {
                showSendPasscodeDialog()
            } else {
                setActiveQuickCommand(picked.id)
                sendDiscordCommand(picked.id)
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
                  // Type '/' first so Discord's slash-command detector reliably opens the autocomplete popup
                  insertSlateText('/');
                  var rest = textToInsert.slice(1);
                  setTimeout(function() {
                    box.focus();
                    if (rest) insertSlateText(rest);
                    var tries = 0;
                    var targetName = $bareName;
                    var timer = setInterval(function() {
                      tries++;
                      var popup = document.querySelector('div[class*="autocomplete"], [role="listbox"]');
                      if (popup && popup.getBoundingClientRect().height > 20) {
                        clearInterval(timer);
                        var rows = popup.querySelectorAll('[role="option"], div[class*="autocompleteRow"], div[class*="clickable_"], div[class*="selectable_"]');
                        var firstText = (rows && rows.length > 0) ? (rows[0].textContent || '').toLowerCase() : '';
                        var matched = null;
                        if (rows && rows.length > 1 && targetName && firstText.indexOf(targetName) === -1) {
                          for (var i = 1; i < rows.length; i++) {
                            if ((rows[i].textContent || '').toLowerCase().indexOf(targetName) !== -1) {
                              matched = rows[i];
                              break;
                            }
                          }
                        }
                        if (matched) {
                          matched.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                          matched.click();
                          if (window.STHoardBridge && window.STHoardBridge.requestNativeEnter) {
                            window.STHoardBridge.requestNativeEnter(420);
                          }
                        } else if (window.STHoardBridge && window.STHoardBridge.requestNativeTabThenEnter) {
                          window.STHoardBridge.requestNativeTabThenEnter(120, 420);
                        }
                      } else if (tries >= 28) {
                        clearInterval(timer);
                      }
                    }, 120);
                  }, 180);
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

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        binding.webDiscord.stopLoading()
        binding.webDiscord.destroy()
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
                var exts = /\.(png|json|zip|css|js|html|htm|txt)(\?|$)/i;
                var seen = {};
                var results = [];
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
                function findAuthor(el) {
                  var msg = el.closest('li[id^="chat-messages-"], div[id^="chat-messages-"], [class*="messageListItem"]');
                  if (!msg) return '';
                  var cur = msg;
                  for (var steps = 0; steps < 8 && cur; steps++) {
                    var u = cur.querySelector('[id^="message-username-"], span[class*="username_"], h3 [class*="username"]');
                    if (u && u.textContent && u.textContent.trim()) {
                      return u.textContent.trim().slice(0, 24);
                    }
                    cur = cur.previousElementSibling;
                  }
                  return '';
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
                  if (seen[key]) return;
                  seen[key] = true;
                  var urlFn = path.substring(path.lastIndexOf('/') + 1);
                  try { urlFn = decodeURIComponent(urlFn); } catch (e) {}
                  var fn = extractDomFileName(el, urlFn);
                  results.push({ url: u, fileName: fn, author: findAuthor(el) });
                }
                var anchors = document.querySelectorAll('a[href]');
                for (var i = 0; i < anchors.length; i++) {
                  pushUrl(anchors[i].href, anchors[i]);
                }
                var imgs = document.querySelectorAll('img[src]');
                for (var j = 0; j < imgs.length; j++) {
                  var src = imgs[j].src || '';
                  if (src.indexOf('/attachments/') !== -1) {
                    pushUrl(src, imgs[j]);
                  }
                }
                return JSON.stringify(results);
              } catch (e) {
                return '[]';
              }
            })();
        """.trimIndent()
    }
}
