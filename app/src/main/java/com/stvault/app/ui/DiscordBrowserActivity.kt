package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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
import com.stvault.app.core.InboxManager
import com.stvault.app.core.SmartImportSummary
import com.stvault.app.databinding.ActivityDiscordBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

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

    @SuppressLint("SetJavaScriptEnabled")
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

        binding.fabHoard.setOnClickListener {
            if (busy) {
                toast("正在囤入中，请稍候...")
                return@setOnClickListener
            }
            scanVisibleAttachments()
        }

        web.loadUrl("https://discord.com/app")
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
        val hasAnyAuthor = found.any { it.author.isNotBlank() }

        VaultSheets.multiSelect(
            ctx = this,
            title = "发现 ${found.size} 个附件",
            items = pickerItems,
            preselectedIds = allIds,
            primaryConfirmText = if (hasAnyAuthor) "带作者囤入" else "全部囤入",
            secondaryConfirm = if (hasAnyAuthor) {
                "仅囤入" to { picked ->
                    val chosen = picked.mapNotNull { byId[it] }
                    if (chosen.isNotEmpty()) runBatchHoard(chosen, includeAuthorTag = false)
                }
            } else null
        ) { picked ->
            val chosen = picked.mapNotNull { byId[it] }
            if (chosen.isEmpty()) {
                toast("未勾选任何附件")
                return@multiSelect
            }
            runBatchHoard(chosen, includeAuthorTag = hasAnyAuthor)
        }
    }

    private fun runBatchHoard(items: List<PageAttachment>, includeAuthorTag: Boolean) {
        setBusy(true, "正在囤入 (1/${items.size})：${items.first().fileName}")
        lifecycleScope.launch(Dispatchers.IO) {
            val summary = SmartImportSummary()
            for ((idx, item) in items.withIndex()) {
                runOnUiThread {
                    binding.tvHoardProgress.text = "正在囤入 (${idx + 1}/${items.size})：${item.fileName}"
                }
                val tags = if (includeAuthorTag && item.author.isNotBlank()) listOf(item.author) else emptyList()
                InboxManager.downloadAndImportSingleUrl(
                    context = this@DiscordBrowserActivity,
                    url = item.url,
                    fallbackName = item.fileName,
                    extraTags = tags,
                    summary = summary
                )
            }
            withContext(Dispatchers.Main) {
                setBusy(false)
                toast(summary.formatMessage())
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
              if (!document.getElementById('st-mobile-dc-style')) {
                var s = document.createElement('style');
                s.id = 'st-mobile-dc-style';
                s.textContent = [
                  'div[class*="membersWrap_"], aside[class*="membersWrap_"] { display: none !important; }',
                  'html.st-hide-sidebar nav[class*="guilds_"], html.st-hide-sidebar div[class*="guilds_"] { display: none !important; }',
                  'html.st-hide-sidebar div[class*="base_"] > div[class*="content_"] > div[class*="sidebar_"] { display: none !important; }',
                  'html.st-hide-sidebar div[class*="sidebarList_"] { display: none !important; }',
                  'html.st-hide-sidebar div[class*="base_"] { left: 0 !important; width: 100vw !important; }',
                  'html.st-hide-sidebar div[class*="chat_"], html.st-hide-sidebar main[class*="chatContent_"] { min-width: 0 !important; width: 100% !important; max-width: 100vw !important; }',
                  'html.st-hide-sidebar div[class*="chatLayerWrapper_"] { position: fixed !important; inset: 0 !important; width: 100vw !important; max-width: 100vw !important; z-index: 60 !important; }',
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
                  var fn = path.substring(path.lastIndexOf('/') + 1);
                  try { fn = decodeURIComponent(fn); } catch (e) {}
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
