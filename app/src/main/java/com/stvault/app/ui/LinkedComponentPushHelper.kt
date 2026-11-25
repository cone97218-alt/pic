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
            val (linked, boundBgs) = withContext(Dispatchers.IO) {
                val comps = libRepo.getComponentsLinkedTo(hostType, hostId)
                val bgs = if (hostType == "theme") {
                    com.stvault.app.core.BackgroundRepository.getInstance(activity).getBoundBackgrounds(hostId)
                } else emptyList()
                comps to bgs
            }
            if ((linked.isEmpty() && boundBgs.isEmpty()) || activity.isFinishing || activity.isDestroyed) return@launch

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

            if (pushable.isNotEmpty() || boundBgs.isNotEmpty()) {
                val items = mutableListOf<PickerItem>()
                val preselected = HashSet<String>()
                boundBgs.forEachIndexed { idx, bg ->
                    val pid = "__BOUND_BG__${bg.id}"
                    val sub = if (idx == 0) "默认绑定背景 · 一并装入酒馆" else "绑定背景 #${idx + 1} · 一并装入酒馆"
                    items.add(PickerItem(pid, bg.name, sub))
                    preselected.add(pid)
                }
                for (c in pushable) {
                    items.add(
                        PickerItem(
                            id = c.id,
                            title = c.name,
                            subtitle = if (c.type == "qr") "快速回复" else "正则"
                        )
                    )
                    preselected.add(c.id)
                }
                val title = when {
                    boundBgs.isNotEmpty() && pushable.isEmpty() -> "一并推送绑定背景 (${boundBgs.size})"
                    helpers.isNotEmpty() -> "一并推送关联项（另有 ${helpers.size} 个助手脚本）"
                    else -> "一并推送关联项"
                }
                VaultSheets.multiSelect(
                    ctx = activity,
                    title = title,
                    items = items,
                    preselectedIds = preselected,
                    primaryConfirmText = "一并装入",
                    searchHint = "搜索..."
                ) { selectedIds ->
                    val chosenBgs = boundBgs.filter { "__BOUND_BG__${it.id}" in selectedIds }
                    val chosen = pushable.filter { it.id in selectedIds }
                    if (chosen.isNotEmpty() || chosenBgs.isNotEmpty()) {
                        activity.lifecycleScope.launch {
                            var count = 0
                            withContext(Dispatchers.IO) {
                                if (chosenBgs.isNotEmpty()) {
                                    val bgRepo = com.stvault.app.core.BackgroundRepository.getInstance(activity)
                                    for (bg in chosenBgs) {
                                        val bf = bgRepo.getFile(bg)
                                        if (bf.exists()) {
                                            val topTags = bg.categories.map { it.substringBefore('/').trim() }.filter { it.isNotBlank() && it != "待整理" }.distinct()
                                            bridge.enqueueOutbox("background", bg.name, bg.fileName, bf.readBytes(), topTags)
                                            count++
                                        }
                                    }
                                }
                                for (c in chosen) {
                                    val f = libRepo.getComponentFile(c)
                                    if (f.exists()) {
                                        bridge.enqueueOutbox(c.type, c.name, c.fileName, f.readBytes())
                                        count++
                                    }
                                }
                            }
                            if (count > 0) {
                                Toast.makeText(activity, "已一并加入 $count 个关联项", Toast.LENGTH_SHORT).show()
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
