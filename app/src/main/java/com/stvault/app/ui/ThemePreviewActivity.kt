package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivityThemePreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream

class ThemePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemePreviewBinding
    private lateinit var repo: VaultRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = VaultRepository.getInstance(this)
        binding.btnClosePreview.setOnClickListener { finish() }

        val web = binding.webPreview
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Serve real SillyTavern CSS/webfonts from APK assets/st/ AND cached images/fonts from local vault!
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 1. Local bundled SillyTavern frontend assets (style.css, mobile-styles.css, fontawesome, webfonts)
                if (url.startsWith(ST_LOCAL_ORIGIN)) {
                    val relPath = url.removePrefix(ST_LOCAL_ORIGIN).substringBefore('?').trimStart('/')
                    if (relPath.isNotEmpty()) {
                        return try {
                            val inp = assets.open("st/$relPath")
                            val mime = guessLocalAssetMime(relPath)
                            val headers = mapOf("Access-Control-Allow-Origin" to "*")
                            WebResourceResponse(mime, "UTF-8", 200, "OK", headers, inp)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                // 2. Local Vault cached images & fonts
                val cached = repo.getCachedAssetByUrl(url) ?: return null
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
                binding.tvPreviewTitle.text = fileName
                val html = buildRealSillyTavernHtml(fileName, rawText)
                web.loadDataWithBaseURL(ST_LOCAL_ORIGIN, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                toast("无法预览：${e.message}")
                finish()
            }
        }
    }

    private fun guessLocalAssetMime(path: String): String = when {
        path.endsWith(".css", true) -> "text/css"
        path.endsWith(".woff2", true) -> "font/woff2"
        path.endsWith(".ttf", true) -> "font/ttf"
        path.endsWith(".js", true) -> "application/javascript"
        else -> "application/octet-stream"
    }

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

        val safeCustomCss = customCss.replace("</style>", "<\\/style>", ignoreCase = true)
        val bodyClassAttr = bodyClasses.joinToString(" ")

        val botAvatar = "data:image/svg+xml;utf8," +
            "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
            "%3Cdefs%3E%3ClinearGradient id='g1' x1='0' y1='0' x2='1' y2='1'%3E" +
            "%3Cstop offset='0%25' stop-color='%237c6cf6'/%3E%3Cstop offset='100%25' stop-color='%2334d399'/%3E" +
            "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g1)'/%3E" +
            "%3Ctext x='50' y='63' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EA%3C/text%3E%3C/svg%3E"

        val userAvatar = "data:image/svg+xml;utf8," +
            "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
            "%3Cdefs%3E%3ClinearGradient id='g2' x1='0' y1='0' x2='1' y2='1'%3E" +
            "%3Cstop offset='0%25' stop-color='%23f59e0b'/%3E%3Cstop offset='100%25' stop-color='%23f43f5e'/%3E" +
            "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g2)'/%3E" +
            "%3Ctext x='50' y='63' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EU%3C/text%3E%3C/svg%3E"

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${title.replace("<", "&lt;")}</title>
<link rel="stylesheet" href="css/fontawesome.min.css">
<link rel="stylesheet" href="css/solid.min.css">
<link rel="stylesheet" href="css/st-tailwind.css">
<link rel="stylesheet" href="css/backgrounds.css">
<link rel="stylesheet" href="style.css">
<link rel="stylesheet" href="css/mobile-styles.css">
<style id="st-theme-vars">
:root {
$rootVars
}
/* Ensure #send_but is visible in static preview */
#send_but { display: flex !important; }
#sheldheader { display: none; }
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
      <div class="mes" mesid="0" ch_name="Seraphina" is_user="false" is_system="false">
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
                <span class="name_text">Seraphina</span>
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
            <p><q>欢迎回到酒馆。这是一段基于真实酒馆前端样式渲染的主题预览。</q></p>
            <blockquote>引用块样式预览 · <code>code</code> 标签样式</blockquote>
          </div>
          <div class="mes_bias"></div>
        </div>
        <div class="flex-container swipeRightBlock flexFlowColumn flexNoGap">
          <div class="swipe_right fa-solid fa-chevron-right"></div>
          <div class="swipes-counter"></div>
        </div>
      </div>

      <div class="mes" mesid="1" ch_name="You" is_user="true" is_system="false">
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
                <span class="name_text">You</span>
                <small class="timestamp">15:31</small>
              </div>
            </div>
            <div class="mes_buttons">
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis"></div>
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><q>这套美化的顶栏、气泡边框、字体和底栏看起来如何？</q></p>
            <p><em>合上手中的笔记本，抬头打量着四周的装潢。</em></p>
          </div>
          <div class="mes_bias"></div>
        </div>
        <div class="flex-container swipeRightBlock flexFlowColumn flexNoGap">
          <div class="swipe_right fa-solid fa-chevron-right"></div>
          <div class="swipes-counter"></div>
        </div>
      </div>

      <div class="mes last_mes" mesid="2" ch_name="Seraphina" is_user="false" is_system="false">
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
                <span class="name_text">Seraphina</span>
                <small class="timestamp">15:32</small>
              </div>
            </div>
            <div class="mes_buttons">
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis"></div>
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><strong>加粗文本</strong>、<u>下划线</u> 与 <em>斜体动作</em>。<q>满意后再导入到 TT 酒馆使用。</q></p>
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

        fun open(context: Context, sourceId: String) {
            val intent = Intent(context, ThemePreviewActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            context.startActivity(intent)
        }
    }
}
