package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
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
        binding.btnWebBack.setOnClickListener {
            if (binding.webDiscord.canGoBack()) binding.webDiscord.goBack() else finish()
        }
        binding.btnWebReload.setOnClickListener { binding.webDiscord.reload() }
        binding.btnWebCommands.setOnClickListener { showQuickCommandsSheet() }
        binding.btnWebSidebar.setOnClickListener {
            binding.webDiscord.evaluateJavascript(
                "(function(){ if(!window.__stSetSidebarHidden) return false; var next = !document.documentElement.classList.contains('st-hide-sidebar'); window.__stSetSidebarHidden(next); return next; })();"
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
                if (binding.webDiscord.canGoBack()) {
                    binding.webDiscord.goBack()
                } else {
                    finish()
                }
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

        // Make the floating「囤囤」button freely draggable anywhere on screen so it never blocks Discord inputs
        setupDraggableHoardButton()

        web.loadUrl("https://discord.com/app")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableHoardButton() {
        val fab = binding.fabHoard
        val touchSlop = 8f * resources.displayMetrics.density
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        fab.setOnTouchListener { v, event ->
            val parent = v.parent as? View ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = v.x
                    startY = v.y
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
                        val maxX = (parent.width - v.width).coerceAtLeast(0).toFloat()
                        val maxY = (parent.height - v.height).coerceAtLeast(0).toFloat()
                        v.x = (startX + dx).coerceIn(0f, maxX)
                        v.y = (startY + dy).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (busy) {
                            toast("正在囤入中，请稍候...")
                        } else {
                            scanVisibleAttachments()
                        }
                    }
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

    private fun injectMobileLayoutHelper() {
        binding.webDiscord.evaluateJavascript(MOBILE_LAYOUT_JS) {
            applySidebarMode()
        }
    }

    private fun applySidebarMode() {
        binding.btnWebSidebar.text = if (sidebarHidden) "频道" else "看消息"
        val flag = if (sidebarHidden) "true" else "false"
        binding.webDiscord.evaluateJavascript(
            "if(window.__stSetSidebarHidden) window.__stSetSidebarHidden($flag);",
            null
        )
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
            preselectedIds = allIds,
            primaryConfirmText = "带标签囤入",
            secondaryConfirm = "仅囤入" to { picked ->
                val chosen = picked.mapNotNull { byId[it] }
                if (chosen.isNotEmpty()) runBatchHoard(chosen, overrideTags = emptyList())
            }
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

    private fun showQuickCommandsSheet() {
        val cmds = loadQuickCommands()
        if (cmds.isEmpty()) {
            showAddQuickCommandDialog()
            return
        }
        val items = cmds.map { PickerItem(id = it, title = it, subtitle = "点击自动填入并发送") }
        VaultSheets.searchablePicker(
            ctx = this,
            title = "快捷机器人指令",
            items = items,
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
                            val delItems = cmds.map { PickerItem(it, it, "点击删除") }
                            VaultSheets.searchablePicker(
                                ctx = this,
                                title = "选择要删除的指令",
                                items = delItems
                            ) { toDel ->
                                cmds.remove(toDel.id)
                                saveQuickCommands(cmds)
                                toast("已删除：${toDel.id}")
                            }
                        }
                    )
                )
            }
        ) { picked ->
            sendDiscordCommand(picked.id)
        }
    }

    private fun showAddQuickCommandDialog() {
        VaultSheets.input(
            ctx = this,
            title = "添加快捷指令",
            message = "输入社区 Bot 使用的指令（如 /下载、/get），点击即可自动填入并尝试发送。",
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
            toast("已添加指令：$clean")
            showQuickCommandsSheet()
        }
    }

    private fun sendDiscordCommand(cmd: String) {
        val escaped = JSONObject.quote(cmd)
        val js = """
            (function() {
              try {
                var box = document.querySelector('div[role="textbox"][contenteditable="true"]');
                if (!box) return 'NO_BOX';
                box.focus();
                var sel = window.getSelection();
                if (sel) {
                  var range = document.createRange();
                  range.selectNodeContents(box);
                  range.collapse(false);
                  sel.removeAllRanges();
                  sel.addRange(range);
                }
                document.execCommand('insertText', false, $escaped);
                function fireEnter() {
                  var evDown = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                  var evPress = new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                  var evUp = new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                  box.dispatchEvent(evDown);
                  box.dispatchEvent(evPress);
                  box.dispatchEvent(evUp);
                }
                var isSlash = ($escaped).indexOf('/') === 0;
                if (isSlash) {
                  setTimeout(function() {
                    var opt = document.querySelector('div[class*="autocomplete_"] div[role="option"], div[class*="autocompleteRow_"]');
                    if (opt) {
                      opt.click();
                      setTimeout(fireEnter, 260);
                    } else {
                      fireEnter();
                    }
                  }, 420);
                } else {
                  setTimeout(fireEnter, 160);
                }
                return 'OK';
              } catch (e) {
                return 'ERR:' + e.message;
              }
            })();
        """.trimIndent()
        binding.webDiscord.evaluateJavascript(js) { res ->
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
                  'html.st-hide-sidebar nav[class*="guilds_"], html.st-hide-sidebar div[class*="guilds_"] { display: none !important; width: 0 !important; min-width: 0 !important; }',
                  'html.st-hide-sidebar div[class*="base_"] > div[class*="content_"] > div[class*="sidebar_"] { display: none !important; width: 0 !important; min-width: 0 !important; }',
                  'html.st-hide-sidebar div[class*="sidebarList_"], html.st-hide-sidebar div[class*="resizeHandle_"] { display: none !important; }',
                  'html.st-hide-sidebar div[class*="base_"] { position: relative !important; left: 0 !important; right: 0 !important; margin-left: 0 !important; width: 100% !important; min-width: 0 !important; max-width: 100% !important; }',
                  'html.st-hide-sidebar div[class*="base_"] > div[class*="content_"] { width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; left: 0 !important; }',
                  'html.st-hide-sidebar div[class*="chat_"], html.st-hide-sidebar main[class*="chatContent_"], html.st-hide-sidebar div[class*="container_"] { min-width: 0 !important; max-width: 100% !important; margin-left: 0 !important; left: 0 !important; box-sizing: border-box !important; }',
                  'html.st-hide-sidebar div[class*="chatLayerWrapper_"] { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; padding: 0 !important; transform: none !important; z-index: 200 !important; background: var(--background-primary, #313338) !important; box-sizing: border-box !important; }',
                  'html.st-hide-sidebar div[class*="chatLayerWrapper_"] > * { width: 100% !important; min-width: 0 !important; max-width: 100% !important; margin: 0 !important; left: 0 !important; }',
                  'html.st-hide-sidebar section[class*="title_"] { position: relative !important; z-index: 20 !important; flex-shrink: 0 !important; }',
                  'html.st-hide-sidebar section[class*="title_"] div[class*="children_"] { min-width: 0 !important; flex: 1 1 auto !important; overflow: hidden !important; }',
                  'html.st-hide-sidebar section[class*="title_"] h1, html.st-hide-sidebar section[class*="title_"] div[class*="titleWrapper_"] { min-width: 0 !important; overflow: hidden !important; text-overflow: ellipsis !important; white-space: nowrap !important; }',
                  'html.st-hide-sidebar section[class*="title_"] div[class*="toolbar_"] { flex-shrink: 0 !important; position: relative !important; z-index: 25 !important; }',
                  'html.st-hide-sidebar div[class*="headerRow_"], html.st-hide-sidebar div[class*="buttons_"] { position: relative !important; z-index: 15 !important; }',
                  'html.st-hide-sidebar ol[class*="scrollerInner_"], html.st-hide-sidebar li[class*="messageListItem_"] { max-width: 100% !important; box-sizing: border-box !important; }',
                  'html:not(.st-hide-sidebar) div[class*="base_"] > div[class*="content_"] > div[class*="sidebar_"] { width: calc(100vw - 72px) !important; min-width: 240px !important; max-width: 100vw !important; }',
                  'html:not(.st-hide-sidebar) div[class*="sidebarList_"] { width: calc(100vw - 72px) !important; }'
                ].join('\n');
                document.head.appendChild(s);
              }
              window.__stSetSidebarHidden = function(hidden) {
                if (hidden) {
                  document.documentElement.classList.add('st-hide-sidebar');
                } else {
                  document.documentElement.classList.remove('st-hide-sidebar');
                }
              };
              if (!window.__stSidebarAutoBound) {
                window.__stSidebarAutoBound = true;
                document.addEventListener('click', function(e) {
                  var a = e.target && e.target.closest ? e.target.closest('a[href*="/channels/"]') : null;
                  if (!a) return;
                  var inSidebar = a.closest('div[class*="sidebar_"], div[class*="sidebarList_"]');
                  if (inSidebar && !document.documentElement.classList.contains('st-hide-sidebar')) {
                    setTimeout(function() {
                      document.documentElement.classList.add('st-hide-sidebar');
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
