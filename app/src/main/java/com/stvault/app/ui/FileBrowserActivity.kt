package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stvault.app.R
import com.stvault.app.databinding.ActivityFileBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Built-in minimalist File Browser powered by Android's official MANAGE_EXTERNAL_STORAGE permission.
 * Lets users browse /storage/emulated/0 freely with quick-jump shortcuts, sorting, search,
 * multi-file selection (including .zip), folder selection, and a 1-tap System Picker fallback.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var adapter: FileListAdapter

    private var mode: String = MODE_FILES
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var allEntries: List<File> = emptyList()
    private var sortByNewest: Boolean = true
    private var searchQuery: String = ""
    private val selectedPaths = LinkedHashSet<String>()

    private val prefs by lazy { getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkPermissionAndLoad()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILES
        binding.tvFbTitle.text = intent.getStringExtra(EXTRA_TITLE)
            ?: if (mode == MODE_FOLDER) "选择目录" else "选择文件"

        val lastPath = prefs.getString("fb_last_dir", null)
        if (!lastPath.isNullOrBlank()) {
            val f = File(lastPath)
            if (f.exists() && f.isDirectory && f.canRead()) currentDir = f
        }

        binding.btnFbClose.setOnClickListener { finish() }
        binding.btnFbSystemPicker.setOnClickListener {
            setResult(RESULT_USE_SYSTEM_PICKER)
            finish()
        }
        binding.btnFbFallbackSys.setOnClickListener {
            setResult(RESULT_USE_SYSTEM_PICKER)
            finish()
        }
        binding.btnFbGrantPerm.setOnClickListener { requestAllFilesPermission() }

        binding.btnFbSort.setOnClickListener {
            sortByNewest = !sortByNewest
            binding.btnFbSort.text = if (sortByNewest) "最新" else "名称"
            applyFilterAndSort()
        }

        binding.btnFbUp.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                navigateTo(parent)
            } else {
                toast("已是根目录")
            }
        }

        if (mode == MODE_FILES) {
            binding.btnFbSelectAll.visibility = View.VISIBLE
            binding.btnFbSelectAll.setOnClickListener { toggleSelectAllVisible() }
            binding.btnFbConfirm.text = "导入所选"
        } else {
            binding.btnFbSelectAll.visibility = View.GONE
            binding.btnFbConfirm.text = "选择当前目录"
        }

        binding.btnFbConfirm.setOnClickListener {
            if (mode == MODE_FOLDER) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_FOLDER, currentDir.absolutePath))
                finish()
            } else {
                if (selectedPaths.isEmpty()) {
                    toast("请先勾选要导入的文件")
                    return@setOnClickListener
                }
                setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_RESULT_FILES, ArrayList(selectedPaths)))
                finish()
            }
        }

        binding.etFbSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilterAndSort()
            }
        })

        adapter = FileListAdapter(
            mode = mode,
            onFolderClick = { dir -> navigateTo(dir) },
            onFileToggle = { file ->
                val p = file.absolutePath
                if (p in selectedPaths) selectedPaths.remove(p) else selectedPaths.add(p)
                adapter.updateSelected(selectedPaths)
                updateFooter()
            }
        )
        binding.rvFbList.layoutManager = LinearLayoutManager(this)
        binding.rvFbList.adapter = adapter

        buildQuickJumpBar()
        checkPermissionAndLoad()
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                permLauncher.launch(intent)
            } catch (_: Exception) {
                permLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun checkPermissionAndLoad() {
        if (hasAllFilesPermission()) {
            binding.boxFbPermission.visibility = View.GONE
            binding.boxFbContent.visibility = View.VISIBLE
            navigateTo(currentDir)
        } else {
            binding.boxFbPermission.visibility = View.VISIBLE
            binding.boxFbContent.visibility = View.GONE
        }
    }

    private fun buildQuickJumpBar() {
        val bar = binding.fbQuickBar
        bar.removeAllViews()
        val extRoot = Environment.getExternalStorageDirectory()
        val candidates = mutableListOf(
            "内部存储" to extRoot,
            "Download" to File(extRoot, "Download"),
            "Documents" to File(extRoot, "Documents"),
            "QQ下载" to File(extRoot, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv"),
            "QQ旧目录" to File(extRoot, "Tencent/QQfile_recv"),
            "微信下载" to File(extRoot, "Download/WeiXin"),
            "Telegram" to File(extRoot, "Download/Telegram"),
            "分身存储" to File("/storage/emulated/999")
        )

        // Also detect any readable external SD cards under /storage/
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in setOf("emulated", "self") && f.canRead() && !f.listFiles().isNullOrEmpty()) {
                candidates.add("SD卡 (${f.name})" to f)
            }
        }

        val d = resources.displayMetrics.density
        for ((label, dir) in candidates) {
            if (!dir.exists() || !dir.isDirectory || !dir.canRead() || dir.listFiles() == null) continue
            val pill = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@FileBrowserActivity, R.color.text_primary))
                textSize = 12f
                setBackgroundResource(R.drawable.bg_pill_accent)
                setPadding((11 * d).toInt(), (5 * d).toInt(), (11 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
                setOnClickListener { navigateTo(dir) }
            }
            bar.addView(pill)
        }
    }

    private fun navigateTo(dir: File) {
        currentDir = dir
        prefs.edit().putString("fb_last_dir", dir.absolutePath).apply()
        binding.tvFbPath.text = dir.absolutePath
        binding.etFbSearch.setText("")
        searchQuery = ""
        updateFooter()

        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            }
            allEntries = files
            applyFilterAndSort()
        }
    }

    private fun applyFilterAndSort() {
        val q = searchQuery.lowercase()
        val filtered = if (q.isEmpty()) allEntries else allEntries.filter { it.name.lowercase().contains(q) }
        val dirs = filtered.filter { it.isDirectory }
        val files = filtered.filter { it.isFile }

        val sortedDirs = if (sortByNewest) dirs.sortedByDescending { it.lastModified() } else dirs.sortedBy { it.name.lowercase() }
        val sortedFiles = if (sortByNewest) files.sortedByDescending { it.lastModified() } else files.sortedBy { it.name.lowercase() }

        val combined = if (mode == MODE_FOLDER) sortedDirs else (sortedDirs + sortedFiles)
        adapter.submitList(combined, selectedPaths)
        updateFooter()
    }

    private fun toggleSelectAllVisible() {
        val visibleFiles = adapter.getVisibleFiles()
        if (visibleFiles.isEmpty()) return
        val allSelected = visibleFiles.all { it.absolutePath in selectedPaths }
        if (allSelected) {
            visibleFiles.forEach { selectedPaths.remove(it.absolutePath) }
        } else {
            visibleFiles.forEach { selectedPaths.add(it.absolutePath) }
        }
        adapter.updateSelected(selectedPaths)
        updateFooter()
    }

    private fun updateFooter() {
        if (mode == MODE_FOLDER) {
            binding.tvFbFooterInfo.text = "当前：${currentDir.name}"
        } else {
            binding.tvFbFooterInfo.text = "已选 ${selectedPaths.size} 个文件"
            binding.btnFbConfirm.text = if (selectedPaths.isEmpty()) "导入所选" else "导入 (${selectedPaths.size})"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private class FileListAdapter(
        private val mode: String,
        private val onFolderClick: (File) -> Unit,
        private val onFileToggle: (File) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.VH>() {

        private var items: List<File> = emptyList()
        private var selected: Set<String> = emptySet()
        private val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submitList(list: List<File>, sel: Set<String>) {
            items = list
            selected = sel.toSet()
            notifyDataSetChanged()
        }

        fun updateSelected(sel: Set<String>) {
            selected = sel.toSet()
            notifyDataSetChanged()
        }

        fun getVisibleFiles(): List<File> = items.filter { it.isFile }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            val ctx = holder.row.context
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            fun col(id: Int) = ContextCompat.getColor(ctx, id)

            val isSel = f.isFile && f.absolutePath in selected
            holder.row.removeAllViews()
            holder.row.setPadding(dp(12), dp(11), dp(12), dp(11))
            if (isSel) holder.row.setBackgroundResource(R.drawable.bg_input) else holder.row.background = null

            val ext = f.name.substringAfterLast('.', "").uppercase()
            val badgeText = when {
                f.isDirectory -> "目录"
                ext in setOf("JSON", "PNG", "CSS", "JS", "ZIP", "TXT", "HTML") -> ext
                else -> "文件"
            }

            holder.row.addView(TextView(ctx).apply {
                text = if (isSel) "✓" else badgeText
                setTextColor(col(if (isSel) R.color.on_accent else R.color.text_muted))
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundResource(if (isSel) R.drawable.bg_dock_item_active else R.drawable.bg_pill_accent)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(26)).apply { marginEnd = dp(10) }
            })

            val colBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            colBox.addView(TextView(ctx).apply {
                text = f.name
                setTextColor(col(R.color.text_primary))
                textSize = 13.5f
                if (f.isDirectory) setTypeface(null, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            val sub = if (f.isDirectory) {
                df.format(Date(f.lastModified()))
            } else {
                "${formatSize(f.length())}  ·  ${df.format(Date(f.lastModified()))}"
            }
            colBox.addView(TextView(ctx).apply {
                text = sub
                setTextColor(col(R.color.text_muted))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                }
            })
            holder.row.addView(colBox)

            holder.row.setOnClickListener {
                if (f.isDirectory) onFolderClick(f)
                else if (mode == MODE_FILES) onFileToggle(f)
            }
        }

        private fun formatSize(b: Long): String {
            if (b < 1024) return "$b B"
            if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0)
            return String.format("%.2f MB", b / (1024.0 * 1024.0))
        }

        override fun getItemCount(): Int = items.size
        class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
    }

    companion object {
        const val MODE_FILES = "files"
        const val MODE_FOLDER = "folder"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RESULT_FILES = "result_files"
        const val EXTRA_RESULT_FOLDER = "result_folder"
        const val RESULT_USE_SYSTEM_PICKER = 9001

        fun buildIntent(ctx: Context, mode: String, title: String): Intent {
            return Intent(ctx, FileBrowserActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
}
