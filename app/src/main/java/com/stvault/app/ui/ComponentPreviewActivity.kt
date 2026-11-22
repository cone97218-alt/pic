package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.stvault.app.R
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityComponentPreviewBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream

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
    private var regexListJson: String = ""
    private var charId: String = ""
    private var showingSource: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComponentPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultRepo = VaultRepository.getInstance(this)
        libRepo = ResourceLibraryRepository.getInstance(this)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_REGEX
        titleText = intent.getStringExtra(EXTRA_TITLE) ?: "沙盒预览"
        findRegex = intent.getStringExtra(EXTRA_FIND_REGEX) ?: ""
        replaceString = intent.getStringExtra(EXTRA_REPLACE_STR) ?: ""
        scriptCode = intent.getStringExtra(EXTRA_SCRIPT_CODE) ?: ""
        rawSource = intent.getStringExtra(EXTRA_RAW_SOURCE) ?: ""
        greetingText = intent.getStringExtra(EXTRA_GREETING_TEXT) ?: ""
        regexListJson = intent.getStringExtra(EXTRA_REGEX_LIST_JSON) ?: ""
        charId = intent.getStringExtra(EXTRA_CHAR_ID) ?: ""

        binding.tvPreviewName.text = titleText
        binding.btnCloseCompPreview.setOnClickListener { finish() }

        binding.tvSourceCode.text = rawSource.ifBlank {
            when (mode) {
                MODE_REGEX -> "查找正则：\n$findRegex\n\n替换内容：\n$replaceString"
                MODE_HELPER -> scriptCode
                else -> greetingText
            }
        }

        binding.btnPreviewModeToggle.setOnClickListener {
            showingSource = !showingSource
            binding.scrollSourceView.visibility = if (showingSource) View.VISIBLE else View.GONE
            binding.webCompPreview.visibility = if (showingSource) View.GONE else View.VISIBLE
            binding.btnPreviewModeToggle.text = if (showingSource) "渲染" else "源码"
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webCompPreview
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
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
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 1. Character cover avatar if rendering greeting
                if (url.startsWith("${LOCAL_ORIGIN}__char_avatar__") && charId.isNotEmpty()) {
                    val rec = libRepo.getCharacterById(charId)
                    val f = rec?.let { libRepo.getCharacterFile(it) }
                    if (f != null && f.exists()) {
                        return try {
                            WebResourceResponse("image/png", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(f))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                // 2. Bundled SillyTavern CSS/fonts
                if (url.startsWith(LOCAL_ORIGIN)) {
                    val assetPath = url.removePrefix(LOCAL_ORIGIN).substringBefore('?').trimStart('/')
                    return try {
                        val stream = assets.open("st/$assetPath")
                        WebResourceResponse(guessMime(assetPath), "UTF-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), stream)
                    } catch (_: Exception) {
                        null
                    }
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

    private fun renderCurrentMode() {
        val html = when (mode) {
            MODE_REGEX -> buildRegexPreviewHtml()
            MODE_HELPER -> buildHelperPreviewHtml()
            MODE_GREETING_RENDER -> buildGreetingRenderHtml()
            else -> ""
        }
        binding.webCompPreview.loadDataWithBaseURL(LOCAL_ORIGIN, html, "text/html", "UTF-8", null)
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

    private fun buildRegexPreviewHtml(): String {
        val testInput = binding.etTestInput.text?.toString().orEmpty()
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
                // Strip ```html ... ``` wrappers just like SillyTavern
                output = output.replace(/```html\s*([\s\S]*?)```/gi, '$1');
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
        val greetingJson = JSONObject.quote(greetingText)
        val regexArrStr = regexListJson.ifBlank { "[]" }

        val script = """
            (function() {
                let text = $greetingJson;
                const rules = $regexArrStr;
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
                text = text.replace(/```html\s*([\s\S]*?)```/gi, '$1');
                // Simple markdown bold/italic/newlines for non-HTML paragraphs
                if (!/<(div|style|table|section|details|iframe)/i.test(text)) {
                    text = text
                        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
                        .replace(/\*([^*]+)\*/g, '<em>$1</em>')
                        .replace(/\n/g, '<br>');
                }
                const target = document.getElementById('st-render-slot');
                if (target) {
                    target.innerHTML = text;
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
            hasAvatar = charId.isNotEmpty()
        )
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
    background: #111114; color: #F4F4F5;
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans SC", sans-serif;
    font-size: 14.5px; line-height: 1.65;
  }
  #chat { padding: 14px; max-width: 100%; overflow-x: hidden; }
  .mes {
    display: flex; align-items: flex-start;
    background: #18181C; border: 1px solid #27272C;
    border-radius: 14px; padding: 14px;
  }
  .mes_block { flex: 1; min-width: 0; overflow-x: auto; }
  .name_text { font-weight: 700; font-size: 13.5px; color: #E4E4E7; margin-bottom: 6px; }
  .mes_text { color: #E4E4E7; word-break: break-word; }
  .mes_text img { max-width: 100%; height: auto; border-radius: 8px; }
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

    companion object {
        private const val LOCAL_ORIGIN = "https://st.local/"
        const val MODE_REGEX = "regex"
        const val MODE_HELPER = "helper"
        const val MODE_GREETING_RENDER = "greeting"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FIND_REGEX = "find_regex"
        private const val EXTRA_REPLACE_STR = "replace_str"
        private const val EXTRA_SCRIPT_CODE = "script_code"
        private const val EXTRA_RAW_SOURCE = "raw_source"
        private const val EXTRA_GREETING_TEXT = "greeting_text"
        private const val EXTRA_REGEX_LIST_JSON = "regex_list_json"
        private const val EXTRA_CHAR_ID = "char_id"

        fun openRegex(
            ctx: Context,
            title: String,
            findRegex: String,
            replaceString: String,
            rawSource: String = "",
            sampleInput: String = ""
        ) {
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_REGEX)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FIND_REGEX, findRegex)
                putExtra(EXTRA_REPLACE_STR, replaceString)
                putExtra(EXTRA_RAW_SOURCE, rawSource)
                putExtra(EXTRA_GREETING_TEXT, sampleInput)
            })
        }

        fun openHelper(
            ctx: Context,
            title: String,
            scriptCode: String,
            rawSource: String = ""
        ) {
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_HELPER)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SCRIPT_CODE, scriptCode)
                putExtra(EXTRA_RAW_SOURCE, rawSource)
            })
        }

        fun openCharacterGreeting(
            ctx: Context,
            charId: String,
            charName: String,
            greeting: String,
            regexRules: List<Triple<String, String, Boolean>> // (findRegex, replaceString, disabled)
        ) {
            val arr = JSONArray()
            for ((find, rep, dis) in regexRules) {
                arr.put(JSONObject().apply {
                    put("findRegex", find)
                    put("replaceString", rep)
                    put("disabled", dis)
                })
            }
            ctx.startActivity(Intent(ctx, ComponentPreviewActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_GREETING_RENDER)
                putExtra(EXTRA_TITLE, charName)
                putExtra(EXTRA_CHAR_ID, charId)
                putExtra(EXTRA_GREETING_TEXT, greeting)
                putExtra(EXTRA_REGEX_LIST_JSON, arr.toString())
            })
        }
    }
}
