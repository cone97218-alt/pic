package com.stvault.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
        var isBubbleChat = false
        val cssVars = StringBuilder()

        val trimmed = rawText.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                customCss = json.optString("custom_css", "")
                isBubbleChat = json.optInt("chat_display", 0) == 1

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
        val bodyClass = if (isBubbleChat) "bubblechat" else ""

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>${title.replace("<", "&lt;")}</title>
<style>
  :root {
    --SmartThemeBodyColor: #dcdcd2;
    --SmartThemeEmColor: #95a5a6;
    --SmartThemeUnderlineColor: #bcbcbc;
    --SmartThemeQuoteColor: #e18a24;
    --SmartThemeShadowColor: rgba(0, 0, 0, 0.5);
    --SmartThemeBlurTintColor: rgba(23, 23, 23, 0.85);
    --SmartThemeChatTintColor: rgba(23, 23, 23, 0.72);
    --SmartThemeUserMesBlurTintColor: rgba(36, 40, 56, 0.75);
    --SmartThemeBotMesBlurTintColor: rgba(28, 30, 40, 0.75);
    --SmartThemeBorderColor: rgba(255, 255, 255, 0.14);
    --SmartThemeBlurStrength: 10px;
    --blurStrength: 10px;
    --mainFontSize: 15px;
    --avatar-base-width: 48px;
    --avatar-base-height: 48px;
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
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
    background-color: #14161e;
    background-size: cover;
    background-position: center;
  }
  #bg1, #bg_custom {
    position: fixed;
    inset: 0;
    z-index: 0;
    background-size: cover;
    background-position: center;
    background-image: linear-gradient(160deg, #1b1f2e 0%, #11131b 100%);
  }
  #top-bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: 44px;
    z-index: 20;
    display: flex;
    align-items: center;
    justify-content: space-around;
    background: var(--SmartThemeBlurTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
    border-bottom: 1px solid var(--SmartThemeBorderColor);
  }
  .drawer-icon {
    width: 28px;
    height: 28px;
    border-radius: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--SmartThemeBodyColor);
    opacity: 0.85;
    font-size: 14px;
  }
  #sheld {
    position: relative;
    z-index: 10;
    display: flex;
    flex-direction: column;
    height: 100vh;
    padding-top: 44px;
    max-width: 820px;
    margin: 0 auto;
  }
  #chat {
    flex: 1;
    overflow-y: auto;
    padding: 12px 10px 16px;
    background: var(--SmartThemeChatTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
  }
  .mes {
    display: flex;
    gap: 10px;
    padding: 12px 10px;
    margin-bottom: 10px;
    border-radius: 10px;
    border: 1px solid transparent;
    background: var(--SmartThemeBotMesBlurTintColor);
  }
  .mes[is_user="true"] {
    background: var(--SmartThemeUserMesBlurTintColor);
  }
  body.bubblechat .mes {
    border: 1px solid var(--SmartThemeBorderColor);
    border-radius: 14px;
    margin: 8px 4px;
  }
  .mesAvatarWrapper {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
  }
  .avatar {
    width: var(--avatar-base-width);
    height: var(--avatar-base-height);
    border-radius: 50%;
    overflow: hidden;
    border: 1px solid var(--SmartThemeBorderColor);
    background: linear-gradient(135deg, #6355e8, #34d399);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: bold;
    color: #fff;
    font-size: 16px;
  }
  .mes_block {
    flex: 1;
    min-width: 0;
  }
  .ch_name {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }
  .name_text {
    font-weight: 700;
    font-size: 1em;
    color: var(--SmartThemeBodyColor);
  }
  .timestamp {
    font-size: 0.78em;
    opacity: 0.6;
  }
  .mes_text {
    line-height: 1.65;
    word-break: break-word;
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
  .mes_text blockquote {
    margin: 8px 0;
    padding: 6px 10px;
    border-left: 3px solid var(--SmartThemeQuoteColor);
    background: rgba(255,255,255,0.05);
    border-radius: 0 6px 6px 0;
  }
  #form_sheld {
    padding: 8px 10px 12px;
    background: var(--SmartThemeBlurTintColor);
    backdrop-filter: blur(var(--SmartThemeBlurStrength));
    border-top: 1px solid var(--SmartThemeBorderColor);
  }
  #send_form {
    display: flex;
    align-items: center;
    gap: 8px;
    background: rgba(0, 0, 0, 0.25);
    border: 1px solid var(--SmartThemeBorderColor);
    border-radius: 10px;
    padding: 6px 10px;
  }
  #send_textarea {
    flex: 1;
    background: transparent;
    border: none;
    outline: none;
    color: var(--SmartThemeBodyColor);
    font-size: 14px;
    resize: none;
    height: 36px;
    line-height: 36px;
  }
  #send_but, #options_button {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--SmartThemeBodyColor);
    opacity: 0.85;
  }
</style>
<style id="custom-theme-css">
$safeCustomCss
</style>
</head>
<body class="$bodyClass">
  <div id="bg1"></div>
  <div id="bg_custom"></div>

  <div id="top-bar">
    <div id="top-settings-holder" style="display:flex;width:100%;justify-content:space-around;">
      <div class="drawer-icon">≡</div>
      <div class="drawer-icon">⚙</div>
      <div class="drawer-icon">✦</div>
      <div class="drawer-icon">Aa</div>
      <div class="drawer-icon">◎</div>
    </div>
  </div>

  <div id="sheld">
    <div id="chat">
      <div class="mes" is_user="false" is_system="false">
        <div class="mesAvatarWrapper">
          <div class="avatar">A</div>
        </div>
        <div class="mes_block">
          <div class="ch_name">
            <span class="name_text">Seraphina</span>
            <span class="timestamp">15:30</span>
          </div>
          <div class="mes_text">
            <p><em>窗外的雨声渐渐小了下来，壁炉里的木柴发出轻微的噼啪声。</em></p>
            <p><q>欢迎回到酒馆。这是一段用于预览主题配色、气泡边框、字体与背景图床效果的示例对话。</q></p>
            <blockquote>状态栏与引用块样式预览 · 离线缓存图片会自动加载</blockquote>
          </div>
        </div>
      </div>

      <div class="mes" is_user="true" is_system="false">
        <div class="mesAvatarWrapper">
          <div class="avatar" style="background:linear-gradient(135deg,#f59e0b,#ef4444);">U</div>
        </div>
        <div class="mes_block">
          <div class="ch_name">
            <span class="name_text">You</span>
            <span class="timestamp">15:31</span>
          </div>
          <div class="mes_text">
            <p><q>这套美化的文字对比度和气泡边框看起来怎么样？</q></p>
            <p><em>轻轻合上手中的笔记本，抬头打量着四周的装潢。</em></p>
          </div>
        </div>
      </div>

      <div class="mes" is_user="false" is_system="false">
        <div class="mesAvatarWrapper">
          <div class="avatar">A</div>
        </div>
        <div class="mes_block">
          <div class="ch_name">
            <span class="name_text">Seraphina</span>
            <span class="timestamp">15:32</span>
          </div>
          <div class="mes_text">
            <p><strong>加粗文本效果</strong> 与 <em>斜体动作描写</em> 均已就绪。<q>如果觉得满意，再导入到 TT 酒馆里使用即可。</q></p>
          </div>
        </div>
      </div>
    </div>

    <div id="form_sheld">
      <div id="send_form">
        <div id="options_button">≡</div>
        <textarea id="send_textarea" placeholder="输入消息预览输入框样式..."></textarea>
        <div id="send_but">➤</div>
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
        private const val EXTRA_SOURCE_ID = "source_id"

        fun open(context: Context, sourceId: String) {
            val intent = Intent(context, ThemePreviewActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            context.startActivity(intent)
        }
    }
}
