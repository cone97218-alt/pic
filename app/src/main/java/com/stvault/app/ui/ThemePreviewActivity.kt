package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.stvault.app.R
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityThemePreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

class ThemePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemePreviewBinding
    private lateinit var repo: VaultRepository

    private var currentTitle: String = ""
    private var currentRawText: String = ""
    private var pickingAvatarType: String = "ai" // "ai" | "user"

    private val prefs by lazy { getSharedPreferences("st_preview_prefs", Context.MODE_PRIVATE) }
    private val aiAvatarFile by lazy { File(filesDir, "preview_ai_avatar.img") }
    private val userAvatarFile by lazy { File(filesDir, "preview_user_avatar.img") }

    private val pickAvatarLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val dst = if (pickingAvatarType == "ai") aiAvatarFile else userAvatarFile
                        contentResolver.openInputStream(uri)?.use { inp ->
                            dst.outputStream().use { out -> inp.copyTo(out) }
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) {
                    toast(if (pickingAvatarType == "ai") "AI 头像已更新" else "角色头像已更新")
                    renderPreview()
                } else {
                    toast("头像保存失败")
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = VaultRepository.getInstance(this)
        binding.btnClosePreview.setOnClickListener { finish() }
        binding.btnPreviewSettings.setOnClickListener { showPreviewSettingsDialog() }

        val web = binding.webPreview
        // Eliminate white flash on initial load
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

        web.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                binding.pbPreviewLoading.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.pbPreviewLoading.visibility = View.GONE
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 1. Custom local preview avatars
                if (url.startsWith("${ST_LOCAL_ORIGIN}__preview__/ai_avatar") && aiAvatarFile.exists()) {
                    return try {
                        WebResourceResponse("image/*", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(aiAvatarFile))
                    } catch (_: Exception) {
                        null
                    }
                }
                if (url.startsWith("${ST_LOCAL_ORIGIN}__preview__/user_avatar") && userAvatarFile.exists()) {
                    return try {
                        WebResourceResponse("image/*", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), FileInputStream(userAvatarFile))
                    } catch (_: Exception) {
                        null
                    }
                }

                // 2. Local bundled SillyTavern frontend assets (served from RAM cache for instant render!)
                if (url.startsWith(ST_LOCAL_ORIGIN)) {
                    val relPath = url.removePrefix(ST_LOCAL_ORIGIN).substringBefore('?').trimStart('/')
                    if (relPath.isNotEmpty() && !relPath.startsWith("__preview__/")) {
                        return try {
                            val bytes = assetRamCache.getOrPut(relPath) {
                                assets.open("st/$relPath").use { it.readBytes() }
                            }
                            val mime = guessLocalAssetMime(relPath)
                            val encoding = if (mime.startsWith("text/") || mime == "application/javascript") "UTF-8" else null
                            val headers = mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "public, max-age=31536000"
                            )
                            WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                // 3. Local Vault cached images & fonts
                val cached = repo.getCachedAssetByUrl(url)
                if (cached != null) {
                    return try {
                        val headers = mapOf("Access-Control-Allow-Origin" to "*")
                        WebResourceResponse(
                            cached.second,
                            null,
                            200,
                            "OK",
                            headers,
                            FileInputStream(cached.first)
                        )
                    } catch (_: Exception) {
                        null
                    }
                }

                // 4. Short-circuit known dead/ignored links in 0ms so WebView never hangs waiting for timeout!
                if (repo.isKnownDeadUrl(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                return null
            }
        }

        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        if (sourceId.isNullOrBlank()) {
            toast("缺少美化信息")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val (fileName, rawText) = withContext(Dispatchers.IO) {
                    repo.readSourceRawText(sourceId)
                }
                currentTitle = fileName
                currentRawText = rawText
                binding.tvPreviewTitle.text = fileName
                renderPreview()
            } catch (e: Exception) {
                binding.pbPreviewLoading.visibility = View.GONE
                toast("无法预览：${e.message}")
                finish()
            }
        }
    }

    private fun renderPreview() {
        if (currentRawText.isBlank()) return
        binding.pbPreviewLoading.visibility = View.VISIBLE
        val html = buildRealSillyTavernHtml(currentTitle, currentRawText)
        binding.webPreview.loadDataWithBaseURL(ST_LOCAL_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun showPreviewSettingsDialog() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        scroll.addView(root)

        val avatarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnAiAvatar = MaterialButton(this).apply {
            text = "选择 AI 头像"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                pickingAvatarType = "ai"
                pickAvatarLauncher.launch("image/*")
            }
        }
        val btnUserAvatar = MaterialButton(this).apply {
            text = "选择角色头像"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                pickingAvatarType = "user"
                pickAvatarLauncher.launch("image/*")
            }
        }
        avatarRow.addView(btnAiAvatar)
        avatarRow.addView(btnUserAvatar)
        root.addView(avatarRow)

        fun addField(label: String, value: String, multiLine: Boolean = false): EditText {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, 24, 0, 6)
                setTextColor(ContextCompat.getColor(this@ThemePreviewActivity, R.color.text_muted))
            }
            val et = EditText(this).apply {
                setText(value)
                textSize = 13.5f
                if (multiLine) {
                    minLines = 2
                    maxLines = 4
                } else {
                    isSingleLine = true
                }
            }
            root.addView(tv)
            root.addView(et)
            return et
        }

        val etAiName = addField("AI 名称", prefs.getString("ai_name", DEFAULT_AI_NAME) ?: DEFAULT_AI_NAME)
        val etUserName = addField("角色名称", prefs.getString("user_name", DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME)
        val etAiText = addField("AI 预览文本", prefs.getString("ai_text", DEFAULT_AI_TEXT) ?: DEFAULT_AI_TEXT, multiLine = true)
        val etUserText = addField("角色预览文本", prefs.getString("user_text", DEFAULT_USER_TEXT) ?: DEFAULT_USER_TEXT, multiLine = true)

        AlertDialog.Builder(this)
            .setTitle("预览设置")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("ai_name", etAiName.text.toString().ifBlank { DEFAULT_AI_NAME })
                    .putString("user_name", etUserName.text.toString().ifBlank { DEFAULT_USER_NAME })
                    .putString("ai_text", etAiText.text.toString().ifBlank { DEFAULT_AI_TEXT })
                    .putString("user_text", etUserText.text.toString().ifBlank { DEFAULT_USER_TEXT })
                    .apply()
                renderPreview()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit().clear().apply()
                if (aiAvatarFile.exists()) aiAvatarFile.delete()
                if (userAvatarFile.exists()) userAvatarFile.delete()
                renderPreview()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun guessLocalAssetMime(path: String): String = when {
        path.endsWith(".css", true) -> "text/css"
        path.endsWith(".woff2", true) -> "font/woff2"
        path.endsWith(".ttf", true) -> "font/ttf"
        path.endsWith(".js", true) -> "application/javascript"
        else -> "application/octet-stream"
    }

    private fun escHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun buildRealSillyTavernHtml(title: String, rawText: String): String {
        var customCss = ""
        val bodyClasses = mutableListOf<String>()
        val rootVars = StringBuilder()

        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                customCss = json.optString("custom_css", "")

                when (json.optInt("chat_display", 0)) {
                    1 -> bodyClasses.add("bubblechat")
                    2 -> bodyClasses.add("documentstyle")
                }
                when (json.optInt("avatar_style", 0)) {
                    1 -> bodyClasses.add("square_avatars")
                    2 -> bodyClasses.add("big-avatars")
                }
                if (json.optBoolean("waifuMode", false)) bodyClasses.add("waifuMode")
                if (json.optBoolean("noShadows", false)) bodyClasses.add("no-shadows")
                if (json.optBoolean("reduced_motion", false)) bodyClasses.add("reduced-motion")

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

                if (json.has("blur_strength")) {
                    val blur = json.optDouble("blur_strength", 10.0)
                    rootVars.append("  --SmartThemeBlurStrength: ${blur}px;\n")
                    rootVars.append("  --blurStrength: ${blur}px;\n")
                    if (blur <= 0.0) bodyClasses.add("no-blur")
                }
                if (json.has("shadow_width")) {
                    val sw = json.optDouble("shadow_width", 2.0)
                    rootVars.append("  --SmartThemeShadowWidth: ${sw}px;\n")
                }
                if (json.has("font_scale")) {
                    val fs = json.optDouble("font_scale", 1.0)
                    rootVars.append("  --mainFontSize: ${15.0 * fs}px;\n")
                }
            } catch (_: Exception) {
                customCss = rawText
            }
        } else {
            customCss = rawText
        }

        // Move render-blocking @import url(...) out of <style> into non-blocking async <link> tags!
        val asyncLinks = StringBuilder()
        val importRe = Regex("""@import\s+(?:url\(\s*)?['"]?(https?://[^)'"\s;]+)['"]?\s*\)?\s*;?""", RegexOption.IGNORE_CASE)
        val nonBlockingCss = importRe.replace(customCss) { m ->
            val importUrl = m.groupValues[1]
            if (!repo.isKnownDeadUrl(importUrl)) {
                asyncLinks.append(
                    """<link rel="stylesheet" href="${escHtml(importUrl)}" media="print" onload="this.media='all'">""" + "\n"
                )
            }
            ""
        }

        val safeCustomCss = nonBlockingCss.replace("</style>", "<\\/style>", ignoreCase = true)
        val bodyClassAttr = bodyClasses.joinToString(" ")

        val stamp = System.currentTimeMillis()
        val botAvatar = if (aiAvatarFile.exists()) {
            "__preview__/ai_avatar?t=$stamp"
        } else {
            "data:image/svg+xml;utf8," +
                "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
                "%3Cdefs%3E%3ClinearGradient id='g1' x1='0' y1='0' x2='1' y2='1'%3E" +
                "%3Cstop offset='0%25' stop-color='%237c6cf6'/%3E%3Cstop offset='100%25' stop-color='%2334d399'/%3E" +
                "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g1)'/%3E" +
                "%3Ctext x='50' y='63' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EA%3C/text%3E%3C/svg%3E"
        }

        val userAvatar = if (userAvatarFile.exists()) {
            "__preview__/user_avatar?t=$stamp"
        } else {
            "data:image/svg+xml;utf8," +
                "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
                "%3Cdefs%3E%3ClinearGradient id='g2' x1='0' y1='0' x2='1' y2='1'%3E" +
                "%3Cstop offset='0%25' stop-color='%23f59e0b'/%3E%3Cstop offset='100%25' stop-color='%23f43f5e'/%3E" +
                "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g2)'/%3E" +
                "%3Ctext x='50' y='63' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EU%3C/text%3E%3C/svg%3E"
        }

        val aiName = escHtml(prefs.getString("ai_name", DEFAULT_AI_NAME) ?: DEFAULT_AI_NAME)
        val userName = escHtml(prefs.getString("user_name", DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME)
        val aiText = escHtml(prefs.getString("ai_text", DEFAULT_AI_TEXT) ?: DEFAULT_AI_TEXT).replace("\n", "<br>")
        val userText = escHtml(prefs.getString("user_text", DEFAULT_USER_TEXT) ?: DEFAULT_USER_TEXT).replace("\n", "<br>")

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${escHtml(title)}</title>
<link rel="stylesheet" href="css/fontawesome.min.css">
<link rel="stylesheet" href="css/solid.min.css">
<link rel="stylesheet" href="css/st-tailwind.css">
<link rel="stylesheet" href="css/backgrounds.css">
<link rel="stylesheet" href="style.css">
<link rel="stylesheet" href="css/mobile-styles.css">
$asyncLinks
<style id="st-theme-vars">
:root {
$rootVars
}
#send_but { display: flex !important; }
#sheldheader { display: none; }
/* Horizontal Quick Reply bar matching SillyTavern QR extension */
#qr--bar {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  padding: 4px 6px;
  overflow-x: auto;
  flex-wrap: nowrap;
}
#qr--bar .qr--buttons {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
}
#qr--bar .qr--button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
  flex-shrink: 0;
}
</style>
<style id="custom-css">
$safeCustomCss
</style>
</head>
<body class="$bodyClassAttr">
  <div id="bg1"></div>
  <div id="bg_custom"></div>

  <div id="top-bar"></div>
  <div id="top-settings-holder">
    <div id="ai-config-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-sliders closedIcon"></div></div>
    </div>
    <div id="sys-settings-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-plug closedIcon"></div></div>
    </div>
    <div id="advanced-formatting-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-font closedIcon"></div></div>
    </div>
    <div id="WI-SP-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-book-atlas closedIcon"></div></div>
    </div>
    <div id="user-settings-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-face-smile closedIcon"></div></div>
    </div>
    <div id="backgrounds-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-image closedIcon"></div></div>
    </div>
    <div id="extensions-settings-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-cubes closedIcon"></div></div>
    </div>
    <div id="persona-management-button" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-user-pen closedIcon"></div></div>
    </div>
    <div id="rightNavDrawerIcon" class="drawer">
      <div class="drawer-toggle"><div class="drawer-icon fa-solid fa-address-card closedIcon"></div></div>
    </div>
  </div>

  <div id="sheld">
    <div id="sheldheader" class="fa-solid fa-grip drag-grabber"></div>
    <div id="chat">
      <div class="mes" mesid="0" ch_name="$aiName" is_user="false" is_system="false">
        <div class="for_checkbox"></div><input type="checkbox" class="del_checkbox">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$botAvatar" alt="AI"></div>
          <div class="mesIDDisplay">#0</div>
          <div class="mes_timer">1.4s</div>
          <div class="tokenCounterDisplay">52t</div>
        </div>
        <div class="swipe_left fa-solid fa-chevron-left"></div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <div class="flex-container alignItemsBaseline">
                <span class="name_text">$aiName</span>
                <small class="timestamp">15:30</small>
              </div>
            </div>
            <div class="mes_buttons">
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis"></div>
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><em>窗外的雨声渐渐小了下来，壁炉里的木柴发出轻微的噼啪声。</em></p>
            <p><q>$aiText</q></p>
            <blockquote>引用块样式预览 · <code>code</code> 标签样式</blockquote>
          </div>
          <div class="mes_bias"></div>
        </div>
        <div class="flex-container swipeRightBlock flexFlowColumn flexNoGap">
          <div class="swipe_right fa-solid fa-chevron-right"></div>
          <div class="swipes-counter"></div>
        </div>
      </div>

      <div class="mes" mesid="1" ch_name="$userName" is_user="true" is_system="false">
        <div class="for_checkbox"></div><input type="checkbox" class="del_checkbox">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$userAvatar" alt="User"></div>
          <div class="mesIDDisplay">#1</div>
          <div class="tokenCounterDisplay">24t</div>
        </div>
        <div class="swipe_left fa-solid fa-chevron-left"></div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <div class="flex-container alignItemsBaseline">
                <span class="name_text">$userName</span>
                <small class="timestamp">15:31</small>
              </div>
            </div>
            <div class="mes_buttons">
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis"></div>
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><q>$userText</q></p>
            <p><em>合上手中的笔记本，抬头打量着四周的装潢。</em></p>
          </div>
          <div class="mes_bias"></div>
        </div>
        <div class="flex-container swipeRightBlock flexFlowColumn flexNoGap">
          <div class="swipe_right fa-solid fa-chevron-right"></div>
          <div class="swipes-counter"></div>
        </div>
      </div>

      <div class="mes last_mes" mesid="2" ch_name="$aiName" is_user="false" is_system="false">
        <div class="for_checkbox"></div><input type="checkbox" class="del_checkbox">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$botAvatar" alt="AI"></div>
          <div class="mesIDDisplay">#2</div>
          <div class="mes_timer">0.9s</div>
          <div class="tokenCounterDisplay">36t</div>
        </div>
        <div class="swipe_left fa-solid fa-chevron-left"></div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <div class="flex-container alignItemsBaseline">
                <span class="name_text">$aiName</span>
                <small class="timestamp">15:32</small>
              </div>
            </div>
            <div class="mes_buttons">
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis"></div>
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><strong>加粗文本</strong>、<u>下划线</u> 与 <em>斜体动作</em>。<q>右上角「设置」可自定义头像与对话文本。</q></p>
          </div>
          <div class="mes_bias"></div>
        </div>
        <div class="flex-container swipeRightBlock flexFlowColumn flexNoGap">
          <div class="swipe_right fa-solid fa-chevron-right"></div>
          <div class="swipes-counter">1/1</div>
        </div>
      </div>
    </div>

    <div id="form_sheld">
      <div id="qr--bar" class="flex-container">
        <div class="qr--buttons">
          <div class="qr--button menu_button">继续</div>
          <div class="qr--button menu_button">状态栏</div>
          <div class="qr--button menu_button">总结</div>
          <div class="qr--button menu_button">重生成</div>
        </div>
      </div>
      <div id="send_form">
        <div id="nonQRFormItems">
          <div id="leftSendForm" class="alignContentCenter">
            <div id="options_button" class="fa-solid fa-bars interactable"></div>
          </div>
          <textarea id="send_textarea" name="text" class="mdHotkeys" placeholder="输入消息..." autocomplete="off"></textarea>
          <div id="rightSendForm" class="alignContentCenter">
            <div id="send_but" class="fa-solid fa-paper-plane interactable"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</body>
</html>
        """.trimIndent()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ST_LOCAL_ORIGIN = "https://sillytavern.local/"
        private const val EXTRA_SOURCE_ID = "source_id"
        private val assetRamCache = ConcurrentHashMap<String, ByteArray>()
        private const val DEFAULT_AI_NAME = "Seraphina"
        private const val DEFAULT_USER_NAME = "You"
        private const val DEFAULT_AI_TEXT = "欢迎回到酒馆。这是一段基于真实酒馆前端样式渲染的主题预览。"
        private const val DEFAULT_USER_TEXT = "这套美化的顶栏、气泡边框、字体和底栏看起来如何？"

        fun open(context: Context, sourceId: String) {
            val intent = Intent(context, ThemePreviewActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            context.startActivity(intent)
        }
    }
}
