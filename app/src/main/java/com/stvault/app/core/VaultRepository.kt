package com.stvault.app.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.stvault.app.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultRepository private constructor(private val appContext: Context) {

    private val lock = Any()
    private val vaultDir: File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "vault")
    private val legacyImagesDir: File = File(vaultDir, "images")
    private val legacySourcesDir: File = File(vaultDir, "sources")

    val imagesDir: File = File(vaultDir, "assets/images")
    val fontsDir: File = File(vaultDir, "assets/fonts")
    private val themesRootDir: File = File(vaultDir, "themes")
    private val indexFile: File = File(vaultDir, "index.json")

    private val urlsMap = LinkedHashMap<String, UrlRecord>()
    private val sourcesMap = LinkedHashMap<String, SourceRecord>()

    private val _statsFlow = MutableStateFlow(VaultStats())
    val statsFlow: StateFlow<VaultStats> = _statsFlow.asStateFlow()

    private val _progressFlow = MutableStateFlow(DownloadProgress())
    val progressFlow: StateFlow<DownloadProgress> = _progressFlow.asStateFlow()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        repoScope.launch {
            imagesDir.mkdirs()
            fontsDir.mkdirs()
            themesRootDir.mkdirs()
            migrateLegacyThemeSubdirs()
            loadIndex()
        }
    }

    fun getVaultRootDir(): File = vaultDir

    private fun migrateLegacyThemeSubdirs() {
        val legacySubdirs = listOf("ui", "regex", "cards", "other")
        for (sub in legacySubdirs) {
            val dir = File(themesRootDir, sub)
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: emptyArray()
            for (f in files) {
                if (f.isFile) {
                    val dst = File(themesRootDir, f.name)
                    if (!dst.exists()) {
                        if (!f.renameTo(dst)) {
                            try {
                                f.copyTo(dst, overwrite = false)
                                f.delete()
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        f.delete()
                    }
                }
            }
            if (dir.listFiles().isNullOrEmpty()) {
                dir.delete()
            }
        }
    }

    private fun isFontAsset(mime: String, fileName: String = "", url: String = ""): Boolean {
        val m = mime.lowercase()
        if (m.startsWith("font/") || m == "application/vnd.ms-fontobject") return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext in setOf("woff", "woff2", "ttf", "otf", "eot")) return true
        return UrlExtractor.isFontUrl(url)
    }

    private fun resolveAssetFile(fileName: String, mime: String, url: String = ""): File {
        val dir = if (isFontAsset(mime, fileName, url)) fontsDir else imagesDir
        return File(dir, fileName)
    }

    private fun resolveSourceFile(sourceId: String, @Suppress("UNUSED_PARAMETER") category: String = "ui"): File {
        themesRootDir.mkdirs()
        return File(themesRootDir, "$sourceId.txt")
    }

    fun getVaultPathDisplay(): String = vaultDir.absolutePath

    fun updateProgress(progress: DownloadProgress) {
        _progressFlow.value = progress
    }

    private fun sha1Short(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Short(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    @Volatile
    private var lastStatsRefreshAt = 0L

    private fun loadIndex() {
        var purgedNonImages = false
        synchronized(lock) {
            if (!indexFile.exists()) {
                refreshStatsLocked()
                return
            }
            try {
                // Single-syscall directory listings instead of 10,000+ slow FUSE File.exists() calls!
                val existingImages = imagesDir.list()?.toHashSet() ?: HashSet()
                val existingFonts = fontsDir.list()?.toHashSet() ?: HashSet()
                val legacyImages = if (legacyImagesDir.exists()) legacyImagesDir.list()?.toHashSet() ?: emptySet() else emptySet()
                val legacySources = if (legacySourcesDir.exists()) legacySourcesDir.list()?.toHashSet() ?: emptySet() else emptySet()

                val root = JSONObject(indexFile.readText(Charsets.UTF_8))
                val urlsObj = root.optJSONObject("urls")
                if (urlsObj != null) {
                    val keys = urlsObj.keys()
                    while (keys.hasNext()) {
                        val u = keys.next()
                        if (UrlExtractor.isNonImageUrl(u)) {
                            purgedNonImages = true
                            continue
                        }
                        val o = urlsObj.optJSONObject(u) ?: continue
                        val srcs = mutableListOf<String>()
                        val sArr = o.optJSONArray("sources")
                        if (sArr != null) {
                            for (i in 0 until sArr.length()) srcs.add(sArr.optString(i))
                        }
                        var status = CacheStatus.from(o.optString("status"))
                        var fileName = o.optString("file", "")
                        var size = o.optLong("size", 0L)
                        val mime = o.optString("mime", "")
                        if (status == CacheStatus.OK && fileName.isNotEmpty()) {
                            val isFont = isFontAsset(mime, fileName, u)
                            val existsInNew = if (isFont) fileName in existingFonts else fileName in existingImages
                            if (!existsInNew) {
                                if (fileName in legacyImages) {
                                    val oldFile = File(legacyImagesDir, fileName)
                                    val newFile = resolveAssetFile(fileName, mime, u)
                                    newFile.parentFile?.mkdirs()
                                    if (!oldFile.renameTo(newFile)) {
                                        oldFile.copyTo(newFile, overwrite = true)
                                        oldFile.delete()
                                    }
                                    if (isFont) existingFonts.add(fileName) else existingImages.add(fileName)
                                    purgedNonImages = true
                                } else {
                                    status = CacheStatus.PENDING
                                    fileName = ""
                                    size = 0L
                                }
                            }
                        }
                        urlsMap[u] = UrlRecord(
                            url = u,
                            hash = o.optString("hash", sha1Short(u)),
                            file = fileName,
                            mime = mime,
                            size = size,
                            status = status,
                            error = o.optString("error", ""),
                            updatedAt = o.optLong("updatedAt", 0L),
                            sources = srcs
                        )
                    }
                }
                val srcObj = root.optJSONObject("sources")
                if (srcObj != null) {
                    val keys = srcObj.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val o = srcObj.optJSONObject(id) ?: continue
                        val urlList = mutableListOf<String>()
                        val uArr = o.optJSONArray("urls")
                        if (uArr != null) {
                            for (i in 0 until uArr.length()) {
                                val u = uArr.optString(i)
                                if (!UrlExtractor.isNonImageUrl(u)) urlList.add(u)
                            }
                        }
                        val cat = o.optString("category", "ui").ifBlank { "ui" }
                        val legacySrcName = "$id.txt"
                        if (legacySrcName in legacySources) {
                            val oldSrc = File(legacySourcesDir, legacySrcName)
                            val newSrc = resolveSourceFile(id, cat)
                            if (!newSrc.exists()) {
                                if (!oldSrc.renameTo(newSrc)) {
                                    oldSrc.copyTo(newSrc, overwrite = true)
                                    oldSrc.delete()
                                }
                            }
                        }
                        val cats = mutableListOf<String>()
                        val cArr = o.optJSONArray("categories")
                        if (cArr != null) {
                            for (i in 0 until cArr.length()) {
                                val c = cArr.optString(i, "").trim()
                                if (c.isNotEmpty()) cats.add(c)
                            }
                        }
                        val storedName = o.optString("name", id)
                        var storedFileName = o.optString("fileName", "")
                        val isJsonRec = o.optBoolean("isJson", false)
                        var resolvedName = storedName
                        if (storedFileName.isEmpty() && isJsonRec) {
                            val cachedSrc = resolveSourceFile(id, cat)
                            if (cachedSrc.exists() && cachedSrc.length() in 1L..(20L * 1024L * 1024L)) {
                                try {
                                    val raw = cachedSrc.readText(Charsets.UTF_8)
                                    val extracted = extractThemeDisplayName(raw, true, storedName)
                                    if (extracted.isNotBlank() && extracted != storedName) {
                                        resolvedName = extracted
                                        storedFileName = storedName
                                        purgedNonImages = true
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        sourcesMap[id] = SourceRecord(
                            id = id,
                            name = resolvedName,
                            docUri = o.optString("docUri", ""),
                            isJson = isJsonRec,
                            urls = urlList,
                            scannedAt = o.optLong("scannedAt", 0L),
                            contentHash = o.optString("contentHash", ""),
                            category = cat,
                            folderName = o.optString("folderName", ""),
                            categories = cats,
                            fileName = storedFileName
                        )
                    }
                }
            } catch (_: Exception) {
            }
            refreshStatsLocked()
        }
        if (purgedNonImages) {
            saveIndex()
        }
    }

    fun saveIndex() {
        val snapshotJson: String
        synchronized(lock) {
            val root = JSONObject()
            root.put("version", 2)

            val urlsObj = JSONObject()
            for ((u, rec) in urlsMap) {
                val o = JSONObject()
                o.put("hash", rec.hash)
                o.put("file", rec.file)
                o.put("mime", rec.mime)
                o.put("size", rec.size)
                o.put("status", rec.status.raw)
                o.put("error", rec.error)
                o.put("updatedAt", rec.updatedAt)
                o.put("sources", JSONArray(rec.sources))
                urlsObj.put(u, o)
            }
            root.put("urls", urlsObj)

            val srcObj = JSONObject()
            for ((id, s) in sourcesMap) {
                val o = JSONObject()
                o.put("name", s.name)
                o.put("docUri", s.docUri)
                o.put("isJson", s.isJson)
                o.put("urls", JSONArray(s.urls))
                o.put("scannedAt", s.scannedAt)
                o.put("contentHash", s.contentHash)
                o.put("category", s.category)
                o.put("folderName", s.folderName)
                o.put("categories", JSONArray(s.categories))
                if (s.fileName.isNotEmpty()) o.put("fileName", s.fileName)
                srcObj.put(id, o)
            }
            root.put("sources", srcObj)
            snapshotJson = root.toString()
            refreshStatsLocked()
        }

        val tmp = File(vaultDir, "index.json.tmp")
        tmp.writeText(snapshotJson, Charsets.UTF_8)
        tmp.renameTo(indexFile)
    }

    private fun refreshStatsLocked() {
        var ok = 0
        var err = 0
        var pending = 0
        var ignored = 0
        var bytes = 0L
        val countedFiles = HashSet<String>()
        for (r in urlsMap.values) {
            when (r.status) {
                CacheStatus.OK -> {
                    ok++
                    if (r.file.isNotEmpty() && countedFiles.add(r.file)) {
                        bytes += r.size
                    }
                }
                CacheStatus.ERROR -> err++
                CacheStatus.IGNORED -> ignored++
                CacheStatus.PENDING -> pending++
            }
        }
        _statsFlow.value = VaultStats(
            totalSources = sourcesMap.size,
            totalUrls = urlsMap.size,
            okUrls = ok,
            errorUrls = err,
            pendingUrls = pending,
            ignoredUrls = ignored,
            totalBytes = bytes
        )
    }

    fun ignoreAllFailedUrls(): Int {
        var count = 0
        synchronized(lock) {
            for (rec in urlsMap.values) {
                if (rec.status == CacheStatus.ERROR) {
                    rec.status = CacheStatus.IGNORED
                    rec.updatedAt = System.currentTimeMillis()
                    count++
                }
            }
            if (count > 0) refreshStatsLocked()
        }
        if (count > 0) saveIndex()
        return count
    }

    data class DeleteSourceResult(val removedUrls: Int, val freedBytes: Long)

    /**
     * Deletes a single theme source and cleans up any URLs & cached files
     * exclusively referenced by this source.
     */
    fun deleteSource(sourceId: String): DeleteSourceResult? {
        val removedFilesToDelete = mutableListOf<File>()
        var removedUrls = 0
        var freedBytes = 0L

        synchronized(lock) {
            val source = sourcesMap.remove(sourceId) ?: return null

            try {
                resolveSourceFile(sourceId, source.category).delete()
                File(legacySourcesDir, "$sourceId.txt").delete()
            } catch (_: Exception) {
            }

            val orphanUrls = mutableListOf<UrlRecord>()
            val iter = urlsMap.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val rec = entry.value
                if (rec.sources.remove(sourceId) && rec.sources.isEmpty()) {
                    orphanUrls.add(rec)
                    iter.remove()
                    removedUrls++
                }
            }

            if (orphanUrls.isNotEmpty()) {
                val stillUsedFiles = HashSet<String>()
                for (rec in urlsMap.values) {
                    if (rec.file.isNotEmpty()) stillUsedFiles.add(rec.file)
                }
                val scheduledFiles = HashSet<String>()
                for (orphan in orphanUrls) {
                    if (orphan.file.isNotEmpty() && orphan.file !in stillUsedFiles && scheduledFiles.add(orphan.file)) {
                        val f = resolveAssetFile(orphan.file, orphan.mime, orphan.url)
                        if (f.exists()) {
                            freedBytes += f.length()
                            removedFilesToDelete.add(f)
                        }
                        val legacy = File(legacyImagesDir, orphan.file)
                        if (legacy.exists()) {
                            removedFilesToDelete.add(legacy)
                        }
                    }
                }
            }

            refreshStatsLocked()
        }

        for (f in removedFilesToDelete) {
            try {
                f.delete()
            } catch (_: Exception) {
            }
        }
        saveIndex()
        return DeleteSourceResult(removedUrls = removedUrls, freedBytes = freedBytes)
    }

    /**
     * Registers URLs owned by another module (characters / worldbooks / components)
     * into the shared asset pool. Returns count of newly discovered URLs.
     */
    fun registerExternalUrls(ownerKey: String, urls: List<String>): Int {
        if (urls.isEmpty()) return 0
        var added = 0
        synchronized(lock) {
            for (u in urls) {
                if (UrlExtractor.isNonImageUrl(u)) continue
                val existing = urlsMap[u]
                if (existing == null) {
                    urlsMap[u] = UrlRecord(
                        url = u,
                        hash = sha1Short(u),
                        sources = mutableListOf(ownerKey)
                    )
                    added++
                } else if (!existing.sources.contains(ownerKey)) {
                    existing.sources.add(ownerKey)
                }
            }
            refreshStatsLocked()
        }
        saveIndex()
        return added
    }

    /**
     * Unregisters an external module owner from the shared asset pool and deletes
     * any cached files no longer referenced by any module.
     */
    fun unregisterExternalOwner(ownerKey: String): DeleteSourceResult {
        val removedFilesToDelete = mutableListOf<File>()
        var removedUrls = 0
        var freedBytes = 0L

        synchronized(lock) {
            val orphanUrls = mutableListOf<UrlRecord>()
            val iter = urlsMap.entries.iterator()
            while (iter.hasNext()) {
                val rec = iter.next().value
                if (rec.sources.remove(ownerKey) && rec.sources.isEmpty()) {
                    orphanUrls.add(rec)
                    iter.remove()
                    removedUrls++
                }
            }
            if (orphanUrls.isNotEmpty()) {
                val stillUsedFiles = HashSet<String>()
                for (rec in urlsMap.values) {
                    if (rec.file.isNotEmpty()) stillUsedFiles.add(rec.file)
                }
                val scheduled = HashSet<String>()
                for (orphan in orphanUrls) {
                    if (orphan.file.isNotEmpty() && orphan.file !in stillUsedFiles && scheduled.add(orphan.file)) {
                        val f = resolveAssetFile(orphan.file, orphan.mime, orphan.url)
                        if (f.exists()) {
                            freedBytes += f.length()
                            removedFilesToDelete.add(f)
                        }
                    }
                }
            }
            refreshStatsLocked()
        }

        for (f in removedFilesToDelete) {
            try { f.delete() } catch (_: Exception) {}
        }
        if (removedUrls > 0) saveIndex()
        return DeleteSourceResult(removedUrls, freedBytes)
    }

    fun getUrlRecordsForUrls(urls: List<String>): List<UrlRecord> {
        synchronized(lock) {
            return urls.map { u ->
                urlsMap[u]?.copy() ?: UrlRecord(url = u, hash = sha1Short(u))
            }
        }
    }

    fun getCountsForUrls(urls: List<String>): Triple<Int, Int, Int> {
        synchronized(lock) {
            var ok = 0
            var err = 0
            var pending = 0
            for (u in urls) {
                when (urlsMap[u]?.status) {
                    CacheStatus.OK -> ok++
                    CacheStatus.ERROR -> err++
                    else -> pending++
                }
            }
            return Triple(ok, err, pending)
        }
    }

    /**
     * Imports a single theme directly from raw text (used by SmartImporter, Share Intent & ZIP import).
     * Returns Pair(addedUrls, isDuplicate) or null if no external URLs found.
     */
    fun importRawThemeText(
        rawText: String,
        fileName: String,
        folderName: String = "导入"
    ): Pair<Int, Boolean>? {
        if (rawText.isBlank()) return null
        val (urls, isJson) = UrlExtractor.extract(rawText)
        val contentHash = sha256Short(rawText.trim().toByteArray(Charsets.UTF_8))
        val res = synchronized(lock) {
            registerSourceLocked(
                name = fileName,
                docUri = "",
                urls = urls,
                isJson = isJson,
                contentHash = contentHash,
                rawContent = rawText,
                folderName = folderName,
                allowEmptyUrls = true
            )
        }
        saveIndex()
        return res
    }

    /**
     * Imports one or more user-selected theme files (JSON / CSS / TXT / HTML) directly via SAF.
     */
    fun importFileUris(uris: List<Uri>): ScanResult {
        var scanned = 0
        var matched = 0
        var newUrls = 0
        var duplicateFiles = 0

        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(appContext, uri)
            val fileName = doc?.name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "theme_${System.currentTimeMillis()}.json"
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext == "png") continue
            scanned++

            try {
                val content: String = appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: continue
                if (content.isBlank()) continue
                val (urls, isJson) = UrlExtractor.extract(content)
                if (urls.isNotEmpty()) {
                    val contentHash = sha256Short(content.trim().toByteArray(Charsets.UTF_8))
                    synchronized(lock) {
                        val (added, isDup) = registerSourceLocked(
                            name = fileName,
                            docUri = uri.toString(),
                            urls = urls,
                            isJson = isJson,
                            contentHash = contentHash,
                            rawContent = content,
                            folderName = "手动导入"
                        )
                        if (isDup) {
                            duplicateFiles++
                        } else {
                            matched++
                            newUrls += added
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (matched > 0 || duplicateFiles > 0) {
            saveIndex()
        }
        return ScanResult(scanned, matched, newUrls, duplicateFiles)
    }

    /**
     * Extracts the internal theme name from SillyTavern theme JSON (`name` / `theme_name` / `title`),
     * falling back to the cleaned filename if not present.
     */
    private fun extractThemeDisplayName(rawContent: String, isJson: Boolean, fallbackFileName: String): String {
        val trimmed = rawContent.trimStart()
        if (trimmed.isNotEmpty() && (isJson || trimmed.startsWith("{"))) {
            try {
                val obj = JSONObject(trimmed)
                val candidates = listOf("name", "theme_name", "title")
                for (key in candidates) {
                    val v = obj.optString(key, "").trim()
                    if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) {
                        return v.replace(Regex("[\\r\\n]+"), " ").take(100)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val base = fallbackFileName.substringAfterLast('/')
        val stem = base.replace(Regex("\\.(json|css|html|htm|txt)$", RegexOption.IGNORE_CASE), "").trim()
        return stem.ifEmpty { base }
    }

    /**
     * Returns Pair(addedUrls, skippedDuplicateSource)
     */
    private fun registerSourceLocked(
        name: String,
        docUri: String,
        urls: List<String>,
        isJson: Boolean,
        contentHash: String,
        rawContent: String = "",
        folderName: String = "",
        allowEmptyUrls: Boolean = false
    ): Pair<Int, Boolean> {
        if (urls.isEmpty() && !allowEmptyUrls) return 0 to false
        val sourceId = sha1Short(if (docUri.isNotBlank()) docUri else name)
        val category = "ui"
        val displayName = extractThemeDisplayName(rawContent, isJson, name)

        // Check if an existing record already represents this exact file (by contentHash)
        val existingDup = if (contentHash.isNotEmpty()) {
            sourcesMap.values.firstOrNull { it.id != sourceId && it.contentHash == contentHash }
        } else null

        if (existingDup != null) {
            if (rawContent.isNotEmpty() && rawContent.length <= 20 * 1024 * 1024) {
                try {
                    val f = resolveSourceFile(existingDup.id, existingDup.category)
                    if (!f.exists() || f.length() == 0L) {
                        f.writeText(rawContent, Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                }
            }
            sourcesMap[existingDup.id] = existingDup.copy(
                name = displayName.ifBlank { existingDup.name },
                fileName = name.ifBlank { existingDup.fileName },
                docUri = docUri,
                folderName = folderName.ifBlank { existingDup.folderName }
            )
            return 0 to true
        }

        val legacySameName = sourcesMap.values.firstOrNull { it.id != sourceId && (it.name == name || it.fileName == name) && it.contentHash.isEmpty() }
        val targetId = legacySameName?.id ?: sourceId

        if (rawContent.isNotEmpty() && rawContent.length <= 20 * 1024 * 1024) {
            try {
                resolveSourceFile(targetId, category).writeText(rawContent, Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }

        sourcesMap[targetId] = SourceRecord(
            id = targetId,
            name = displayName,
            docUri = docUri,
            isJson = isJson,
            urls = urls,
            scannedAt = System.currentTimeMillis(),
            contentHash = contentHash,
            category = category,
            folderName = folderName,
            fileName = name
        )
        var addedUrls = 0
        for (u in urls) {
            val existing = urlsMap[u]
            if (existing == null) {
                urlsMap[u] = UrlRecord(
                    url = u,
                    hash = sha1Short(u),
                    sources = mutableListOf(targetId)
                )
                addedUrls++
            } else {
                if (!existing.sources.contains(targetId)) {
                    existing.sources.add(targetId)
                }
            }
        }
        return addedUrls to false
    }

    /**
     * Recursively scans an Android SAF folder for theme files (JSON / CSS / TXT / HTML).
     */
    fun scanTreeUri(
        treeUri: Uri,
        @Suppress("UNUSED_PARAMETER") includePng: Boolean = false,
        onProgress: (scannedFiles: Int, matchedFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选文件夹")

        val allowedExts = setOf("json", "css", "txt", "html", "htm")

        var scanned = 0
        var matched = 0
        var newUrls = 0
        var duplicateFiles = 0

        fun walk(dir: DocumentFile, prefix: String) {
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                return
            }
            for (child in children) {
                val childName = child.name ?: continue
                if (child.isDirectory) {
                    if (childName != "vault" && childName != ".git" && childName != "node_modules") {
                        val nextPrefix = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        walk(child, nextPrefix)
                    }
                    continue
                }
                if (!child.isFile) continue
                val ext = childName.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExts) continue
                if (child.length() <= 0L || child.length() > 30L * 1024L * 1024L) continue

                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)

                try {
                    val content = appContext.contentResolver.openInputStream(child.uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: continue
                    if (content.isBlank()) continue
                    val (urls, isJson) = UrlExtractor.extract(content)
                    if (urls.isNotEmpty()) {
                        val displayName = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        val parentFolder = if (prefix.isNotEmpty()) prefix.substringAfterLast('/') else (rootDoc.name ?: "")
                        val contentHash = sha256Short(content.trim().toByteArray(Charsets.UTF_8))
                        synchronized(lock) {
                            val (added, isDup) = registerSourceLocked(displayName, child.uri.toString(), urls, isJson, contentHash, content, parentFolder)
                            if (isDup) {
                                duplicateFiles++
                            } else {
                                matched++
                                newUrls += added
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        walk(rootDoc, "")
        onProgress(scanned, matched)
        saveIndex()
        return ScanResult(scanned, matched, newUrls, duplicateFiles)
    }

    /**
     * Directly scans a local File directory for theme files (JSON / CSS / TXT / HTML).
     */
    fun scanLocalDirectory(
        rootDir: File,
        @Suppress("UNUSED_PARAMETER") includePng: Boolean = false,
        onProgress: (scannedFiles: Int, matchedFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw IllegalStateException("找不到目录或无读取权限：${rootDir.absolutePath}")
        }
        val testList = rootDir.listFiles()
            ?: throw IllegalStateException("系统限制了直接读取该目录（请尝试授权 SAF 或所有文件访问权限）")

        val allowedExts = setOf("json", "css", "txt", "html", "htm")

        var scanned = 0
        var matched = 0
        var newUrls = 0
        var duplicateFiles = 0

        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val childName = child.name
                if (child.isDirectory) {
                    if (childName != "vault" && childName != ".git" && childName != "node_modules") {
                        val nextPrefix = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        walk(child, nextPrefix)
                    }
                    continue
                }
                if (!child.isFile) continue
                val ext = childName.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExts) continue
                val len = child.length()
                if (len <= 0L || len > 30L * 1024L * 1024L) continue

                scanned++
                if (scanned % 10 == 0) onProgress(scanned, matched)

                try {
                    val content = child.readText(Charsets.UTF_8)
                    if (content.isBlank()) continue
                    val (urls, isJson) = UrlExtractor.extract(content)
                    if (urls.isNotEmpty()) {
                        val displayName = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        val parentFolder = if (prefix.isNotEmpty()) prefix.substringAfterLast('/') else rootDir.name
                        val contentHash = sha256Short(content.trim().toByteArray(Charsets.UTF_8))
                        synchronized(lock) {
                            val (added, isDup) = registerSourceLocked(displayName, "file://${child.absolutePath}", urls, isJson, contentHash, content, parentFolder)
                            if (isDup) {
                                duplicateFiles++
                            } else {
                                matched++
                                newUrls += added
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (testList.isNotEmpty()) {
            walk(rootDir, "")
        }
        onProgress(scanned, matched)
        saveIndex()
        return ScanResult(scanned, matched, newUrls, duplicateFiles)
    }

    fun getUrlsToDownload(mode: String): List<String> {
        synchronized(lock) {
            return urlsMap.values.filter { rec ->
                when (mode) {
                    "failed" -> rec.status == CacheStatus.ERROR
                    "all" -> rec.status == CacheStatus.PENDING || rec.status == CacheStatus.ERROR
                    else -> rec.status == CacheStatus.PENDING
                }
            }.map { it.url }
        }
    }

    fun markDownloadSuccess(url: String, bytes: ByteArray, mime: String, throttleStats: Boolean = false) {
        synchronized(lock) {
            if (!urlsMap.containsKey(url)) return
        }
        // Content-level SHA-256 deduplication: store fonts under assets/fonts/, images under assets/images/
        val contentHash = sha256Short(bytes)
        val ext = ImageDownloader.mimeToExt(mime, url)
        val isFont = isFontAsset(mime, "f.$ext", url)
        val fileName = "${if (isFont) "font" else "img"}_$contentHash.$ext"
        val targetFile = resolveAssetFile(fileName, mime, url)
        targetFile.parentFile?.mkdirs()
        if (!targetFile.exists()) {
            targetFile.writeBytes(bytes)
        }

        synchronized(lock) {
            val rec = urlsMap[url] ?: return
            rec.file = fileName
            rec.mime = mime
            rec.size = targetFile.length()
            rec.status = CacheStatus.OK
            rec.error = ""
            rec.updatedAt = System.currentTimeMillis()
            maybeRefreshStatsLocked(throttleStats)
        }
    }

    fun markDownloadError(url: String, errorMsg: String, throttleStats: Boolean = false) {
        synchronized(lock) {
            val rec = urlsMap[url] ?: return
            rec.status = CacheStatus.ERROR
            rec.error = errorMsg
            rec.updatedAt = System.currentTimeMillis()
            maybeRefreshStatsLocked(throttleStats)
        }
    }

    private fun maybeRefreshStatsLocked(throttle: Boolean) {
        val now = System.currentTimeMillis()
        if (!throttle || now - lastStatsRefreshAt >= 500L) {
            lastStatsRefreshAt = now
            refreshStatsLocked()
        }
    }

    fun replaceImageFromUri(url: String, imageUri: Uri): Boolean {
        val bytes = appContext.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: return false
        val mime = ImageDownloader.detectImageMime(bytes)
            ?: appContext.contentResolver.getType(imageUri)
            ?: "image/png"
        markDownloadSuccess(url, bytes, mime)
        saveIndex()
        return true
    }

    fun setSourceCategories(sourceId: String, categories: List<String>) {
        synchronized(lock) {
            val cur = sourcesMap[sourceId] ?: return
            sourcesMap[sourceId] = cur.copy(categories = categories.distinct())
        }
        saveIndex()
    }

    fun batchSetSourceCategories(ids: List<String>, categories: List<String>, append: Boolean) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            for (id in ids) {
                val cur = sourcesMap[id] ?: continue
                val next = if (append) (cur.categories + categories).distinct() else categories.distinct()
                sourcesMap[id] = cur.copy(categories = next)
            }
        }
        saveIndex()
    }

    fun batchDeleteSources(ids: List<String>): Int {
        var deleted = 0
        for (id in ids) {
            if (deleteSource(id) != null) deleted++
        }
        return deleted
    }

    fun renameSource(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val cur = sourcesMap[id] ?: return false
            sourcesMap[id] = cur.copy(name = clean)
        }
        saveIndex()
        return true
    }

    /**
     * Overwrites an existing theme's stored raw content & extracted URLs while preserving
     * its ID, categories, and folder metadata (used when pulling updated themes from Tavern).
     */
    fun overwriteSourceContent(id: String, rawText: String, newFileName: String = ""): Boolean {
        if (rawText.isBlank()) return false
        val (urls, isJson) = UrlExtractor.extract(rawText)
        val contentHash = sha256Short(rawText.trim().toByteArray(Charsets.UTF_8))
        synchronized(lock) {
            val cur = sourcesMap[id] ?: return false
            try {
                resolveSourceFile(id, cur.category).writeText(rawText, Charsets.UTF_8)
            } catch (_: Exception) {
                return false
            }
            val displayName = extractThemeDisplayName(rawText, isJson, newFileName.ifBlank { cur.fileName.ifBlank { cur.name } })
            sourcesMap[id] = cur.copy(
                name = displayName.ifBlank { cur.name },
                fileName = newFileName.ifBlank { cur.fileName },
                isJson = isJson,
                urls = urls,
                contentHash = contentHash,
                scannedAt = System.currentTimeMillis()
            )
            for (u in urls) {
                val existing = urlsMap[u]
                if (existing == null) {
                    urlsMap[u] = UrlRecord(url = u, hash = sha1Short(u), sources = mutableListOf(id))
                } else if (!existing.sources.contains(id)) {
                    existing.sources.add(id)
                }
            }
        }
        saveIndex()
        return true
    }

    fun getAllSources(): List<SourceRecord> = synchronized(lock) {
        sourcesMap.values.sortedByDescending { it.scannedAt }
    }

    fun searchSourceRows(
        query: String,
        filter: String = "all",
        categoryFilter: String = ""
    ): List<SourceRowUi> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            var list = sourcesMap.values.asSequence()
            if (categoryFilter.isNotBlank()) {
                list = list.filter { s ->
                    s.categories.any { c -> c == categoryFilter || c.startsWith("$categoryFilter/") }
                }
            }
            if (q.isNotEmpty()) {
                list = list.filter { s ->
                    s.name.lowercase().contains(q) ||
                        s.fileName.lowercase().contains(q) ||
                        s.categories.any { it.lowercase().contains(q) } ||
                        s.urls.any { it.lowercase().contains(q) }
                }
            }
            val rows = list.map { s ->
                var ok = 0
                var err = 0
                var pending = 0
                for (u in s.urls) {
                    when (urlsMap[u]?.status) {
                        CacheStatus.OK -> ok++
                        CacheStatus.ERROR -> err++
                        else -> pending++
                    }
                }
                SourceRowUi(s, ok, err, pending)
            }.filter { r ->
                when (filter) {
                    "installed" -> TavernBridgeManager.getInstance(appContext)
                        .isThemeInstalled(r.source.name, r.source.fileName)
                    "not_installed" -> !TavernBridgeManager.getInstance(appContext)
                        .isThemeInstalled(r.source.name, r.source.fileName)
                    "has_error" -> r.errCount > 0
                    "incomplete" -> r.okCount < r.source.urls.size
                    "completed" -> r.source.urls.isNotEmpty() && r.okCount == r.source.urls.size
                    else -> true
                }
            }.toList()

            return when (filter) {
                "most_imgs" -> rows.sortedByDescending { it.source.urls.size }
                else -> rows.sortedByDescending { it.source.scannedAt }
            }
        }
    }

    fun searchSources(query: String, filter: String = "all"): List<SourceRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            var list = if (q.isEmpty()) {
                sourcesMap.values.toList()
            } else {
                sourcesMap.values.filter { s ->
                    s.name.lowercase().contains(q) ||
                        s.fileName.lowercase().contains(q) ||
                        s.urls.any { it.lowercase().contains(q) }
                }
            }
            list = when (filter) {
                "has_error" -> list.filter { s -> s.urls.any { urlsMap[it]?.status == CacheStatus.ERROR } }
                "incomplete" -> list.filter { s -> s.urls.any { urlsMap[it]?.status != CacheStatus.OK } }
                "completed" -> list.filter { s -> s.urls.isNotEmpty() && s.urls.all { urlsMap[it]?.status == CacheStatus.OK } }
                else -> list
            }
            return when (filter) {
                "most_imgs" -> list.sortedByDescending { it.urls.size }
                else -> list.sortedByDescending { it.scannedAt }
            }
        }
    }

    fun getSourceById(id: String): SourceRecord? = synchronized(lock) { sourcesMap[id] }

    fun getUrlRecordsForSource(source: SourceRecord): List<UrlRecord> {
        synchronized(lock) {
            return source.urls.map { u ->
                urlsMap[u]?.copy() ?: UrlRecord(url = u, hash = sha1Short(u))
            }
        }
    }

    fun getSourceCounts(source: SourceRecord): Triple<Int, Int, Int> {
        synchronized(lock) {
            var ok = 0
            var err = 0
            var pending = 0
            for (u in source.urls) {
                when (urlsMap[u]?.status) {
                    CacheStatus.OK -> ok++
                    CacheStatus.ERROR -> err++
                    else -> pending++
                }
            }
            return Triple(ok, err, pending)
        }
    }

    fun searchUrls(query: String, statusFilter: String): List<UrlRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            val filtered = urlsMap.values.filter { rec ->
                val statusOk = when (statusFilter) {
                    "ok", "largest" -> rec.status == CacheStatus.OK
                    "fonts" -> isFontAsset(rec.mime, rec.file, rec.url)
                    "error" -> rec.status == CacheStatus.ERROR
                    "ignored" -> rec.status == CacheStatus.IGNORED
                    "pending" -> rec.status == CacheStatus.PENDING
                    else -> true
                }
                if (!statusOk) return@filter false
                if (q.isEmpty()) return@filter true
                if (rec.url.lowercase().contains(q)) return@filter true
                rec.sources.any { sid -> sourcesMap[sid]?.name?.lowercase()?.contains(q) == true }
            }.map { it.copy() }

            return when (statusFilter) {
                "largest" -> filtered.sortedByDescending { it.size }
                else -> filtered.sortedByDescending { it.updatedAt }
            }
        }
    }

    fun getImageFile(rec: UrlRecord): File? {
        if (rec.status != CacheStatus.OK || rec.file.isEmpty()) return null
        val f = resolveAssetFile(rec.file, rec.mime, rec.url)
        if (f.exists()) return f
        val legacy = File(legacyImagesDir, rec.file)
        return if (legacy.exists()) legacy else null
    }

    fun getCachedAssetByUrl(url: String): Pair<File, String>? {
        val rec = synchronized(lock) { urlsMap[url]?.copy() } ?: return null
        val f = getImageFile(rec) ?: return null
        return f to rec.mime.ifBlank { "application/octet-stream" }
    }

    fun isKnownDeadUrl(url: String): Boolean {
        val status = synchronized(lock) { urlsMap[url]?.status }
        return status == CacheStatus.ERROR || status == CacheStatus.IGNORED
    }

    fun readSourceRawText(sourceId: String): Pair<String, String> {
        val source = getSourceById(sourceId) ?: throw IllegalArgumentException("找不到该美化记录")
        val baseName = source.fileName.ifBlank { source.name }.substringAfterLast('/')

        // 1. Prefer local cached copy (categorized under themes/<category>/)
        val cachedFile = resolveSourceFile(sourceId, source.category)
        if (cachedFile.exists() && cachedFile.length() > 0L) {
            return baseName to cachedFile.readText(Charsets.UTF_8)
        }
        val legacyCached = File(legacySourcesDir, "$sourceId.txt")
        if (legacyCached.exists() && legacyCached.length() > 0L) {
            return baseName to legacyCached.readText(Charsets.UTF_8)
        }

        // 2. Fallback to original file/SAF URI
        if (source.docUri.isBlank()) throw IllegalStateException("请先回首页点一次「重新扫描」以生成预览缓存")
        val rawText = try {
            if (source.docUri.startsWith("file://")) {
                val f = File(source.docUri.removePrefix("file://"))
                if (!f.exists()) throw IllegalStateException("原文件不存在")
                f.readText(Charsets.UTF_8)
            } else {
                val uri = Uri.parse(source.docUri)
                appContext.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: throw IllegalStateException("无法读取原文件")
            }
        } catch (_: Exception) {
            throw IllegalStateException("原文件授权已过期，请回首页点一次「重新扫描」即可离线预览")
        }

        // Save local copy for next time
        try {
            if (rawText.length <= 2 * 1024 * 1024) {
                cachedFile.parentFile?.mkdirs()
                cachedFile.writeText(rawText, Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
        return baseName to rawText
    }

    fun repairSourceById(
        sourceId: String,
        mode: String = "base64_webp",
        pathPrefix: String = "/user/images/vault/",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RepairResult {
        val (baseName, rawText) = readSourceRawText(sourceId)
        return repairRawText(rawText, baseName, mode, pathPrefix, onProgress)
    }

    fun repairRawText(
        rawText: String,
        fileName: String,
        mode: String = "base64_webp",
        pathPrefix: String = "/user/images/vault/",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RepairResult {
        val (urls, isJson) = UrlExtractor.extract(rawText)
        val urlMap = HashMap<String, String>()
        var missing = 0
        val cleanPrefix = if (pathPrefix.isNotEmpty() && !pathPrefix.endsWith("/")) "$pathPrefix/" else pathPrefix

        urls.forEachIndexed { index, u ->
            onProgress(index + 1, urls.size)
            val rec = synchronized(lock) { urlsMap[u]?.copy() }
            val imgFile = rec?.let { getImageFile(it) }
            if (rec != null && imgFile != null) {
                val mime = rec.mime.ifBlank { "image/png" }
                when (mode) {
                    "catbox" -> {
                        val uploadedUrl = ImageHostUploader.upload(appContext, imgFile, mime)
                        urlMap[u] = uploadedUrl
                    }
                    "path" -> {
                        urlMap[u] = cleanPrefix + imgFile.name
                    }
                    else -> {
                        val (bytes, outMime) = ImageHostUploader.compressForBase64(imgFile, mime)
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        urlMap[u] = "data:$outMime;base64,$b64"
                    }
                }
            } else {
                missing++
            }
        }

        val sortedEntries = urlMap.entries.sortedByDescending { it.key.length }
        val replaceAll: (String) -> String = { str ->
            var out = str
            for ((oldUrl, newVal) in sortedEntries) {
                if (out.contains(oldUrl)) out = out.replace(oldUrl, newVal)
                if (!isJson) {
                    val esc = oldUrl.replace("/", "\\/")
                    if (esc != oldUrl && out.contains(esc)) out = out.replace(esc, newVal)
                }
            }
            out
        }

        val repairedText = if (isJson) {
            val trimmed = rawText.trim()
            if (trimmed.startsWith("{")) {
                val replaced = UrlExtractor.replaceInJson(JSONObject(trimmed), replaceAll) as JSONObject
                replaced.toString(2)
            } else {
                val replaced = UrlExtractor.replaceInJson(JSONArray(trimmed), replaceAll) as JSONArray
                replaced.toString(2)
            }
        } else {
            replaceAll(rawText)
        }

        return RepairResult(
            fileName = fileName,
            isJson = isJson,
            totalUrls = urls.size,
            matchedCount = urlMap.size,
            missingCount = missing,
            repairedText = repairedText
        )
    }

    data class ExportZipResult(val imageCount: Int, val sourceCount: Int, val totalBytes: Long)

    /**
     * Streams all cached images + human-readable manifest.json + index.json into a ZIP archive.
     * Uses fast level-0 deflate streaming so even thousands of images pack quickly with low RAM.
     */
    fun exportVaultToZip(
        outputStream: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportZipResult {
        val okRecords: List<UrlRecord>
        val allSources: List<SourceRecord>
        val manifestJson: String

        synchronized(lock) {
            okRecords = urlsMap.values
                .filter { it.status == CacheStatus.OK && it.file.isNotEmpty() && getImageFile(it) != null }
                .map { it.copy() }
            allSources = sourcesMap.values.toList()

            val manifestRoot = JSONObject()
            manifestRoot.put("exportedAt", System.currentTimeMillis())
            manifestRoot.put("totalSources", allSources.size)
            manifestRoot.put("totalCachedAssets", okRecords.size)

            val srcArr = JSONArray()
            for (s in allSources) {
                val sObj = JSONObject()
                sObj.put("name", s.name)
                sObj.put("category", s.category)
                val imgArr = JSONArray()
                for (u in s.urls) {
                    val rec = urlsMap[u]
                    if (rec != null && rec.status == CacheStatus.OK && rec.file.isNotEmpty()) {
                        val sub = if (isFontAsset(rec.mime, rec.file, u)) "assets/fonts" else "assets/images"
                        val iObj = JSONObject()
                        iObj.put("file", "$sub/${rec.file}")
                        iObj.put("url", u)
                        iObj.put("size", rec.size)
                        imgArr.put(iObj)
                    }
                }
                sObj.put("cachedCount", imgArr.length())
                sObj.put("totalUrls", s.urls.size)
                sObj.put("assets", imgArr)
                srcArr.put(sObj)
            }
            manifestRoot.put("sources", srcArr)
            manifestJson = manifestRoot.toString(2)
        }

        val total = okRecords.size
        var writtenBytes = 0L
        val buffer = ByteArray(32 * 1024)

        ZipOutputStream(BufferedOutputStream(outputStream, 64 * 1024)).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)

            // 1. Human-readable manifest.json
            val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()
            writtenBytes += manifestBytes.size

            // 2. Raw index.json for backup/restore
            if (indexFile.exists()) {
                zos.putNextEntry(ZipEntry("index.json"))
                indexFile.inputStream().use { inp ->
                    while (true) {
                        val n = inp.read(buffer)
                        if (n <= 0) break
                        zos.write(buffer, 0, n)
                        writtenBytes += n
                    }
                }
                zos.closeEntry()
            }

            // 3. All cached assets under assets/images/ and assets/fonts/
            val seenFiles = HashSet<String>()
            okRecords.forEachIndexed { idx, rec ->
                onProgress(idx + 1, total)
                val sub = if (isFontAsset(rec.mime, rec.file, rec.url)) "assets/fonts" else "assets/images"
                val entryName = "$sub/${rec.file}"
                if (!seenFiles.add(entryName)) return@forEachIndexed
                val assetFile = getImageFile(rec) ?: return@forEachIndexed

                zos.putNextEntry(ZipEntry(entryName))
                assetFile.inputStream().use { inp ->
                    while (true) {
                        val n = inp.read(buffer)
                        if (n <= 0) break
                        zos.write(buffer, 0, n)
                        writtenBytes += n
                    }
                }
                zos.closeEntry()
            }
        }

        return ExportZipResult(
            imageCount = total,
            sourceCount = allSources.size,
            totalBytes = writtenBytes
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: VaultRepository? = null

        fun getInstance(context: Context): VaultRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VaultRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
