package com.stvault.app.core

import android.content.Context
import android.os.Build
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscordSyncResult(
    val messagesChecked: Int = 0,
    val filesFetched: Int = 0,
    val summary: SmartImportSummary = SmartImportSummary(),
    val error: String? = null
)

data class LocalInboxResult(
    val filesScanned: Int = 0,
    val deletedCount: Int = 0,
    val summary: SmartImportSummary = SmartImportSummary(),
    val needsStoragePermission: Boolean = false,
    val error: String? = null
)

/**
 * Unified resource ingestion engine for「收件箱」:
 * 1. Official Discord Bot REST API v10 sync (supports forwarded message_snapshots & multi-attachments)
 * 2. Batch URL / text link extractor & downloader
 * 3. Fast local Download / Pictures/Discord auto-scanner with PNG chara-chunk pre-filtering
 */
object InboxManager {

    private const val PREFS_NAME = "st_inbox_prefs"
    private const val KEY_DC_TOKEN = "dc_bot_token"
    private const val KEY_DC_CHANNEL = "dc_channel_id"
    private const val KEY_DC_CHANNEL_NAME = "dc_channel_name"
    private const val KEY_DC_LAST_MSG = "dc_last_message_id"
    private const val KEY_DC_LAST_SYNC_AT = "dc_last_sync_at"
    private const val KEY_DC_LAST_NOTE = "dc_last_sync_note"
    private const val KEY_DC_AUTO_SYNC = "dc_auto_sync"
    private const val KEY_AUTO_TAG = "auto_tag_pending"
    private const val KEY_WATCH_DOWNLOAD = "watch_download"
    private const val KEY_WATCH_DISCORD_PICS = "watch_discord_pics"
    private const val KEY_DELETE_AFTER = "delete_after_import"
    private const val KEY_SEEN_FILES = "seen_local_files"

    private const val AUTO_TAG_NAME = "待整理"
    private const val MAX_FILE_BYTES = 45L * 1024L * 1024L

    private val URL_REGEX = Regex("""https?://[^\s<>"'`)\]}]+""")
    private val HASHTAG_REGEX = Regex("""#([^\s#\\,，;；]{1,28})""")
    private val DIRECT_EXTS = setOf("png", "json", "zip", "css", "js", "html", "htm", "txt")
    private val LOCAL_EXTS = setOf("png", "json", "zip", "css", "js", "html", "htm")

    private val discordSyncLock = AtomicBoolean(false)
    @Volatile
    private var lastAutoSyncAttemptMs = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Settings accessors ---

    fun getBotToken(context: Context): String = prefs(context).getString(KEY_DC_TOKEN, "") ?: ""
    fun getChannelId(context: Context): String = prefs(context).getString(KEY_DC_CHANNEL, "") ?: ""
    fun getChannelName(context: Context): String = prefs(context).getString(KEY_DC_CHANNEL_NAME, "") ?: ""
    fun getLastMessageId(context: Context): String = prefs(context).getString(KEY_DC_LAST_MSG, "") ?: ""
    fun getLastSyncAt(context: Context): Long = prefs(context).getLong(KEY_DC_LAST_SYNC_AT, 0L)
    fun getLastSyncNote(context: Context): String = prefs(context).getString(KEY_DC_LAST_NOTE, "") ?: ""

    fun isDiscordConfigured(context: Context): Boolean =
        getBotToken(context).isNotBlank() && getChannelId(context).isNotBlank()

    fun saveDiscordConfig(context: Context, rawToken: String, rawChannelId: String) {
        val cleanToken = rawToken.trim().removePrefix("Bot ").removePrefix("bot ").trim()
        val cleanChannel = rawChannelId.trim().filter { it.isDigit() }
        val prevChannel = getChannelId(context)
        val editor = prefs(context).edit()
            .putString(KEY_DC_TOKEN, cleanToken)
            .putString(KEY_DC_CHANNEL, cleanChannel)
        if (prevChannel != cleanChannel) {
            editor.remove(KEY_DC_LAST_MSG).remove(KEY_DC_CHANNEL_NAME)
        }
        editor.apply()
    }

    fun resetDiscordCursor(context: Context) {
        prefs(context).edit().remove(KEY_DC_LAST_MSG).apply()
    }

    fun isDiscordAutoSyncEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_DC_AUTO_SYNC, true)
    fun setDiscordAutoSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DC_AUTO_SYNC, enabled).apply()
    }

    fun shouldRunAutoSync(context: Context, cooldownMs: Long = 30_000L): Boolean {
        if (!isDiscordConfigured(context) || !isDiscordAutoSyncEnabled(context)) return false
        if (discordSyncLock.get()) return false
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAttemptMs < cooldownMs) return false
        lastAutoSyncAttemptMs = now
        return true
    }

    fun isAutoTagEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_TAG, true)
    fun setAutoTagEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_TAG, enabled).apply()
    }

    fun getEffectiveAutoTag(context: Context): String =
        if (isAutoTagEnabled(context)) AUTO_TAG_NAME else ""

    fun isWatchDownload(context: Context): Boolean = prefs(context).getBoolean(KEY_WATCH_DOWNLOAD, true)
    fun setWatchDownload(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCH_DOWNLOAD, enabled).apply()
    }

    fun isWatchDiscordPics(context: Context): Boolean = prefs(context).getBoolean(KEY_WATCH_DISCORD_PICS, true)
    fun setWatchDiscordPics(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCH_DISCORD_PICS, enabled).apply()
    }

    fun isDeleteAfterImport(context: Context): Boolean = prefs(context).getBoolean(KEY_DELETE_AFTER, false)
    fun setDeleteAfterImport(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DELETE_AFTER, enabled).apply()
    }

    fun resetSeenLocalFiles(context: Context) {
        prefs(context).edit().remove(KEY_SEEN_FILES).apply()
    }

    // --- 1. Discord Bot REST API v10 ---

    fun testDiscordConnection(context: Context, rawToken: String, rawChannelId: String): Result<String> {
        val token = rawToken.trim().removePrefix("Bot ").removePrefix("bot ").trim()
        val channelId = rawChannelId.trim().filter { it.isDigit() }
        if (token.isBlank()) return Result.failure(IllegalArgumentException("请先填写 Bot Token"))
        if (channelId.isBlank()) return Result.failure(IllegalArgumentException("请先填写频道 ID"))

        val req = Request.Builder()
            .url("https://discord.com/api/v10/channels/$channelId")
            .header("Authorization", "Bot $token")
            .header("User-Agent", "STThemeVault/1.10 (Android)")
            .get()
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val obj = JSONObject(body)
                        val name = obj.optString("name", channelId)
                        saveDiscordConfig(context, token, channelId)
                        prefs(context).edit().putString(KEY_DC_CHANNEL_NAME, name).apply()
                        Result.success("#$name")
                    }
                    401 -> Result.failure(IllegalStateException("Bot Token 无效（401），请检查是否复制完整"))
                    403 -> Result.failure(IllegalStateException("Bot 缺少该频道权限（403），请确认已拉入该私人服务器"))
                    404 -> Result.failure(IllegalStateException("频道 ID 不存在（404），请确认复制的是频道 ID"))
                    else -> Result.failure(IllegalStateException("Discord 返回状态码 ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("无法连接 Discord（请确认代理已开启）：${e.message ?: "网络异常"}"))
        }
    }

    private data class CandidateFile(
        val url: String,
        val fileName: String,
        val sizeHint: Long = 0L,
        val allowGenericHtml: Boolean = false,
        val extraTags: List<String> = emptyList()
    )

    private fun extractForwardTags(text: String, isForwardOrAttachment: Boolean): List<String> {
        if (text.isBlank()) return emptyList()
        // Ignore URL fragments (http://...#anchor) by stripping URLs first
        val noUrls = URL_REGEX.replace(text, " ").trim()
        if (noUrls.isEmpty()) return emptyList()

        // 1. Explicit #hashtag syntax always works
        val hashTags = HASHTAG_REGEX.findAll(noUrls)
            .map { it.groupValues[1].trim('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .toList()
        if (hashTags.isNotEmpty()) return hashTags

        // 2. For forwarded messages or direct file uploads in the private relay channel,
        //    allow bare shorthand tags without '#' (e.g. "某某老师" or "某某老师 古风")
        //    as long as the note is short (<= 40 chars) so pasted prose isn't mistaken for tags.
        if (isForwardOrAttachment && noUrls.length <= 40 && !noUrls.contains('\n')) {
            return noUrls.split(Regex("""[\s,，、;；]+"""))
                .map { it.trim().trimStart('#').trim('/') }
                .filter { it.isNotBlank() && it.length <= 24 }
                .distinct()
                .take(5)
        }
        return emptyList()
    }

    fun syncDiscordChannel(
        context: Context,
        fullResync: Boolean = false,
        onDownloadStart: (Int) -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): DiscordSyncResult {
        if (!discordSyncLock.compareAndSet(false, true)) {
            return DiscordSyncResult(error = "正在同步中，请稍候")
        }
        return try {
            syncDiscordChannelLocked(context, fullResync, onDownloadStart, onProgress)
        } finally {
            discordSyncLock.set(false)
        }
    }

    private fun syncDiscordChannelLocked(
        context: Context,
        fullResync: Boolean,
        onDownloadStart: (Int) -> Unit,
        onProgress: (String) -> Unit
    ): DiscordSyncResult {
        val token = getBotToken(context)
        val channelId = getChannelId(context)
        if (token.isBlank() || channelId.isBlank()) {
            return DiscordSyncResult(error = "请先填写 Bot Token 和频道 ID")
        }

        val lastId = if (fullResync) "" else getLastMessageId(context)
        val allMessages = mutableListOf<JSONObject>()
        var cursor = lastId

        try {
            // Pull up to 4 pages (200 messages) per sync
            for (page in 0 until 4) {
                onProgress(if (page == 0) "正在拉取 DC 频道消息..." else "正在拉取更多消息（第 ${page + 1} 页）...")
                val url = if (cursor.isNotBlank()) {
                    "https://discord.com/api/v10/channels/$channelId/messages?limit=50&after=$cursor"
                } else {
                    "https://discord.com/api/v10/channels/$channelId/messages?limit=50"
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bot $token")
                    .header("User-Agent", "STThemeVault/1.10 (Android)")
                    .get()
                    .build()

                val pageArr: JSONArray = httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    when (resp.code) {
                        200 -> JSONArray(body)
                        401 -> return DiscordSyncResult(error = "Bot Token 无效（401）")
                        403 -> return DiscordSyncResult(error = "Bot 无权读取该频道（403）")
                        404 -> return DiscordSyncResult(error = "频道 ID 不存在（404）")
                        429 -> return DiscordSyncResult(error = "请求过于频繁（429），请稍后再试")
                        else -> return DiscordSyncResult(error = "Discord 接口错误（${resp.code}）")
                    }
                }

                if (pageArr.length() == 0) break
                val batch = mutableListOf<JSONObject>()
                for (i in 0 until pageArr.length()) {
                    pageArr.optJSONObject(i)?.let { batch.add(it) }
                }
                // Sort oldest -> newest by Snowflake ID
                batch.sortBy { it.optString("id").toLongOrNull() ?: 0L }
                allMessages.addAll(batch)

                val newestInPage = batch.lastOrNull()?.optString("id").orEmpty()
                if (cursor.isBlank() || pageArr.length() < 50 || newestInPage.isBlank()) {
                    // On initial sync (no cursor), fetch latest 50 only so we don't crawl ancient history
                    break
                }
                cursor = newestInPage
            }
        } catch (e: Exception) {
            return DiscordSyncResult(error = "连接 Discord 失败（请检查代理）：${e.message ?: "网络异常"}")
        }

        if (allMessages.isEmpty()) {
            val now = System.currentTimeMillis()
            prefs(context).edit()
                .putLong(KEY_DC_LAST_SYNC_AT, now)
                .putString(KEY_DC_LAST_NOTE, "暂无新消息")
                .apply()
            return DiscordSyncResult(messagesChecked = 0, filesFetched = 0)
        }

        // Extract all candidate files from messages, forwarded message_snapshots, embeds & text links
        val candidates = LinkedHashMap<String, CandidateFile>()

        fun collectFromMessageLike(obj: JSONObject, msgExtraTags: List<String>) {
            // 1. Native attachments
            val atts = obj.optJSONArray("attachments")
            if (atts != null) {
                for (i in 0 until atts.length()) {
                    val att = atts.optJSONObject(i) ?: continue
                    val u = att.optString("url").trim()
                    if (u.isBlank()) continue
                    val fn = att.optString("filename").ifBlank { inferFileNameFromUrl(u, null) }
                    val sz = att.optLong("size", 0L)
                    val dedupKey = normalizeUrlDedupKey(u)
                    candidates.putIfAbsent(
                        dedupKey,
                        CandidateFile(u, fn, sz, allowGenericHtml = fn.endsWith(".html", true) || fn.endsWith(".htm", true), extraTags = msgExtraTags)
                    )
                }
            }
            // 2. Links inside text content
            val content = obj.optString("content", "")
            if (content.isNotBlank()) {
                for (u in extractDownloadableUrls(content, strictOnly = true)) {
                    val dedupKey = normalizeUrlDedupKey(u)
                    candidates.putIfAbsent(dedupKey, CandidateFile(u, inferFileNameFromUrl(u, null), extraTags = msgExtraTags))
                }
            }
            // 3. Embeds (some bots attach card PNGs / files via embeds)
            val embeds = obj.optJSONArray("embeds")
            if (embeds != null) {
                for (i in 0 until embeds.length()) {
                    val emb = embeds.optJSONObject(i) ?: continue
                    val embUrl = emb.optString("url", "")
                    if (embUrl.isNotBlank() && isDownloadableUrl(embUrl)) {
                        candidates.putIfAbsent(normalizeUrlDedupKey(embUrl), CandidateFile(embUrl, inferFileNameFromUrl(embUrl, null), extraTags = msgExtraTags))
                    }
                    val imgUrl = emb.optJSONObject("image")?.optString("url", "").orEmpty()
                    if (imgUrl.isNotBlank() && isDownloadableUrl(imgUrl)) {
                        candidates.putIfAbsent(normalizeUrlDedupKey(imgUrl), CandidateFile(imgUrl, inferFileNameFromUrl(imgUrl, null), extraTags = msgExtraTags))
                    }
                    val desc = emb.optString("description", "")
                    if (desc.isNotBlank()) {
                        for (u in extractDownloadableUrls(desc, strictOnly = true)) {
                            candidates.putIfAbsent(normalizeUrlDedupKey(u), CandidateFile(u, inferFileNameFromUrl(u, null), extraTags = msgExtraTags))
                        }
                    }
                }
            }
        }

        var maxSnowflakeId = lastId.toLongOrNull() ?: 0L
        var maxSnowflakeStr = lastId
        val appliedUserTags = LinkedHashSet<String>()

        fun snowflakeTimeMs(idNum: Long): Long = (idNum ushr 22) + 1420070400000L

        for (i in allMessages.indices) {
            val msg = allMessages[i]
            val idStr = msg.optString("id", "")
            val idNum = idStr.toLongOrNull() ?: 0L
            if (idNum > maxSnowflakeId) {
                maxSnowflakeId = idNum
                maxSnowflakeStr = idStr
            }
            val snapshots = msg.optJSONArray("message_snapshots")
            val hasSnapshots = snapshots != null && snapshots.length() > 0
            val hasAttachments = (msg.optJSONArray("attachments")?.length() ?: 0) > 0

            // 1. Tags on the message itself (e.g. direct upload with caption or text+links with #tag)
            val tagList = LinkedHashSet<String>()
            tagList.addAll(extractForwardTags(msg.optString("content", ""), hasSnapshots || hasAttachments))

            // 2. Discord sends the Forward "Optional Message" as a SEPARATE text-only message immediately after the forward!
            if (hasSnapshots || hasAttachments) {
                val nextMsg = allMessages.getOrNull(i + 1)
                if (nextMsg != null) {
                    val nextSnaps = nextMsg.optJSONArray("message_snapshots")
                    val nextAtts = nextMsg.optJSONArray("attachments")
                    val nextHasFiles = (nextSnaps != null && nextSnaps.length() > 0) ||
                        (nextAtts != null && nextAtts.length() > 0)
                    val sameAuthor = msg.optJSONObject("author")?.optString("id").orEmpty() ==
                        nextMsg.optJSONObject("author")?.optString("id").orEmpty()
                    val nextIdNum = nextMsg.optString("id", "").toLongOrNull() ?: 0L
                    val deltaMs = if (idNum > 0L && nextIdNum >= idNum) {
                        snowflakeTimeMs(nextIdNum) - snowflakeTimeMs(idNum)
                    } else Long.MAX_VALUE
                    val nextContent = nextMsg.optString("content", "")
                    val nextHasUrls = extractDownloadableUrls(nextContent, strictOnly = true).isNotEmpty()

                    if (!nextHasFiles && !nextHasUrls && sameAuthor && deltaMs in 0L..30_000L) {
                        tagList.addAll(extractForwardTags(nextContent, isForwardOrAttachment = true))
                    }
                }
            }

            val userTags = tagList.toList()
            if (userTags.isNotEmpty()) appliedUserTags.addAll(userTags)

            collectFromMessageLike(msg, userTags)
            // Forwarded message snapshots (Discord Forward feature)
            if (snapshots != null) {
                for (j in 0 until snapshots.length()) {
                    val snapMsg = snapshots.optJSONObject(j)?.optJSONObject("message") ?: continue
                    collectFromMessageLike(snapMsg, userTags)
                }
            }
        }

        val list = candidates.values.toList()
        if (list.isNotEmpty()) {
            onDownloadStart(list.size)
        }
        val summary = SmartImportSummary()
        val autoTag = getEffectiveAutoTag(context)
        var fetched = 0

        for ((idx, item) in list.withIndex()) {
            if (item.sizeHint > MAX_FILE_BYTES) {
                summary.skipped++
                continue
            }
            onProgress("正在拉取附件 (${idx + 1}/${list.size})：${item.fileName}")
            val dl = downloadUrlBytes(item.url, item.fileName, item.allowGenericHtml)
            if (dl == null) {
                summary.skipped++
                continue
            }
            fetched++
            SmartImporter.importBytes(
                context = context,
                bytes = dl.first,
                fileName = dl.second,
                sourceRef = "discord://$channelId/${dl.second}",
                autoTag = autoTag,
                extraTags = item.extraTags,
                summary = summary
            )
        }

        val now = System.currentTimeMillis()
        val tagHint = if (appliedUserTags.isNotEmpty()) " · 标签：${appliedUserTags.joinToString("/")}" else ""
        val note = if (list.isEmpty()) {
            "检查 ${allMessages.size} 条消息，无新附件"
        } else {
            summary.formatMessage() + tagHint
        }
        prefs(context).edit()
            .apply { if (maxSnowflakeStr.isNotBlank()) putString(KEY_DC_LAST_MSG, maxSnowflakeStr) }
            .putLong(KEY_DC_LAST_SYNC_AT, now)
            .putString(KEY_DC_LAST_NOTE, note)
            .apply()

        return DiscordSyncResult(
            messagesChecked = allMessages.size,
            filesFetched = fetched,
            summary = summary
        )
    }

    // --- 2. Batch URL / Text Link Fetcher ---

    fun extractDownloadableUrls(text: String, strictOnly: Boolean = false): List<String> {
        if (text.isBlank()) return emptyList()
        val rawMatches = URL_REGEX.findAll(text).map { m ->
            m.value.trimEnd('.', ',', ';', '!', '?', '，', '。', '；', '！', '？', ')', ']', '}', '）', '】', '」')
        }.distinct().toList()

        if (rawMatches.isEmpty()) return emptyList()
        val strict = rawMatches.filter { isDownloadableUrl(it) }
        if (strict.isNotEmpty() || strictOnly) return strict
        // When user explicitly pastes/shares links (non-strict), allow direct http(s) links (excluding discord.com/channels web pages)
        return rawMatches.filter { u ->
            val low = u.lowercase()
            !low.contains("discord.com/channels/") && !low.contains("discordapp.com/channels/")
        }.take(20)
    }

    private fun isDownloadableUrl(url: String): Boolean {
        val low = url.lowercase()
        if (low.contains("cdn.discordapp.com/attachments/") || low.contains("media.discordapp.net/attachments/")) {
            return true
        }
        val pathOnly = low.substringBefore('?').substringBefore('#')
        val ext = pathOnly.substringAfterLast('.', "")
        return ext in DIRECT_EXTS
    }

    private fun normalizeUrlDedupKey(url: String): String {
        val low = url.lowercase()
        return if (low.contains("cdn.discordapp.com/attachments/") || low.contains("media.discordapp.net/attachments/")) {
            low.substringBefore('?')
        } else {
            url
        }
    }

    fun importUrlsFromText(
        context: Context,
        rawText: String,
        onProgress: (String) -> Unit = {}
    ): Pair<Int, SmartImportSummary> {
        val urls = extractDownloadableUrls(rawText, strictOnly = false)
        val summary = SmartImportSummary()
        if (urls.isEmpty()) return 0 to summary
        val autoTag = getEffectiveAutoTag(context)
        var fetched = 0

        for ((idx, u) in urls.withIndex()) {
            val hintName = inferFileNameFromUrl(u, null)
            onProgress("正在抓取链接 (${idx + 1}/${urls.size})：$hintName")
            val allowHtml = hintName.endsWith(".html", true) || hintName.endsWith(".htm", true)
            val dl = downloadUrlBytes(u, hintName, allowHtml)
            if (dl == null) {
                summary.skipped++
                continue
            }
            fetched++
            SmartImporter.importBytes(
                context = context,
                bytes = dl.first,
                fileName = dl.second,
                sourceRef = u,
                autoTag = autoTag,
                summary = summary
            )
        }
        return fetched to summary
    }

    fun downloadAndImportSingleUrl(
        context: Context,
        url: String,
        fallbackName: String,
        extraTags: List<String> = emptyList(),
        summary: SmartImportSummary = SmartImportSummary()
    ): Boolean {
        val hintName = fallbackName.ifBlank { inferFileNameFromUrl(url, null) }
        val allowHtml = hintName.endsWith(".html", true) || hintName.endsWith(".htm", true)
        val hasCleanDomName = fallbackName.isNotBlank() && fallbackName.contains('.')
        val dl = downloadUrlBytes(url, hintName, allowHtml, preferFallbackName = hasCleanDomName) ?: run {
            summary.skipped++
            return false
        }
        SmartImporter.importBytes(
            context = context,
            bytes = dl.first,
            fileName = dl.second,
            sourceRef = url,
            autoTag = getEffectiveAutoTag(context),
            extraTags = extraTags,
            summary = summary
        )
        return true
    }

    private fun downloadUrlBytes(
        url: String,
        fallbackName: String,
        allowGenericHtml: Boolean = false,
        preferFallbackName: Boolean = false
    ): Pair<ByteArray, String>? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val len = body.contentLength()
                if (len > MAX_FILE_BYTES) return null

                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val disposition = resp.header("Content-Disposition")
                val resolvedName = if (preferFallbackName && fallbackName.isNotBlank()) {
                    fallbackName
                } else {
                    extractFilenameFromDisposition(disposition)
                        ?: fallbackName.ifBlank { inferFileNameFromUrl(url, contentType) }
                }

                // Guard against accidentally importing a web login/404 HTML page as a theme
                if (contentType.contains("text/html") && !allowGenericHtml &&
                    !resolvedName.endsWith(".html", true) && !resolvedName.endsWith(".htm", true)
                ) {
                    return null
                }

                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return null
                bytes to resolvedName
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFilenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val utf8Match = Regex("""filename\*=UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE).find(disposition)
        if (utf8Match != null) {
            return try {
                URLDecoder.decode(utf8Match.groupValues[1], "UTF-8").substringAfterLast('/')
            } catch (_: Exception) {
                null
            }
        }
        val plainMatch = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)
        return plainMatch?.groupValues?.getOrNull(1)?.trim()?.substringAfterLast('/')
    }

    private fun inferFileNameFromUrl(url: String, contentType: String?): String {
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(seg, "UTF-8")
        } catch (_: Exception) {
            seg
        }.trim()
        if (decoded.isNotBlank() && decoded.contains('.') && decoded.length <= 160) {
            return decoded
        }
        val ext = when {
            contentType == null -> "bin"
            contentType.contains("png") -> "png"
            contentType.contains("json") -> "json"
            contentType.contains("zip") -> "zip"
            contentType.contains("javascript") -> "js"
            contentType.contains("css") -> "css"
            else -> "json"
        }
        return "inbox_${System.currentTimeMillis()}.$ext"
    }

    // --- 3. Local Download Folder Auto-Inbox ---

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun scanLocalInbox(
        context: Context,
        onProgress: (String) -> Unit = {}
    ): LocalInboxResult {
        if (!hasAllFilesAccess()) {
            return LocalInboxResult(needsStoragePermission = true)
        }

        val dirs = mutableListOf<File>()
        if (isWatchDownload(context)) {
            dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }
        if (isWatchDiscordPics(context)) {
            dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Discord"))
        }
        if (dirs.isEmpty()) {
            return LocalInboxResult(error = "请至少勾选一个本地监控目录")
        }

        val seenSet = (prefs(context).getStringSet(KEY_SEEN_FILES, emptySet()) ?: emptySet()).toMutableSet()
        val candidates = mutableListOf<File>()

        fun walk(dir: File, depth: Int) {
            if (!dir.exists() || !dir.isDirectory || depth > 2) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (f.isFile) {
                    val ext = f.extension.lowercase()
                    if (ext !in LOCAL_EXTS) continue
                    if (f.length() == 0L || f.length() > MAX_FILE_BYTES) continue
                    val fp = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
                    if (fp in seenSet) continue
                    candidates.add(f)
                }
            }
        }

        onProgress("正在扫描本地目录...")
        for (d in dirs) walk(d, 0)

        if (candidates.isEmpty()) {
            return LocalInboxResult(filesScanned = 0)
        }

        val summary = SmartImportSummary()
        val autoTag = getEffectiveAutoTag(context)
        val deleteAfter = isDeleteAfterImport(context)
        var deleted = 0

        for ((idx, file) in candidates.withIndex()) {
            val fp = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
            val ext = file.extension.lowercase()

            // Fast zero-decode PNG pre-filter: skip regular screenshots / memes immediately
            if (ext == "png" && !hasPngCharaChunk(file)) {
                seenSet.add(fp)
                continue
            }

            onProgress("正在识别 (${idx + 1}/${candidates.size})：${file.name}")
            val beforeTotal = summary.totalImported
            val beforeDup = summary.duplicates

            try {
                val bytes = file.readBytes()
                SmartImporter.importBytes(
                    context = context,
                    bytes = bytes,
                    fileName = file.name,
                    sourceRef = "file://${file.absolutePath}",
                    autoTag = autoTag,
                    summary = summary
                )
            } catch (_: Exception) {
                summary.skipped++
            }

            seenSet.add(fp)
            val wasImported = summary.totalImported > beforeTotal
            val wasDup = summary.duplicates > beforeDup
            if (deleteAfter && (wasImported || wasDup)) {
                try {
                    if (file.delete()) deleted++
                } catch (_: Exception) {
                }
            }
        }

        // Keep seen set bounded so SharedPreferences stays fast
        val trimmedSeen = if (seenSet.size > 4000) {
            seenSet.toList().takeLast(3000).toSet()
        } else {
            seenSet
        }
        prefs(context).edit().putStringSet(KEY_SEEN_FILES, trimmedSeen).apply()

        return LocalInboxResult(
            filesScanned = candidates.size,
            deletedCount = deleted,
            summary = summary
        )
    }

    /**
     * Ultra-fast PNG chunk header walker: checks if a PNG contains a SillyTavern
     * `chara` or `ccv3` metadata chunk without decoding image pixel data.
     */
    private fun hasPngCharaChunk(file: File): Boolean {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 8192)).use { dis ->
                val sig = ByteArray(8)
                dis.readFully(sig)
                if (sig[0] != 0x89.toByte() || sig[1] != 0x50.toByte() ||
                    sig[2] != 0x4E.toByte() || sig[3] != 0x47.toByte()
                ) return false

                val typeBuf = ByteArray(4)
                val keyBuf = ByteArray(6)
                while (true) {
                    val len = try { dis.readInt() } catch (_: Exception) { return false }
                    if (len < 0 || len > 50 * 1024 * 1024) return false
                    dis.readFully(typeBuf)
                    val type = String(typeBuf, Charsets.ISO_8859_1)
                    if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                        val toRead = minOf(len, keyBuf.size)
                        dis.readFully(keyBuf, 0, toRead)
                        val prefix = String(keyBuf, 0, toRead, Charsets.ISO_8859_1).lowercase()
                        if (prefix.startsWith("chara") || prefix.startsWith("ccv3")) {
                            return true
                        }
                        val remain = (len - toRead).toLong() + 4L
                        skipFully(dis, remain)
                    } else {
                        if (type == "IEND") return false
                        skipFully(dis, len.toLong() + 4L)
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun skipFully(dis: DataInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = dis.skip(remaining)
            if (skipped <= 0) {
                if (dis.read() == -1) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
