package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stvault.app.R
import com.stvault.app.core.BackgroundRepository
import com.stvault.app.core.ImageDownloader
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityComponentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Sandboxed SillyTavern WebView previewer for:
 * 1. Regex Scripts (auto-synthesized sample or custom test input -> live HTML/CSS render)
 * 2. Tavern Helper / JS-Slash-Runner Scripts (with lightweight Mock Bridge for $, TavernHelper, toastr)
 * 3. Character Greetings rendered with embedded/linked Regex Scripts
 */
class ComponentPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComponentPreviewBinding
    private lateinit var vaultRepo: VaultRepository
    private lateinit var libRepo: ResourceLibraryRepository

    private var mode: String = MODE_REGEX
    private var titleText: String = "沙盒预览"
    private var findRegex: String = ""
    private var replaceString: String = ""
    private var scriptCode: String = ""
    private var rawSource: String = ""
    private var greetingText: String = ""
    private var greetingItems: List<Pair<String, String>> = emptyList()
    private var greetingIndex: Int = 0
    private var regexListJson: String = ""
    private var charId: String = ""
    private var showingSource: Boolean = false
    private var sourcePopulated: Boolean = false
    private var previewDark: Boolean = false
    private data class GreetingThemeBundle(
        val rootVars: String,
        val customCss: String,
        val bodyClassAttr: String,
        val asyncLinks: String
    )

    private var activeGreetingThemeId: String = ""
    private var cachedThemeBundlePair: Pair<String, GreetingThemeBundle>? = null
    private var greetingShellLoaded: Boolean = false
    private var renderJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComponentPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        val payload = pendingPayload
        mode = payload?.mode ?: intent.getStringExtra(EXTRA_MODE) ?: MODE_REGEX
        titleText = payload?.title ?: intent.getStringExtra(EXTRA_TITLE) ?: "沙盒预览"
        findRegex = payload?.findRegex ?: ""
        replaceString = payload?.replaceString ?: ""
        scriptCode = payload?.scriptCode ?: ""
        rawSource = payload?.rawSource ?: ""
        greetingItems = payload?.greetingItems ?: emptyList()
        greetingIndex = (payload?.greetingIndex ?: 0).coerceIn(0, (greetingItems.size - 1).coerceAtLeast(0))
        greetingText = if (greetingItems.isNotEmpty()) greetingItems[greetingIndex].second else (payload?.greetingText ?: "")
        regexListJson = payload?.regexListJson ?: ""
        charId = payload?.charId ?: ""

        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        previewDark = (uiMode == Configuration.UI_MODE_NIGHT_YES)
        updateDayNightLabel()

        binding.tvPreviewName.text = titleText
        binding.btnCloseCompPreview.setOnClickListener { finish() }

        binding.btnPreviewDayNight.setOnClickListener {
            previewDark = !previewDark
            updateDayNightLabel()
            renderCurrentMode()
        }

        binding.btnPreviewModeToggle.setOnClickListener {
            showingSource = !showingSource
            if (showingSource && !sourcePopulated) {
                populateSourceTextLazy()
            }
            binding.scrollSourceView.visibility = if (showingSource) View.VISIBLE else View.GONE
            binding.webCompPreview.visibility = if (showingSource) View.GONE else View.VISIBLE
            binding.btnPreviewModeToggle.text = if (showingSource) "渲染" else "源码"
        }

        if (mode == MODE_GREETING_RENDER && greetingItems.size > 1) {
            binding.boxGreetingNav.visibility = View.VISIBLE
            binding.btnGreetingPrev.setOnClickListener { stepGreeting(-1) }
            binding.btnGreetingNext.setOnClickListener { stepGreeting(1) }
            binding.tvGreetingIndicator.setOnClickListener { showGreetingPickerSheet() }
            updateGreetingNavBar()
        }

        if (mode == MODE_REGEX) {
            binding.btnToggleTestBox.visibility = View.VISIBLE
            val initialSample = greetingText.ifBlank { synthesizeSampleInput(findRegex) }
            binding.etTestInput.setText(initialSample)
            binding.btnToggleTestBox.setOnClickListener {
                val show = binding.boxRegexTest.visibility != View.VISIBLE
                binding.boxRegexTest.visibility = if (show) View.VISIBLE else View.GONE
            }
            binding.btnRunRegexTest.setOnClickListener {
                renderCurrentMode()
            }
        }

        setupWebView()
        renderCurrentMode()
    }

    private fun populateSourceTextLazy() {
        sourcePopulated = true
        val fullSource = rawSource.ifBlank {
            when (mode) {
                MODE_REGEX -> "查找正则：\n$findRegex\n\n替换内容：\n$replaceString"
                MODE_HELPER -> scriptCode
                else -> greetingText
            }
        }
        binding.tvSourceCode.text = if (fullSource.length > 60_000) {
            fullSource.take(60_000) + "\n\n...（源码较长，已截断前 60,000 字符）"
        } else {
            fullSource
        }
    }

    private fun updateGreetingNavBar() {
        if (greetingItems.size <= 1) return
        val label = greetingItems[greetingIndex].first
        binding.tvGreetingIndicator.text = "$label · ${greetingIndex + 1} / ${greetingItems.size} ▾"
    }

    private fun stepGreeting(delta: Int) {
        if (greetingItems.size <= 1) return
        greetingIndex = (greetingIndex + delta + greetingItems.size) % greetingItems.size
        greetingText = greetingItems[greetingIndex].second
        sourcePopulated = false
        if (showingSource) populateSourceTextLazy()
        updateGreetingNavBar()
        updateGreetingContentFast()
    }

    private fun updateGreetingContentFast() {
        if (mode == MODE_GREETING_RENDER && greetingShellLoaded) {
            val quoted = JSONObject.quote(greetingText)
            binding.webCompPreview.evaluateJavascript(
                "(function(){ if(typeof window.__stRenderGreeting==='function'){ window.__stRenderGreeting($quoted); window.scrollTo(0,0); var c=document.getElementById('chat'); if(c) c.scrollTop=0; return 'OK'; } return 'RELOAD'; })();"
            ) { res ->
                if (res == null || !res.contains("OK")) {
                    renderCurrentMode()
                }
            }
        } else {
            renderCurrentMode()
        }
    }

    private fun showGreetingPickerSheet() {
        if (greetingItems.size <= 1) return
        VaultSheets.actionList(
            ctx = this,
            title = "切换开场白 (${greetingItems.size})",
            actions = greetingItems.mapIndexed { idx, (label, txt) ->
                SheetAction(
                    label = label,
                    subtitle = txt.take(40).replace('\n', ' '),
                    checked = (idx == greetingIndex)
                ) {
                    if (idx != greetingIndex) {
                        greetingIndex = idx
                        greetingText = txt
                        sourcePopulated = false
                        if (showingSource) populateSourceTextLazy()
                        updateGreetingNavBar()
                        updateGreetingContentFast()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        binding.webCompPreview.onResume()
        binding.webCompPreview.resumeTimers()
    }

    override fun onPause() {
        binding.webCompPreview.evaluateJavascript(
            "(function(){ document.querySelectorAll('audio,video').forEach(function(m){ try{ m.pause(); m.currentTime=0; }catch(e){} }); })();",
            null
        )
        binding.webCompPreview.onPause()
        binding.webCompPreview.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            val web = binding.webCompPreview
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        val web = binding.webCompPreview
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        if (mode == MODE_GREETING_RENDER && greetingItems.size > 1) {
            val density = resources.displayMetrics.density
            val minSwipeDist = 64f * density
            val edgeGuardPx = 24f * density
            val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (greetingItems.size <= 1 || e1 == null) return false
                    val w = web.width.toFloat()
                    if (w > 0f && (e1.x < edgeGuardPx || e1.x > w - edgeGuardPx)) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > minSwipeDist && abs(dx) > abs(dy) * 1.8f && abs(velocityX) > 250f) {
                        if (dx < 0f) stepGreeting(1) else stepGreeting(-1)
                        return true
                    }
                    return false
                }
            })
            web.setOnTouchListener { _, event ->
                swipeDetector.onTouchEvent(event)
                false
            }
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                binding.pbCompPreviewLoading.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pbCompPreviewLoading.visibility = View.GONE
                if (mode == MODE_GREETING_RENDER) {
                    greetingShellLoaded = true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 0. Bound theme background image for themed greeting preview
                if (url.startsWith("${LOCAL_ORIGIN}__preview__/bound_bg") && activeGreetingThemeId.isNotEmpty()) {
                    val bgRepo = BackgroundRepository.getInstance(this@ComponentPreviewActivity)
                    val bgRec = bgRepo.getBoundBackground(activeGreetingThemeId)
                    val bgFile = bgRec?.let { bgRepo.getFile(it) }
                    if (bgFile != null && bgFile.exists()) {
                        return try {
                            val bytes = bgFile.readBytes()
                            val mime = ImageDownloader.detectImageMime(bytes) ?: "image/png"
                            WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
                        } catch (_: Exception) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                // 1. Character cover avatar if rendering greeting
                if (url.startsWith("${LOCAL_ORIGIN}__char_avatar__") && charId.isNotEmpty()) {
                    val rec = libRepo.getCharacterById(charId)
                    val f = rec?.let { libRepo.getCharacterFile(it) }
                    if (f != null && f.exists()) {
                        return try {
                            WebResourceResponse("image/png", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(f))
                        } catch (_: Exception) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                // 2. Bundled SillyTavern CSS/fonts (RAM-cached + instant 404 on miss so WebView NEVER stalls on DNS!)
                if (url.startsWith(LOCAL_ORIGIN)) {
                    val assetPath = url.removePrefix(LOCAL_ORIGIN).substringBefore('?').trimStart('/')
                    if (assetPath.isNotEmpty() && !assetPath.startsWith("__")) {
                        return try {
                            val bytes = stAssetRamCache.getOrPut(assetPath) {
                                assets.open("st/$assetPath").use { it.readBytes() }
                            }
                            val mime = guessMime(assetPath)
                            val encoding = if (mime.startsWith("text/") || mime == "application/javascript") "UTF-8" else null
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "public, max-age=31536000"
                            )
                            WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
                        } catch (_: Exception) {
                            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                // 3. Offline cached images & fonts from VaultRepository
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val cached = vaultRepo.getCachedAssetByUrl(url)
                    if (cached != null) {
                        return try {
                            WebResourceResponse(cached.second, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(cached.first))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (vaultRepo.isKnownDeadUrl(url)) {
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return null
            }
        }
    }

    private fun updateDayNightLabel() {
        binding.btnPreviewDayNight.text = if (previewDark) "深底" else "浅底"
    }

    private fun renderCurrentMode() {
        renderJob?.cancel()
        greetingShellLoaded = false
        binding.webCompPreview.setBackgroundColor(
            android.graphics.Color.parseColor(if (previewDark) "#111114" else "#F7F7F5")
        )
        binding.pbCompPreviewLoading.visibility = View.VISIBLE
        val testInputSnap = if (mode == MODE_REGEX) binding.etTestInput.text?.toString().orEmpty() else ""
        renderJob = lifecycleScope.launch {
            val html = withContext(Dispatchers.Default) {
                when (mode) {
                    MODE_REGEX -> buildRegexPreviewHtml(testInputSnap)
                    MODE_HELPER -> buildHelperPreviewHtml()
                    MODE_GREETING_RENDER -> buildGreetingRenderHtml()
                    else -> ""
                }
            }
            if (!isFinishing && !isDestroyed) {
                binding.webCompPreview.loadDataWithBaseURL(LOCAL_ORIGIN, html, "text/html", "UTF-8", null)
            }
        }
    }

    /**
     * Synthesizes a clean sample input string that matches common SillyTavern regex tag patterns
     * (e.g. `<status>...</status>`, `[状态栏]`, ` ```html...``` `) so preview works with zero manual typing.
     */
    private fun synthesizeSampleInput(rawPattern: String): String {
        val cleaned = stripRegexSlashes(rawPattern).first
        // Look for XML-like tag pair: <tagName>...</tagName>
        val tagMatch = Regex("""<([a-zA-Z0-9_\-\u4e00-\u9fa5]+)[^>]*>""").find(cleaned)
        if (tagMatch != null) {
            val tag = tagMatch.groupValues[1]
            return "<$tag>\n时间：2026-11-22 14:30\n地点：酒馆大厅\n状态：心情愉悦\n好感度：85 / 100\n</$tag>"
        }
        // Look for fenced code block: ```lang
        val fenceMatch = Regex("""```([a-zA-Z0-9_\-]*)""").find(cleaned)
        if (fenceMatch != null) {
            val lang = fenceMatch.groupValues[1]
            return "```$lang\n状态：正常\n好感度：85\n```"
        }
        return "这是一段用于测试正则替换的示例对话内容。\n时间：傍晚 · 地点：星光酒馆 · 好感度：85"
    }

    private fun stripRegexSlashes(raw: String): Pair<String, String> {
        val t = raw.trim()
        if (t.startsWith("/") && t.length > 2) {
            val lastSlash = t.lastIndexOf('/')
            if (lastSlash > 0) {
                val pattern = t.substring(1, lastSlash)
                val flags = t.substring(lastSlash + 1).filter { it in "gimsuy" }
                return pattern to flags.ifEmpty { "gm" }
            }
        }
        return t to "gm"
    }

    private fun buildRegexPreviewHtml(testInput: String): String {
        val (pattern, flags) = stripRegexSlashes(findRegex)
        val inputJson = JSONObject.quote(testInput)
        val patternJson = JSONObject.quote(pattern)
        val flagsJson = JSONObject.quote(flags)
        val replaceJson = JSONObject.quote(replaceString)

        val script = """
            (function() {
                const input = $inputJson;
                const patStr = $patternJson;
                const flags = $flagsJson;
                const rep = $replaceJson;
                let output = '';
                let matched = false;
                try {
                    if (patStr) {
                        const re = new RegExp(patStr, flags || 'gm');
                        matched = re.test(input);
                        re.lastIndex = 0;
                        output = input.replace(re, rep);
                    }
                } catch (e) {
                    output = '<div style="color:#E56B68;font-size:12px;">正则解析异常：' + e.message + '</div>';
                }
                if (!matched && rep) {
                    // Fallback: render replaceString directly with clean placeholder values so HTML/CSS cards preview immediately!
                    output = rep
                        .replace(/\{\{match\}\}/g, '示例内容')
                        .replace(/\$\d+/g, '示例内容');
                }
                // Strip ``` / ```html / ```... wrappers just like SillyTavern
                output = output.replace(/```[a-zA-Z0-9_-]*[ \t]*\r?\n?([\s\S]*?)```/g, '$1');
                output = output.replace(/^\s*```[a-zA-Z0-9_-]*\s*$/gm, '');
                const target = document.getElementById('st-render-slot');
                if (target) {
                    target.innerHTML = output;
                    // Execute any inline <script> tags injected by HTML status bars
                    target.querySelectorAll('script').forEach(old => {
                        const s = document.createElement('script');
                        s.textContent = old.textContent;
                        old.replaceWith(s);
                    });
                }
            })();
        """.trimIndent()

        return wrapInStShell(
            bodyHtml = """<div id="st-render-slot"></div>""",
            extraScript = script,
            charName = titleText,
            hasAvatar = false
        )
    }

    private fun buildHelperPreviewHtml(): String {
        val trimmed = scriptCode.trim()
        val isHtml = trimmed.startsWith("<") || trimmed.contains("<div", ignoreCase = true) || trimmed.contains("<style", ignoreCase = true)
        val bodyHtml = if (isHtml) {
            trimmed
        } else {
            """
                <div style="font-size:12.5px;color:rgba(244,244,245,0.65);margin-bottom:10px;">
                    酒馆助手脚本已在模拟沙盒中运行（支持 jQuery / TavernHelper / toastr 模拟）。
                </div>
                <div id="helper-mount"></div>
                <div id="sandbox-console" style="margin-top:12px;padding:10px;border-radius:10px;background:rgba(255,255,255,0.05);font-family:monospace;font-size:11.5px;color:#A1A1AA;white-space:pre-wrap;">[沙盒] 正在初始化脚本...</div>
            """.trimIndent()
        }
        val execJs = if (isHtml) "" else """
            try {
                $scriptCode
                window.__sandboxLog('脚本加载完成');
            } catch (err) {
                window.__sandboxLog('运行异常: ' + (err && err.message ? err.message : err));
            }
        """.trimIndent()

        return wrapInStShell(
            bodyHtml = bodyHtml,
            extraScript = execJs,
            charName = titleText,
            hasAvatar = false
        )
    }

    private fun buildGreetingRenderHtml(): String {
        val prefs = getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE)
        val renderMode = prefs.getString("greeting_render_mode", "plain") ?: "plain"
        val themeId = prefs.getString("greeting_theme_id", "").orEmpty()
        activeGreetingThemeId = if (renderMode == "theme") themeId else ""

        val greetingJson = JSONObject.quote(greetingText)
        val regexArrStr = regexListJson.ifBlank { "[]" }

        val script = """
            (function() {
                const rules = $regexArrStr;
                let __iframeSeq = 0;

                function scopeFragmentStyle(styleTag) {
                    return styleTag.replace(/(<style\b[^>]*>)([\s\S]*?)(<\/style>)/i, function(_, open, css, close) {
                        // Scope global body/html/* rules inside regex status-bar fragments so they cannot nuke the ST theme
                        let scoped = css
                            .replace(/(^|[},;\n]\s*)(?:html\s*,\s*body|body\s*,\s*html|html|body)(?=[\s{,:>+~])/gi, '$1#st-render-slot')
                            .replace(/(^|[},;\n]\s*)\*(?=\s*[{,])/g, '$1#st-render-slot *');
                        return open + scoped + close;
                    });
                }

                function buildAutoIframePlaceholder(docHtml, stash) {
                    const id = 'st-iso-frame-' + (++__iframeSeq);
                    const idx = stash.length;
                    stash.push({ type: 'iframe', id: id, html: docHtml });
                    return '@@STASH_' + idx + '@@';
                }

                function formatHybridMarkdownHtml(src) {
                    let s = String(src || '').replace(/\r\n/g, '\n');
                    const stash = [];
                    // 0) Isolate full HTML documents (<!DOCTYPE html>... or <html>...</html>) produced by status-bar regexes
                    //    into auto-resizing sandboxed iframes (just like SillyTavern / JS-Slash-Runner),
                    //    so their global CSS/JS can NEVER break the parent SillyTavern theme or background!
                    s = s.replace(/(?:<!DOCTYPE\s+html[^>]*>\s*)?<html\b[\s\S]*?<\/html>/gi, function(m) {
                        return buildAutoIframePlaceholder(m, stash);
                    });
                    // Also catch stray <body>...</body> documents without <html> wrapper
                    s = s.replace(/<body\b[^>]*>[\s\S]*?<\/body>/gi, function(m) {
                        return buildAutoIframePlaceholder('<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>' + m + '</html>', stash);
                    });
                    // 1) Protect <style>, <script>, <pre>, <svg> blocks verbatim (scoping global selectors in <style>)
                    s = s.replace(/<(style|script|pre|svg)\b[^>]*>[\s\S]*?<\/\1>/gi, function(m, tag) {
                        const tok = '@@STASH_' + stash.length + '@@';
                        const val = tag.toLowerCase() === 'style' ? scopeFragmentStyle(m) : m;
                        stash.push({ type: 'raw', html: val });
                        return tok;
                    });
                    // 2) Walk HTML tags vs text nodes; ONLY convert \n -> <br> outside HTML container blocks (depth === 0)
                    //    so story paragraphs keep all line breaks while HTML status bars / flex cards stay pixel-perfect!
                    const containerRe = /^<(\/?)(div|section|article|header|footer|aside|nav|main|details|summary|table|thead|tbody|tfoot|tr|td|th|ul|ol|li|figure|form|blockquote|iframe)\b/i;
                    const parts = s.split(/(<[^>]+>)/g);
                    let depth = 0;
                    for (let i = 0; i < parts.length; i++) {
                        const tok = parts[i];
                        if (!tok) continue;
                        if (i % 2 === 1) {
                            const cm = containerRe.exec(tok);
                            if (cm) {
                                const isClose = cm[1] === '/';
                                const isSelf = /\/\s*>$/.test(tok);
                                if (!isSelf) {
                                    if (isClose) depth = Math.max(0, depth - 1);
                                    else depth++;
                                }
                            }
                        } else if (depth === 0) {
                            let chunk = tok;
                            if (i > 0 && /^\s*\n/.test(chunk)) chunk = chunk.replace(/^[ \t]*\n/, '');
                            if (i < parts.length - 1 && /\n[ \t]*$/.test(chunk)) chunk = chunk.replace(/\n[ \t]*$/, '');
                            if (chunk) {
                                // Convert consecutive Markdown blockquote lines ("> ...") into standard <blockquote><p>...</p></blockquote>
                                const lines = chunk.split('\n');
                                const outLines = [];
                                let bqBuf = [];
                                const flushBq = function() {
                                    if (!bqBuf.length) return;
                                    outLines.push('<blockquote><p>' + bqBuf.join('<br>') + '</p></blockquote>');
                                    bqBuf = [];
                                };
                                const fmtInline = function(line) {
                                    return line
                                        .replace(/`([^`\n]+)`/g, '<code>$1</code>')
                                        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
                                        .replace(/__([^_\n]+)__/g, '<u>$1</u>')
                                        .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
                                        // Wrap dialogue quotes in <q> (keeping original quotation marks, matching SillyTavern style.css)
                                        .replace(/(“[^“”\n]+”|「[^「」\n]+」|『[^『』\n]+』|"[^"\n]+")/g, '<q>$1</q>');
                                };
                                for (let li = 0; li < lines.length; li++) {
                                    const bm = /^[ \t]*>[ \t]?(.*)$/.exec(lines[li]);
                                    if (bm) {
                                        bqBuf.push(fmtInline(bm[1]));
                                    } else {
                                        flushBq();
                                        outLines.push(fmtInline(lines[li]));
                                    }
                                }
                                flushBq();
                                chunk = outLines.join('<br>').replace(/<\/blockquote><br>/g, '</blockquote>').replace(/<br><blockquote>/g, '<blockquote>');
                            }
                            parts[i] = chunk;
                        }
                    }
                    s = parts.join('');
                    // 3) Restore protected blocks (iframe placeholders become real mount points)
                    const iframes = [];
                    for (let k = 0; k < stash.length; k++) {
                        const entry = stash[k];
                        if (entry.type === 'iframe') {
                            iframes.push(entry);
                            s = s.replace('@@STASH_' + k + '@@', '<div class="st-iso-wrap" style="width:100%;margin:8px 0;"><iframe id="' + entry.id + '" sandbox="allow-scripts allow-same-origin" style="width:100%;min-height:120px;border:0;background:transparent;display:block;overflow:hidden;" scrolling="no"></iframe></div>');
                        } else {
                            s = s.replace('@@STASH_' + k + '@@', function() { return entry.html; });
                        }
                    }
                    return { html: s, iframes: iframes };
                }

                function mountIsolatedIframes(iframes) {
                    if (!iframes || !iframes.length) return;
                    for (const item of iframes) {
                        const frame = document.getElementById(item.id);
                        if (!frame) continue;
                        frame.onload = function() {
                            const resize = function() {
                                try {
                                    const d = frame.contentDocument || (frame.contentWindow && frame.contentWindow.document);
                                    if (!d) return;
                                    const h = Math.max(
                                        d.body ? d.body.scrollHeight : 0,
                                        d.documentElement ? d.documentElement.scrollHeight : 0
                                    );
                                    if (h > 40) frame.style.height = (h + 12) + 'px';
                                } catch (e) {}
                            };
                            resize();
                            setTimeout(resize, 180);
                            setTimeout(resize, 600);
                            try {
                                const d = frame.contentDocument;
                                if (d && d.body && typeof ResizeObserver !== 'undefined') {
                                    new ResizeObserver(resize).observe(d.body);
                                }
                            } catch (e) {}
                        };
                        frame.srcdoc = item.html;
                    }
                }

                window.__stRenderGreeting = function(rawInput) {
                    let text = String(rawInput || '');
                    for (const r of rules) {
                        if (r.disabled || !r.findRegex) continue;
                        try {
                            let pat = r.findRegex.trim();
                            let flags = 'gm';
                            if (pat.startsWith('/') && pat.length > 2) {
                                const last = pat.lastIndexOf('/');
                                if (last > 0) {
                                    flags = pat.slice(last + 1).replace(/[^gimsuy]/g, '') || 'gm';
                                    pat = pat.slice(1, last);
                                }
                            }
                            const re = new RegExp(pat, flags);
                            text = text.replace(re, r.replaceString || '');
                        } catch (e) {}
                    }
                    text = text.replace(/```[a-zA-Z0-9_-]*[ \t]*\r?\n?([\s\S]*?)```/g, '$1');
                    text = text.replace(/^\s*```[a-zA-Z0-9_-]*\s*$/gm, '');
                    const rendered = formatHybridMarkdownHtml(text);
                    const target = document.getElementById('st-render-slot');
                    if (target) {
                        target.innerHTML = rendered.html;
                        mountIsolatedIframes(rendered.iframes);
                        target.querySelectorAll('script').forEach(old => {
                            const s = document.createElement('script');
                            s.textContent = old.textContent;
                            old.replaceWith(s);
                        });
                    }
                };
                window.__stRenderGreeting($greetingJson);
            })();
        """.trimIndent()

        if (renderMode == "theme" && themeId.isNotEmpty()) {
            val bundle = extractThemeBundleForGreeting(themeId)
            if (bundle != null) {
                val hasBoundBg = BackgroundRepository.getInstance(this).getBoundBackground(themeId) != null
                return wrapInThemedStShell(
                    bodyHtml = """<div id="st-render-slot"></div>""",
                    extraScript = script,
                    charName = titleText,
                    hasAvatar = charId.isNotEmpty(),
                    theme = bundle,
                    hasBoundBg = hasBoundBg
                )
            }
        }

        // Plain mode: NO chat bubble, NO avatar — clean full-width text + regex render
        return wrapInPlainShell(
            bodyHtml = """<div id="st-render-slot"></div>""",
            extraScript = script
        )
    }

    private fun extractThemeBundleForGreeting(themeId: String): GreetingThemeBundle? {
        cachedThemeBundlePair?.let { (cachedId, cachedBundle) ->
            if (cachedId == themeId) return cachedBundle
        }
        return try {
            val (_, raw) = vaultRepo.readSourceRawText(themeId)
            val trimmed = raw.trim()
            val rootVars = StringBuilder()
            val bodyClasses = mutableListOf("charlist_grid")
            var customCss = ""
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                customCss = json.optString("custom_css", "")
                when (json.optInt("chat_display", 0)) {
                    1 -> bodyClasses.add("bubblechat")
                    2 -> bodyClasses.add("documentstyle")
                }
                when (json.optInt("avatar_style", 0)) {
                    1 -> bodyClasses.add("big-avatars")
                    2 -> bodyClasses.add("square-avatars")
                    3 -> bodyClasses.add("rounded-avatars")
                }
                if (json.optBoolean("waifuMode", false)) bodyClasses.add("waifuMode")
                if (json.optBoolean("noShadows", false)) bodyClasses.add("noShadows")
                if (json.optBoolean("fast_ui_mode", false)) bodyClasses.add("no-blur")
                if (json.optBoolean("reduced_motion", false)) bodyClasses.add("reduced-motion")
                if (json.optBoolean("hideChatAvatars_enabled", false)) bodyClasses.add("hideChatAvatars")

                fun putColor(jsonKey: String, cssVar: String) {
                    val v = json.optString(jsonKey, "").trim()
                    if (v.isNotEmpty()) rootVars.append("  $cssVar: $v;\n")
                }
                putColor("main_text_color", "--SmartThemeBodyColor")
                putColor("italics_text_color", "--SmartThemeEmColor")
                putColor("underline_text_color", "--SmartThemeUnderlineColor")
                putColor("quote_text_color", "--SmartThemeQuoteColor")
                putColor("shadow_color", "--SmartThemeShadowColor")
                putColor("blur_tint_color", "--SmartThemeBlurTintColor")
                putColor("chat_tint_color", "--SmartThemeChatTintColor")
                putColor("user_mes_blur_tint_color", "--SmartThemeUserMesBlurTintColor")
                putColor("bot_mes_blur_tint_color", "--SmartThemeBotMesBlurTintColor")
                putColor("border_color", "--SmartThemeBorderColor")
                putColor("fastui_bg_color", "--SmartThemeFastUIBGColor")

                if (json.has("blur_strength")) rootVars.append("  --blurStrength: ${json.optDouble("blur_strength", 10.0)};\n")
                if (json.has("shadow_width")) rootVars.append("  --shadowWidth: ${json.optDouble("shadow_width", 2.0)};\n")
                if (json.has("font_scale")) rootVars.append("  --fontScale: ${json.optDouble("font_scale", 1.0)};\n")
                if (json.has("chat_width")) {
                    val cw = json.optDouble("chat_width", 50.0)
                    if (cw > 0) rootVars.append("  --sheldWidth: ${cw}vw;\n")
                }
            } else {
                customCss = trimmed
            }
            val asyncLinks = StringBuilder()
            val importRe = Regex("""@import\s+(?:url\(\s*)?['"]?(https?://[^)'"\s;]+)['"]?\s*\)?\s*;?""", RegexOption.IGNORE_CASE)
            val nonBlocking = importRe.replace(customCss) { m ->
                val importUrl = m.groupValues[1]
                if (!vaultRepo.isKnownDeadUrl(importUrl)) {
                    asyncLinks.append("""<link rel="stylesheet" href="${escapeHtml(importUrl)}" media="print" onload="this.media='all'">""" + "\n")
                }
                ""
            }.replace("</style>", "<\\/style>", ignoreCase = true)

            val bundle = GreetingThemeBundle(
                rootVars = rootVars.toString(),
                customCss = nonBlocking,
                bodyClassAttr = bodyClasses.joinToString(" "),
                asyncLinks = asyncLinks.toString()
            )
            cachedThemeBundlePair = themeId to bundle
            bundle
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapInPlainShell(bodyHtml: String, extraScript: String): String {
        val bgCol = if (previewDark) "#0C0C0E" else "#F7F7F5"
        val textCol = if (previewDark) "#E4E4E7" else "#18181B"
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/fontawesome.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/solid.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/brands.min.css" />
<style>
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 18px 16px 32px 16px;
    background: $bgCol; color: $textCol;
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans SC", sans-serif;
    font-size: 15px; line-height: 1.75;
    word-break: break-word; overflow-x: hidden;
  }
  img { max-width: 100%; height: auto; border-radius: 8px; }
</style>
</head>
<body>
  $bodyHtml
  <script>$extraScript</script>
</body>
</html>
        """.trimIndent()
    }

    private fun wrapInThemedStShell(
        bodyHtml: String,
        extraScript: String,
        charName: String,
        hasAvatar: Boolean,
        theme: GreetingThemeBundle,
        hasBoundBg: Boolean = false
    ): String {
        val avatarHtml = if (hasAvatar) {
            """<div class="mesAvatarWrapper"><div class="avatar"><img src="${LOCAL_ORIGIN}__char_avatar__" /></div></div>"""
        } else ""
        val stamp = System.currentTimeMillis()
        val boundBgCss = if (hasBoundBg) {
            """#bg1 { background-image: url("__preview__/bound_bg?t=$stamp") !important; background-size: cover !important; background-position: center !important; background-repeat: no-repeat !important; }"""
        } else ""
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/fontawesome.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/solid.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/brands.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}style.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/st-tailwind.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/backgrounds.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/toggle-dependent.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/mobile-styles.css" />
${theme.asyncLinks}
<style id="st-theme-vars">
:root {
${theme.rootVars}
}
html, body { margin: 0; padding: 0; overflow-x: hidden; }
#sheld { top: 0 !important; height: 100vh !important; max-height: 100vh !important; }
#chat { height: 100% !important; max-height: 100% !important; overflow-y: auto !important; padding-bottom: 48px !important; }
.mes_text img { max-width: 100%; height: auto; }
</style>
<style id="custom-css">
${theme.customCss}
</style>
</head>
<body class="${theme.bodyClassAttr}">
  <style>$boundBgCss</style>
  <div id="bg1"></div>
  <div id="bg_custom"></div>
  <div id="sheld">
    <div id="chat">
      <div class="mes" is_user="false" is_system="false">
        $avatarHtml
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <span class="name_text">${escapeHtml(charName)}</span>
          </div>
          <div class="mes_text">$bodyHtml</div>
        </div>
      </div>
    </div>
  </div>
  <script>$extraScript</script>
</body>
</html>
        """.trimIndent()
    }

    private fun wrapInStShell(
        bodyHtml: String,
        extraScript: String,
        charName: String,
        hasAvatar: Boolean
    ): String {
        val avatarHtml = if (hasAvatar) {
            """<img src="${LOCAL_ORIGIN}__char_avatar__" style="width:42px;height:42px;border-radius:50%;object-fit:cover;margin-right:10px;flex-shrink:0;" />"""
        } else ""

        val bgCol = if (previewDark) "#111114" else "#F7F7F5"
        val mesBg = if (previewDark) "#18181C" else "#FFFFFF"
        val mesBorder = if (previewDark) "#27272C" else "#E6E4DD"
        val textCol = if (previewDark) "#E4E4E7" else "#18181B"
        val mutedBg = if (previewDark) "rgba(255,255,255,0.05)" else "rgba(24,24,27,0.05)"

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/fontawesome.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/solid.min.css" />
<link rel="stylesheet" href="${LOCAL_ORIGIN}css/brands.min.css" />
<style>
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 0;
    background: $bgCol; color: $textCol;
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans SC", sans-serif;
    font-size: 14.5px; line-height: 1.65;
  }
  #chat { padding: 14px; max-width: 100%; overflow-x: hidden; }
  .mes {
    display: flex; align-items: flex-start;
    background: $mesBg; border: 1px solid $mesBorder;
    border-radius: 14px; padding: 14px;
  }
  .mes_block { flex: 1; min-width: 0; overflow-x: auto; }
  .name_text { font-weight: 700; font-size: 13.5px; color: $textCol; margin-bottom: 6px; }
  .mes_text { color: $textCol; word-break: break-word; }
  .mes_text img { max-width: 100%; height: auto; border-radius: 8px; }
  #sandbox-console { background: $mutedBg !important; }
  #mock-toast {
    position: fixed; bottom: 18px; left: 50%; transform: translateX(-50%);
    background: #18181B; color: #F4F4F5; border: 1px solid #323238;
    padding: 8px 16px; border-radius: 999px; font-size: 12.5px;
    opacity: 0; pointer-events: none; transition: opacity .2s; z-index: 9999;
  }
</style>
<script>
// Lightweight Tavern Helper & jQuery Mock Bridge for safe offline preview
(function() {
  const logs = [];
  window.__sandboxLog = function(msg) {
    logs.push(String(msg));
    const el = document.getElementById('sandbox-console');
    if (el) el.textContent = logs.slice(-20).join('\n');
  };
  const showToast = function(msg) {
    window.__sandboxLog('[Toast] ' + msg);
    const t = document.getElementById('mock-toast');
    if (t) { t.textContent = String(msg); t.style.opacity = '1'; setTimeout(() => t.style.opacity = '0', 1800); }
  };
  window.toastr = { info: showToast, success: showToast, warning: showToast, error: showToast };

  const memVars = {};
  const listeners = {};
  window.TavernHelper = {
    getVariables: () => Object.assign({}, memVars),
    replaceVariables: (v) => { Object.assign(memVars, v || {}); window.__sandboxLog('replaceVariables'); },
    insertOrAssignVariables: (v) => { Object.assign(memVars, v || {}); window.__sandboxLog('insertOrAssignVariables'); },
    getChatMessages: () => [{ message_id: 0, name: ${JSONObject.quote(charName)}, message: '' }],
    getLastMessageId: () => 0,
    triggerSlash: (cmd) => { window.__sandboxLog('triggerSlash: ' + cmd); return Promise.resolve(''); },
    eventOn: (ev, fn) => { (listeners[ev] = listeners[ev] || []).push(fn); window.__sandboxLog('eventOn: ' + ev); },
    eventOnce: (ev, fn) => { (listeners[ev] = listeners[ev] || []).push(fn); },
    eventEmit: (ev, ...args) => { (listeners[ev] || []).forEach(fn => { try { fn(...args); } catch(e){} }); },
    generate: () => { showToast('沙盒模式：已拦截大模型生成调用'); return Promise.resolve(''); }
  };
  window.SillyTavern = { getContext: () => ({ chat: [], name1: 'User', name2: ${JSONObject.quote(charName)} }) };
  window.eventOn = window.TavernHelper.eventOn;
  window.triggerSlash = window.TavernHelper.triggerSlash;

  // Minimal jQuery-compatible wrapper ($) if not loaded
  if (!window.jQuery) {
    function wrap(els) {
      const list = Array.isArray(els) ? els : (els ? [els] : []);
      const api = {
        length: list.length,
        get: (i) => i == null ? list : list[i],
        each: (fn) => { list.forEach((el, i) => fn.call(el, i, el)); return api; },
        find: (sel) => wrap(list.flatMap(el => Array.from(el.querySelectorAll(sel)))),
        html: (v) => v === undefined ? (list[0]?.innerHTML || '') : (list.forEach(e => e.innerHTML = v), api),
        text: (v) => v === undefined ? (list[0]?.textContent || '') : (list.forEach(e => e.textContent = v), api),
        val: (v) => v === undefined ? (list[0]?.value || '') : (list.forEach(e => e.value = v), api),
        css: (k, v) => { list.forEach(e => { if (typeof k === 'object') Object.assign(e.style, k); else e.style[k] = v; }); return api; },
        append: (h) => { list.forEach(e => { if (typeof h === 'string') e.insertAdjacentHTML('beforeend', h); else if (h instanceof Node) e.appendChild(h); }); return api; },
        prepend: (h) => { list.forEach(e => { if (typeof h === 'string') e.insertAdjacentHTML('afterbegin', h); else if (h instanceof Node) e.prepend(h); }); return api; },
        addClass: (c) => (list.forEach(e => c.split(/\s+/).forEach(x => x && e.classList.add(x))), api),
        removeClass: (c) => (list.forEach(e => c.split(/\s+/).forEach(x => x && e.classList.remove(x))), api),
        toggleClass: (c) => (list.forEach(e => e.classList.toggle(c)), api),
        on: (ev, fn) => (list.forEach(e => e.addEventListener(ev, fn)), api),
        click: (fn) => fn ? api.on('click', fn) : (list.forEach(e => e.click()), api),
        remove: () => (list.forEach(e => e.remove()), api),
        ready: (fn) => { if (document.readyState !== 'loading') fn(); else document.addEventListener('DOMContentLoaded', fn); return api; }
      };
      return api;
    }
    window.$ = window.jQuery = function(sel) {
      if (typeof sel === 'function') {
        if (document.readyState !== 'loading') sel(); else document.addEventListener('DOMContentLoaded', sel);
        return;
      }
      if (typeof sel === 'string') {
        if (sel.trim().startsWith('<')) {
          const d = document.createElement('div'); d.innerHTML = sel; return wrap(Array.from(d.childNodes));
        }
        return wrap(Array.from(document.querySelectorAll(sel)));
      }
      return wrap(sel);
    };
  }
})();
</script>
</head>
<body>
  <div id="chat">
    <div class="mes">
      $avatarHtml
      <div class="mes_block">
        <div class="name_text">${escapeHtml(charName)}</div>
        <div class="mes_text">$bodyHtml</div>
      </div>
    </div>
  </div>
  <div id="mock-toast"></div>
  <script>
    $extraScript
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "js" -> "application/javascript"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private data class PreviewPayload(
        val mode: String,
        val title: String,
        val findRegex: String = "",
        val replaceString: String = "",
        val scriptCode: String = "",
        val rawSource: String = "",
        val greetingText: String = "",
        val greetingItems: List<Pair<String, String>> = emptyList(),
        val greetingIndex: Int = 0,
        val regexListJson: String = "",
        val charId: String = ""
    )

    companion object {
        private const val LOCAL_ORIGIN = "https://st.local/"
        private val stAssetRamCache = ConcurrentHashMap<String, ByteArray>()
        const val MODE_REGEX = "regex"
        const val MODE_HELPER = "helper"
        const val MODE_GREETING_RENDER = "greeting"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"

        @Volatile
        private var pendingPayload: PreviewPayload? = null

        fun openRegex(
            ctx: Context,
            title: String,
            findRegex: String,
            replaceString: String,
            rawSource: String = "",
            sampleInput: String = ""
        ) {
            pendingPayload = PreviewPayload(
                mode = MODE_REGEX,
                title = title,
                findRegex = findRegex,
                replaceString = replaceString,
                rawSource = rawSource,
                greetingText = sampleInput
            )
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_REGEX)
                putExtra(EXTRA_TITLE, title)
            })
        }

        fun openHelper(
            ctx: Context,
            title: String,
            scriptCode: String,
            rawSource: String = ""
        ) {
            pendingPayload = PreviewPayload(
                mode = MODE_HELPER,
                title = title,
                scriptCode = scriptCode,
                rawSource = rawSource
            )
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_HELPER)
                putExtra(EXTRA_TITLE, title)
            })
        }

        fun openCharacterGreeting(
            ctx: Context,
            charId: String,
            charName: String,
            greeting: String,
            regexRules: List<Triple<String, String, Boolean>>,
            allGreetings: List<Pair<String, String>> = emptyList(),
            initialIndex: Int = 0
        ) {
            val arr = JSONArray()
            for ((find, rep, dis) in regexRules) {
                arr.put(JSONObject().apply {
                    put("findRegex", find)
                    put("replaceString", rep)
                    put("disabled", dis)
                })
            }
            pendingPayload = PreviewPayload(
                mode = MODE_GREETING_RENDER,
                title = charName,
                charId = charId,
                greetingText = greeting,
                greetingItems = allGreetings,
                greetingIndex = initialIndex,
                regexListJson = arr.toString()
            )
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_GREETING_RENDER)
                putExtra(EXTRA_TITLE, charName)
            })
        }
    }
}
