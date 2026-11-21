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

        // Intercept cached images & fonts from local vault so offline/broken links still preview!
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
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
                val html = buildSillyTavernPreviewHtml(fileName, rawText)
                web.loadDataWithBaseURL("https://sillytavern.local/", html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                toast("无法预览：${e.message}")
                finish()
            }
        }
    }

    private fun buildSillyTavernPreviewHtml(title: String, rawText: String): String {
        var customCss = ""
        val bodyClasses = mutableListOf<String>()
        val cssVars = StringBuilder()

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
                if (json.optBoolean("waifuMode", false)) {
                    bodyClasses.add("waifuMode")
                }
                if (json.optBoolean("noShadows", false)) {
                    bodyClasses.add("no-shadows")
                }

                fun putColor(jsonKey: String, cssVar: String) {
                    val v = json.optString(jsonKey, "").trim()
                    if (v.isNotEmpty()) cssVars.append("  $cssVar: $v;\n")
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

                if (json.has("blur_strength")) {
                    val blur = json.optDouble("blur_strength", 10.0)
                    cssVars.append("  --SmartThemeBlurStrength: ${blur}px;\n")
                    cssVars.append("  --blurStrength: ${blur}px;\n")
                    if (blur <= 0.0) bodyClasses.add("no-blur")
                }
                if (json.has("shadow_width")) {
                    val sw = json.optDouble("shadow_width", 2.0)
                    cssVars.append("  --SmartThemeShadowWidth: ${sw}px;\n")
                }
                if (json.has("font_scale")) {
                    val fs = json.optDouble("font_scale", 1.0)
                    cssVars.append("  --mainFontSize: ${15.0 * fs}px;\n")
                }
            } catch (_: Exception) {
                customCss = rawText
            }
        } else {
            customCss = rawText
        }

        val safeCustomCss = customCss.replace("</style>", "<\\/style>", ignoreCase = true)
        val bodyClassAttr = bodyClasses.joinToString(" ")

        // Clean SVG data-URI avatars so CSS rules targeting `.avatar img` work properly
        val botAvatar = "data:image/svg+xml;utf8," +
            "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
            "%3Cdefs%3E%3ClinearGradient id='g1' x1='0' y1='0' x2='1' y2='1'%3E" +
            "%3Cstop offset='0%25' stop-color='%237c6cf6'/%3E%3Cstop offset='100%25' stop-color='%2334d399'/%3E" +
            "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g1)'/%3E" +
            "%3Ctext x='50' y='62' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EA%3C/text%3E%3C/svg%3E"

        val userAvatar = "data:image/svg+xml;utf8," +
            "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E" +
            "%3Cdefs%3E%3ClinearGradient id='g2' x1='0' y1='0' x2='1' y2='1'%3E" +
            "%3Cstop offset='0%25' stop-color='%23f59e0b'/%3E%3Cstop offset='100%25' stop-color='%23f43f5e'/%3E" +
            "%3C/linearGradient%3E%3C/defs%3E%3Crect width='100' height='100' fill='url(%23g2)'/%3E" +
            "%3Ctext x='50' y='62' font-size='44' font-family='sans-serif' font-weight='bold' fill='white' text-anchor='middle'%3EU%3C/text%3E%3C/svg%3E"

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${title.replace("<", "&lt;")}</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css">
<style id="st-base-css">
  :root {
    --white100: rgba(255, 255, 255, 1);
    --white50a: rgba(255, 255, 255, 0.5);
    --white30a: rgba(255, 255, 255, 0.3);
    --black100: rgba(0, 0, 0, 1);
    --black70a: rgba(0, 0, 0, 0.7);
    --black50a: rgba(0, 0, 0, 0.5);
    --black30a: rgba(0, 0, 0, 0.3);
    --grey100: rgba(180, 180, 180, 1);
    --grey70: rgba(180, 180, 180, 0.7);
    --grey50: rgba(180, 180, 180, 0.5);
    --grey30: rgba(180, 180, 180, 0.3);
    --grey10: rgba(180, 180, 180, 0.1);

    --SmartThemeBodyColor: rgba(220, 220, 210, 1);
    --SmartThemeEmColor: rgba(149, 165, 166, 1);
    --SmartThemeUnderlineColor: rgba(188, 231, 204, 1);
    --SmartThemeQuoteColor: rgba(225, 138, 36, 1);
    --SmartThemeShadowColor: rgba(0, 0, 0, 0.5);
    --SmartThemeBlurTintColor: rgba(23, 23, 23, 0.85);
    --SmartThemeChatTintColor: rgba(23, 23, 23, 0.75);
    --SmartThemeUserMesBlurTintColor: rgba(0, 0, 0, 0.3);
    --SmartThemeBotMesBlurTintColor: rgba(60, 60, 60, 0.3);
    --SmartThemeBorderColor: rgba(255, 255, 255, 0.18);
    --SmartThemeBlurStrength: 10px;
    --blurStrength: 10px;
    --SmartThemeShadowWidth: 2px;
    --mainFontSize: 15px;

    --topBarBlockSize: 40px;
    --bottomFormBlockSize: 52px;
    --sheldWidth: 100vw;
    --nav-bar-width: 100vw;
    --avatar-base-width: 48px;
    --avatar-base-height: 48px;
    --avatar-base-border-radius-round: 50%;
    --avatar-base-border-radius: 10px;
    --animation-duration: 125ms;
$cssVars
  }

  * { box-sizing: border-box; }

  html, body {
    margin: 0;
    padding: 0;
    width: 100%;
    height: 100%;
    overflow: hidden;
    color: var(--SmartThemeBodyColor);
    font-size: var(--mainFontSize);
    font-family: "Noto Sans SC", -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
    background-color: #161821;
    background-size: cover;
    background-position: center;
    background-repeat: no-repeat;
  }

  /* Keep #bg1 transparent by default so body background-image in themes is never hidden */
  #bg1 {
    position: fixed;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    background-size: cover;
    background-position: center;
    background-repeat: no-repeat;
  }
  #bg_custom {
    display: none;
    position: fixed;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    background-size: cover;
    background-position: center;
  }

  .flex-container { display: flex; }
  .flex1 { flex: 1; }
  .justifySpaceBetween { justify-content: space-between; }
  .alignitemscenter { align-items: center; }

  #top-bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: var(--topBarBlockSize);
    z-index: 30;
    background-color: var(--SmartThemeBlurTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
    -webkit-backdrop-filter: blur(var(--SmartThemeBlurStrength));
    border-bottom: 1px solid var(--SmartThemeBorderColor);
  }

  #top-settings-holder {
    display: flex;
    align-items: center;
    justify-content: space-around;
    width: 100%;
    height: 100%;
    padding: 0 6px;
  }

  .drawer { display: flex; align-items: center; justify-content: center; }
  .drawer-toggle { display: flex; align-items: center; justify-content: center; }
  .drawer-icon {
    width: 30px;
    height: 30px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--SmartThemeBodyColor);
    opacity: 0.78;
    font-size: 16px;
    cursor: pointer;
  }

  #sheld {
    position: relative;
    z-index: 10;
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: var(--sheldWidth);
    height: 100vh;
    margin: 0 auto;
    padding-top: var(--topBarBlockSize);
  }

  #chat {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 8px 6px 14px;
    background-color: var(--SmartThemeChatTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
    -webkit-backdrop-filter: blur(var(--SmartThemeBlurStrength));
  }

  .mes {
    display: flex;
    flex-direction: row;
    align-items: flex-start;
    gap: 10px;
    padding: 10px 8px;
    margin-bottom: 6px;
    border-radius: 0;
    background-color: var(--SmartThemeBotMesBlurTintColor);
    border-bottom: 1px solid var(--SmartThemeBorderColor);
  }

  .mes[is_user="true"] {
    background-color: var(--SmartThemeUserMesBlurTintColor);
  }

  body.bubblechat .mes {
    border: 1px solid var(--SmartThemeBorderColor);
    border-radius: 12px;
    margin: 6px 4px;
    padding: 12px 10px;
  }

  body.documentstyle .mes {
    background-color: transparent;
    border: none;
    padding: 8px 12px;
  }

  .mesAvatarWrapper {
    display: flex;
    flex-direction: column;
    align-items: center;
    flex-shrink: 0;
    gap: 3px;
  }

  .avatar {
    width: var(--avatar-base-width);
    height: var(--avatar-base-height);
    border-radius: var(--avatar-base-border-radius-round);
    overflow: hidden;
    border: 1px solid var(--SmartThemeBorderColor);
    flex-shrink: 0;
  }

  body.square_avatars .avatar,
  body.big-avatars .avatar {
    border-radius: var(--avatar-base-border-radius);
  }

  .avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center;
    display: block;
  }

  .mesIDDisplay, .mes_timer, .tokenCounterDisplay {
    font-size: 10px;
    opacity: 0.45;
    line-height: 1.1;
  }

  .mes_block {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  .ch_name {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 5px;
    gap: 6px;
  }

  .name_text {
    font-weight: 700;
    font-size: 1em;
    color: var(--SmartThemeBodyColor);
  }

  .timestamp {
    font-size: 0.78em;
    opacity: 0.55;
    margin-left: 6px;
  }

  .mes_buttons {
    display: flex;
    align-items: center;
    gap: 8px;
    opacity: 0.55;
    font-size: 12px;
  }

  .mes_text {
    line-height: 1.6;
    word-break: break-word;
    text-shadow: 0 0 var(--SmartThemeShadowWidth) var(--SmartThemeShadowColor);
  }

  .mes_text p { margin: 0 0 8px; }
  .mes_text p:last-child { margin-bottom: 0; }

  .mes_text q {
    color: var(--SmartThemeQuoteColor);
    quotes: "“" "”";
  }

  .mes_text em {
    color: var(--SmartThemeEmColor);
    font-style: italic;
  }

  .mes_text u {
    color: var(--SmartThemeUnderlineColor);
  }

  .mes_text blockquote {
    margin: 8px 0;
    padding: 6px 10px;
    border-left: 3px solid var(--SmartThemeQuoteColor);
    background: rgba(255, 255, 255, 0.05);
    border-radius: 0 6px 6px 0;
  }

  .mes_text code {
    padding: 1px 5px;
    border-radius: 4px;
    background: rgba(0, 0, 0, 0.35);
    font-family: monospace;
    font-size: 0.9em;
  }

  #form_sheld {
    width: 100%;
    padding: 6px 8px 10px;
    background-color: var(--SmartThemeBlurTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
    -webkit-backdrop-filter: blur(var(--SmartThemeBlurStrength));
    border-top: 1px solid var(--SmartThemeBorderColor);
  }

  #qr--bar {
    display: flex;
    gap: 6px;
    margin-bottom: 6px;
    flex-wrap: wrap;
  }

  .qr--buttons {
    display: flex;
    gap: 6px;
  }

  .menu_button, .qr--button {
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 12px;
    border: 1px solid var(--SmartThemeBorderColor);
    background: var(--black30a);
    color: var(--SmartThemeBodyColor);
    cursor: pointer;
  }

  #send_form {
    width: 100%;
    margin: 0;
  }

  #nonQRFormItems {
    display: flex;
    align-items: center;
    gap: 6px;
    background: var(--black30a);
    border: 1px solid var(--SmartThemeBorderColor);
    border-radius: 8px;
    padding: 4px 8px;
  }

  #leftSendForm, #rightSendForm {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  #send_textarea {
    flex: 1;
    min-height: 36px;
    max-height: 80px;
    background: transparent;
    border: none;
    outline: none;
    color: var(--SmartThemeBodyColor);
    font-size: var(--mainFontSize);
    line-height: 36px;
    resize: none;
    padding: 0 4px;
  }

  .interactable {
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--SmartThemeBodyColor);
    opacity: 0.8;
    font-size: 15px;
    cursor: pointer;
  }
</style>
<style id="custom-theme-css">
$safeCustomCss
</style>
</head>
<body class="$bodyClassAttr">
  <div id="bg1"></div>
  <div id="bg_custom"></div>

  <div id="top-bar">
    <div id="top-settings-holder">
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-sliders closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-plug closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-font closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-book-atlas closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-image closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-cubes closedIcon"></div></div></div>
      <div class="drawer"><div class="drawer-toggle"><div class="drawer-icon fa-solid fa-address-card closedIcon"></div></div></div>
    </div>
  </div>

  <div id="sheld">
    <div id="chat">
      <div class="mes" mesid="0" is_user="false" is_system="false" ch_name="Seraphina">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$botAvatar" alt="AI"></div>
          <div class="mesIDDisplay">#0</div>
          <div class="tokenCounterDisplay">52t</div>
        </div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <span class="name_text">Seraphina</span>
              <span class="timestamp">15:30</span>
            </div>
            <div class="mes_buttons">
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis-vertical"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><em>窗外的雨声渐渐小了下来，壁炉里的木柴发出轻微的噼啪声。</em></p>
            <p><q>欢迎回到酒馆。这是一段用于预览主题配色、气泡边框、字体与背景图床效果的示例对话。</q></p>
            <blockquote>引用块与状态栏样式预览 · <code>code</code> 标签样式</blockquote>
          </div>
        </div>
      </div>

      <div class="mes" mesid="1" is_user="true" is_system="false" ch_name="You">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$userAvatar" alt="User"></div>
          <div class="mesIDDisplay">#1</div>
          <div class="tokenCounterDisplay">24t</div>
        </div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <span class="name_text">You</span>
              <span class="timestamp">15:31</span>
            </div>
            <div class="mes_buttons">
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis-vertical"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><q>这套美化的气泡边框、顶栏和输入框看起来怎么样？</q></p>
            <p><em>合上手中的笔记本，抬头打量着四周的装潢。</em></p>
          </div>
        </div>
      </div>

      <div class="mes last_mes" mesid="2" is_user="false" is_system="false" ch_name="Seraphina">
        <div class="mesAvatarWrapper">
          <div class="avatar"><img src="$botAvatar" alt="AI"></div>
          <div class="mesIDDisplay">#2</div>
          <div class="tokenCounterDisplay">36t</div>
        </div>
        <div class="mes_block">
          <div class="ch_name flex-container justifySpaceBetween">
            <div class="flex-container flex1 alignitemscenter">
              <span class="name_text">Seraphina</span>
              <span class="timestamp">15:32</span>
            </div>
            <div class="mes_buttons">
              <div class="mes_button mes_edit fa-solid fa-pencil"></div>
              <div class="mes_button extraMesButtonsHint fa-solid fa-ellipsis-vertical"></div>
            </div>
          </div>
          <div class="mes_text">
            <p><strong>加粗文本</strong>、<u>下划线</u> 与 <em>斜体动作</em> 均已渲染。<q>满意后再导入到 TT 酒馆使用即可。</q></p>
          </div>
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
      <form id="send_form">
        <div id="nonQRFormItems" class="flex-container">
          <div id="leftSendForm">
            <div id="options_button" class="fa-solid fa-bars interactable"></div>
            <div id="extensionsMenuButton" class="fa-solid fa-wand-magic-sparkles interactable"></div>
          </div>
          <textarea id="send_textarea" class="text_pole" placeholder="输入消息..."></textarea>
          <div id="rightSendForm">
            <div id="send_but_sheld">
              <div id="send_but" class="fa-solid fa-paper-plane interactable"></div>
            </div>
          </div>
        </div>
      </form>
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
        private const val EXTRA_SOURCE_ID = "source_id"

        fun open(context: Context, sourceId: String) {
            val intent = Intent(context, ThemePreviewActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            context.startActivity(intent)
        }
    }
}
