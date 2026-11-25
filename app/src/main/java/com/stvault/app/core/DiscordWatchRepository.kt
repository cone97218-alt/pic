package com.stvault.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Persists watched Discord channels (「追新」), per-channel seen post watermarks,
 * and cross-community deduplicated new-post feed items.
 *
 * All heavy operations are pure/in-memory or fast local JSON I/O; zero background
 * polling timers and zero network calls.
 */
data class WatchedChannel(
    val id: String,
    val guildId: String,
    val url: String,
    val name: String,
    val category: String = "其他",
    val priority: Int = 0,
    val seenPostIds: List<String> = emptyList(),
    val lastCheckedAt: Long = 0L
)

data class ObservedPost(
    val postId: String,
    val channelId: String,
    val channelName: String,
    val category: String,
    val title: String,
    val author: String,
    val url: String,
    val discoveredAt: Long = System.currentTimeMillis()
)

data class PostMirror(
    val postId: String,
    val channelId: String,
    val channelName: String,
    val url: String
)

data class FeedPost(
    val id: String,
    val primaryChannelId: String,
    val primaryChannelName: String,
    val category: String,
    val title: String,
    val author: String,
    val url: String,
    val discoveredAt: Long,
    val read: Boolean = false,
    val mirrors: List<PostMirror> = emptyList()
)

data class SweepIngestSummary(
    val newCount: Int,
    val foldedDuplicateCount: Int,
    val initializedChannels: Int
)

class DiscordWatchRepository private constructor(private val appContext: Context) {

    private val lock = Any()
    private val file: File = File(appContext.filesDir, "discord_watch_v1.json")
    private var loaded = false
    private val channels = mutableListOf<WatchedChannel>()
    private val feed = mutableListOf<FeedPost>()

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val chArr = root.optJSONArray("channels") ?: JSONArray()
            channels.clear()
            for (i in 0 until chArr.length()) {
                val o = chArr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val url = o.optString("url").trim()
                if (id.isEmpty() || url.isEmpty()) continue
                val seenArr = o.optJSONArray("seenPostIds") ?: JSONArray()
                val seen = ArrayList<String>(seenArr.length())
                for (j in 0 until seenArr.length()) {
                    val sid = seenArr.optString(j).trim()
                    if (sid.isNotEmpty()) seen.add(sid)
                }
                channels.add(
                    WatchedChannel(
                        id = id,
                        guildId = o.optString("guildId").trim(),
                        url = url,
                        name = o.optString("name").trim().ifEmpty { "频道 $id" },
                        category = normalizeCategory(o.optString("category")),
                        priority = o.optInt("priority", i),
                        seenPostIds = seen,
                        lastCheckedAt = o.optLong("lastCheckedAt", 0L)
                    )
                )
            }
            reindexPrioritiesLocked()

            val feedArr = root.optJSONArray("feed") ?: JSONArray()
            feed.clear()
            for (i in 0 until feedArr.length()) {
                val o = feedArr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val url = o.optString("url").trim()
                val title = o.optString("title").trim()
                if (id.isEmpty() || url.isEmpty() || title.isEmpty()) continue
                val mArr = o.optJSONArray("mirrors") ?: JSONArray()
                val mirrors = ArrayList<PostMirror>(mArr.length())
                for (j in 0 until mArr.length()) {
                    val mo = mArr.optJSONObject(j) ?: continue
                    val mUrl = mo.optString("url").trim()
                    if (mUrl.isNotEmpty()) {
                        mirrors.add(
                            PostMirror(
                                postId = mo.optString("postId").trim(),
                                channelId = mo.optString("channelId").trim(),
                                channelName = mo.optString("channelName").trim(),
                                url = mUrl
                            )
                        )
                    }
                }
                feed.add(
                    FeedPost(
                        id = id,
                        primaryChannelId = o.optString("primaryChannelId").trim(),
                        primaryChannelName = o.optString("primaryChannelName").trim(),
                        category = normalizeCategory(o.optString("category")),
                        title = title,
                        author = o.optString("author").trim(),
                        url = url,
                        discoveredAt = o.optLong("discoveredAt", 0L),
                        read = o.optBoolean("read", false),
                        mirrors = mirrors
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun reindexPrioritiesLocked() {
        channels.sortBy { it.priority }
        for (i in channels.indices) {
            if (channels[i].priority != i) {
                channels[i] = channels[i].copy(priority = i)
            }
        }
    }

    private fun saveLocked() {
        try {
            val root = JSONObject()
            val chArr = JSONArray()
            for (c in channels) {
                val seenArr = JSONArray()
                for (s in c.seenPostIds.takeLast(MAX_SEEN_PER_CHANNEL)) seenArr.put(s)
                chArr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("guildId", c.guildId)
                        put("url", c.url)
                        put("name", c.name)
                        put("category", c.category)
                        put("priority", c.priority)
                        put("seenPostIds", seenArr)
                        put("lastCheckedAt", c.lastCheckedAt)
                    }
                )
            }
            root.put("channels", chArr)

            val feedArr = JSONArray()
            for (f in feed.take(MAX_FEED_ITEMS)) {
                val mArr = JSONArray()
                for (m in f.mirrors) {
                    mArr.put(
                        JSONObject().apply {
                            put("postId", m.postId)
                            put("channelId", m.channelId)
                            put("channelName", m.channelName)
                            put("url", m.url)
                        }
                    )
                }
                feedArr.put(
                    JSONObject().apply {
                        put("id", f.id)
                        put("primaryChannelId", f.primaryChannelId)
                        put("primaryChannelName", f.primaryChannelName)
                        put("category", f.category)
                        put("title", f.title)
                        put("author", f.author)
                        put("url", f.url)
                        put("discoveredAt", f.discoveredAt)
                        put("read", f.read)
                        put("mirrors", mArr)
                    }
                )
            }
            root.put("feed", feedArr)

            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun getChannels(): List<WatchedChannel> = synchronized(lock) {
        ensureLoadedLocked()
        channels.toList()
    }

    fun getFeed(): List<FeedPost> = synchronized(lock) {
        ensureLoadedLocked()
        feed.toList()
    }

    fun getUnreadCount(): Int = synchronized(lock) {
        ensureLoadedLocked()
        feed.count { !it.read }
    }

    fun isChannelWatched(channelId: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        channels.any { it.id == channelId }
    }

    fun upsertChannel(
        channelId: String,
        guildId: String,
        name: String,
        category: String,
        initialSeenIds: List<String> = emptyList()
    ): WatchedChannel = synchronized(lock) {
        ensureLoadedLocked()
        val normCat = normalizeCategory(category)
        val cleanUrl = "https://discord.com/channels/$guildId/$channelId"
        val cleanName = name.trim().ifEmpty { "频道 $channelId" }
        val existingIdx = channels.indexOfFirst { it.id == channelId }
        val result = if (existingIdx >= 0) {
            val old = channels[existingIdx]
            val mergedSeen = if (old.seenPostIds.isEmpty() && initialSeenIds.isNotEmpty()) {
                initialSeenIds.distinct().takeLast(MAX_SEEN_PER_CHANNEL)
            } else {
                old.seenPostIds
            }
            val updated = old.copy(
                guildId = guildId.ifBlank { old.guildId },
                url = cleanUrl,
                name = cleanName,
                category = normCat,
                seenPostIds = mergedSeen
            )
            channels[existingIdx] = updated
            updated
        } else {
            val created = WatchedChannel(
                id = channelId,
                guildId = guildId,
                url = cleanUrl,
                name = cleanName,
                category = normCat,
                priority = channels.size,
                seenPostIds = initialSeenIds.distinct().takeLast(MAX_SEEN_PER_CHANNEL),
                lastCheckedAt = if (initialSeenIds.isNotEmpty()) System.currentTimeMillis() else 0L
            )
            channels.add(created)
            created
        }
        reindexPrioritiesLocked()
        saveLocked()
        result
    }

    fun updateChannelMeta(channelId: String, name: String, category: String) = synchronized(lock) {
        ensureLoadedLocked()
        val idx = channels.indexOfFirst { it.id == channelId }
        if (idx >= 0) {
            val old = channels[idx]
            channels[idx] = old.copy(
                name = name.trim().ifEmpty { old.name },
                category = normalizeCategory(category)
            )
            saveLocked()
        }
    }

    fun moveChannelToTop(channelId: String) = synchronized(lock) {
        ensureLoadedLocked()
        val idx = channels.indexOfFirst { it.id == channelId }
        if (idx > 0) {
            val item = channels.removeAt(idx)
            channels.add(0, item)
            for (i in channels.indices) {
                channels[i] = channels[i].copy(priority = i)
            }
            saveLocked()
        }
    }

    fun moveChannelUp(channelId: String) = synchronized(lock) {
        ensureLoadedLocked()
        val idx = channels.indexOfFirst { it.id == channelId }
        if (idx > 0) {
            val item = channels.removeAt(idx)
            channels.add(idx - 1, item)
            for (i in channels.indices) {
                channels[i] = channels[i].copy(priority = i)
            }
            saveLocked()
        }
    }

    fun removeChannel(channelId: String) = synchronized(lock) {
        ensureLoadedLocked()
        val removed = channels.removeAll { it.id == channelId }
        if (removed) {
            reindexPrioritiesLocked()
            saveLocked()
        }
    }

    fun markPostRead(postId: String) = synchronized(lock) {
        ensureLoadedLocked()
        var changed = false
        for (i in feed.indices) {
            val f = feed[i]
            if (f.id == postId && !f.read) {
                feed[i] = f.copy(read = true)
                changed = true
            }
        }
        if (changed) saveLocked()
    }

    fun markAllRead(categoryFilter: String? = null) = synchronized(lock) {
        ensureLoadedLocked()
        var changed = false
        for (i in feed.indices) {
            val f = feed[i]
            val matchCat = categoryFilter.isNullOrBlank() || categoryFilter == "全部" || f.category == categoryFilter
            if (matchCat && !f.read) {
                feed[i] = f.copy(read = true)
                changed = true
            }
        }
        if (changed) saveLocked()
    }

    fun clearReadPosts() = synchronized(lock) {
        ensureLoadedLocked()
        if (feed.removeAll { it.read }) {
            saveLocked()
        }
    }

    fun removeFeedPost(postId: String) = synchronized(lock) {
        ensureLoadedLocked()
        if (feed.removeAll { it.id == postId }) {
            saveLocked()
        }
    }

    /**
     * Ingests scraped posts from a sweep across one or more channels.
     *
     * - For a brand-new channel with no prior watermark (seenPostIds empty), only the top
     *   [FIRST_SWEEP_KEEP_TOP] posts are surfaced (so the user isn't flooded with months-old posts),
     *   while all observed IDs are recorded into the channel watermark.
     * - For established channels, only unseen postIds are surfaced as new.
     * - Cross-community duplicates are collapsed according to channel priority (lower index wins),
     *   recording alternate communities in [FeedPost.mirrors].
     */
    fun ingestSweepResults(observedByChannel: Map<String, List<ObservedPost>>): SweepIngestSummary = synchronized(lock) {
        ensureLoadedLocked()
        val now = System.currentTimeMillis()
        val priorityByChannel = channels.associate { it.id to it.priority }
        val candidates = mutableListOf<ObservedPost>()
        var initializedChannels = 0

        for (i in channels.indices) {
            val ch = channels[i]
            val obs = observedByChannel[ch.id] ?: continue
            val oldSeen = ch.seenPostIds.toHashSet()
            val isFirstTime = oldSeen.isEmpty()
            val fresh = obs.filter { it.postId.isNotBlank() && it.postId !in oldSeen }

            val toSurface = if (isFirstTime) {
                initializedChannels++
                fresh.take(FIRST_SWEEP_KEEP_TOP)
            } else {
                fresh
            }
            candidates.addAll(toSurface)

            val updatedSeen = LinkedHashSet<String>(oldSeen.size + obs.size)
            updatedSeen.addAll(ch.seenPostIds)
            for (p in obs) {
                if (p.postId.isNotBlank()) updatedSeen.add(p.postId)
            }
            channels[i] = ch.copy(
                seenPostIds = updatedSeen.toList().takeLast(MAX_SEEN_PER_CHANNEL),
                lastCheckedAt = now
            )
        }

        // Sort candidates by channel priority ASC so preferred communities always become primary
        val sortedCandidates = candidates.sortedWith(
            compareBy<ObservedPost> { priorityByChannel[it.channelId] ?: Int.MAX_VALUE }
                .thenByDescending { it.discoveredAt }
        )

        var newAdded = 0
        var foldedDups = 0
        val newlyCreated = mutableListOf<FeedPost>()

        fun findMatchingIndex(list: List<FeedPost>, cand: ObservedPost): Int {
            val candNorm = normalizePostTitle(cand.title)
            for (idx in list.indices) {
                val existing = list[idx]
                if (existing.id == cand.postId) return idx
                if (existing.mirrors.any { it.postId == cand.postId }) return idx
                // Never merge two distinct posts from the exact same channel
                if (existing.primaryChannelId == cand.channelId) continue
                if (existing.mirrors.any { it.channelId == cand.channelId }) continue
                if (isCrossServerDuplicate(existing.title, existing.author, cand.title, cand.author, candNorm)) {
                    return idx
                }
            }
            return -1
        }

        for (cand in sortedCandidates) {
            // 1) Check against already-created items in this sweep
            val newIdx = findMatchingIndex(newlyCreated, cand)
            if (newIdx >= 0) {
                val primary = newlyCreated[newIdx]
                if (primary.id != cand.postId && primary.mirrors.none { it.channelId == cand.channelId }) {
                    newlyCreated[newIdx] = primary.copy(
                        mirrors = primary.mirrors + PostMirror(
                            postId = cand.postId,
                            channelId = cand.channelId,
                            channelName = cand.channelName,
                            url = cand.url
                        )
                    )
                    foldedDups++
                }
                continue
            }

            // 2) Check against existing persisted feed (unread or recent read)
            val existIdx = findMatchingIndex(feed, cand)
            if (existIdx >= 0) {
                val existing = feed[existIdx]
                if (existing.id != cand.postId && existing.mirrors.none { it.channelId == cand.channelId }) {
                    val existPri = priorityByChannel[existing.primaryChannelId] ?: Int.MAX_VALUE
                    val candPri = priorityByChannel[cand.channelId] ?: Int.MAX_VALUE
                    feed[existIdx] = if (candPri < existPri && !existing.read) {
                        // Higher-priority community arrived later: promote it to primary
                        val oldAsMirror = PostMirror(
                            postId = existing.id,
                            channelId = existing.primaryChannelId,
                            channelName = existing.primaryChannelName,
                            url = existing.url
                        )
                        existing.copy(
                            id = cand.postId,
                            primaryChannelId = cand.channelId,
                            primaryChannelName = cand.channelName,
                            url = cand.url,
                            mirrors = (existing.mirrors + oldAsMirror).distinctBy { it.channelId }
                        )
                    } else {
                        existing.copy(
                            mirrors = existing.mirrors + PostMirror(
                                postId = cand.postId,
                                channelId = cand.channelId,
                                channelName = cand.channelName,
                                url = cand.url
                            )
                        )
                    }
                    foldedDups++
                }
                continue
            }

            // 3) Brand-new unique post
            newlyCreated.add(
                FeedPost(
                    id = cand.postId,
                    primaryChannelId = cand.channelId,
                    primaryChannelName = cand.channelName,
                    category = cand.category,
                    title = cand.title,
                    author = cand.author,
                    url = cand.url,
                    discoveredAt = cand.discoveredAt,
                    read = false,
                    mirrors = emptyList()
                )
            )
            newAdded++
        }

        if (newlyCreated.isNotEmpty()) {
            feed.addAll(0, newlyCreated)
            if (feed.size > MAX_FEED_ITEMS) {
                val trimmed = feed.take(MAX_FEED_ITEMS)
                feed.clear()
                feed.addAll(trimmed)
            }
        }

        saveLocked()
        SweepIngestSummary(
            newCount = newAdded,
            foldedDuplicateCount = foldedDups,
            initializedChannels = initializedChannels
        )
    }

    companion object {
        val CATEGORIES = listOf("美化", "角色卡", "世界书", "预设", "其他")
        private const val MAX_SEEN_PER_CHANNEL = 220
        private const val MAX_FEED_ITEMS = 160
        private const val FIRST_SWEEP_KEEP_TOP = 6

        private val BRACKET_TAG_RE = Regex("(【[^】]{1,14}】|\\[[^\\]]{1,14}]|「[^」]{1,14}」|（[^）]{1,10}）|\\([^)]{1,10}\\))")
        private val HASH_TAG_RE = Regex("#[^\\s#]{1,16}")
        private val NON_ALNUM_CJK_RE = Regex("[^a-z0-9\\u4e00-\\u9fff]")

        @Volatile
        private var instance: DiscordWatchRepository? = null

        fun getInstance(context: Context): DiscordWatchRepository {
            return instance ?: synchronized(this) {
                instance ?: DiscordWatchRepository(context.applicationContext).also { instance = it }
            }
        }

        fun normalizeCategory(raw: String?): String {
            val t = raw?.trim().orEmpty()
            return if (t in CATEGORIES) t else "其他"
        }

        fun normalizePostTitle(raw: String): String {
            var s = raw.trim().lowercase()
            val strippedTags = s.replace(BRACKET_TAG_RE, " ").replace(HASH_TAG_RE, " ")
            val strippedClean = strippedTags.replace(NON_ALNUM_CJK_RE, "")
            if (strippedClean.length >= 3) {
                return strippedClean
            }
            return s.replace(NON_ALNUM_CJK_RE, "")
        }

        fun isCrossServerDuplicate(
            titleA: String,
            authorA: String,
            titleB: String,
            authorB: String,
            preNormB: String? = null
        ): Boolean {
            val normA = normalizePostTitle(titleA)
            val normB = preNormB ?: normalizePostTitle(titleB)
            if (normA.length < 3 || normB.length < 3) return false
            if (normA == normB) return true

            val sameAuthor = authorA.isNotBlank() && authorB.isNotBlank() &&
                authorA.trim().equals(authorB.trim(), ignoreCase = true)

            val minLen = min(normA.length, normB.length)
            val maxLen = max(normA.length, normB.length)
            if (minLen >= 5 && minLen.toFloat() / maxLen.toFloat() >= 0.65f) {
                if (normA.contains(normB) || normB.contains(normA)) return true
            }

            if (minLen < 4) return false
            val sim = bigramDice(normA, normB)
            return if (sameAuthor) sim >= 0.62f else sim >= 0.78f
        }

        private fun bigramDice(a: String, b: String): Float {
            if (a.length < 2 || b.length < 2) return 0f
            val counts = HashMap<String, Int>(a.length)
            for (i in 0 until a.length - 1) {
                val bg = a.substring(i, i + 2)
                counts[bg] = (counts[bg] ?: 0) + 1
            }
            var overlap = 0
            for (i in 0 until b.length - 1) {
                val bg = b.substring(i, i + 2)
                val c = counts[bg] ?: 0
                if (c > 0) {
                    overlap++
                    counts[bg] = c - 1
                }
            }
            return (2f * overlap) / ((a.length - 1) + (b.length - 1)).toFloat()
        }
    }
}
