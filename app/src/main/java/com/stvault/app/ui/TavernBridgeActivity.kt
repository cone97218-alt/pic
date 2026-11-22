package com.stvault.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.stvault.app.R
import com.stvault.app.core.BridgeOutboxItem
import com.stvault.app.core.BridgePullConflict
import com.stvault.app.core.TavernBridgeManager
import com.stvault.app.core.TavernBridgeState
import com.stvault.app.databinding.ActivityTavernBridgeBinding
import com.stvault.app.service.TavernBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TavernBridgeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTavernBridgeBinding
    private lateinit var bridge: TavernBridgeManager
    private var guideExpanded = false

    private val exportHelperScriptLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(HELPER_SCRIPT_JSON.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                toast(if (ok) "热更新脚本已导出，可导入 TT 酒馆助手" else "导出失败")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTavernBridgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bridge = TavernBridgeManager.getInstance(this)

        binding.btnBridgeClose.setOnClickListener { finish() }
        binding.btnBridgeToggle.setOnClickListener {
            if (bridge.isRunning()) {
                TavernBridgeService.stop(this)
                bridge.stopServer()
                toast("已断开本机通道")
            } else {
                lifecycleScope.launch {
                    val port = withContext(Dispatchers.IO) { bridge.startServer() }
                    try { TavernBridgeService.start(this@TavernBridgeActivity) } catch (_: Exception) {}
                    if (port > 0) {
                        toast("通道已开启（端口 $port），请切回 TT 酒馆点击「连接酒馆匣」")
                    } else {
                        toast("端口 8796-8800 均被占用，开启失败")
                    }
                }
            }
        }
        binding.btnToggleGuide.setOnClickListener {
            guideExpanded = !guideExpanded
            binding.layoutGuideBody.visibility = if (guideExpanded) View.VISIBLE else View.GONE
            binding.tvGuideChevron.text = if (guideExpanded) "收起 ▴" else "展开 ▾"
        }
        binding.btnExportHelperScript.setOnClickListener {
            exportHelperScriptLauncher.launch("酒馆匣互联_热更新.json")
        }
        binding.btnCopyHelperCode.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("helper_code", HELPER_LOADER_JS))
            toast("已复制热更新代码，可在酒馆助手新建脚本粘贴")
        }
        binding.btnCopyExtUrl.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("ext_url", EXT_REPO_URL))
            toast("已复制扩展地址，可前往 TT 酒馆粘贴安装")
        }
        binding.btnOutboxClear.setOnClickListener {
            bridge.clearOutbox()
            toast("已清空待装入队列")
        }
        binding.btnConflictsKeepAll.setOnClickListener {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { bridge.resolveAllConflictsKeepBoth() }
                toast("已全部保留入库（$n 项）")
            }
        }
        binding.btnBridgePull.setOnClickListener { showPullSheet() }

        lifecycleScope.launch {
            bridge.stateFlow.collectLatest { st ->
                renderState(st)
            }
        }
    }

    private fun showPullSheet() {
        if (!bridge.isRunning()) {
            TavernBridgeService.start(this)
        }
        VaultSheets.actionList(
            ctx = this,
            title = "从酒馆拉取更新",
            actions = listOf(
                SheetAction("全部拉取", "同步美化、角色卡、世界书与预设，同名自动覆盖") {
                    bridge.requestPull("all")
                    toast("已发出拉取请求，请切回 TT 酒馆等待自动传输")
                },
                SheetAction("仅拉取美化", "同步 TT 中的全部主题美化") {
                    bridge.requestPull("theme")
                    toast("已请求拉取美化，切回 TT 酒馆即可自动传输")
                },
                SheetAction("仅拉取角色卡", "同步 TT 中的角色卡") {
                    bridge.requestPull("character")
                    toast("已请求拉取角色卡，切回 TT 酒馆即可自动传输")
                },
                SheetAction("仅拉取世界书", "同步 TT 中的世界书") {
                    bridge.requestPull("worldbook")
                    toast("已请求拉取世界书，切回 TT 酒馆即可自动传输")
                },
                SheetAction("仅拉取预设", "同步 TT 中的对话预设") {
                    bridge.requestPull("preset")
                    toast("已请求拉取预设，切回 TT 酒馆即可自动传输")
                }
            )
        )
    }

    private fun renderState(st: TavernBridgeState) {
        val dotColor = when {
            st.connected -> Color.parseColor("#16A34A")
            st.running -> Color.parseColor("#D97706")
            else -> ContextCompat.getColor(this, R.color.text_muted)
        }
        binding.dotBridgeStatus.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }

        binding.tvBridgeStatusTitle.text = when {
            st.connected -> "已连接 · 端口 ${st.port}"
            st.running -> "监听中 · 端口 ${st.port}"
            else -> "通道未开启"
        }
        binding.btnBridgeToggle.text = if (st.running) "断开连接" else "开启连接"
        val diagSuffix = if (st.lastDiagLog.isNotEmpty()) "\n最近通讯：${st.lastDiagLog}" else ""
        binding.tvBridgeSub.text = when {
            st.connected -> "TT 酒馆扩展在线，支持即时装入与双向同步。$diagSuffix"
            st.running -> "本机通道 127.0.0.1:${st.port} 就绪，切回 TT 酒馆点「连接酒馆匣」即可。$diagSuffix"
            else -> "仅在手机本机回环通信，不上云、无需账号。"
        }

        val totalInv = st.installedThemesCount + st.installedCharsCount + st.installedWbsCount + st.installedPresetsCount
        binding.tvBridgeInventory.text = if (totalInv > 0) {
            "美化 ${st.installedThemesCount}  ·  角色卡 ${st.installedCharsCount}  ·  世界书 ${st.installedWbsCount}  ·  预设 ${st.installedPresetsCount}"
        } else {
            "尚未同步 · 连接后自动在列表中标记「已装」"
        }

        if (st.pendingPullKind.isNotEmpty() || st.lastPullSummary.isNotEmpty()) {
            binding.tvBridgePullStatus.visibility = View.VISIBLE
            binding.tvBridgePullStatus.text = if (st.pendingPullKind.isNotEmpty()) {
                "待酒馆响应：正在等待 TT 扩展回传（切回 TT 酒馆自动开始）..."
            } else {
                st.lastPullSummary
            }
        } else {
            binding.tvBridgePullStatus.visibility = View.GONE
        }

        renderConflicts(bridge.listConflicts())
        renderOutbox(bridge.listOutbox())
    }

    private fun renderConflicts(list: List<BridgePullConflict>) {
        if (list.isEmpty()) {
            binding.sectionBridgeConflicts.visibility = View.GONE
            return
        }
        binding.sectionBridgeConflicts.visibility = View.VISIBLE
        binding.tvConflictsHeader.text = "近似版本待确认 (${list.size})"
        binding.containerBridgeConflicts.removeAllViews()

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val col = { res: Int -> ContextCompat.getColor(this, res) }

        for (c in list) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                radius = dp(12).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(col(R.color.surface))
                strokeWidth = dp(1)
                strokeColor = col(R.color.border)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            box.addView(TextView(this).apply {
                text = "${c.moduleLabel} · ${c.reason}"
                setTextColor(col(R.color.text_muted))
                textSize = 11.5f
            })
            box.addView(TextView(this).apply {
                text = "酒馆拉取：${c.incomingName}\n匣内已有：${c.matchedName}"
                setTextColor(col(R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            fun addAct(label: String, action: String, bold: Boolean = false) {
                row.addView(TextView(this).apply {
                    text = label
                    setTextColor(if (bold) col(R.color.text_primary) else col(R.color.text_muted))
                    textSize = 12.5f
                    if (bold) setTypeface(null, Typeface.BOLD)
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    setOnClickListener {
                        lifecycleScope.launch {
                            val msg = withContext(Dispatchers.IO) { bridge.resolveConflict(c.id, action) }
                            toast(msg)
                        }
                    }
                })
            }
            addAct("跳过", "skip")
            addAct("保留两份", "keep_both")
            if (c.module != "themes") addAct("归为新版", "merge", bold = true)
            addAct("覆盖原版", "overwrite", bold = true)

            box.addView(row)
            card.addView(box)
            binding.containerBridgeConflicts.addView(card)
        }
    }

    private fun renderOutbox(list: List<BridgeOutboxItem>) {
        if (list.isEmpty()) {
            binding.sectionBridgeOutbox.visibility = View.GONE
            return
        }
        binding.sectionBridgeOutbox.visibility = View.VISIBLE
        binding.tvOutboxHeader.text = "待装入队列 (${list.size})"
        binding.containerBridgeOutbox.removeAllViews()

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val col = { res: Int -> ContextCompat.getColor(this, res) }

        for (item in list) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                radius = dp(10).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(col(R.color.surface))
                strokeWidth = dp(1)
                strokeColor = col(R.color.border)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val kindLabel = when (item.kind) {
                "theme" -> "美化"
                "character" -> "角色卡"
                "worldbook" -> "世界书"
                "preset" -> "预设"
                else -> "组件"
            }
            row.addView(TextView(this).apply {
                text = kindLabel
                setTextColor(col(R.color.text_muted))
                setBackgroundResource(R.drawable.bg_pill_accent)
                textSize = 10.5f
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            row.addView(TextView(this).apply {
                text = "  ${item.name}"
                setTextColor(col(R.color.text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "移除"
                setTextColor(col(R.color.text_muted))
                textSize = 12f
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener { bridge.removeOutbox(item.id) }
            })
            card.addView(row)
            binding.containerBridgeOutbox.addView(card)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXT_REPO_URL = "https://github.com/cone97218-alt/pic"

        val HELPER_LOADER_JS = """
(async () => {
  const w = (() => {
    try { if (window.__TAURITAVERN__ || window.SillyTavern) return window; } catch (_) {}
    try { if (window.parent && (window.parent.__TAURITAVERN__ || window.parent.SillyTavern)) return window.parent; } catch (_) {}
    try { if (window.top && (window.top.__TAURITAVERN__ || window.top.SillyTavern)) return window.top; } catch (_) {}
    return window;
  })();
  const toast = (type, msg) => { try { w.toastr && w.toastr[type] && w.toastr[type](msg, '酒馆匣互联'); } catch (_) {} };
  const urls = [
    'https://raw.githubusercontent.com/cone97218-alt/pic/main/index.js?t=' + Date.now(),
    'https://cdn.jsdelivr.net/gh/cone97218-alt/pic@main/index.js?t=' + Date.now()
  ];
  let lastErr = '';
  for (const url of urls) {
    try {
      const res = await (w.fetch || fetch)(url, { cache: 'no-store' });
      if (!res.ok) { lastErr = 'HTTP ' + res.status; continue; }
      const code = await res.text();
      if (code && code.includes('getMainWin')) {
        (new w.Function(code))();
        toast('success', '已加载，请在 TT 扩展面板中打开「酒馆匣互联」');
        return;
      }
      lastErr = '远端缓存尚未刷新';
    } catch (e) { lastErr = String(e && e.message ? e.message : e); }
  }
  toast('error', '加载失败（' + lastErr + '），请检查网络或改用原生扩展');
})();
        """.trimIndent()

        val HELPER_SCRIPT_JSON: String
            get() = org.json.JSONObject().apply {
                put("type", "script")
                put("id", "stvault-bridge-hotloader")
                put("name", "酒馆匣互联（热更新）")
                put("enabled", true)
                put("info", "自动加载最新版酒馆匣互联面板（在 TT 扩展面板中打开），免手动更新。")
                put("content", HELPER_LOADER_JS)
                put("button", org.json.JSONObject().apply {
                    put("enabled", false)
                    put("buttons", org.json.JSONArray())
                })
                put("data", org.json.JSONObject())
            }.toString(2)

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, TavernBridgeActivity::class.java))
        }
    }
}
