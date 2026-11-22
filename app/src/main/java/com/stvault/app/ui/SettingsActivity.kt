package com.stvault.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.stvault.app.R
import com.stvault.app.core.CategoryManager
import com.stvault.app.core.VaultRepository
import com.stvault.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var catManager: CategoryManager
    private val prefs by lazy { getSharedPreferences("st_vault_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        catManager = CategoryManager.getInstance(this)
        binding.btnCloseSettings.setOnClickListener { finish() }

        // 1. Detail UI Style
        val style = prefs.getString("detail_ui_style", "editorial") ?: "editorial"
        if (style == "card") binding.rbStyleCard.isChecked = true
        else binding.rbStyleEditorial.isChecked = true

        binding.rgDetailStyle.setOnCheckedChangeListener { _, checkedId ->
            val next = if (checkedId == R.id.rbStyleCard) "card" else "editorial"
            prefs.edit().putString("detail_ui_style", next).apply()
        }

        // 1b. Bottom Bar Style (iOS Flat vs Floating Pill)
        val bottomStyle = prefs.getString("bottom_bar_style", "ios_flat") ?: "ios_flat"
        if (bottomStyle == "floating_pill") binding.rbBottomFloating.isChecked = true
        else binding.rbBottomIosFlat.isChecked = true

        binding.rgBottomBarStyle.setOnCheckedChangeListener { _, checkedId ->
            val next = if (checkedId == R.id.rbBottomFloating) "floating_pill" else "ios_flat"
            prefs.edit().putString("bottom_bar_style", next).apply()
        }

        // 1c. Character Greeting Render Mode + Theme Linkage
        val vaultRepo = VaultRepository.getInstance(this)
        val greetMode = prefs.getString("greeting_render_mode", "plain") ?: "plain"
        if (greetMode == "theme") binding.rbGreetingTheme.isChecked = true
        else binding.rbGreetingPlain.isChecked = true

        fun updateGreetingThemeLabel() {
            val tid = prefs.getString("greeting_theme_id", "").orEmpty()
            val tname = prefs.getString("greeting_theme_name", "").orEmpty()
            binding.btnPickGreetingTheme.text = if (tid.isNotEmpty() && tname.isNotEmpty()) {
                "联动美化：$tname"
            } else {
                "选择联动美化（未选择）"
            }
            binding.btnPickGreetingTheme.visibility =
                if (binding.rbGreetingTheme.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateGreetingThemeLabel()

        binding.rgGreetingRenderMode.setOnCheckedChangeListener { _, checkedId ->
            val next = if (checkedId == R.id.rbGreetingTheme) "theme" else "plain"
            prefs.edit().putString("greeting_render_mode", next).apply()
            updateGreetingThemeLabel()
        }
        binding.btnPickGreetingTheme.setOnClickListener {
            val themes = vaultRepo.searchSources("")
            if (themes.isEmpty()) {
                toast("美化库暂无可用美化，请先导入美化")
                return@setOnClickListener
            }
            val curId = prefs.getString("greeting_theme_id", "").orEmpty()
            val items = themes.map { PickerItem(it.id, it.name.substringAfterLast('/'), it.folderName) }
            VaultSheets.searchablePicker(
                ctx = this,
                title = "选择联动美化主题",
                items = items,
                selectedId = curId,
                searchHint = "搜索美化名称..."
            ) { picked ->
                prefs.edit()
                    .putString("greeting_theme_id", picked.id)
                    .putString("greeting_theme_name", picked.title)
                    .apply()
                updateGreetingThemeLabel()
                toast("已联动美化：${picked.title}")
            }
        }

        // 2. Version Auto-Grouping
        binding.swGroupVersions.isChecked = prefs.getBoolean("group_versions", true)
        binding.swGroupVersions.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("group_versions", isChecked).apply()
        }

        // 3. Hierarchical Categories
        binding.btnManageThemeCats.setOnClickListener {
            showCategoryManagerDialog(CategoryManager.SCOPE_THEMES, "美化分类")
        }
        binding.btnManageCharCats.setOnClickListener {
            showCategoryManagerDialog(CategoryManager.SCOPE_CHARACTERS, "角色卡分类")
        }

        // 4. Night Mode
        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rbThemeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }
        binding.rgNightMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rbThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putInt("night_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun showCategoryManagerDialog(scope: String, title: String) {
        val cats = catManager.getCategories(scope)
        if (cats.isEmpty()) {
            showAddCategoryDialog(scope, title)
            return
        }
        val items = cats.map { PickerItem(id = it, title = catManager.formatIndentedLabel(it), subtitle = "点击删除") }
        VaultSheets.searchablePicker(
            ctx = this,
            title = title,
            items = items,
            searchHint = "搜索分类...",
            headerAction = "+ 新建" to { showAddCategoryDialog(scope, title) }
        ) { picked ->
            VaultSheets.confirm(
                ctx = this,
                title = "删除分类",
                message = "确定删除「${picked.id}」及其子分类？",
                confirmText = "删除",
                destructive = true
            ) {
                catManager.deleteCategory(scope, picked.id)
                toast("已删除分类")
                showCategoryManagerDialog(scope, title)
            }
        }
    }

    private fun showAddCategoryDialog(scope: String, title: String) {
        VaultSheets.input(
            ctx = this,
            title = "新建$title（1~3级）",
            message = "用斜杠 / 分隔层级，例如 古风/仙侠/师尊，父级分类会自动创建。",
            hint = "例如：古风/仙侠",
            confirmText = "添加"
        ) { raw ->
            val added = catManager.addCategory(scope, raw)
            if (added != null) {
                toast("已添加：$added")
                showCategoryManagerDialog(scope, title)
            } else {
                toast("请输入有效的分类名称")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
