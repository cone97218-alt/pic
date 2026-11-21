package com.stvault.app.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.stvault.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val imagesDir: File = File(vaultDir, "images")
    private val indexFile: File = File(vaultDir, "index.json")

    private val urlsMap = LinkedHashMap<String, UrlRecord>()
    private val sourcesMap = LinkedHashMap<String, SourceRecord>()

    private val _statsFlow = MutableStateFlow(VaultStats())
    val statsFlow: StateFlow<VaultStats> = _statsFlow.asStateFlow()

    private val _progressFlow = MutableStateFlow(DownloadProgress())
    val progressFlow: StateFlow<DownloadProgress> = _progressFlow.asStateFlow()

    init {
        imagesDir.mkdirs()
        loadIndex()
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

    private fun loadIndex() {
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
                        val o = urlsObj.optJSONObject(u) ?: continue
                        val srcs = mutableListOf<String>()
                        val sArr = o.optJSONArray("sources")
                        if (sArr != null) {
                            for (i in 0 until sArr.length()) srcs.add(sArr.optString(i))
                        }
                        var status = CacheStatus.from(o.optString("status"))
                        var fileName = o.optString("file", "")
                        var size = o.optLong("size", 0L)
                        if (status == CacheStatus.OK && (fileName.isEmpty() || !File(imagesDir, fileName).exists())) {
                            status = CacheStatus.PENDING
                            fileName = ""
                            size = 0L
                        }
                        urlsMap[u] = UrlRecord(
                            url = u,
                            hash = o.optString("hash", sha1Short(u)),
                            file = fileName,
                            mime = o.optString("mime", ""),
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
                            for (i in 0 until uArr.length()) urlList.add(uArr.optString(i))
                        }
                        sourcesMap[id] = SourceRecord(
                            id = id,
                            name = o.optString("name", id),
                            docUri = o.optString("docUri", ""),
                            isJson = o.optBoolean("isJson", false),
                            urls = urlList,
                            scannedAt = o.optLong("scannedAt", 0L)
                        )
                    }
                }
            } catch (_: Exception) {
            }
            refreshStatsLocked()
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
        for (r in urlsMap.values) {
            when (r.status) {
                CacheStatus.OK -> {
                    ok++
                    bytes += r.size
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

    private fun registerSourceLocked(name: String, docUri: String, urls: List<String>, isJson: Boolean): Int {
        if (urls.isEmpty()) return 0
        val sourceId = sha1Short(if (docUri.isNotBlank()) docUri else name)
        sourcesMap[sourceId] = SourceRecord(
            id = sourceId,
            name = name,
            docUri = docUri,
            isJson = isJson,
            urls = urls,
            scannedAt = System.currentTimeMillis()
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
        return addedUrls
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
                        matched++
                        val displayName = if (prefix.isEmpty()) childName else "$prefix/$childName"
                        synchronized(lock) {
                            newUrls += registerSourceLocked(displayName, child.uri.toString(), urls, isJson)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        walk(rootDoc, "")
        onProgress(scanned, matched)
        saveIndex()
        return ScanResult(scanned, matched, newUrls)
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

    fun markDownloadSuccess(url: String, bytes: ByteArray, mime: String) {
        val hash: String
        synchronized(lock) {
            val rec = urlsMap[url] ?: return
            hash = rec.hash
        }
        val ext = ImageDownloader.mimeToExt(mime, url)
        val fileName = "$hash.$ext"
        File(imagesDir, fileName).writeBytes(bytes)

        synchronized(lock) {
            val rec = urlsMap[url] ?: return
            rec.file = fileName
            rec.mime = mime
            rec.size = bytes.size.toLong()
            rec.status = CacheStatus.OK
            rec.error = ""
            rec.updatedAt = System.currentTimeMillis()
            refreshStatsLocked()
        }
    }

    fun markDownloadError(url: String, errorMsg: String) {
        synchronized(lock) {
            val rec = urlsMap[url] ?: return
            rec.status = CacheStatus.ERROR
            rec.error = errorMsg
            rec.updatedAt = System.currentTimeMillis()
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

    fun searchSources(query: String): List<SourceRecord> {
        val q = query.trim().lowercase()
        synchronized(lock) {
            val list = if (q.isEmpty()) {
                sourcesMap.values.toList()
            } else {
                sourcesMap.values.filter { s ->
                    s.name.lowercase().contains(q) || s.urls.any { it.lowercase().contains(q) }
                }
            }
            return list.sortedByDescending { it.scannedAt }
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
            return urlsMap.values.filter { rec ->
                val statusOk = when (statusFilter) {
                    "ok" -> rec.status == CacheStatus.OK
                    "error" -> rec.status == CacheStatus.ERROR
                    "pending" -> rec.status == CacheStatus.PENDING
                    else -> true
                }
                if (!statusOk) return@filter false
                if (q.isEmpty()) return@filter true
                if (rec.url.lowercase().contains(q)) return@filter true
                rec.sources.any { sid -> sourcesMap[sid]?.name?.lowercase()?.contains(q) == true }
            }.map { it.copy() }
        }
    }

    fun getImageFile(rec: UrlRecord): File? {
        if (rec.status != CacheStatus.OK || rec.file.isEmpty()) return null
        val f = File(imagesDir, rec.file)
        return if (f.exists()) f else null
    }

    fun repairSourceById(
        sourceId: String,
        mode: String = "base64_webp",
        pathPrefix: String = "/user/images/vault/",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RepairResult {
        val source = getSourceById(sourceId) ?: throw IllegalArgumentException("找不到该美化记录")
        if (source.docUri.isBlank()) throw IllegalStateException("该记录缺少原文件权限，请去「复活」页手动选择文件")
        val uri = Uri.parse(source.docUri)
        val rawText = appContext.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("无法读取原文件，可能文件已被移动或删除")
        val baseName = source.name.substringAfterLast('/')
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
                .filter { it.status == CacheStatus.OK && it.file.isNotEmpty() && File(imagesDir, it.file).exists() }
                .map { it.copy() }
            allSources = sourcesMap.values.toList()

            val manifestRoot = JSONObject()
            manifestRoot.put("exportedAt", System.currentTimeMillis())
            manifestRoot.put("totalSources", allSources.size)
            manifestRoot.put("totalCachedImages", okRecords.size)

            val srcArr = JSONArray()
            for (s in allSources) {
                val sObj = JSONObject()
                sObj.put("name", s.name)
                val imgArr = JSONArray()
                for (u in s.urls) {
                    val rec = urlsMap[u]
                    if (rec != null && rec.status == CacheStatus.OK && rec.file.isNotEmpty()) {
                        val iObj = JSONObject()
                        iObj.put("file", "images/${rec.file}")
                        iObj.put("url", u)
                        iObj.put("size", rec.size)
                        imgArr.put(iObj)
                    }
                }
                sObj.put("cachedCount", imgArr.length())
                sObj.put("totalUrls", s.urls.size)
                sObj.put("images", imgArr)
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

            // 3. All cached images under images/
            val seenFiles = HashSet<String>()
            okRecords.forEachIndexed { idx, rec ->
                onProgress(idx + 1, total)
                if (!seenFiles.add(rec.file)) return@forEachIndexed
                val imgFile = File(imagesDir, rec.file)
                if (!imgFile.exists()) return@forEachIndexed

                zos.putNextEntry(ZipEntry("images/${rec.file}"))
                imgFile.inputStream().use { inp ->
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
