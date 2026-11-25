package com.stvault.app.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class BackgroundRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val imageFile: String,
    val fileSize: Long = 0L,
    val contentHash: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val categories: List<String> = emptyList()
)

/**
 * Lightweight, deduplicated repository for Theme Background Images and Theme<->Background bindings.
 * Supports 1 theme bound to multiple backgrounds (first bound background = default),
 * custom categories, and backward-compatible JSON persistence.
 */
class BackgroundRepository private constructor(private val appContext: Context) {

    private val lock = Any()
    private val rootDir = File(VaultRepository.getInstance(appContext).getVaultRootDir(), "backgrounds")
    private val indexFile = File(rootDir, "backgrounds_index.json")
    private val bindingsFile = File(rootDir, "theme_bg_bindings.json")

    private val bgMap = LinkedHashMap<String, BackgroundRecord>()
    // sourceId -> ordered list of bgIds (first = default preview background)
    private val themeBindings = LinkedHashMap<String, MutableList<String>>()

    init {
        rootDir.mkdirs()
        loadLocked()
    }

    private fun loadLocked() {
        synchronized(lock) {
            bgMap.clear()
            themeBindings.clear()
            if (indexFile.exists()) {
                try {
                    val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "")
                        if (id.isEmpty()) continue
                        val catsArr = o.optJSONArray("categories")
                        val cats = mutableListOf<String>()
                        if (catsArr != null) {
                            for (j in 0 until catsArr.length()) {
                                val c = catsArr.optString(j, "").trim()
                                if (c.isNotEmpty() && c !in cats) cats.add(c)
                            }
                        }
                        bgMap[id] = BackgroundRecord(
                            id = id,
                            name = o.optString("name", "背景图"),
                            fileName = o.optString("fileName", ""),
                            imageFile = o.optString("imageFile", ""),
                            fileSize = o.optLong("fileSize", 0L),
                            contentHash = o.optString("contentHash", ""),
                            addedAt = o.optLong("addedAt", 0L),
                            categories = cats
                        )
                    }
                } catch (_: Exception) {
                }
            }
            if (bindingsFile.exists()) {
                try {
                    val obj = JSONObject(bindingsFile.readText(Charsets.UTF_8))
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        if (k.isEmpty()) continue
                        val rawVal = obj.opt(k)
                        val validIds = mutableListOf<String>()
                        when (rawVal) {
                            is JSONArray -> {
                                for (i in 0 until rawVal.length()) {
                                    val bid = rawVal.optString(i, "").trim()
                                    if (bid.isNotEmpty() && bid in bgMap && bid !in validIds) {
                                        validIds.add(bid)
                                    }
                                }
                            }
                            is String -> {
                                val bid = rawVal.trim()
                                if (bid.isNotEmpty() && bid in bgMap) validIds.add(bid)
                            }
                        }
                        if (validIds.isNotEmpty()) {
                            themeBindings[k] = validIds
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun saveIndex() {
        val json = synchronized(lock) {
            val arr = JSONArray()
            for (b in bgMap.values) {
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("name", b.name)
                    put("fileName", b.fileName)
                    put("imageFile", b.imageFile)
                    put("fileSize", b.fileSize)
                    put("contentHash", b.contentHash)
                    put("addedAt", b.addedAt)
                    if (b.categories.isNotEmpty()) {
                        put("categories", JSONArray(b.categories))
                    }
                })
            }
            arr.toString()
        }
        try {
            rootDir.mkdirs()
            val tmp = File(rootDir, "backgrounds_index.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(indexFile)
        } catch (_: Exception) {
        }
    }

    private fun saveBindings() {
        val json = synchronized(lock) {
            val obj = JSONObject()
            for ((k, v) in themeBindings) {
                if (v.isNotEmpty()) obj.put(k, JSONArray(v))
            }
            obj.toString()
        }
        try {
            rootDir.mkdirs()
            val tmp = File(rootDir, "theme_bg_bindings.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(bindingsFile)
        } catch (_: Exception) {
        }
    }

    fun getFile(rec: BackgroundRecord): File = File(rootDir, rec.imageFile)

    fun getAll(
        query: String = "",
        categoryFilter: String = "",
        catMgr: CategoryManager? = null
    ): List<BackgroundRecord> {
        val q = query.trim().lowercase()
        return synchronized(lock) {
            bgMap.values
                .filter { rec ->
                    val matchQ = q.isEmpty() ||
                        rec.name.lowercase().contains(q) ||
                        rec.fileName.lowercase().contains(q) ||
                        rec.categories.any { it.lowercase().contains(q) }
                    val matchCat = if (categoryFilter.isBlank() || catMgr == null) true
                    else catMgr.matchesFilter(rec.categories, categoryFilter)
                    matchQ && matchCat
                }
                .sortedByDescending { it.addedAt }
        }
    }

    fun getById(id: String): BackgroundRecord? = synchronized(lock) { bgMap[id] }

    fun findByFileName(fileName: String): BackgroundRecord? {
        val clean = fileName.trim().lowercase()
        if (clean.isEmpty()) return null
        val stem = clean.substringBeforeLast('.')
        return synchronized(lock) {
            bgMap.values.firstOrNull {
                it.fileName.lowercase() == clean ||
                    it.name.lowercase() == stem ||
                    it.name.lowercase() == clean
            }
        }
    }

    fun importBytes(bytes: ByteArray, fileName: String, displayName: String = ""): Pair<BackgroundRecord?, Boolean> {
        if (bytes.size < 64) return null to false
        val hash = sha256Short(bytes)
        synchronized(lock) {
            val existing = bgMap.values.firstOrNull { it.contentHash == hash }
            if (existing != null) return existing to true
        }
        val rawExt = fileName.substringAfterLast('.', "").lowercase()
        val ext = when {
            rawExt in setOf("png", "jpg", "jpeg", "webp", "gif") -> rawExt
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> "webp"
        }
        val id = "bg_$hash"
        val storedFile = "$id.$ext"
        val f = File(rootDir, storedFile)
        rootDir.mkdirs()
        f.writeBytes(bytes)

        val cleanName = displayName.trim().ifBlank { fileName.substringBeforeLast('.').ifBlank { "背景图" } }
        val cleanFileName = if (fileName.contains('.')) fileName else "$cleanName.$ext"
        val rec = BackgroundRecord(
            id = id,
            name = cleanName,
            fileName = cleanFileName,
            imageFile = storedFile,
            fileSize = f.length(),
            contentHash = hash,
            addedAt = System.currentTimeMillis()
        )
        synchronized(lock) { bgMap[id] = rec }
        saveIndex()
        return rec to false
    }

    fun importUri(uri: Uri): Pair<BackgroundRecord?, Boolean> {
        return try {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val fn = doc?.name ?: "bg_${System.currentTimeMillis()}.png"
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null to false
            importBytes(bytes, fn)
        } catch (_: Exception) {
            null to false
        }
    }

    fun rename(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = bgMap[id] ?: return false
            val ext = cur.fileName.substringAfterLast('.', "png")
            bgMap[id] = cur.copy(name = clean, fileName = "$clean.$ext")
        }
        saveIndex()
        return true
    }

    fun setCategories(id: String, categories: List<String>) {
        val clean = categories.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        synchronized(lock) {
            val cur = bgMap[id] ?: return
            bgMap[id] = cur.copy(categories = clean)
        }
        saveIndex()
    }

    fun renameCategoryPrefix(oldPrefix: String, newPrefix: String): Int {
        if (oldPrefix.isBlank() || newPrefix.isBlank() || oldPrefix == newPrefix) return 0
        var migrated = 0
        synchronized(lock) {
            for ((id, rec) in bgMap.entries.toList()) {
                if (rec.categories.isEmpty()) continue
                val updated = rec.categories.map { cat ->
                    when {
                        cat == oldPrefix -> newPrefix
                        cat.startsWith("$oldPrefix/") -> newPrefix + cat.removePrefix(oldPrefix)
                        else -> cat
                    }
                }.distinct()
                if (updated != rec.categories) {
                    bgMap[id] = rec.copy(categories = updated)
                    migrated++
                }
            }
        }
        if (migrated > 0) saveIndex()
        return migrated
    }

    fun removeCategoryPrefix(prefix: String): Int {
        if (prefix.isBlank()) return 0
        var cleaned = 0
        synchronized(lock) {
            for ((id, rec) in bgMap.entries.toList()) {
                if (rec.categories.isEmpty()) continue
                val updated = rec.categories.filterNot { it == prefix || it.startsWith("$prefix/") }
                if (updated.size != rec.categories.size) {
                    bgMap[id] = rec.copy(categories = updated)
                    cleaned++
                }
            }
        }
        if (cleaned > 0) saveIndex()
        return cleaned
    }

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            val r = bgMap.remove(id)
            if (r != null) {
                val iter = themeBindings.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    entry.value.removeAll { it == id }
                    if (entry.value.isEmpty()) iter.remove()
                }
            }
            r
        } ?: return false
        try { getFile(removed).delete() } catch (_: Exception) {}
        saveIndex()
        saveBindings()
        return true
    }

    /** Replaces all bound backgrounds for a theme (first item = default preview background). */
    fun setThemeBindings(sourceId: String, bgIds: List<String>) {
        if (sourceId.isBlank()) return
        synchronized(lock) {
            val valid = bgIds.filter { it.isNotBlank() && it in bgMap }.distinct().toMutableList()
            if (valid.isEmpty()) {
                themeBindings.remove(sourceId)
            } else {
                themeBindings[sourceId] = valid
            }
        }
        saveBindings()
    }

    /** Adds a background to a theme's bound list (optionally making it the default first item). */
    fun addThemeBinding(sourceId: String, bgId: String, makeDefault: Boolean = false) {
        if (sourceId.isBlank() || bgId.isBlank()) return
        synchronized(lock) {
            if (bgId !in bgMap) return
            val list = themeBindings.getOrPut(sourceId) { mutableListOf() }
            if (makeDefault) {
                list.remove(bgId)
                list.add(0, bgId)
            } else if (bgId !in list) {
                list.add(bgId)
            }
        }
        saveBindings()
    }

    fun removeThemeBinding(sourceId: String, bgId: String) {
        if (sourceId.isBlank() || bgId.isBlank()) return
        synchronized(lock) {
            val list = themeBindings[sourceId] ?: return
            list.remove(bgId)
            if (list.isEmpty()) themeBindings.remove(sourceId)
        }
        saveBindings()
    }

    fun setDefaultThemeBinding(sourceId: String, bgId: String) {
        addThemeBinding(sourceId, bgId, makeDefault = true)
    }

    /** Legacy single-bind helper: blank clears all; non-blank adds (preserving existing, first = default). */
    fun bindTheme(sourceId: String, bgId: String) {
        if (sourceId.isBlank()) return
        if (bgId.isBlank()) {
            setThemeBindings(sourceId, emptyList())
        } else {
            addThemeBinding(sourceId, bgId, makeDefault = false)
        }
    }

    fun getBoundBgIds(sourceId: String): List<String> = synchronized(lock) {
        themeBindings[sourceId]?.toList() ?: emptyList()
    }

    fun getBoundBgId(sourceId: String): String = synchronized(lock) {
        themeBindings[sourceId]?.firstOrNull().orEmpty()
    }

    fun getBoundBackgrounds(sourceId: String): List<BackgroundRecord> = synchronized(lock) {
        themeBindings[sourceId]?.mapNotNull { bgMap[it] } ?: emptyList()
    }

    fun getBoundBackground(sourceId: String): BackgroundRecord? = synchronized(lock) {
        themeBindings[sourceId]?.firstOrNull()?.let { bgMap[it] }
    }

    fun getThemesBoundTo(bgId: String): List<String> = synchronized(lock) {
        themeBindings.entries.filter { bgId in it.value }.map { it.key }
    }

    fun countThemesBoundTo(bgId: String): Int = synchronized(lock) {
        themeBindings.values.count { bgId in it }
    }

    /** Returns { themeName -> defaultBgFileName } for syncing with st-theme-companion. */
    fun getBindingsByThemeName(vaultRepo: VaultRepository): Map<String, String> {
        val sources = vaultRepo.getAllSources().associateBy { it.id }
        val out = LinkedHashMap<String, String>()
        synchronized(lock) {
            for ((sid, bids) in themeBindings) {
                val src = sources[sid] ?: continue
                val firstBg = bids.firstOrNull()?.let { bgMap[it] } ?: continue
                if (src.name.isNotBlank() && firstBg.fileName.isNotBlank()) {
                    out[src.name] = firstBg.fileName
                }
            }
        }
        return out
    }

    /** Merges { themeName -> bgFileName } pulled from st-theme-companion into local bindings. */
    fun applyBindingsByThemeName(vaultRepo: VaultRepository, nameToBgFile: Map<String, String>): Int {
        if (nameToBgFile.isEmpty()) return 0
        val sourcesByName = vaultRepo.getAllSources().associateBy { it.name.trim().lowercase() }
        var updated = 0
        for ((tName, bgFile) in nameToBgFile) {
            val src = sourcesByName[tName.trim().lowercase()] ?: continue
            val bg = findByFileName(bgFile) ?: continue
            synchronized(lock) {
                val list = themeBindings.getOrPut(src.id) { mutableListOf() }
                if (bg.id !in list) {
                    list.add(0, bg.id)
                    updated++
                }
            }
        }
        if (updated > 0) saveBindings()
        return updated
    }

    private fun sha256Short(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile
        private var INSTANCE: BackgroundRepository? = null

        fun getInstance(context: Context): BackgroundRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackgroundRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
