package com.stvault.app.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BridgeOutboxItem(
    val id: String,
    val kind: String, // "theme" | "character" | "worldbook" | "preset" | "component"
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val queuedAt: Long,
    val tags: List<String> = emptyList()
)

data class BridgePullConflict(
    val id: String,
    val module: String, // "themes" | "characters" | "worldbooks" | "presets" | "components"
    val moduleLabel: String,
    val incomingName: String,
    val incomingFileName: String,
    val incomingFilePath: String,
    val matchedId: String,
    val matchedName: String,
    val reason: String
)

data class BridgeSyncLogEntry(
    val id: String,
    val timestamp: Long,
    val direction: String, // "push_to_tavern" | "pull_from_tavern" | "tag_sync"
    val action: String,    // "pushed" | "imported" | "overwritten" | "unchanged" | "conflict" | "tags_merged"
    val module: String,    // "themes" | "characters" | "worldbooks" | "presets" | "components"
    val moduleLabel: String,
    val name: String,
    val summary: String,
    val diffDetail: String = ""
)

data class TavernBridgeState(
    val running: Boolean = false,
    val port: Int = 0,
    val connected: Boolean = false,
    val lastContactAt: Long = 0L,
    val installedThemesCount: Int = 0,
    val installedCharsCount: Int = 0,
    val installedWbsCount: Int = 0,
    val installedPresetsCount: Int = 0,
    val outboxCount: Int = 0,
    val conflictCount: Int = 0,
    val logCount: Int = 0,
    val pushedToTavernCount: Int = 0,
    val pulledFromTavernCount: Int = 0,
    val overwrittenCount: Int = 0,
    val pendingPullKind: String = "",
    val lastPullSummary: String = "",
    val lastDiagLog: String = ""
)

class TavernBridgeManager private constructor(private val appContext: Context) {

    private val lock = Any()
    private val bridgeDir = File(appContext.filesDir, "vault/bridge").apply { mkdirs() }
    private val outboxDir = File(bridgeDir, "outbox").apply { mkdirs() }
    private val conflictDir = File(bridgeDir, "conflicts").apply { mkdirs() }
    private val inventoryFile = File(bridgeDir, "tavern_inventory.json")
    private val logsFile = File(bridgeDir, "sync_logs.json")

    private val installedThemes = HashSet<String>()
    private val installedChars = HashSet<String>()
    private val installedWbs = HashSet<String>()
    private val installedPresets = HashSet<String>()

    private val outboxMap = LinkedHashMap<String, BridgeOutboxItem>()
    private val conflictsMap = LinkedHashMap<String, BridgePullConflict>()
    private val syncLogs = ArrayList<BridgeSyncLogEntry>()

    private data class UploadSession(
        val id: String,
        val kind: String,
        val fileName: String,
        val totalBytes: Long,
        val tags: List<String> = emptyList(),
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()

    @Volatile
    private var server: TavernBridgeServer? = null

    @Volatile
    private var lastContactAt: Long = 0L

    @Volatile
    private var pendingPullKind: String = ""

    @Volatile
    private var lastPullSummary: String = ""

    @Volatile
    private var lastDiagLog: String = ""

    fun recordDiag(msg: String) {
        lastDiagLog = msg
        publishState()
    }

    private val _stateFlow = MutableStateFlow(TavernBridgeState())
    val stateFlow: StateFlow<TavernBridgeState> = _stateFlow.asStateFlow()

    init {
        loadInventory()
        loadLogs()
        publishState()
    }

    fun startServer(): Int {
        synchronized(lock) {
            val cur = server
            if (cur != null && cur.isRunning) {
                publishStateLocked()
                return cur.boundPort
            }
            val srv = TavernBridgeServer(this)
            val port = srv.start()
            if (port > 0) {
                server = srv
                publishStateLocked()
            }
            return port
        }
    }

    fun stopServer() {
        synchronized(lock) {
            server?.stop()
            server = null
            pendingPullKind = ""
            publishStateLocked()
        }
    }

    fun isRunning(): Boolean = server?.isRunning == true
    fun currentPort(): Int = server?.boundPort ?: 0

    // ==================== INSTALLED INVENTORY CHECKS ("已装") ====================

    private fun normKey(raw: String): String {
        return raw.trim()
            .substringAfterLast('/')
            .replace(Regex("\\.(json|png|css|html|htm|txt|js)$", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
    }

    fun isThemeInstalled(name: String, fileName: String = ""): Boolean = synchronized(lock) {
        if (installedThemes.isEmpty()) return false
        normKey(name) in installedThemes || (fileName.isNotEmpty() && normKey(fileName) in installedThemes)
    }

    fun isCharacterInstalled(name: String, fileName: String = ""): Boolean = synchronized(lock) {
        if (installedChars.isEmpty()) return false
        normKey(name) in installedChars || (fileName.isNotEmpty() && normKey(fileName) in installedChars)
    }

    fun isWorldBookInstalled(name: String, fileName: String = ""): Boolean = synchronized(lock) {
        if (installedWbs.isEmpty()) return false
        normKey(name) in installedWbs || (fileName.isNotEmpty() && normKey(fileName) in installedWbs)
    }

    fun isPresetInstalled(name: String, fileName: String = ""): Boolean = synchronized(lock) {
        if (installedPresets.isEmpty()) return false
        normKey(name) in installedPresets || (fileName.isNotEmpty() && normKey(fileName) in installedPresets)
    }

    // ==================== OUTBOX (酒馆匣 -> 酒馆) ====================

    fun enqueueOutbox(
        kind: String,
        name: String,
        fileName: String,
        bytes: ByteArray,
        tags: List<String> = emptyList()
    ): BridgeOutboxItem {
        val id = "out_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val safeFile = File(outboxDir, id)
        safeFile.writeBytes(bytes)
        val item = BridgeOutboxItem(
            id = id,
            kind = kind,
            name = name,
            fileName = fileName,
            sizeBytes = bytes.size.toLong(),
            queuedAt = System.currentTimeMillis(),
            tags = tags.filter { it.isNotBlank() }.distinct()
        )
        synchronized(lock) {
            outboxMap[id] = item
            publishStateLocked()
        }
        return item
    }

    fun listOutbox(): List<BridgeOutboxItem> = synchronized(lock) {
        outboxMap.values.toList()
    }

    fun removeOutbox(id: String): Boolean {
        synchronized(lock) {
            val removed = outboxMap.remove(id) != null
            try { File(outboxDir, id).delete() } catch (_: Exception) {}
            publishStateLocked()
            return removed
        }
    }

    fun clearOutbox() {
        synchronized(lock) {
            for (id in outboxMap.keys) {
                try { File(outboxDir, id).delete() } catch (_: Exception) {}
            }
            outboxMap.clear()
            publishStateLocked()
        }
    }

    // ==================== PULL REQUEST (酒馆 -> 酒馆匣) ====================

    fun requestPull(kind: String) {
        pendingPullKind = kind // "all" | "theme" | "character" | "worldbook" | "preset"
        lastPullSummary = "等待酒馆响应拉取..."
        publishState()
    }

    fun cancelPull() {
        pendingPullKind = ""
        publishState()
    }

    // ==================== SYNC LOGS (双向推送与代码变更日志) ====================

    fun listLogs(): List<BridgeSyncLogEntry> = synchronized(lock) {
        syncLogs.toList()
    }

    fun clearLogs() {
        synchronized(lock) {
            syncLogs.clear()
        }
        saveLogs()
        publishState()
    }

    private fun recordSyncLog(
        direction: String,
        action: String,
        module: String,
        name: String,
        summary: String,
        diffDetail: String = "",
        deferSave: Boolean = false
    ) {
        val modLabel = when (module) {
            "themes", "theme" -> "美化"
            "characters", "character" -> "角色卡"
            "worldbooks", "worldbook" -> "世界书"
            "presets", "preset" -> "预设"
            "backgrounds", "background" -> "背景"
            else -> "组件"
        }
        val entry = BridgeSyncLogEntry(
            id = "log_${UUID.randomUUID().toString().replace("-", "").take(10)}",
            timestamp = System.currentTimeMillis(),
            direction = direction,
            action = action,
            module = module,
            moduleLabel = modLabel,
            name = name,
            summary = summary,
            diffDetail = diffDetail
        )
        synchronized(lock) {
            syncLogs.add(0, entry)
            while (syncLogs.size > 300) {
                syncLogs.removeAt(syncLogs.lastIndex)
            }
        }
        if (!deferSave) {
            saveLogs()
        }
    }

    private fun loadLogs() {
        if (!logsFile.exists()) return
        try {
            val arr = JSONArray(logsFile.readText(Charsets.UTF_8))
            synchronized(lock) {
                syncLogs.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    syncLogs.add(
                        BridgeSyncLogEntry(
                            id = o.optString("id"),
                            timestamp = o.optLong("timestamp", 0L),
                            direction = o.optString("direction"),
                            action = o.optString("action"),
                            module = o.optString("module"),
                            moduleLabel = o.optString("moduleLabel"),
                            name = o.optString("name"),
                            summary = o.optString("summary"),
                            diffDetail = o.optString("diffDetail", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveLogs() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (e in syncLogs) {
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("timestamp", e.timestamp)
                    put("direction", e.direction)
                    put("action", e.action)
                    put("module", e.module)
                    put("moduleLabel", e.moduleLabel)
                    put("name", e.name)
                    put("summary", e.summary)
                    if (e.diffDetail.isNotEmpty()) put("diffDetail", e.diffDetail)
                })
            }
            arr.toString()
        }
        try {
            logsFile.writeText(json, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    // ==================== BIDIRECTIONAL TAG SYNC (st-theme-companion <-> 酒馆匣) ====================

    fun syncThemeTagsWithCompanion(args: JSONObject): JSONObject {
        val vaultRepo = VaultRepository.getInstance(appContext)
        val catManager = CategoryManager.getInstance(appContext)
        val incomingPathsArr = args.optJSONArray("tagPaths")
        val incomingThemeTagsObj = args.optJSONObject("themeTags")

        var addedCategoryPaths = 0
        var updatedThemesInApp = 0
        val existingCatSet = catManager.getCategories(CategoryManager.SCOPE_THEMES).toHashSet()

        if (incomingPathsArr != null) {
            for (i in 0 until incomingPathsArr.length()) {
                val rawPath = incomingPathsArr.optString(i, "").trim()
                val norm = catManager.normalizePath(rawPath)
                if (norm.isNotEmpty() && norm != "待整理" && norm !in existingCatSet) {
                    catManager.addCategory(CategoryManager.SCOPE_THEMES, norm)
                    existingCatSet.add(norm)
                    addedCategoryPaths++
                }
            }
        }

        if (incomingThemeTagsObj != null) {
            val sourcesByNorm = vaultRepo.getAllSources().groupBy { normKey(it.name) }
            val keys = incomingThemeTagsObj.keys()
            while (keys.hasNext()) {
                val tName = keys.next()
                val arr = incomingThemeTagsObj.optJSONArray(tName) ?: continue
                val incTags = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val p = catManager.normalizePath(arr.optString(i, ""))
                    if (p.isNotEmpty() && p != "待整理") {
                        if (p !in existingCatSet) {
                            catManager.addCategory(CategoryManager.SCOPE_THEMES, p)
                            existingCatSet.add(p)
                            addedCategoryPaths++
                        }
                        incTags.add(p)
                    }
                }
                if (incTags.isEmpty()) continue
                val matchedSources = sourcesByNorm[normKey(tName)].orEmpty()
                for (src in matchedSources) {
                    val merged = (src.categories + incTags).distinct()
                    if (merged.size != src.categories.size) {
                        vaultRepo.setSourceCategories(src.id, merged)
                        updatedThemesInApp++
                    }
                }
            }
        }

        val bgRepo = BackgroundRepository.getInstance(appContext)
        val incomingBgObj = args.optJSONObject("backgroundBindings")
        var updatedBgBindings = 0
        if (incomingBgObj != null) {
            val map = LinkedHashMap<String, String>()
            val it = incomingBgObj.keys()
            while (it.hasNext()) {
                val tName = it.next()
                val bgFile = incomingBgObj.optString(tName, "").trim()
                if (tName.isNotBlank() && bgFile.isNotBlank()) map[tName] = bgFile
            }
            updatedBgBindings = bgRepo.applyBindingsByThemeName(vaultRepo, map)
        }

        if (addedCategoryPaths > 0 || updatedThemesInApp > 0 || updatedBgBindings > 0) {
            recordSyncLog(
                direction = "tag_sync",
                action = "tags_merged",
                module = "themes",
                name = "美化标签与背景绑定联动",
                summary = "从 st-theme-companion 同步：新增分类 $addedCategoryPaths 个，更新标签 $updatedThemesInApp 项，背景绑定 $updatedBgBindings 项"
            )
        }

        val allVaultPaths = catManager.getCategories(CategoryManager.SCOPE_THEMES).filter { it != "待整理" }
        val vaultThemeTagsObj = JSONObject()
        for (src in vaultRepo.getAllSources()) {
            val cleanCats = src.categories.filter { it.isNotBlank() && it != "待整理" }
            if (cleanCats.isNotEmpty()) {
                vaultThemeTagsObj.put(src.name, JSONArray(cleanCats))
            }
        }
        val vaultBgBindingsObj = JSONObject()
        for ((tName, bgFile) in bgRepo.getBindingsByThemeName(vaultRepo)) {
            vaultBgBindingsObj.put(tName, bgFile)
        }

        lastDiagLog = "标签联动完成：更新 $updatedThemesInApp 项标签，$updatedBgBindings 项背景绑定"
        return JSONObject().apply {
            put("ok", true)
            put("addedCategoryPaths", addedCategoryPaths)
            put("updatedThemesInApp", updatedThemesInApp)
            put("updatedBgBindings", updatedBgBindings)
            put("backgroundBindings", vaultBgBindingsObj)
            put("vaultTagPaths", JSONArray(allVaultPaths))
            put("vaultThemeTags", vaultThemeTagsObj)
        }
    }

    // ==================== CONFLICTS (近似名处理) ====================

    fun listConflicts(): List<BridgePullConflict> = synchronized(lock) {
        conflictsMap.values.toList()
    }

    fun resolveAllConflictsKeepBoth(): Int {
        val ids = synchronized(lock) { conflictsMap.keys.toList() }
        var count = 0
        for (id in ids) {
            resolveConflict(id, "keep_both")
            count++
        }
        return count
    }

    fun resolveConflict(conflictId: String, action: String): String {
        val conflict = synchronized(lock) { conflictsMap.remove(conflictId) } ?: return "冲突已处理"
        val file = File(conflict.incomingFilePath)
        val bytes = if (file.exists()) file.readBytes() else ByteArray(0)
        val vaultRepo = VaultRepository.getInstance(appContext)
        val libRepo = ResourceLibraryRepository.getInstance(appContext)

        val msg = when (action) {
            "overwrite" -> {
                if (bytes.isEmpty()) "文件已失效"
                else {
                    val oldRaw = readExistingRawCode(conflict.module, conflict.matchedId, vaultRepo, libRepo)
                    val newRaw = extractIncomingRawCode(conflict.module, conflict.incomingFileName, bytes)
                    val diffText = VersionDiffEngine.diffRawTexts(oldRaw, newRaw, conflict.module)
                    overwriteMatchedItem(conflict.module, conflict.matchedId, conflict.incomingFileName, bytes, vaultRepo, libRepo)
                    recordSyncLog(
                        direction = "pull_from_tavern",
                        action = "overwritten",
                        module = conflict.module,
                        name = conflict.matchedName,
                        summary = "用酒馆近似新版「${conflict.incomingName}」覆盖原版",
                        diffDetail = diffText
                    )
                    "已用酒馆新版覆盖「${conflict.matchedName}」"
                }
            }
            "merge" -> {
                if (bytes.isEmpty()) "文件已失效"
                else {
                    val newId = importAndGetId(conflict.module, conflict.incomingFileName, bytes, vaultRepo, libRepo)
                    if (newId != null) {
                        val ids = listOf(conflict.matchedId, newId)
                        when (conflict.module) {
                            "characters" -> libRepo.mergeCharacterVersions(ids, conflict.matchedName)
                            "worldbooks" -> libRepo.mergeWorldBookVersions(ids, conflict.matchedName)
                            "presets" -> libRepo.mergePresetVersions(ids, conflict.matchedName)
                            "components" -> libRepo.mergeComponentVersions(ids, conflict.matchedName)
                            else -> Unit
                        }
                        recordSyncLog(
                            direction = "pull_from_tavern",
                            action = "imported",
                            module = conflict.module,
                            name = conflict.incomingName,
                            summary = "归为「${conflict.matchedName}」的新版本"
                        )
                        "已归为「${conflict.matchedName}」的新版本"
                    } else {
                        "已导入新版本"
                    }
                }
            }
            "keep_both" -> {
                if (bytes.isEmpty()) "文件已失效"
                else {
                    importAndGetId(conflict.module, conflict.incomingFileName, bytes, vaultRepo, libRepo)
                    recordSyncLog(
                        direction = "pull_from_tavern",
                        action = "imported",
                        module = conflict.module,
                        name = conflict.incomingName,
                        summary = "与「${conflict.matchedName}」保留两份入库"
                    )
                    "已保留两份"
                }
            }
            else -> "已跳过"
        }

        try { file.delete() } catch (_: Exception) {}
        publishState()
        return msg
    }

    // ==================== MCP TOOL DISPATCH ====================

    fun handleToolCall(toolName: String, args: JSONObject): JSONObject {
        lastContactAt = System.currentTimeMillis()
        val result = when (toolName) {
            "stvault_ping" -> JSONObject().apply {
                put("ok", true)
                put("app", "stvault")
                put("name", "酒馆匣")
                put("version", "1.8.0")
                put("outboxCount", synchronized(lock) { outboxMap.size })
                put("pullKind", pendingPullKind)
            }

            "stvault_sync_inventory" -> {
                updateInventoryFromJson(args)
                lastDiagLog = "已同步清单：美化 ${installedThemes.size} · 卡 ${installedChars.size} · 书 ${installedWbs.size} · 预设 ${installedPresets.size}"
                JSONObject().apply {
                    put("ok", true)
                    put("outboxCount", synchronized(lock) { outboxMap.size })
                    put("pullKind", pendingPullKind)
                }
            }

            "stvault_outbox_list" -> {
                val arr = JSONArray()
                for (item in listOutbox()) {
                    arr.put(JSONObject().apply {
                        put("id", item.id)
                        put("kind", item.kind)
                        put("name", item.name)
                        put("fileName", item.fileName)
                        put("bytes", item.sizeBytes)
                        if (item.tags.isNotEmpty()) {
                            put("tags", JSONArray(item.tags))
                        }
                    })
                }
                JSONObject().apply {
                    put("ok", true)
                    put("items", arr)
                }
            }

            "stvault_outbox_fetch" -> {
                val id = args.optString("id", "")
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val length = args.optInt("length", 384 * 1024).coerceIn(1024, 1024 * 1024)
                val f = File(outboxDir, id)
                if (!f.exists()) {
                    JSONObject().apply { put("ok", false); put("error", "item_not_found") }
                } else {
                    val all = f.readBytes()
                    val end = minOf(all.size, offset + length)
                    val slice = if (offset < all.size) all.copyOfRange(offset, end) else ByteArray(0)
                    JSONObject().apply {
                        put("ok", true)
                        put("id", id)
                        put("total", all.size)
                        put("offset", offset)
                        put("eof", end >= all.size)
                        put("data", Base64.encodeToString(slice, Base64.NO_WRAP))
                    }
                }
            }

            "stvault_outbox_ack" -> {
                val id = args.optString("id", "")
                val item = synchronized(lock) { outboxMap[id] }
                if (item != null) {
                    val wasInstalled = synchronized(lock) {
                        val nk = normKey(item.name)
                        val had = when (item.kind) {
                            "theme" -> nk in installedThemes
                            "character" -> nk in installedChars
                            "worldbook" -> nk in installedWbs
                            "preset" -> nk in installedPresets
                            else -> false
                        }
                        when (item.kind) {
                            "theme" -> installedThemes.add(nk)
                            "character" -> installedChars.add(nk)
                            "worldbook" -> installedWbs.add(nk)
                            "preset" -> installedPresets.add(nk)
                            else -> Unit
                        }
                        had
                    }
                    saveInventory()
                    val tagSuffix = if (item.tags.isNotEmpty()) "（标签：${item.tags.joinToString("、")}）" else ""
                    recordSyncLog(
                        direction = "push_to_tavern",
                        action = "pushed",
                        module = item.kind,
                        name = item.name,
                        summary = (if (wasInstalled) "已覆盖装入酒馆" else "已新装入酒馆") + tagSuffix
                    )
                }
                removeOutbox(id)
                JSONObject().apply { put("ok", true) }
            }

            "stvault_sync_theme_tags" -> syncThemeTagsWithCompanion(args)

            "stvault_pull_poll" -> {
                val kind = pendingPullKind
                if (kind.isNotEmpty()) pendingPullKind = ""
                JSONObject().apply {
                    put("ok", true)
                    put("pullKind", kind)
                }
            }

            "stvault_pull_done" -> {
                val msg = args.optString("summary", "同步完成")
                pendingPullKind = ""
                lastPullSummary = msg
                JSONObject().apply { put("ok", true) }
            }

            "stvault_push_begin" -> {
                val kind = args.optString("kind", "")
                val fileName = args.optString("fileName", "item.json")
                val totalBytes = args.optLong("bytes", 0L)
                val tags = readTagsArray(args.optJSONArray("tags"))
                val opId = "up_${UUID.randomUUID().toString().replace("-", "").take(10)}"
                uploadSessions[opId] = UploadSession(opId, kind, fileName, totalBytes, tags)
                JSONObject().apply {
                    put("ok", true)
                    put("opId", opId)
                }
            }

            "stvault_push_chunk" -> {
                val opId = args.optString("opId", "")
                val index = args.optInt("index", 0)
                val b64 = args.optString("data", "")
                val session = uploadSessions[opId]
                if (session == null) {
                    JSONObject().apply { put("ok", false); put("error", "invalid_session") }
                } else {
                    session.chunks[index] = Base64.decode(b64, Base64.DEFAULT)
                    JSONObject().apply { put("ok", true) }
                }
            }

            "stvault_push_end" -> {
                val opId = args.optString("opId", "")
                val session = uploadSessions.remove(opId)
                if (session == null) {
                    JSONObject().apply { put("ok", false); put("error", "invalid_session") }
                } else {
                    val baos = ByteArrayOutputStream()
                    for (k in session.chunks.keys.sorted()) {
                        session.chunks[k]?.let { baos.write(it) }
                    }
                    val status = ingestIncomingFromTavern(session.kind, session.fileName, baos.toByteArray(), incomingTags = session.tags)
                    lastDiagLog = "已接收：${session.fileName} ($status)"
                    JSONObject().apply {
                        put("ok", true)
                        put("status", status)
                    }
                }
            }

            "stvault_push_fast" -> {
                val kind = args.optString("kind", "")
                val fileName = args.optString("fileName", "item.json")
                val b64 = args.optString("data", "")
                val tags = readTagsArray(args.optJSONArray("tags"))
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val status = ingestIncomingFromTavern(kind, fileName, bytes, incomingTags = tags)
                lastDiagLog = "已接收：$fileName"
                JSONObject().apply {
                    put("ok", true)
                    put("status", status)
                }
            }

            "stvault_push_batch" -> {
                val arr = args.optJSONArray("items") ?: JSONArray()
                var hasWorldBook = false
                for (i in 0 until arr.length()) {
                    if (arr.optJSONObject(i)?.optString("kind", "") == "worldbook") {
                        hasWorldBook = true
                        break
                    }
                }
                val embeddedWbKeys = if (hasWorldBook) {
                    buildEmbeddedWorldBookKeys(ResourceLibraryRepository.getInstance(appContext))
                } else emptySet()
                var imported = 0
                var overwritten = 0
                var unchanged = 0
                var conflicts = 0
                var touchedThemes = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = o.optString("kind", "")
                    val fileName = o.optString("fileName", "item.json")
                    val b64 = o.optString("data", "")
                    val tags = readTagsArray(o.optJSONArray("tags"))
                    if (b64.isEmpty()) continue
                    try {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        if (kind == "theme") touchedThemes = true
                        when (ingestIncomingFromTavern(kind, fileName, bytes, embeddedWbKeys, deferThemeSave = true, incomingTags = tags)) {
                            "imported" -> imported++
                            "overwritten" -> overwritten++
                            "unchanged" -> unchanged++
                            "conflict" -> conflicts++
                            else -> Unit
                        }
                    } catch (_: Exception) {
                    }
                }
                if (touchedThemes) {
                    VaultRepository.getInstance(appContext).saveIndex()
                }
                saveLogs()
                val totalOk = imported + overwritten + unchanged + conflicts
                val parts = mutableListOf<String>()
                if (imported > 0) parts.add("新增 $imported")
                if (overwritten > 0) parts.add("代码变更覆盖 $overwritten")
                if (unchanged > 0) parts.add("无变化 $unchanged")
                if (conflicts > 0) parts.add("待确认冲突 $conflicts")
                lastDiagLog = if (parts.isNotEmpty()) "批量接收：" + parts.joinToString(" · ") else "批量接收完成"
                JSONObject().apply {
                    put("ok", true)
                    put("count", totalOk)
                }
            }

            else -> JSONObject().apply {
                put("ok", false)
                put("error", "unknown_tool: $toolName")
            }
        }
        publishState()
        return result
    }

    // ==================== SMART INGEST WITH OVERWRITE & SIMILARITY CONFLICT ====================

    private fun buildEmbeddedWorldBookKeys(libRepo: ResourceLibraryRepository): Set<String> {
        val keys = HashSet<String>()
        for (ch in libRepo.getAllCharacters()) {
            if (!ch.hasEmbeddedBook) continue
            keys.add(normKey(ch.name))
            val bookName = libRepo.parseCharacterDetail(ch)?.embeddedWorldBook?.name.orEmpty()
            if (bookName.isNotEmpty()) keys.add(normKey(bookName))
        }
        return keys
    }

    private fun readTagsArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun extractThemeXiaTags(bytes: ByteArray): List<String> {
        return try {
            val text = String(bytes, Charsets.UTF_8).trimStart()
            if (!text.startsWith("{")) return emptyList()
            val obj = JSONObject(text)
            val xia = obj.optJSONObject("_xia") ?: return emptyList()
            val out = mutableListOf<String>()
            val tagsArr = xia.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(i, "").trim()
                    if (t.isNotEmpty()) out.add(t)
                }
            }
            val cat = xia.optString("category", "").trim()
            if (cat.isNotEmpty()) out.add(cat)
            out.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeCategoriesIntoTheme(
        sourceId: String,
        rawTags: List<String>,
        vaultRepo: VaultRepository,
        deferSave: Boolean
    ) {
        if (rawTags.isEmpty()) return
        val catManager = CategoryManager.getInstance(appContext)
        val normTags = rawTags.map { catManager.normalizePath(it) }.filter { it.isNotEmpty() && it != "待整理" }
        if (normTags.isEmpty()) return
        for (p in normTags) {
            catManager.addCategory(CategoryManager.SCOPE_THEMES, p)
        }
        val cur = vaultRepo.getSourceById(sourceId) ?: return
        val merged = (cur.categories + normTags).distinct()
        if (merged.size != cur.categories.size) {
            vaultRepo.setSourceCategories(sourceId, merged)
            if (deferSave) {
                // setSourceCategories already saves index; safe either way
            }
        }
    }

    private fun readExistingRawCode(
        module: String,
        existingId: String,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository
    ): String {
        return try {
            when (module) {
                "themes" -> vaultRepo.readSourceRawText(existingId).second
                "characters" -> {
                    val rec = libRepo.getCharacterById(existingId) ?: return ""
                    val f = libRepo.getCharacterFile(rec)
                    if (!f.exists()) return ""
                    if (rec.isPng) CharacterCardParser.extractJsonFromPng(f.readBytes()).orEmpty()
                    else f.readText(Charsets.UTF_8)
                }
                "worldbooks" -> {
                    val rec = libRepo.getWorldBookById(existingId) ?: return ""
                    val f = libRepo.getWorldBookFile(rec)
                    if (f.exists()) f.readText(Charsets.UTF_8) else ""
                }
                "presets" -> {
                    val rec = libRepo.getPresetById(existingId) ?: return ""
                    val f = libRepo.getPresetFile(rec)
                    if (f.exists()) f.readText(Charsets.UTF_8) else ""
                }
                "components" -> {
                    val rec = libRepo.getComponentById(existingId) ?: return ""
                    libRepo.readComponentText(rec)
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractIncomingRawCode(module: String, fileName: String, bytes: ByteArray): String {
        return try {
            if (module == "characters" && !fileName.endsWith(".json", ignoreCase = true) && CharacterCardParser.isPng(bytes)) {
                CharacterCardParser.extractJsonFromPng(bytes).orEmpty()
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun ingestIncomingFromTavern(
        kind: String,
        fileName: String,
        bytes: ByteArray,
        precomputedEmbeddedWbKeys: Set<String>? = null,
        deferThemeSave: Boolean = false,
        incomingTags: List<String> = emptyList()
    ): String {
        if (bytes.isEmpty()) return "empty"
        val vaultRepo = VaultRepository.getInstance(appContext)
        val libRepo = ResourceLibraryRepository.getInstance(appContext)

        if (kind == "background") {
            val bgRepo = BackgroundRepository.getInstance(appContext)
            val (rec, isDup) = bgRepo.importBytes(bytes, fileName)
            val name = rec?.name ?: fileName.substringBeforeLast('.')
            if (!isDup && rec != null) {
                recordSyncLog(
                    direction = "pull_from_tavern",
                    action = "imported",
                    module = "backgrounds",
                    name = name,
                    summary = "从 TT 酒馆拉取背景图入库"
                )
                return "imported"
            }
            return "unchanged"
        }

        val module = when (kind) {
            "theme" -> "themes"
            "character" -> "characters"
            "worldbook" -> "worldbooks"
            "preset" -> "presets"
            "regex", "qr", "component" -> "components"
            else -> "themes"
        }

        val incomingDisplayName = extractDisplayName(module, fileName, bytes)
        val allIncomingTags = if (module == "themes") {
            (incomingTags + extractThemeXiaTags(bytes)).distinct()
        } else incomingTags

        // Avoid duplicate standalone worldbook if already embedded inside an existing Character Card
        if (module == "worldbooks") {
            val incKey = normKey(incomingDisplayName)
            val embeddedKeys = precomputedEmbeddedWbKeys ?: buildEmbeddedWorldBookKeys(libRepo)
            if (incKey in embeddedKeys) return "skipped_embedded"
        }
        val existingList: List<Pair<String, String>> = when (module) {
            "themes" -> vaultRepo.getAllSources().map { it.id to it.name }
            "characters" -> libRepo.getAllCharacters().map { it.id to it.name }
            "worldbooks" -> libRepo.getAllWorldBooks().map { it.id to it.name }
            "presets" -> libRepo.getAllPresets().map { it.id to it.name }
            "components" -> libRepo.getAllComponents().map { it.id to it.name }
            else -> emptyList()
        }

        // 1. Exact same name -> Compare code diff & Overwrite with latest code from Tavern!
        val exactMatch = existingList.firstOrNull { (_, n) ->
            normKey(n) == normKey(incomingDisplayName)
        }
        if (exactMatch != null) {
            val oldRaw = readExistingRawCode(module, exactMatch.first, vaultRepo, libRepo)
            val newRaw = extractIncomingRawCode(module, fileName, bytes)
            val diffText = VersionDiffEngine.diffRawTexts(oldRaw, newRaw, module)
            val codeChanged = (diffText != "代码完全一致，未发生变化")

            if (codeChanged) {
                overwriteMatchedItem(module, exactMatch.first, fileName, bytes, vaultRepo, libRepo, deferThemeSave)
            }
            if (module == "themes" && allIncomingTags.isNotEmpty()) {
                mergeCategoriesIntoTheme(exactMatch.first, allIncomingTags, vaultRepo, deferThemeSave)
            }
            val tagInfo = if (allIncomingTags.isNotEmpty()) " · 标签：${allIncomingTags.joinToString("、")}" else ""
            if (codeChanged) {
                recordSyncLog(
                    direction = "pull_from_tavern",
                    action = "overwritten",
                    module = module,
                    name = exactMatch.second,
                    summary = "从酒馆覆盖更新（代码有改动）$tagInfo",
                    diffDetail = diffText,
                    deferSave = deferThemeSave
                )
                return "overwritten"
            } else {
                return "unchanged"
            }
        }

        // 2. Near-duplicate name -> Record conflict for user decision (skip color/form sibling variants for themes!)
        var bestMatch: Pair<String, String>? = null
        var bestSim = 0.0
        for (pair in existingList) {
            if (module == "themes" && ThemeVariantGrouper.areSiblingVariants(incomingDisplayName, pair.second)) {
                continue
            }
            val sim = SimilarityDeduplicator.computeNameSimilarity(incomingDisplayName, pair.second)
            if (sim >= 0.68 && sim > bestSim) {
                bestSim = sim
                bestMatch = pair
            }
        }
        if (bestMatch != null) {
            val cid = "conf_${UUID.randomUUID().toString().replace("-", "").take(10)}"
            val tmpFile = File(conflictDir, cid)
            tmpFile.writeBytes(bytes)
            val pct = (bestSim * 100).toInt().coerceIn(68, 99)
            val modLabel = when (module) {
                "themes" -> "美化"
                "characters" -> "角色卡"
                "worldbooks" -> "世界书"
                "presets" -> "预设"
                else -> "组件"
            }
            val conflict = BridgePullConflict(
                id = cid,
                module = module,
                moduleLabel = modLabel,
                incomingName = incomingDisplayName,
                incomingFileName = fileName,
                incomingFilePath = tmpFile.absolutePath,
                matchedId = bestMatch.first,
                matchedName = bestMatch.second,
                reason = if (pct >= 99) "同主干近似版本" else "近似名称 ($pct%)"
            )
            synchronized(lock) {
                conflictsMap[cid] = conflict
            }
            recordSyncLog(
                direction = "pull_from_tavern",
                action = "conflict",
                module = module,
                name = incomingDisplayName,
                summary = "与「${bestMatch.second}」近似 ($pct%)，待确认处理",
                deferSave = deferThemeSave
            )
            return "conflict"
        }

        // 3. Brand new -> Import directly!
        importAndGetId(module, fileName, bytes, vaultRepo, libRepo, deferThemeSave, allIncomingTags)
        val tagInfo = if (allIncomingTags.isNotEmpty()) "（同步标签：${allIncomingTags.joinToString("、")}）" else ""
        recordSyncLog(
            direction = "pull_from_tavern",
            action = "imported",
            module = module,
            name = incomingDisplayName,
            summary = "从酒馆新拉取入库$tagInfo",
            deferSave = deferThemeSave
        )
        return "imported"
    }

    private fun extractDisplayName(module: String, fileName: String, bytes: ByteArray): String {
        if (module != "characters" || fileName.endsWith(".json", ignoreCase = true)) {
            try {
                val text = String(bytes, Charsets.UTF_8).trimStart()
                if (text.startsWith("{")) {
                    val obj = JSONObject(text)
                    val n = obj.optString("name", "").ifBlank {
                        obj.optJSONObject("data")?.optString("name", "").orEmpty()
                    }.trim()
                    if (n.isNotEmpty()) return n
                }
            } catch (_: Exception) {
            }
        }
        return fileName.substringAfterLast('/')
            .replace(Regex("\\.(json|png|css|html|htm|txt|js)$", RegexOption.IGNORE_CASE), "")
            .ifBlank { fileName }
    }

    private fun overwriteMatchedItem(
        module: String,
        existingId: String,
        fileName: String,
        bytes: ByteArray,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository,
        deferThemeSave: Boolean = false
    ) {
        when (module) {
            "themes" -> {
                val raw = String(bytes, Charsets.UTF_8)
                vaultRepo.overwriteSourceContent(existingId, raw, fileName, deferSave = deferThemeSave)
            }
            "characters" -> {
                val oldCats = libRepo.getCharacterById(existingId)?.categories ?: emptyList()
                val oldSeries = libRepo.getCharacterById(existingId)?.seriesKey ?: ""
                libRepo.deleteCharacter(existingId)
                val (rec, _) = libRepo.importCharacterFromBytes(bytes, fileName)
                if (rec != null) {
                    if (oldCats.isNotEmpty()) libRepo.setCharacterCategories(rec.id, oldCats)
                    if (oldSeries.isNotEmpty()) libRepo.mergeCharacterVersions(listOf(rec.id, rec.id), oldSeries)
                }
            }
            "worldbooks" -> {
                libRepo.deleteWorldBook(existingId)
                libRepo.importWorldBookFromText(String(bytes, Charsets.UTF_8), fileName)
            }
            "presets" -> {
                libRepo.deletePreset(existingId)
                libRepo.importPresetFromText(String(bytes, Charsets.UTF_8), fileName)
            }
            "components" -> {
                libRepo.deleteComponent(existingId)
                libRepo.importComponentFromText(String(bytes, Charsets.UTF_8), fileName)
            }
        }
    }

    private fun importAndGetId(
        module: String,
        fileName: String,
        bytes: ByteArray,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository,
        deferThemeSave: Boolean = false,
        initialTags: List<String> = emptyList()
    ): String? {
        return when (module) {
            "themes" -> {
                val catManager = CategoryManager.getInstance(appContext)
                val normCats = initialTags.map { catManager.normalizePath(it) }.filter { it.isNotEmpty() && it != "待整理" }.distinct()
                for (p in normCats) catManager.addCategory(CategoryManager.SCOPE_THEMES, p)
                vaultRepo.importRawThemeText(
                    rawText = String(bytes, Charsets.UTF_8),
                    fileName = fileName,
                    folderName = "酒馆同步",
                    deferSave = deferThemeSave,
                    initialCategories = normCats
                )
                null
            }
            "characters" -> libRepo.importCharacterFromBytes(bytes, fileName).first?.id
            "worldbooks" -> libRepo.importWorldBookFromText(String(bytes, Charsets.UTF_8), fileName).first?.id
            "presets" -> libRepo.importPresetFromText(String(bytes, Charsets.UTF_8), fileName).first?.id
            "components" -> libRepo.importComponentFromText(String(bytes, Charsets.UTF_8), fileName).first?.id
            else -> null
        }
    }

    private fun updateInventoryFromJson(args: JSONObject) {
        synchronized(lock) {
            fun readSet(key: String, target: HashSet<String>) {
                val arr = args.optJSONArray(key) ?: return
                target.clear()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotBlank()) target.add(normKey(v))
                }
            }
            readSet("themes", installedThemes)
            readSet("characters", installedChars)
            readSet("worldbooks", installedWbs)
            readSet("presets", installedPresets)
        }
        saveInventory()
    }

    private fun loadInventory() {
        if (!inventoryFile.exists()) return
        try {
            val root = JSONObject(inventoryFile.readText(Charsets.UTF_8))
            updateInventoryFromJson(root)
        } catch (_: Exception) {
        }
    }

    private fun saveInventory() {
        val json = synchronized(lock) {
            JSONObject().apply {
                put("themes", JSONArray(installedThemes.toList()))
                put("characters", JSONArray(installedChars.toList()))
                put("worldbooks", JSONArray(installedWbs.toList()))
                put("presets", JSONArray(installedPresets.toList()))
            }.toString()
        }
        try {
            inventoryFile.writeText(json, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun publishState() {
        synchronized(lock) { publishStateLocked() }
    }

    private fun publishStateLocked() {
        val running = server?.isRunning == true
        val port = server?.boundPort ?: 0
        val connected = running && (System.currentTimeMillis() - lastContactAt < 25_000L)
        val pushedCount = syncLogs.count { it.direction == "push_to_tavern" }
        val pulledCount = syncLogs.count { it.direction == "pull_from_tavern" && (it.action == "imported" || it.action == "overwritten") }
        val overCount = syncLogs.count { it.action == "overwritten" }
        _stateFlow.value = TavernBridgeState(
            running = running,
            port = port,
            connected = connected,
            lastContactAt = lastContactAt,
            installedThemesCount = installedThemes.size,
            installedCharsCount = installedChars.size,
            installedWbsCount = installedWbs.size,
            installedPresetsCount = installedPresetsCount(),
            outboxCount = outboxMap.size,
            conflictCount = conflictsMap.size,
            logCount = syncLogs.size,
            pushedToTavernCount = pushedCount,
            pulledFromTavernCount = pulledCount,
            overwrittenCount = overCount,
            pendingPullKind = pendingPullKind,
            lastPullSummary = lastPullSummary,
            lastDiagLog = lastDiagLog
        )
    }

    private fun installedPresetsCount(): Int = installedPresets.size

    companion object {
        @Volatile
        private var INSTANCE: TavernBridgeManager? = null

        fun getInstance(ctx: Context): TavernBridgeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TavernBridgeManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
