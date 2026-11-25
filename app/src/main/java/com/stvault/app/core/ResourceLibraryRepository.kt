package com.stvault.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stvault.app.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ResourceLibraryRepository private constructor(private val appContext: Context) {

    private val lock = Any()
    private val vaultRepo = VaultRepository.getInstance(appContext)
    private val vaultDir: File = vaultRepo.getVaultRootDir()

    val charactersDir: File = File(vaultDir, "characters")
    private val charThumbsDir: File = File(charactersDir, "thumbs")
    private val charsIndexFile: File = File(charactersDir, "index.json")

    val worldBooksDir: File = File(vaultDir, "worldbooks")
    private val booksIndexFile: File = File(worldBooksDir, "index.json")

    val presetsDir: File = File(vaultDir, "presets")
    private val presetsIndexFile: File = File(presetsDir, "index.json")

    val componentsDir: File = File(vaultDir, "components")
    private val compsIndexFile: File = File(componentsDir, "index.json")

    private val charactersMap = LinkedHashMap<String, CharacterRecord>()
    private val worldBooksMap = LinkedHashMap<String, WorldBookRecord>()
    private val presetsMap = LinkedHashMap<String, PresetRecord>()
    private val componentsMap = LinkedHashMap<String, ComponentRecord>()

    private val _countsFlow = MutableStateFlow(LibraryCounts())
    val countsFlow: StateFlow<LibraryCounts> = _countsFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    init {
        scope.launch {
            charactersDir.mkdirs()
            charThumbsDir.mkdirs()
            worldBooksDir.mkdirs()
            presetsDir.mkdirs()
            componentsDir.mkdirs()
            loadAllIndexes()
        }
    }

    private fun sha256Short(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).take(10).joinToString("") { "%02x".format(it) }
    }

    private fun loadAllIndexes() {
        synchronized(lock) {
            loadCharactersLocked()
            loadWorldBooksLocked()
            loadPresetsLocked()
            loadComponentsLocked()
            refreshCountsLocked()
        }
    }

    private fun refreshCountsLocked() {
        val charBytes = charactersMap.values.sumOf { it.fileSize }
        val bookBytes = worldBooksMap.values.sumOf { it.fileSize }
        val presetBytes = presetsMap.values.sumOf { it.fileSize }
        val compBytes = componentsMap.values.sumOf { it.fileSize }
        val assetBytes = vaultRepo.statsFlow.value.totalBytes
        _countsFlow.value = LibraryCounts(
            themes = vaultRepo.statsFlow.value.totalSources,
            characters = charactersMap.size,
            worldBooks = worldBooksMap.size,
            presets = presetsMap.size,
            components = componentsMap.size,
            totalStorageBytes = charBytes + bookBytes + presetBytes + compBytes + assetBytes
        )
    }

    fun refreshCounts() {
        synchronized(lock) { refreshCountsLocked() }
    }

    // ==================== CHARACTERS MODULE ====================

    private fun loadCharactersLocked() {
        if (!charsIndexFile.exists()) return
        try {
            val arr = JSONArray(charsIndexFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cName = o.optString("name", "未命名角色")
                val cFileName = o.optString("fileName", "")
                val (autoSeries, autoVer) = VersionGrouper.extractSeriesAndVersion(cName.ifBlank { cFileName })
                val rec = CharacterRecord(
                    id = o.optString("id"),
                    name = cName,
                    fileName = cFileName,
                    creator = o.optString("creator", ""),
                    tags = readJsonStringList(o.optJSONArray("tags")),
                    categories = readJsonStringList(o.optJSONArray("categories")),
                    spec = o.optString("spec", "v2"),
                    isPng = o.optBoolean("isPng", true),
                    cardFile = o.optString("cardFile", ""),
                    thumbFile = o.optString("thumbFile", ""),
                    fileSize = o.optLong("fileSize", 0L),
                    hasEmbeddedBook = o.optBoolean("hasEmbeddedBook", false),
                    embeddedBookEntryCount = o.optInt("embeddedBookEntryCount", 0),
                    embeddedRegexCount = o.optInt("embeddedRegexCount", 0),
                    embeddedHelperCount = o.optInt("embeddedHelperCount", 0),
                    urls = readJsonStringList(o.optJSONArray("urls")),
                    contentHash = o.optString("contentHash", ""),
                    sourceType = o.optString("sourceType", "local"),
                    sourceRef = o.optString("sourceRef", ""),
                    seriesKey = o.optString("seriesKey", "").ifBlank { autoSeries },
                    seriesName = o.optString("seriesName", ""),
                    versionTag = o.optString("versionTag", "").ifBlank { autoVer },
                    addedAt = o.optLong("addedAt", 0L)
                )
                if (rec.id.isNotEmpty()) charactersMap[rec.id] = rec
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCharactersIndex() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (c in charactersMap.values) {
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("fileName", c.fileName)
                    put("creator", c.creator)
                    put("tags", JSONArray(c.tags))
                    put("categories", JSONArray(c.categories))
                    put("spec", c.spec)
                    put("isPng", c.isPng)
                    put("cardFile", c.cardFile)
                    put("thumbFile", c.thumbFile)
                    put("fileSize", c.fileSize)
                    put("hasEmbeddedBook", c.hasEmbeddedBook)
                    put("embeddedBookEntryCount", c.embeddedBookEntryCount)
                    put("embeddedRegexCount", c.embeddedRegexCount)
                    put("embeddedHelperCount", c.embeddedHelperCount)
                    put("urls", JSONArray(c.urls))
                    put("contentHash", c.contentHash)
                    put("sourceType", c.sourceType)
                    put("sourceRef", c.sourceRef)
                    put("seriesKey", c.seriesKey)
                    if (c.seriesName.isNotEmpty()) put("seriesName", c.seriesName)
                    put("versionTag", c.versionTag)
                    put("addedAt", c.addedAt)
                })
            }
            refreshCountsLocked()
            arr.toString()
        }
        atomicWrite(charsIndexFile, json)
    }

    fun getCharacterFile(rec: CharacterRecord): File = File(charactersDir, rec.cardFile)
    fun getCharacterThumbFile(rec: CharacterRecord): File? {
        if (rec.thumbFile.isBlank()) return null
        val f = File(charThumbsDir, rec.thumbFile)
        return if (f.exists()) f else null
    }

    fun getCharacterById(id: String): CharacterRecord? = synchronized(lock) { charactersMap[id] }

    fun parseCharacterDetail(rec: CharacterRecord): ParsedCharacterCard? {
        val file = getCharacterFile(rec)
        if (!file.exists()) return null
        return try {
            val rawJson = if (rec.isPng) {
                CharacterCardParser.extractJsonFromPng(file.readBytes()) ?: return null
            } else {
                file.readText(Charsets.UTF_8)
            }
            CharacterCardParser.parseJson(rawJson, rec.name)
        } catch (_: Exception) {
            null
        }
    }

    fun setCharacterCategories(charId: String, categories: List<String>) {
        synchronized(lock) {
            val cur = charactersMap[charId] ?: return
            charactersMap[charId] = cur.copy(categories = categories.distinct())
        }
        saveCharactersIndex()
    }

    fun getCharactersInSeries(seriesKey: String): List<CharacterRecord> = synchronized(lock) {
        if (seriesKey.isBlank()) return emptyList()
        charactersMap.values.filter { it.seriesKey == seriesKey }
    }

    fun deltaUpdateCharacterSeriesCategories(seriesKey: String, added: Set<String>, removed: Set<String>): Int {
        if (seriesKey.isBlank()) return 0
        var count = 0
        synchronized(lock) {
            for ((id, cur) in charactersMap) {
                if (cur.seriesKey == seriesKey) {
                    val next = ((cur.categories - removed) + added).distinct()
                    charactersMap[id] = cur.copy(categories = next)
                    count++
                }
            }
        }
        if (count > 0) saveCharactersIndex()
        return count
    }

    fun batchDeltaUpdateCharacterCategories(ids: List<String>, added: Set<String>, removed: Set<String>) {
        if (ids.isEmpty() || (added.isEmpty() && removed.isEmpty())) return
        synchronized(lock) {
            for (id in ids) {
                val cur = charactersMap[id] ?: continue
                val next = ((cur.categories - removed) + added).distinct()
                charactersMap[id] = cur.copy(categories = next)
            }
        }
        saveCharactersIndex()
    }

    fun renameCharacterCategoryPrefix(oldPath: String, newPath: String): Int {
        if (oldPath.isBlank() || newPath.isBlank() || oldPath == newPath) return 0
        var changed = 0
        synchronized(lock) {
            for ((id, cur) in charactersMap) {
                if (cur.categories.any { it == oldPath || it.startsWith("$oldPath/") }) {
                    val next = cur.categories.map { cat ->
                        when {
                            cat == oldPath -> newPath
                            cat.startsWith("$oldPath/") -> (newPath + cat.removePrefix(oldPath))
                                .split("/").filter { it.isNotBlank() }.take(3).joinToString("/")
                            else -> cat
                        }
                    }.distinct()
                    charactersMap[id] = cur.copy(categories = next)
                    changed++
                }
            }
        }
        if (changed > 0) saveCharactersIndex()
        return changed
    }

    fun removeCharacterCategoryPrefix(oldPath: String): Int {
        if (oldPath.isBlank()) return 0
        var changed = 0
        synchronized(lock) {
            for ((id, cur) in charactersMap) {
                val next = cur.categories.filterNot { it == oldPath || it.startsWith("$oldPath/") }
                if (next.size != cur.categories.size) {
                    charactersMap[id] = cur.copy(categories = next)
                    changed++
                }
            }
        }
        if (changed > 0) saveCharactersIndex()
        return changed
    }

    fun batchSetCharacterCategories(ids: List<String>, categories: List<String>, append: Boolean) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            for (id in ids) {
                val cur = charactersMap[id] ?: continue
                val next = if (append) (cur.categories + categories).distinct() else categories.distinct()
                charactersMap[id] = cur.copy(categories = next)
            }
        }
        saveCharactersIndex()
    }

    fun mergeCharacterVersions(ids: List<String>, seriesName: String) {
        if (ids.size < 2) return
        val clean = seriesName.trim()
        val key = VersionGrouper.extractSeriesAndVersion(clean).first
        synchronized(lock) {
            for (id in ids) {
                val cur = charactersMap[id] ?: continue
                charactersMap[id] = cur.copy(seriesKey = key, seriesName = clean)
            }
        }
        saveCharactersIndex()
    }

    fun mergeWorldBookVersions(ids: List<String>, seriesName: String) {
        if (ids.size < 2) return
        val clean = seriesName.trim()
        val key = VersionGrouper.extractSeriesAndVersion(clean).first
        synchronized(lock) {
            for (id in ids) {
                val cur = worldBooksMap[id] ?: continue
                worldBooksMap[id] = cur.copy(seriesKey = key, seriesName = clean)
            }
        }
        saveWorldBooksIndex()
    }

    fun mergePresetVersions(ids: List<String>, seriesName: String) {
        if (ids.size < 2) return
        val clean = seriesName.trim()
        val key = VersionGrouper.extractSeriesAndVersion(clean).first
        synchronized(lock) {
            for (id in ids) {
                val cur = presetsMap[id] ?: continue
                presetsMap[id] = cur.copy(seriesKey = key, seriesName = clean)
            }
        }
        savePresetsIndex()
    }

    fun mergeComponentVersions(ids: List<String>, seriesName: String) {
        if (ids.size < 2) return
        val clean = seriesName.trim()
        val key = VersionGrouper.extractSeriesAndVersion(clean).first
        synchronized(lock) {
            for (id in ids) {
                val cur = componentsMap[id] ?: continue
                componentsMap[id] = cur.copy(seriesKey = key, seriesName = clean)
            }
        }
        saveComponentsIndex()
    }

    fun renameSeries(module: String, seriesKey: String, newSeriesName: String): Boolean {
        val clean = newSeriesName.trim()
        if (seriesKey.isBlank() || clean.isEmpty()) return false
        when (module) {
            "characters" -> {
                synchronized(lock) {
                    charactersMap.entries.forEach { (id, cur) ->
                        if (cur.seriesKey == seriesKey) charactersMap[id] = cur.copy(seriesName = clean)
                    }
                }
                saveCharactersIndex()
            }
            "worldbooks" -> {
                synchronized(lock) {
                    worldBooksMap.entries.forEach { (id, cur) ->
                        if (cur.seriesKey == seriesKey) worldBooksMap[id] = cur.copy(seriesName = clean)
                    }
                }
                saveWorldBooksIndex()
            }
            "presets" -> {
                synchronized(lock) {
                    presetsMap.entries.forEach { (id, cur) ->
                        if (cur.seriesKey == seriesKey) presetsMap[id] = cur.copy(seriesName = clean)
                    }
                }
                savePresetsIndex()
            }
            "components" -> {
                synchronized(lock) {
                    componentsMap.entries.forEach { (id, cur) ->
                        if (cur.seriesKey == seriesKey) componentsMap[id] = cur.copy(seriesName = clean)
                    }
                }
                saveComponentsIndex()
            }
            else -> return false
        }
        return true
    }

    // ==================== RENAME & GLOBAL ACCESS ====================

    fun renameCharacter(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = charactersMap[id] ?: return false
            charactersMap[id] = cur.copy(name = clean)
        }
        saveCharactersIndex()
        return true
    }

    fun renameWorldBook(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = worldBooksMap[id] ?: return false
            worldBooksMap[id] = cur.copy(name = clean)
        }
        saveWorldBooksIndex()
        return true
    }

    fun renamePreset(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = presetsMap[id] ?: return false
            presetsMap[id] = cur.copy(name = clean)
        }
        savePresetsIndex()
        return true
    }

    fun renameComponent(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = componentsMap[id] ?: return false
            componentsMap[id] = cur.copy(name = clean)
        }
        saveComponentsIndex()
        return true
    }

    fun updateCharacterVersionTag(id: String, newTag: String): Boolean {
        synchronized(lock) {
            val cur = charactersMap[id] ?: return false
            charactersMap[id] = cur.copy(versionTag = newTag.trim())
        }
        saveCharactersIndex()
        return true
    }

    fun updateWorldBookVersionTag(id: String, newTag: String): Boolean {
        synchronized(lock) {
            val cur = worldBooksMap[id] ?: return false
            worldBooksMap[id] = cur.copy(versionTag = newTag.trim())
        }
        saveWorldBooksIndex()
        return true
    }

    fun updatePresetVersionTag(id: String, newTag: String): Boolean {
        synchronized(lock) {
            val cur = presetsMap[id] ?: return false
            presetsMap[id] = cur.copy(versionTag = newTag.trim())
        }
        savePresetsIndex()
        return true
    }

    fun updateComponentVersionTag(id: String, newTag: String): Boolean {
        synchronized(lock) {
            val cur = componentsMap[id] ?: return false
            componentsMap[id] = cur.copy(versionTag = newTag.trim())
        }
        saveComponentsIndex()
        return true
    }

    fun getAllCharacters(): List<CharacterRecord> = synchronized(lock) {
        charactersMap.values.sortedByDescending { it.addedAt }
    }

    fun getAllWorldBooks(): List<WorldBookRecord> = synchronized(lock) {
        worldBooksMap.values.sortedByDescending { it.addedAt }
    }

    fun getAllPresets(): List<PresetRecord> = synchronized(lock) {
        presetsMap.values.sortedByDescending { it.addedAt }
    }

    fun getComponentById(id: String): ComponentRecord? = synchronized(lock) { componentsMap[id] }

    fun getAllComponents(): List<ComponentRecord> = synchronized(lock) {
        componentsMap.values.sortedByDescending { it.addedAt }
    }

    data class GlobalSearchHit(
        val module: String, // "themes" | "characters" | "worldbooks" | "presets" | "components"
        val id: String,
        val title: String,
        val subtitle: String,
        val badge: String
    )

    fun searchGlobal(query: String, vaultRepo: VaultRepository): List<GlobalSearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<GlobalSearchHit>()

        // 1. Themes
        for (s in vaultRepo.getAllSources()) {
            if (s.name.lowercase().contains(q) ||
                s.fileName.lowercase().contains(q) ||
                s.categories.any { it.lowercase().contains(q) }
            ) {
                hits.add(GlobalSearchHit("themes", s.id, s.name, "外链图 ${s.urls.size}", "美化"))
                if (hits.size >= 60) return hits
            }
        }

        // 2. Characters
        for (c in getAllCharacters()) {
            if (c.name.lowercase().contains(q) ||
                c.fileName.lowercase().contains(q) ||
                c.creator.lowercase().contains(q) ||
                c.tags.any { it.lowercase().contains(q) } ||
                c.categories.any { it.lowercase().contains(q) }
            ) {
                val sub = c.creator.ifBlank { c.fileName }
                hits.add(GlobalSearchHit("characters", c.id, c.name, sub, "角色卡"))
                if (hits.size >= 60) return hits
            }
        }

        // 3. WorldBooks (name + entry keys/titles when query >= 2 chars)
        for (w in getAllWorldBooks()) {
            var matchedReason = ""
            if (w.name.lowercase().contains(q) || w.fileName.lowercase().contains(q)) {
                matchedReason = "${w.entryCount} 条目"
            } else if (q.length >= 2) {
                val parsed = parseWorldBookDetail(w)
                val entry = parsed?.entries?.firstOrNull { e ->
                    e.title.lowercase().contains(q) || e.keys.any { it.lowercase().contains(q) }
                }
                if (entry != null) {
                    matchedReason = "命中条目：${entry.title.ifBlank { entry.keys.firstOrNull().orEmpty() }}"
                }
            }
            if (matchedReason.isNotEmpty()) {
                hits.add(GlobalSearchHit("worldbooks", w.id, w.name, matchedReason, "世界书"))
                if (hits.size >= 60) return hits
            }
        }

        // 4. Presets
        for (p in getAllPresets()) {
            if (p.name.lowercase().contains(q) || p.fileName.lowercase().contains(q)) {
                hits.add(GlobalSearchHit("presets", p.id, p.name, "启用 ${p.enabledPromptCount}/${p.promptCount}", "预设"))
                if (hits.size >= 60) return hits
            }
        }

        // 5. Components
        for (comp in getAllComponents()) {
            if (comp.name.lowercase().contains(q) ||
                comp.fileName.lowercase().contains(q) ||
                comp.summary.lowercase().contains(q)
            ) {
                val badge = when (comp.type) {
                    "helper" -> "酒馆助手"
                    "qr" -> "QR"
                    else -> "正则"
                }
                hits.add(GlobalSearchHit("components", comp.id, comp.name, comp.summary, badge))
                if (hits.size >= 60) return hits
            }
        }

        return hits
    }

    fun getVersionsForCharacter(seriesKey: String): List<CharacterRecord> = synchronized(lock) {
        if (seriesKey.isBlank()) return emptyList()
        charactersMap.values.filter { it.seriesKey == seriesKey }.sortedByDescending { it.addedAt }
    }

    fun searchCharacters(
        query: String,
        filter: String = "all",
        categoryFilter: String = ""
    ): List<CharacterRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            var list = charactersMap.values.toList()
            if (categoryFilter.isNotBlank()) {
                list = list.filter { c ->
                    c.categories.any { cat -> cat == categoryFilter || cat.startsWith("$categoryFilter/") }
                }
            }
            if (q.isNotEmpty()) {
                list = list.filter { c ->
                    c.name.lowercase().contains(q) ||
                        c.fileName.lowercase().contains(q) ||
                        c.tags.any { it.lowercase().contains(q) } ||
                        c.categories.any { it.lowercase().contains(q) }
                }
            }
            list = when (filter) {
                "has_book" -> list.filter { it.hasEmbeddedBook }
                "has_script" -> list.filter { it.embeddedRegexCount > 0 || it.embeddedHelperCount > 0 }
                "has_urls" -> list.filter { it.urls.isNotEmpty() }
                else -> list
            }
            return list.sortedByDescending { it.addedAt }
        }
    }

    fun getAllCharacterUrls(): List<String> = synchronized(lock) {
        charactersMap.values.flatMap { it.urls }.distinct()
    }

    /**
     * Imports a character card directly from a remote URL (PNG or JSON).
     */
    fun importCharacterFromUrl(rawUrl: String): Pair<CharacterRecord?, String> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null to "请输入有效的 http/https 链接"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            .build()

        val bytes: ByteArray
        val guessedName: String
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return null to "下载失败 (HTTP ${resp.code})"
            }
            val body = resp.body ?: return null to "空响应"
            bytes = body.bytes()
            if (bytes.isEmpty()) return null to "文件内容为空"
            if (bytes.size > 40 * 1024 * 1024) return null to "文件超过 40MB 上限"

            val cd = resp.header("Content-Disposition")
            val fromCd = cd?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
            val fromPath = Uri.parse(url).lastPathSegment?.substringBefore('?')
            guessedName = (fromCd ?: fromPath ?: "remote_card.png").ifBlank { "remote_card.png" }
        }

        val (rec, isDup, newUrls) = importCharacterBytes(bytes, guessedName, "url", url)
        return when {
            isDup -> rec to "该角色卡已在库中"
            rec != null -> rec to "已导入「${rec.name}」" + if (newUrls > 0) "，提取 $newUrls 个图床" else ""
            else -> null to "该链接不是有效的酒馆角色卡（未检测到 chara/ccv3 数据）"
        }
    }

    fun importCharacterUris(uris: List<Uri>): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val name = doc?.name ?: "card_${System.currentTimeMillis()}.png"
            scanned++
            try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val (rec, isDup, addedUrls) = importCharacterBytes(bytes, name, "local", uri.toString())
                if (isDup) dups++
                else if (rec != null) {
                    matched++
                    newUrls += addedUrls
                }
            } catch (_: Exception) {
            }
        }
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun scanCharactersTreeUri(
        treeUri: Uri,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选目录")
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0

        fun walk(dir: DocumentFile) {
            val children = try { dir.listFiles() } catch (_: Exception) { return }
            for (child in children) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git" && name != "thumbs") walk(child)
                    continue
                }
                if (!child.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "png" && ext != "json") continue
                if (child.length() <= 0L || child.length() > 40L * 1024L * 1024L) continue
                scanned++
                if (scanned % 5 == 0) onProgress(scanned, matched)
                try {
                    val bytes = appContext.contentResolver.openInputStream(child.uri)?.use { it.readBytes() } ?: continue
                    val (rec, isDup, addedUrls) = importCharacterBytes(bytes, name, "local", child.uri.toString())
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(root)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun importCharacterFromBytes(
        bytes: ByteArray,
        fileName: String,
        sourceRef: String = ""
    ): Triple<CharacterRecord?, Boolean, Int> = importCharacterBytes(bytes, fileName, "local", sourceRef)

    fun scanCharactersLocalDir(
        rootDir: File,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git" && name != "thumbs") walk(child)
                    continue
                }
                if (!child.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "png" && ext != "json") continue
                if (child.length() <= 0L || child.length() > 40L * 1024L * 1024L) continue
                scanned++
                if (scanned % 5 == 0) onProgress(scanned, matched)
                try {
                    val (rec, isDup, addedUrls) = importCharacterBytes(child.readBytes(), name, "local", "file://${child.absolutePath}")
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(rootDir)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    private fun importCharacterBytes(
        bytes: ByteArray,
        fileName: String,
        sourceType: String,
        sourceRef: String
    ): Triple<CharacterRecord?, Boolean, Int> {
        val isPng = CharacterCardParser.isPng(bytes)
        val rawJson = if (isPng) {
            CharacterCardParser.extractJsonFromPng(bytes) ?: return Triple(null, false, 0)
        } else {
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { return Triple(null, false, 0) }
            if (!CharacterCardParser.isCharacterCardJson(text)) return Triple(null, false, 0)
            text
        }
        val parsed = CharacterCardParser.parseJson(rawJson, fileName.substringBeforeLast('.'))
            ?: return Triple(null, false, 0)

        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val existing = charactersMap.values.firstOrNull { it.contentHash == contentHash }
            if (existing != null) return Triple(existing, true, 0)
        }

        val id = "char_$contentHash"
        val ext = if (isPng) "png" else "json"
        val cardFileName = "$id.$ext"
        val cardFile = File(charactersDir, cardFileName)
        cardFile.parentFile?.mkdirs()
        cardFile.writeBytes(bytes)

        var thumbFileName = ""
        if (isPng) {
            thumbFileName = "${id}_thumb.jpg"
            generateThumbnail(bytes, File(charThumbsDir, thumbFileName))
        }

        val (urls, _) = UrlExtractor.extract(rawJson)
        val newUrls = vaultRepo.registerExternalUrls("char:$id", urls)

        val (seriesKey, versionTag) = VersionGrouper.extractSeriesAndVersion(
            rawName = parsed.name.ifBlank { fileName },
            explicitVersion = parsed.characterVersion
        )
        val rec = CharacterRecord(
            id = id,
            name = parsed.name,
            fileName = fileName,
            creator = parsed.creator,
            tags = parsed.tags,
            spec = parsed.spec,
            isPng = isPng,
            cardFile = cardFileName,
            thumbFile = thumbFileName,
            fileSize = cardFile.length(),
            hasEmbeddedBook = parsed.embeddedWorldBook != null,
            embeddedBookEntryCount = parsed.embeddedWorldBook?.entries?.size ?: 0,
            embeddedRegexCount = parsed.embeddedRegexScripts.size,
            embeddedHelperCount = parsed.embeddedHelperScripts.size,
            urls = urls,
            contentHash = contentHash,
            sourceType = sourceType,
            sourceRef = sourceRef,
            seriesKey = seriesKey,
            versionTag = versionTag,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            charactersMap[id] = rec
        }
        saveCharactersIndex()
        return Triple(rec, false, newUrls)
    }

    private fun generateThumbnail(pngBytes: ByteArray, outFile: File) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 480 || opts.outHeight / sample > 640) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, decodeOpts) ?: return
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos)
            }
            bmp.recycle()
        } catch (_: Exception) {
        }
    }

    fun deleteCharacter(id: String): VaultRepository.DeleteSourceResult? {
        val removed = synchronized(lock) { charactersMap.remove(id) } ?: return null
        try { getCharacterFile(removed).delete() } catch (_: Exception) {}
        try { getCharacterThumbFile(removed)?.delete() } catch (_: Exception) {}
        val assetRes = vaultRepo.unregisterExternalOwner("char:$id")
        saveCharactersIndex()
        return assetRes
    }

    /**
     * Extracts a character card's embedded `character_book` into the standalone WorldBook module.
     */
    fun extractEmbeddedWorldBook(characterId: String): Pair<WorldBookRecord?, String> {
        val charRec = getCharacterById(characterId) ?: return null to "找不到该角色卡"
        val parsed = parseCharacterDetail(charRec) ?: return null to "解析角色卡失败"
        val book = parsed.embeddedWorldBook ?: return null to "该角色卡不含内嵌世界书"
        val fileName = "${book.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.json"
        val (wb, isDup, _) = importWorldBookText(
            rawText = book.rawJson,
            fileName = fileName,
            originCardId = charRec.id,
            originCardName = charRec.name
        )
        return if (isDup) wb to "该世界书已存在于独立世界书库中"
        else wb to "已抽离到独立世界书「${wb?.name ?: book.name}」"
    }

    /**
     * Repairs dead image URLs inside a character card and returns (suggestedFileName, fileBytes).
     * For PNG cards, preserves original cover image pixels losslessly!
     */
    fun repairCharacterCard(
        characterId: String,
        mode: String = "catbox",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Triple<String, ByteArray, RepairResult> {
        val rec = getCharacterById(characterId) ?: throw IllegalArgumentException("找不到角色卡")
        val file = getCharacterFile(rec)
        if (!file.exists()) throw IllegalStateException("原角色卡文件缺失")

        val rawBytes = file.readBytes()
        val rawJson = if (rec.isPng) {
            CharacterCardParser.extractJsonFromPng(rawBytes)
                ?: throw IllegalStateException("无法读取角色卡数据块")
        } else {
            String(rawBytes, Charsets.UTF_8)
        }

        val repairRes = vaultRepo.repairRawText(rawJson, rec.fileName, mode, onProgress = onProgress)
        val outBytes = if (rec.isPng) {
            CharacterCardParser.rewritePngCardJson(rawBytes, repairRes.repairedText)
        } else {
            repairRes.repairedText.toByteArray(Charsets.UTF_8)
        }
        val base = rec.fileName.substringBeforeLast('.')
        val ext = if (rec.isPng) "png" else "json"
        return Triple("${base}_已复活.$ext", outBytes, repairRes)
    }

    /**
     * In-place updates a Character Card's JSON (losslessly rewriting PNG tEXt chunks if PNG)
     * while preserving its ID, categories, series metadata, and thumbnail.
     */
    fun updateCharacterRawJson(id: String, newRawJson: String): Boolean {
        val cur = synchronized(lock) { charactersMap[id] } ?: return false
        val parsed = CharacterCardParser.parseJson(newRawJson, cur.name) ?: return false
        val file = getCharacterFile(cur)
        if (!file.exists()) return false
        val outBytes = if (cur.isPng) {
            val origBytes = file.readBytes()
            CharacterCardParser.rewritePngCardJson(origBytes, newRawJson)
        } else {
            newRawJson.trim().toByteArray(Charsets.UTF_8)
        }
        file.writeBytes(outBytes)
        val (urls, _) = UrlExtractor.extract(newRawJson)
        vaultRepo.registerExternalUrls("char:$id", urls)
        val contentHash = sha256Short(outBytes)
        synchronized(lock) {
            val latest = charactersMap[id] ?: return false
            charactersMap[id] = latest.copy(
                name = parsed.name.ifBlank { latest.name },
                creator = parsed.creator,
                tags = parsed.tags,
                spec = parsed.spec,
                fileSize = file.length(),
                hasEmbeddedBook = parsed.embeddedWorldBook != null,
                embeddedBookEntryCount = parsed.embeddedWorldBook?.entries?.size ?: 0,
                embeddedRegexCount = parsed.embeddedRegexScripts.size,
                embeddedHelperCount = parsed.embeddedHelperScripts.size,
                urls = urls,
                contentHash = contentHash
            )
        }
        saveCharactersIndex()
        return true
    }

    // ==================== WORLD BOOKS MODULE ====================

    private fun loadWorldBooksLocked() {
        if (!booksIndexFile.exists()) return
        try {
            val arr = JSONArray(booksIndexFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val wName = o.optString("name", "未命名世界书")
                val wFileName = o.optString("fileName", "")
                val (autoSeries, autoVer) = VersionGrouper.extractSeriesAndVersion(wName.ifBlank { wFileName })
                val rec = WorldBookRecord(
                    id = o.optString("id"),
                    name = wName,
                    fileName = wFileName,
                    entryCount = o.optInt("entryCount", 0),
                    constantCount = o.optInt("constantCount", 0),
                    totalChars = o.optInt("totalChars", 0),
                    urls = readJsonStringList(o.optJSONArray("urls")),
                    bookFile = o.optString("bookFile", ""),
                    fileSize = o.optLong("fileSize", 0L),
                    contentHash = o.optString("contentHash", ""),
                    originCardId = o.optString("originCardId", ""),
                    originCardName = o.optString("originCardName", ""),
                    seriesKey = o.optString("seriesKey", "").ifBlank { autoSeries },
                    seriesName = o.optString("seriesName", ""),
                    versionTag = o.optString("versionTag", "").ifBlank { autoVer },
                    categories = readJsonStringList(o.optJSONArray("categories")),
                    addedAt = o.optLong("addedAt", 0L)
                )
                if (rec.id.isNotEmpty()) worldBooksMap[rec.id] = rec
            }
        } catch (_: Exception) {
        }
    }

    private fun saveWorldBooksIndex() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (b in worldBooksMap.values) {
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("name", b.name)
                    put("fileName", b.fileName)
                    put("entryCount", b.entryCount)
                    put("constantCount", b.constantCount)
                    put("totalChars", b.totalChars)
                    put("urls", JSONArray(b.urls))
                    put("bookFile", b.bookFile)
                    put("fileSize", b.fileSize)
                    put("contentHash", b.contentHash)
                    put("originCardId", b.originCardId)
                    put("originCardName", b.originCardName)
                    put("seriesKey", b.seriesKey)
                    if (b.seriesName.isNotEmpty()) put("seriesName", b.seriesName)
                    if (b.categories.isNotEmpty()) put("categories", JSONArray(b.categories))
                    put("versionTag", b.versionTag)
                    put("addedAt", b.addedAt)
                })
            }
            refreshCountsLocked()
            arr.toString()
        }
        atomicWrite(booksIndexFile, json)
    }

    fun getWorldBookFile(rec: WorldBookRecord): File = File(worldBooksDir, rec.bookFile)
    fun getWorldBookById(id: String): WorldBookRecord? = synchronized(lock) { worldBooksMap[id] }
    fun getVersionsForWorldBook(seriesKey: String): List<WorldBookRecord> = synchronized(lock) {
        if (seriesKey.isBlank()) return emptyList()
        worldBooksMap.values.filter { it.seriesKey == seriesKey }.sortedByDescending { it.addedAt }
    }

    fun parseWorldBookDetail(rec: WorldBookRecord): ParsedWorldBook? {
        val f = getWorldBookFile(rec)
        if (!f.exists()) return null
        return try {
            WorldBookParser.parse(f.readText(Charsets.UTF_8), rec.name)
        } catch (_: Exception) {
            null
        }
    }

    fun setWorldBookCategories(id: String, categories: List<String>) {
        val clean = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        synchronized(lock) {
            val cur = worldBooksMap[id] ?: return
            worldBooksMap[id] = cur.copy(categories = clean)
        }
        saveWorldBooksIndex()
    }

    fun searchWorldBooks(query: String, filter: String = "all", categoryFilter: String = ""): List<WorldBookRecord> {
        val q = query.trim().lowercase()
        val catMgr = CategoryManager.getInstance(appContext)
        synchronized(lock) {
            var list = worldBooksMap.values.toList()
            if (q.isNotEmpty()) {
                list = list.filter {
                    it.name.lowercase().contains(q) ||
                        it.fileName.lowercase().contains(q) ||
                        it.originCardName.lowercase().contains(q) ||
                        it.categories.any { c -> c.lowercase().contains(q) }
                }
            }
            if (categoryFilter.isNotBlank()) {
                list = list.filter { catMgr.matchesCategory(it.categories, categoryFilter) }
            }
            list = when (filter) {
                "standalone" -> list.filter { it.originCardId.isEmpty() }
                "from_card" -> list.filter { it.originCardId.isNotEmpty() }
                "has_urls" -> list.filter { it.urls.isNotEmpty() }
                else -> list
            }
            return list.sortedByDescending { it.addedAt }
        }
    }

    fun getAllWorldBookUrls(): List<String> = synchronized(lock) {
        worldBooksMap.values.flatMap { it.urls }.distinct()
    }

    fun importWorldBookUris(uris: List<Uri>): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val name = doc?.name ?: "worldbook_${System.currentTimeMillis()}.json"
            scanned++
            try {
                val text = appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: continue
                val (rec, isDup, addedUrls) = importWorldBookText(text, name)
                if (isDup) dups++
                else if (rec != null) {
                    matched++
                    newUrls += addedUrls
                }
            } catch (_: Exception) {
            }
        }
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun scanWorldBooksTreeUri(
        treeUri: Uri,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选目录")
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0

        fun walk(dir: DocumentFile) {
            val children = try { dir.listFiles() } catch (_: Exception) { return }
            for (child in children) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile || !name.endsWith(".json", ignoreCase = true)) continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val text = appContext.contentResolver.openInputStream(child.uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: continue
                    val (rec, isDup, addedUrls) = importWorldBookText(text, name)
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(root)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun importWorldBookFromText(rawText: String, fileName: String): Triple<WorldBookRecord?, Boolean, Int> =
        importWorldBookText(rawText, fileName)

    fun scanWorldBooksLocalDir(
        rootDir: File,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile || !name.endsWith(".json", ignoreCase = true)) continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val (rec, isDup, addedUrls) = importWorldBookText(child.readText(Charsets.UTF_8), name)
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(rootDir)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    private fun importWorldBookText(
        rawText: String,
        fileName: String,
        originCardId: String = "",
        originCardName: String = ""
    ): Triple<WorldBookRecord?, Boolean, Int> {
        if (!WorldBookParser.isWorldBookJson(rawText)) return Triple(null, false, 0)
        val parsed = WorldBookParser.parse(rawText, fileName.substringBeforeLast('.'))
            ?: return Triple(null, false, 0)
        val bytes = rawText.trim().toByteArray(Charsets.UTF_8)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val existing = worldBooksMap.values.firstOrNull { it.contentHash == contentHash }
            if (existing != null) return Triple(existing, true, 0)
        }

        val id = "wb_$contentHash"
        val bookFileName = "$id.json"
        val file = File(worldBooksDir, bookFileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        val (urls, _) = UrlExtractor.extract(rawText)
        val newUrls = vaultRepo.registerExternalUrls("wb:$id", urls)

        val (seriesKey, versionTag) = VersionGrouper.extractSeriesAndVersion(parsed.name.ifBlank { fileName })
        val rec = WorldBookRecord(
            id = id,
            name = parsed.name,
            fileName = fileName,
            entryCount = parsed.entries.size,
            constantCount = parsed.entries.count { it.constant },
            totalChars = parsed.entries.sumOf { it.content.length },
            urls = urls,
            bookFile = bookFileName,
            fileSize = file.length(),
            contentHash = contentHash,
            originCardId = originCardId,
            originCardName = originCardName,
            seriesKey = seriesKey,
            versionTag = versionTag,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) { worldBooksMap[id] = rec }
        saveWorldBooksIndex()
        return Triple(rec, false, newUrls)
    }

    fun deleteWorldBook(id: String): VaultRepository.DeleteSourceResult? {
        val removed = synchronized(lock) { worldBooksMap.remove(id) } ?: return null
        try { getWorldBookFile(removed).delete() } catch (_: Exception) {}
        val assetRes = vaultRepo.unregisterExternalOwner("wb:$id")
        saveWorldBooksIndex()
        return assetRes
    }

    fun updateWorldBookRawJson(id: String, newRawJson: String): Boolean {
        val cur = synchronized(lock) { worldBooksMap[id] } ?: return false
        val parsed = WorldBookParser.parse(newRawJson, cur.name) ?: return false
        val bytes = newRawJson.trim().toByteArray(Charsets.UTF_8)
        val file = getWorldBookFile(cur)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val (urls, _) = UrlExtractor.extract(newRawJson)
        vaultRepo.registerExternalUrls("wb:$id", urls)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val latest = worldBooksMap[id] ?: return false
            worldBooksMap[id] = latest.copy(
                name = parsed.name.ifBlank { latest.name },
                entryCount = parsed.entries.size,
                constantCount = parsed.entries.count { it.constant },
                totalChars = parsed.entries.sumOf { it.content.length },
                urls = urls,
                fileSize = file.length(),
                contentHash = contentHash
            )
        }
        saveWorldBooksIndex()
        return true
    }

    // ==================== PRESETS MODULE ====================

    private fun loadPresetsLocked() {
        if (!presetsIndexFile.exists()) return
        try {
            val arr = JSONArray(presetsIndexFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pName = o.optString("name", "未命名预设")
                val pFileName = o.optString("fileName", "")
                val (autoSeries, autoVer) = VersionGrouper.extractSeriesAndVersion(pName.ifBlank { pFileName })
                val rec = PresetRecord(
                    id = o.optString("id"),
                    name = pName,
                    fileName = pFileName,
                    promptCount = o.optInt("promptCount", 0),
                    enabledPromptCount = o.optInt("enabledPromptCount", 0),
                    embeddedRegexCount = o.optInt("embeddedRegexCount", 0),
                    embeddedHelperCount = o.optInt("embeddedHelperCount", 0),
                    temperature = if (o.has("temperature") && !o.isNull("temperature")) o.optDouble("temperature") else null,
                    topP = if (o.has("topP") && !o.isNull("topP")) o.optDouble("topP") else null,
                    maxTokens = if (o.has("maxTokens") && !o.isNull("maxTokens")) o.optInt("maxTokens") else null,
                    presetFile = o.optString("presetFile", ""),
                    fileSize = o.optLong("fileSize", 0L),
                    contentHash = o.optString("contentHash", ""),
                    seriesKey = o.optString("seriesKey", "").ifBlank { autoSeries },
                    seriesName = o.optString("seriesName", ""),
                    versionTag = o.optString("versionTag", "").ifBlank { autoVer },
                    addedAt = o.optLong("addedAt", 0L)
                )
                if (rec.id.isNotEmpty()) presetsMap[rec.id] = rec
            }
        } catch (_: Exception) {
        }
    }

    private fun savePresetsIndex() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (p in presetsMap.values) {
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("fileName", p.fileName)
                    put("promptCount", p.promptCount)
                    put("enabledPromptCount", p.enabledPromptCount)
                    put("embeddedRegexCount", p.embeddedRegexCount)
                    put("embeddedHelperCount", p.embeddedHelperCount)
                    put("temperature", p.temperature ?: JSONObject.NULL)
                    put("topP", p.topP ?: JSONObject.NULL)
                    put("maxTokens", p.maxTokens ?: JSONObject.NULL)
                    put("presetFile", p.presetFile)
                    put("fileSize", p.fileSize)
                    put("contentHash", p.contentHash)
                    put("seriesKey", p.seriesKey)
                    if (p.seriesName.isNotEmpty()) put("seriesName", p.seriesName)
                    put("versionTag", p.versionTag)
                    put("addedAt", p.addedAt)
                })
            }
            refreshCountsLocked()
            arr.toString()
        }
        atomicWrite(presetsIndexFile, json)
    }

    fun getPresetFile(rec: PresetRecord): File = File(presetsDir, rec.presetFile)
    fun getPresetById(id: String): PresetRecord? = synchronized(lock) { presetsMap[id] }
    fun getVersionsForPreset(seriesKey: String): List<PresetRecord> = synchronized(lock) {
        if (seriesKey.isBlank()) return emptyList()
        presetsMap.values.filter { it.seriesKey == seriesKey }.sortedByDescending { it.addedAt }
    }

    fun parsePresetDetail(rec: PresetRecord): ParsedPreset? {
        val f = getPresetFile(rec)
        if (!f.exists()) return null
        return try {
            PresetParser.parse(f.readText(Charsets.UTF_8), rec.fileName)
        } catch (_: Exception) {
            null
        }
    }

    fun searchPresets(query: String): List<PresetRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            val list = if (q.isEmpty()) presetsMap.values.toList()
            else presetsMap.values.filter {
                it.name.lowercase().contains(q) || it.fileName.lowercase().contains(q)
            }
            return list.sortedByDescending { it.addedAt }
        }
    }

    fun importPresetUris(uris: List<Uri>): ScanResult {
        var scanned = 0
        var matched = 0
        var dups = 0
        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val name = doc?.name ?: "preset_${System.currentTimeMillis()}.json"
            scanned++
            try {
                val text = appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: continue
                val (rec, isDup) = importPresetText(text, name)
                if (isDup) dups++
                else if (rec != null) matched++
            } catch (_: Exception) {
            }
        }
        return ScanResult(scanned, matched, 0, dups)
    }

    fun scanPresetsTreeUri(
        treeUri: Uri,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选目录")
        var scanned = 0
        var matched = 0
        var dups = 0

        fun walk(dir: DocumentFile) {
            val children = try { dir.listFiles() } catch (_: Exception) { return }
            for (child in children) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile || !name.endsWith(".json", ignoreCase = true)) continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val text = appContext.contentResolver.openInputStream(child.uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: continue
                    val (rec, isDup) = importPresetText(text, name)
                    if (isDup) dups++
                    else if (rec != null) matched++
                } catch (_: Exception) {
                }
            }
        }
        walk(root)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, 0, dups)
    }

    fun importPresetFromText(rawText: String, fileName: String): Pair<PresetRecord?, Boolean> =
        importPresetText(rawText, fileName)

    fun scanPresetsLocalDir(
        rootDir: File,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        var scanned = 0
        var matched = 0
        var dups = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile || !name.endsWith(".json", ignoreCase = true)) continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val (rec, isDup) = importPresetText(child.readText(Charsets.UTF_8), name)
                    if (isDup) dups++
                    else if (rec != null) matched++
                } catch (_: Exception) {
                }
            }
        }
        walk(rootDir)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, 0, dups)
    }

    private fun importPresetText(rawText: String, fileName: String): Pair<PresetRecord?, Boolean> {
        if (!PresetParser.isPresetJson(rawText)) return null to false
        val parsed = PresetParser.parse(rawText, fileName) ?: return null to false
        val bytes = rawText.trim().toByteArray(Charsets.UTF_8)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val existing = presetsMap.values.firstOrNull { it.contentHash == contentHash }
            if (existing != null) return existing to true
        }
        val id = "preset_$contentHash"
        val presetFileName = "$id.json"
        val file = File(presetsDir, presetFileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        val (seriesKey, versionTag) = VersionGrouper.extractSeriesAndVersion(parsed.name.ifBlank { fileName })
        val rec = PresetRecord(
            id = id,
            name = parsed.name,
            fileName = fileName,
            promptCount = parsed.prompts.size,
            enabledPromptCount = parsed.prompts.count { it.enabled },
            embeddedRegexCount = parsed.embeddedRegexScripts.size,
            embeddedHelperCount = parsed.embeddedHelperScripts.size,
            temperature = parsed.temperature,
            topP = parsed.topP,
            maxTokens = parsed.maxTokens,
            presetFile = presetFileName,
            fileSize = file.length(),
            contentHash = contentHash,
            seriesKey = seriesKey,
            versionTag = versionTag,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) { presetsMap[id] = rec }
        savePresetsIndex()
        return rec to false
    }

    fun deletePreset(id: String): Boolean {
        val removed = synchronized(lock) { presetsMap.remove(id) } ?: return false
        try { getPresetFile(removed).delete() } catch (_: Exception) {}
        savePresetsIndex()
        return true
    }

    fun updatePresetRawJson(id: String, newRawJson: String): Boolean {
        val cur = synchronized(lock) { presetsMap[id] } ?: return false
        val parsed = PresetParser.parse(newRawJson, cur.fileName.ifBlank { cur.name }) ?: return false
        val bytes = newRawJson.trim().toByteArray(Charsets.UTF_8)
        val file = getPresetFile(cur)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val latest = presetsMap[id] ?: return false
            presetsMap[id] = latest.copy(
                promptCount = parsed.prompts.size,
                enabledPromptCount = parsed.prompts.count { it.enabled },
                embeddedRegexCount = parsed.embeddedRegexScripts.size,
                embeddedHelperCount = parsed.embeddedHelperScripts.size,
                temperature = parsed.temperature,
                topP = parsed.topP,
                maxTokens = parsed.maxTokens,
                fileSize = file.length(),
                contentHash = contentHash
            )
        }
        savePresetsIndex()
        return true
    }

    // ==================== COMPONENTS MODULE (Regex / Helper / QR) ====================

    private fun loadComponentsLocked() {
        if (!compsIndexFile.exists()) return
        try {
            val arr = JSONArray(compsIndexFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val compName = o.optString("name", "未命名组件")
                val compFileName = o.optString("fileName", "")
                val (autoSeries, autoVer) = VersionGrouper.extractSeriesAndVersion(compName.ifBlank { compFileName })
                val rec = ComponentRecord(
                    id = o.optString("id"),
                    name = compName,
                    fileName = compFileName,
                    type = o.optString("type", "regex"),
                    summary = o.optString("summary", ""),
                    urls = readJsonStringList(o.optJSONArray("urls")),
                    linkedTargetType = o.optString("linkedTargetType", ""),
                    linkedTargetId = o.optString("linkedTargetId", ""),
                    linkedTargetName = o.optString("linkedTargetName", ""),
                    compFile = o.optString("compFile", ""),
                    fileSize = o.optLong("fileSize", 0L),
                    contentHash = o.optString("contentHash", ""),
                    seriesKey = o.optString("seriesKey", "").ifBlank { autoSeries },
                    seriesName = o.optString("seriesName", ""),
                    versionTag = o.optString("versionTag", "").ifBlank { autoVer },
                    addedAt = o.optLong("addedAt", 0L)
                )
                if (rec.id.isNotEmpty()) componentsMap[rec.id] = rec
            }
        } catch (_: Exception) {
        }
    }

    private fun saveComponentsIndex() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (c in componentsMap.values) {
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("fileName", c.fileName)
                    put("type", c.type)
                    put("summary", c.summary)
                    put("urls", JSONArray(c.urls))
                    put("linkedTargetType", c.linkedTargetType)
                    put("linkedTargetId", c.linkedTargetId)
                    put("linkedTargetName", c.linkedTargetName)
                    put("compFile", c.compFile)
                    put("fileSize", c.fileSize)
                    put("contentHash", c.contentHash)
                    put("seriesKey", c.seriesKey)
                    if (c.seriesName.isNotEmpty()) put("seriesName", c.seriesName)
                    put("versionTag", c.versionTag)
                    put("addedAt", c.addedAt)
                })
            }
            refreshCountsLocked()
            arr.toString()
        }
        atomicWrite(compsIndexFile, json)
    }

    fun getComponentFile(rec: ComponentRecord): File = File(componentsDir, rec.compFile)
    fun getVersionsForComponent(seriesKey: String): List<ComponentRecord> = synchronized(lock) {
        if (seriesKey.isBlank()) return emptyList()
        componentsMap.values.filter { it.seriesKey == seriesKey }.sortedByDescending { it.addedAt }
    }

    fun readComponentText(rec: ComponentRecord): String {
        val f = getComponentFile(rec)
        if (!f.exists()) return ""
        val lower = rec.fileName.ifBlank { rec.compFile }.lowercase()
        if (lower.endsWith(".docx")) {
            return extractDocxPlainText(f).ifBlank { "Word 文档（${formatBytesSimple(f.length())}），可点击「导出」保存原文件。" }
        }
        if (lower.endsWith(".doc") || lower.endsWith(".pdf") || lower.endsWith(".rtf")) {
            return "文档原件（${formatBytesSimple(f.length())}），请点击「导出」保存后用系统阅读器打开。"
        }
        return f.readText(Charsets.UTF_8)
    }

    private fun extractDocxPlainText(file: File): String {
        return try {
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        val text = xml
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<w:br[^>]*/?>"), "\n")
                            .replace(Regex("<w:tab[^>]*/?>"), "\t")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .replace(Regex("\n{3,}"), "\n\n")
                            .trim()
                        return text
                    }
                    zis.closeEntry()
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatBytesSimple(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }

    fun importDocumentBytes(
        bytes: ByteArray,
        fileName: String,
        displayName: String = ""
    ): Pair<ComponentRecord?, Boolean> {
        if (bytes.isEmpty()) return null to false
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val existing = componentsMap.values.firstOrNull { it.contentHash == contentHash }
            if (existing != null) return existing to true
        }
        val ext = fileName.substringAfterLast('.', "txt").lowercase()
            .filter { it.isLetterOrDigit() }.ifBlank { "txt" }
        val id = "comp_$contentHash"
        val compFileName = "$id.$ext"
        val file = File(componentsDir, compFileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        val cleanName = displayName.trim().ifBlank { fileName.substringBeforeLast('.').ifBlank { fileName } }
        val summary = when (ext) {
            "docx", "doc" -> "Word 文档 · ${formatBytesSimple(file.length())}"
            "pdf" -> "PDF 文档 · ${formatBytesSimple(file.length())}"
            "md" -> "Markdown · ${formatBytesSimple(file.length())}"
            "rtf" -> "RTF 文档 · ${formatBytesSimple(file.length())}"
            else -> "文本笔记 · ${bytes.toString(Charsets.UTF_8).length} 字"
        }
        val (seriesKey, versionTag) = VersionGrouper.extractSeriesAndVersion(cleanName)
        val rec = ComponentRecord(
            id = id,
            name = cleanName,
            fileName = fileName,
            type = "doc",
            summary = summary,
            urls = emptyList(),
            compFile = compFileName,
            fileSize = file.length(),
            contentHash = contentHash,
            seriesKey = seriesKey,
            versionTag = versionTag,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) { componentsMap[id] = rec }
        saveComponentsIndex()
        return rec to false
    }

    fun searchComponents(query: String, filter: String = "all"): List<ComponentRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            var list = componentsMap.values.toList()
            if (q.isNotEmpty()) {
                list = list.filter {
                    it.name.lowercase().contains(q) ||
                        it.fileName.lowercase().contains(q) ||
                        it.linkedTargetName.lowercase().contains(q)
                }
            }
            list = when (filter) {
                "regex" -> list.filter { it.type == "regex" }
                "helper" -> list.filter { it.type == "helper" }
                "qr" -> list.filter { it.type == "qr" }
                "global" -> list.filter { it.linkedTargetId.isEmpty() }
                "linked" -> list.filter { it.linkedTargetId.isNotEmpty() }
                else -> list
            }
            return list.sortedByDescending { it.addedAt }
        }
    }

    fun getComponentsLinkedTo(targetType: String, targetId: String): List<ComponentRecord> {
        synchronized(lock) {
            return componentsMap.values.filter {
                it.linkedTargetType == targetType && it.linkedTargetId == targetId
            }
        }
    }

    fun linkComponent(componentId: String, targetType: String, targetId: String, targetName: String) {
        synchronized(lock) {
            val cur = componentsMap[componentId] ?: return
            componentsMap[componentId] = cur.copy(
                linkedTargetType = targetType,
                linkedTargetId = targetId,
                linkedTargetName = targetName
            )
        }
        saveComponentsIndex()
    }

    fun importComponentUris(uris: List<Uri>): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val name = doc?.name ?: "component_${System.currentTimeMillis()}.json"
            scanned++
            try {
                val text = appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: continue
                val (rec, isDup, addedUrls) = importComponentText(text, name)
                if (isDup) dups++
                else if (rec != null) {
                    matched++
                    newUrls += addedUrls
                }
            } catch (_: Exception) {
            }
        }
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun scanComponentsTreeUri(
        treeUri: Uri,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选目录")
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0

        fun walk(dir: DocumentFile) {
            val children = try { dir.listFiles() } catch (_: Exception) { return }
            for (child in children) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "json" && ext != "js") continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val text = appContext.contentResolver.openInputStream(child.uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: continue
                    val (rec, isDup, addedUrls) = importComponentText(text, name)
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(root)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    fun importComponentFromText(rawText: String, fileName: String): Triple<ComponentRecord?, Boolean, Int> =
        importComponentText(rawText, fileName)

    fun scanComponentsLocalDir(
        rootDir: File,
        onProgress: (scanned: Int, matched: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var dups = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                if (child.isDirectory) {
                    if (name != "vault" && name != ".git") walk(child)
                    continue
                }
                if (!child.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "json" && ext != "js") continue
                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)
                try {
                    val (rec, isDup, addedUrls) = importComponentText(child.readText(Charsets.UTF_8), name)
                    if (isDup) dups++
                    else if (rec != null) {
                        matched++
                        newUrls += addedUrls
                    }
                } catch (_: Exception) {
                }
            }
        }
        walk(rootDir)
        onProgress(scanned, matched)
        return ScanResult(scanned, matched, newUrls, dups)
    }

    private fun importComponentText(rawText: String, fileName: String): Triple<ComponentRecord?, Boolean, Int> {
        val parsed = ComponentParser.parse(rawText, fileName) ?: return Triple(null, false, 0)
        val bytes = rawText.trim().toByteArray(Charsets.UTF_8)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val existing = componentsMap.values.firstOrNull { it.contentHash == contentHash }
            if (existing != null) return Triple(existing, true, 0)
        }
        val id = "comp_$contentHash"
        val ext = if (fileName.endsWith(".js", ignoreCase = true)) "js" else "json"
        val compFileName = "$id.$ext"
        val file = File(componentsDir, compFileName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        val (urls, _) = UrlExtractor.extract(rawText)
        val newUrls = vaultRepo.registerExternalUrls("comp:$id", urls)

        val (seriesKey, versionTag) = VersionGrouper.extractSeriesAndVersion(parsed.name.ifBlank { fileName })
        val rec = ComponentRecord(
            id = id,
            name = parsed.name,
            fileName = fileName,
            type = parsed.type,
            summary = parsed.summary,
            urls = urls,
            compFile = compFileName,
            fileSize = file.length(),
            contentHash = contentHash,
            seriesKey = seriesKey,
            versionTag = versionTag,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) { componentsMap[id] = rec }
        saveComponentsIndex()
        return Triple(rec, false, newUrls)
    }

    fun deleteComponent(id: String): VaultRepository.DeleteSourceResult? {
        val removed = synchronized(lock) { componentsMap.remove(id) } ?: return null
        try { getComponentFile(removed).delete() } catch (_: Exception) {}
        val assetRes = vaultRepo.unregisterExternalOwner("comp:$id")
        saveComponentsIndex()
        return assetRes
    }

    fun updateComponentRawText(id: String, newRawText: String): Boolean {
        val cur = synchronized(lock) { componentsMap[id] } ?: return false
        val parsed = ComponentParser.parse(newRawText, cur.fileName.ifBlank { cur.name }) ?: return false
        val bytes = newRawText.trim().toByteArray(Charsets.UTF_8)
        val file = getComponentFile(cur)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val (urls, _) = UrlExtractor.extract(newRawText)
        vaultRepo.registerExternalUrls("comp:$id", urls)
        val contentHash = sha256Short(bytes)
        synchronized(lock) {
            val latest = componentsMap[id] ?: return false
            componentsMap[id] = latest.copy(
                name = parsed.name.ifBlank { latest.name },
                type = parsed.type,
                summary = parsed.summary,
                urls = urls,
                fileSize = file.length(),
                contentHash = contentHash
            )
        }
        saveComponentsIndex()
        return true
    }

    // ==================== HELPERS ====================

    private fun readJsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        tmp.renameTo(target)
    }

    companion object {
        @Volatile
        private var INSTANCE: ResourceLibraryRepository? = null

        fun getInstance(context: Context): ResourceLibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResourceLibraryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
