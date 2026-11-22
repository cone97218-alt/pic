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
    val queuedAt: Long
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

    private val installedThemes = HashSet<String>()
    private val installedChars = HashSet<String>()
    private val installedWbs = HashSet<String>()
    private val installedPresets = HashSet<String>()

    private val outboxMap = LinkedHashMap<String, BridgeOutboxItem>()
    private val conflictsMap = LinkedHashMap<String, BridgePullConflict>()

    private data class UploadSession(
        val id: String,
        val kind: String,
        val fileName: String,
        val totalBytes: Long,
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

    fun enqueueOutbox(kind: String, name: String, fileName: String, bytes: ByteArray): BridgeOutboxItem {
        val id = "out_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val safeFile = File(outboxDir, id)
        safeFile.writeBytes(bytes)
        val item = BridgeOutboxItem(
            id = id,
            kind = kind,
            name = name,
            fileName = fileName,
            sizeBytes = bytes.size.toLong(),
            queuedAt = System.currentTimeMillis()
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

    // ==================== CONFLICTS (近似名处理) ====================

    fun listConflicts(): List<BridgePullConflict> = synchronized(lock) {
        conflictsMap.values.toList()
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
                    overwriteMatchedItem(conflict.module, conflict.matchedId, conflict.incomingFileName, bytes, vaultRepo, libRepo)
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
                val length = args.optInt("length", 48 * 1024).coerceIn(1024, 256 * 1024)
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
                    synchronized(lock) {
                        val nk = normKey(item.name)
                        when (item.kind) {
                            "theme" -> installedThemes.add(nk)
                            "character" -> installedChars.add(nk)
                            "worldbook" -> installedWbs.add(nk)
                            "preset" -> installedPresets.add(nk)
                            else -> Unit
                        }
                    }
                    saveInventory()
                }
                removeOutbox(id)
                JSONObject().apply { put("ok", true) }
            }

            "stvault_pull_poll" -> {
                val kind = pendingPullKind
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
                val opId = "up_${UUID.randomUUID().toString().replace("-", "").take(10)}"
                uploadSessions[opId] = UploadSession(opId, kind, fileName, totalBytes)
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
                    val status = ingestIncomingFromTavern(session.kind, session.fileName, baos.toByteArray())
                    lastDiagLog = "已接收：${session.fileName} ($status)"
                    JSONObject().apply {
                        put("ok", true)
                        put("status", status)
                    }
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

    private fun ingestIncomingFromTavern(kind: String, fileName: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        val vaultRepo = VaultRepository.getInstance(appContext)
        val libRepo = ResourceLibraryRepository.getInstance(appContext)

        val module = when (kind) {
            "theme" -> "themes"
            "character" -> "characters"
            "worldbook" -> "worldbooks"
            "preset" -> "presets"
            "regex", "qr", "component" -> "components"
            else -> "themes"
        }

        val incomingDisplayName = extractDisplayName(module, fileName, bytes)

        // Avoid duplicate standalone worldbook if already embedded inside an existing Character Card
        if (module == "worldbooks") {
            val incKey = normKey(incomingDisplayName)
            val embeddedHit = libRepo.getAllCharacters().any { ch ->
                if (!ch.hasEmbeddedBook) false
                else {
                    val bookName = libRepo.parseCharacterDetail(ch)?.embeddedWorldBook?.name.orEmpty()
                    (bookName.isNotEmpty() && normKey(bookName) == incKey) || normKey(ch.name) == incKey
                }
            }
            if (embeddedHit) return "skipped_embedded"
        }
        val existingList: List<Pair<String, String>> = when (module) {
            "themes" -> vaultRepo.getAllSources().map { it.id to it.name }
            "characters" -> libRepo.getAllCharacters().map { it.id to it.name }
            "worldbooks" -> libRepo.getAllWorldBooks().map { it.id to it.name }
            "presets" -> libRepo.getAllPresets().map { it.id to it.name }
            "components" -> libRepo.getAllComponents().map { it.id to it.name }
            else -> emptyList()
        }

        // 1. Exact same name -> Overwrite with latest code from Tavern!
        val exactMatch = existingList.firstOrNull { (_, n) ->
            normKey(n) == normKey(incomingDisplayName)
        }
        if (exactMatch != null) {
            overwriteMatchedItem(module, exactMatch.first, fileName, bytes, vaultRepo, libRepo)
            return "overwritten"
        }

        // 2. Near-duplicate name -> Record conflict for user decision!
        var bestMatch: Pair<String, String>? = null
        var bestSim = 0.0
        for (pair in existingList) {
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
            return "conflict"
        }

        // 3. Brand new -> Import directly!
        importAndGetId(module, fileName, bytes, vaultRepo, libRepo)
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
        libRepo: ResourceLibraryRepository
    ) {
        when (module) {
            "themes" -> {
                val raw = String(bytes, Charsets.UTF_8)
                vaultRepo.overwriteSourceContent(existingId, raw, fileName)
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
        libRepo: ResourceLibraryRepository
    ): String? {
        return when (module) {
            "themes" -> {
                vaultRepo.importRawThemeText(String(bytes, Charsets.UTF_8), fileName, "酒馆同步")
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
        _stateFlow.value = TavernBridgeState(
            running = running,
            port = port,
            connected = connected,
            lastContactAt = lastContactAt,
            installedThemesCount = installedThemes.size,
            installedCharsCount = installedChars.size,
            installedWbsCount = installedWbs.size,
            installedPresetsCount = installedPresets.size,
            outboxCount = outboxMap.size,
            conflictCount = conflictsMap.size,
            pendingPullKind = pendingPullKind,
            lastPullSummary = lastPullSummary,
            lastDiagLog = lastDiagLog
        )
    }

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
