package com.stvault.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AppLogEntry(
    val id: String,
    val timestamp: Long,
    val category: String, // "bridge" | "inbox" | "library"
    val action: String,   // "覆盖" | "拉取" | "装入" | "标签" | "入库" | "编辑" | "合并" | "查重" | "删除"
    val title: String,
    val detail: String = "",
    val source: String = "" // e.g. "屯屯网页" | "DC 频道" | "本地扫描" | "酒馆互联"
)

/**
 * Unified persistent activity log for the entire app.
 * Combines app-wide operations (inbox imports, edits, dedup/merges, deletes) with
 * TavernBridgeManager's sync & code-diff logs so users have one single place to audit everything.
 */
object AppLogManager {

    const val CAT_BRIDGE = "bridge"
    const val CAT_INBOX = "inbox"
    const val CAT_LIBRARY = "library"

    private const val FILE_NAME = "app_activity_logs.json"
    private const val MAX_ENTRIES = 400
    private val lock = Any()
    private var cached: MutableList<AppLogEntry>? = null

    private fun logFile(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    private fun loadLocked(ctx: Context): MutableList<AppLogEntry> {
        cached?.let { return it }
        val list = mutableListOf<AppLogEntry>()
        try {
            val f = logFile(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        AppLogEntry(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            timestamp = o.optLong("ts", 0L),
                            category = o.optString("cat", CAT_LIBRARY),
                            action = o.optString("act", ""),
                            title = o.optString("title", ""),
                            detail = o.optString("detail", ""),
                            source = o.optString("src", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        cached = list
        return list
    }

    private fun saveLocked(ctx: Context, list: List<AppLogEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("ts", e.timestamp)
                    put("cat", e.category)
                    put("act", e.action)
                    put("title", e.title)
                    if (e.detail.isNotEmpty()) put("detail", e.detail)
                    if (e.source.isNotEmpty()) put("src", e.source)
                })
            }
            logFile(ctx).writeText(arr.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun record(
        ctx: Context,
        category: String,
        action: String,
        title: String,
        detail: String = "",
        source: String = ""
    ) {
        synchronized(lock) {
            val list = loadLocked(ctx)
            list.add(
                0,
                AppLogEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    category = category,
                    action = action,
                    title = title,
                    detail = detail.take(2500),
                    source = source
                )
            )
            while (list.size > MAX_ENTRIES) list.removeAt(list.lastIndex)
            saveLocked(ctx, list)
        }
    }

    /** Returns merged list of app logs + Tavern Bridge sync logs, sorted newest first. */
    fun getAllMerged(ctx: Context): List<AppLogEntry> {
        val local = synchronized(lock) { loadLocked(ctx).toList() }
        val bridgeLogs = try {
            TavernBridgeManager.getInstance(ctx).listLogs().map { b ->
                val act = when (b.action) {
                    "overwritten" -> "覆盖"
                    "imported", "added" -> "拉取"
                    "pushed" -> "装入"
                    "conflict" -> "冲突"
                    "tags_merged", "tags" -> "标签"
                    "unchanged" -> "未变"
                    else -> b.action
                }
                val detail = buildString {
                    if (b.summary.isNotEmpty()) append(b.summary)
                    if (b.diffDetail.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(b.diffDetail)
                    }
                }
                AppLogEntry(
                    id = "bridge_${b.id}",
                    timestamp = b.timestamp,
                    category = CAT_BRIDGE,
                    action = act,
                    title = b.name,
                    detail = detail,
                    source = "酒馆互联"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        return (local + bridgeLogs).sortedByDescending { it.timestamp }
    }

    fun clearAll(ctx: Context, includeBridge: Boolean = true) {
        synchronized(lock) {
            cached = mutableListOf()
            saveLocked(ctx, emptyList())
        }
        if (includeBridge) {
            try {
                TavernBridgeManager.getInstance(ctx).clearLogs()
            } catch (_: Exception) {
            }
        }
    }
}
