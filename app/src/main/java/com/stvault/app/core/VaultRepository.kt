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
            File(themesRootDir, "ui").mkdirs()
            File(themesRootDir, "regex").mkdirs()
            File(themesRootDir, "cards").mkdirs()
            File(themesRootDir, "other").mkdirs()
            loadIndex()
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

    private fun classifySourceCategory(name: String, rawContent: String): String {
        val lowerName = name.lowercase()
        if (lowerName.endsWith(".png")) return "cards"
        if (lowerName.endsWith(".css")) return "ui"
        val trimmed = rawContent.trim()
        if (trimmed.startsWith("{")) {
            if (trimmed.contains("\"findRegex\"") && trimmed.contains("\"replaceString\"")) return "regex"
            if (trimmed.contains("\"custom_css\"") || trimmed.contains("\"main_text_color\"") || trimmed.contains("\"blur_tint_color\"")) return "ui"
        }
        return "other"
    }

    private fun resolveSourceFile(sourceId: String, category: String): File {
        val safeCat = if (category in setOf("ui", "regex", "cards", "other")) category else "other"
        val dir = File(themesRootDir, safeCat)
        dir.mkdirs()
        return File(dir, "$sourceId.txt")
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
                            val newFile = resolveAssetFile(fileName, mime, u)
                            if (!newFile.exists()) {
                                val oldFile = File(legacyImagesDir, fileName)
                                if (oldFile.exists()) {
                                    newFile.parentFile?.mkdirs()
                                    if (!oldFile.renameTo(newFile)) {
                                        oldFile.copyTo(newFile, overwrite = true)
                                        oldFile.delete()
                                    }
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
                        if (urlList.isEmpty()) continue
                        val cat = o.optString("category", "ui").ifBlank { "ui" }
                        // Migrate legacy vault/sources/<id>.txt -> vault/themes/<cat>/<id>.txt
                        val oldSrc = File(legacySourcesDir, "$id.txt")
                        if (oldSrc.exists()) {
                            val newSrc = resolveSourceFile(id, cat)
                            if (!newSrc.exists()) {
                                if (!oldSrc.renameTo(newSrc)) {
                                    oldSrc.copyTo(newSrc, overwrite = true)
                                    oldSrc.delete()
                                }
                            }
                        }
                        sourcesMap[id] = SourceRecord(
                            id = id,
                            name = o.optString("name", id),
                            docUri = o.optString("docUri", ""),
                            isJson = o.optBoolean("isJson", false),
                            urls = urlList,
                            scannedAt = o.optLong("scannedAt", 0L),
                            contentHash = o.optString("contentHash", ""),
                            category = cat
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
                CacheStatus.PENDING -> pending++
            }
        }
        _statsFlow.value = VaultStats(
            totalSources = sourcesMap.size,
            totalUrls = urlsMap.size,
            okUrls = ok,
            errorUrls = err,
            pendingUrls = pending,
            totalBytes = bytes
        )
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
        rawContent: String = ""
    ): Pair<Int, Boolean> {
        if (urls.isEmpty()) return 0 to false
        val sourceId = sha1Short(if (docUri.isNotBlank()) docUri else name)

        // Skip duplicate beautification file with identical content hash (different file path/name)
        if (contentHash.isNotEmpty()) {
            val dup = sourcesMap.values.any { it.id != sourceId && it.contentHash == contentHash }
            if (dup) {
                return 0 to true
            }
        }

        val category = classifySourceCategory(name, rawContent)

        // Cache a local copy of the theme text into categorized themes/<category>/ folder
        if (rawContent.isNotEmpty() && rawContent.length <= 2 * 1024 * 1024) {
            try {
                resolveSourceFile(sourceId, category).writeText(rawContent, Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }

        sourcesMap[sourceId] = SourceRecord(
            id = sourceId,
            name = name,
            docUri = docUri,
            isJson = isJson,
            urls = urls,
            scannedAt = System.currentTimeMillis(),
            contentHash = contentHash,
            category = category
        )
        var addedUrls = 0
        for (u in urls) {
            val existing = urlsMap[u]
            if (existing == null) {
                urlsMap[u] = UrlRecord(
                    url = u,
                    hash = sha1Short(u),
                    sources = mutableListOf(sourceId)
                )
                addedUrls++
            } else {
                if (!existing.sources.contains(sourceId)) {
                    existing.sources.add(sourceId)
                }
            }
        }
        return addedUrls to false
    }

    /**
     * Recursively scans an Android SAF folder selected by the user.
     */
    fun scanTreeUri(
        treeUri: Uri,
        includePng: Boolean,
        onProgress: (scannedFiles: Int, matchedFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalStateException("无法读取所选文件夹")

        val allowedExts = mutableSetOf("json", "css", "txt", "html", "htm")
        if (includePng) allowedExts.add("png")

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
                    val content: String = if (ext == "png") {
                        val bytes = appContext.contentResolver.openInputStream(child.uri)?.use { it.readBytes() }
                            ?: continue
                        UrlExtractor.extractCharaFromPng(bytes) ?: continue
                    } else {
                        appContext.contentResolver.openInputStream(child.uri)?.use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        } ?: continue
                    }
                    if (content.isBlank()) continue
                    val (urls, isJson) = UrlExtractor.extract(content)
                    if (urls.isNotEmpty()) {
                        val displayName = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        val contentHash = sha256Short(content.trim().toByteArray(Charsets.UTF_8))
                        synchronized(lock) {
                            val (added, isDup) = registerSourceLocked(displayName, child.uri.toString(), urls, isJson, contentHash, content)
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
     * Directly scans a local File directory (e.g., TT SillyTavern themes path when accessible).
     */
    fun scanLocalDirectory(
        rootDir: File,
        includePng: Boolean,
        onProgress: (scannedFiles: Int, matchedFiles: Int) -> Unit = { _, _ -> }
    ): ScanResult {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw IllegalStateException("找不到目录或无读取权限：${rootDir.absolutePath}")
        }
        val testList = rootDir.listFiles()
            ?: throw IllegalStateException("系统限制了直接读取该目录（请尝试授权 SAF 或所有文件访问权限）")

        val allowedExts = mutableSetOf("json", "css", "txt", "html", "htm")
        if (includePng) allowedExts.add("png")

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
                    val content: String = if (ext == "png") {
                        UrlExtractor.extractCharaFromPng(child.readBytes()) ?: continue
                    } else {
                        child.readText(Charsets.UTF_8)
                    }
                    if (content.isBlank()) continue
                    val (urls, isJson) = UrlExtractor.extract(content)
                    if (urls.isNotEmpty()) {
                        val displayName = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        val contentHash = sha256Short(content.trim().toByteArray(Charsets.UTF_8))
                        synchronized(lock) {
                            val (added, isDup) = registerSourceLocked(displayName, "file://${child.absolutePath}", urls, isJson, contentHash, content)
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
                    "all" -> rec.status != CacheStatus.OK
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

    fun searchSources(query: String, filter: String = "all"): List<SourceRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            var list = if (q.isEmpty()) {
                sourcesMap.values.toList()
            } else {
                sourcesMap.values.filter { s ->
                    s.name.lowercase().contains(q) || s.urls.any { it.lowercase().contains(q) }
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

    fun readSourceRawText(sourceId: String): Pair<String, String> {
        val source = getSourceById(sourceId) ?: throw IllegalArgumentException("找不到该美化记录")
        val baseName = source.name.substringAfterLast('/')

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
                        val uploadedUrl = ImageHostUploader.uploadToCatbox(imgFile, mime)
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
