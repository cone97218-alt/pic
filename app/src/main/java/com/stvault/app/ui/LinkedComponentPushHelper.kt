package com.stvault.app.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.stvault.app.core.ResourceLibraryRepository
import com.stvault.app.core.TavernBridgeManager
import com.stvault.app.model.ComponentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Supplementary linked-component push prompt:
 * When pushing a host resource (theme / character / preset / worldbook) to Tavern,
 * checks if the user manually linked external components to it:
 * - Lets the user check which pushable components (regex / qr) to enqueue together.
 * - Reminds the user if any linked Tavern Helper scripts exist (cannot be pushed directly).
 */
object LinkedComponentPushHelper {

    fun promptIfLinked(
        activity: AppCompatActivity,
        hostType: String,
        hostId: String,
        bridge: TavernBridgeManager,
        libRepo: ResourceLibraryRepository
    ) {
        if (hostId.isBlank() || hostType !in setOf("theme", "character", "worldbook", "preset")) return
        activity.lifecycleScope.launch {
            val linked = withContext(Dispatchers.IO) {
                libRepo.getComponentsLinkedTo(hostType, hostId)
            }
            if (linked.isEmpty() || activity.isFinishing || activity.isDestroyed) return@launch

            val pushable = linked.filter { it.type == "regex" || it.type == "qr" }
            val helpers = linked.filter { it.type == "helper" }

            fun showHelperReminder(list: List<ComponentRecord>) {
                if (list.isEmpty() || activity.isFinishing || activity.isDestroyed) return
                val names = list.joinToString("\n") { "· ${it.name}" }
                VaultSheets.info(
                    ctx = activity,
                    title = "关联助手脚本提醒",
                    message = "以下 ${list.size} 个关联的酒馆助手脚本暂不支持直推，请记得手动导出安装：\n\n$names",
                    primaryText = "知道了"
                )
            }

            if (pushable.isNotEmpty()) {
                val items = pushable.map { c ->
                    PickerItem(
                        id = c.id,
                        title = c.name,
                        subtitle = if (c.type == "qr") "快速回复" else "正则"
                    )
                }
                val title = if (helpers.isNotEmpty()) {
                    "一并推送关联组件（另有 ${helpers.size} 个助手脚本需手动安装）"
                } else {
                    "一并推送关联组件"
                }
                VaultSheets.multiSelect(
                    ctx = activity,
                    title = title,
                    items = items,
                    preselectedIds = pushable.map { it.id }.toSet(),
                    primaryConfirmText = "一并装入",
                    searchHint = "搜索关联组件..."
                ) { selectedIds ->
                    val chosen = pushable.filter { it.id in selectedIds }
                    if (chosen.isNotEmpty()) {
                        activity.lifecycleScope.launch {
                            var count = 0
                            withContext(Dispatchers.IO) {
                                for (c in chosen) {
                                    val f = libRepo.getComponentFile(c)
                                    if (f.exists()) {
                                        bridge.enqueueOutbox(c.type, c.name, c.fileName, f.readBytes())
                                        count++
                                    }
                                }
                            }
                            if (count > 0) {
                                Toast.makeText(activity, "已一并加入 $count 个关联组件", Toast.LENGTH_SHORT).show()
                            }
                            showHelperReminder(helpers)
                        }
                    } else {
                        showHelperReminder(helpers)
                    }
                }
            } else if (helpers.isNotEmpty()) {
                showHelperReminder(helpers)
            }
        }
    }
}
