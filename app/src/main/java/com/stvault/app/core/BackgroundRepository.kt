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
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Lightweight, deduplicated repository for Theme Background Images and Theme<->Background bindings.
 * Stored under <vaultRoot>/backgrounds/ with a separate theme_bg_bindings.json mapping.
 */
class BackgroundRepository private constructor(private val appContext: Context) {

    private val lock = Any()
    private val rootDir = File(VaultRepository.getInstance(appContext).getVaultRootDir(), "backgrounds")
    private val indexFile = File(rootDir, "backgrounds_index.json")
    private val bindingsFile = File(rootDir, "theme_bg_bindings.json")

    private val bgMap = LinkedHashMap<String, BackgroundRecord>()
    private val themeBindings = LinkedHashMap<String, String>() // sourceId -> bgId

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
                        bgMap[id] = BackgroundRecord(
                            id = id,
                            name = o.optString("name", "背景图"),
                            fileName = o.optString("fileName", ""),
                            imageFile = o.optString("imageFile", ""),
                            fileSize = o.optLong("fileSize", 0L),
                            contentHash = o.optString("contentHash", ""),
                            addedAt = o.optLong("addedAt", 0L)
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
                        val v = obj.optString(k, "")
                        if (k.isNotEmpty() && v.isNotEmpty() && v in bgMap) {
                            themeBindings[k] = v
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
            for ((k, v) in themeBindings) obj.put(k, v)
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

    fun getAll(query: String = ""): List<BackgroundRecord> {
        val q = query.trim().lowercase()
        return synchronized(lock) {
            bgMap.values
                .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.fileName.lowercase().contains(q) }
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

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            val r = bgMap.remove(id)
            if (r != null) {
                themeBindings.entries.removeAll { it.value == id }
            }
            r
        } ?: return false
        try { getFile(removed).delete() } catch (_: Exception) {}
        saveIndex()
        saveBindings()
        return true
    }

    fun bindTheme(sourceId: String, bgId: String) {
        if (sourceId.isBlank()) return
        synchronized(lock) {
            if (bgId.isBlank() || bgId !in bgMap) {
                themeBindings.remove(sourceId)
            } else {
                themeBindings[sourceId] = bgId
            }
        }
        saveBindings()
    }

    fun getBoundBgId(sourceId: String): String = synchronized(lock) { themeBindings[sourceId].orEmpty() }

    fun getBoundBackground(sourceId: String): BackgroundRecord? = synchronized(lock) {
        themeBindings[sourceId]?.let { bgMap[it] }
    }

    fun countThemesBoundTo(bgId: String): Int = synchronized(lock) {
        themeBindings.values.count { it == bgId }
    }

    /** Returns { themeName -> bgFileName } for syncing with st-theme-companion. */
    fun getBindingsByThemeName(vaultRepo: VaultRepository): Map<String, String> {
        val sources = vaultRepo.getAllSources().associateBy { it.id }
        val out = LinkedHashMap<String, String>()
        synchronized(lock) {
            for ((sid, bid) in themeBindings) {
                val src = sources[sid] ?: continue
                val bg = bgMap[bid] ?: continue
                if (src.name.isNotBlank() && bg.fileName.isNotBlank()) {
                    out[src.name] = bg.fileName
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
                if (themeBindings[src.id] != bg.id) {
                    themeBindings[src.id] = bg.id
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
