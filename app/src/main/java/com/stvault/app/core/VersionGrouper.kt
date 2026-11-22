package com.stvault.app.core

import com.stvault.app.model.GroupedItem

/**
 * Extracts normalized series keys and version tags from resource names / file names
 * so multiple versions of the same work auto-aggregate cleanly.
 */
object VersionGrouper {

    private val VERSION_PATTERNS = listOf(
        // v1.2.3, V2.0, ver1.5, v1.2-beta, v1.2_fix
        Regex("""[\s_\-\.\(\[\【]*(?:v|ver|version)[\s._\-]*(\d+(?:\.\d+){0,3}[a-zA-Z0-9\-]*)[\)\]\】\s]*$""", RegexOption.IGNORE_CASE),
        // Trailing date stamp: 20260301, 2026-03-01, 2026.03.01
        Regex("""[\s_\-\(\[\【]*(20\d{2}[\-.]?\d{2}[\-.]?\d{2})[\)\]\】\s]*$"""),
        // Pure trailing dotted version: 1.2, 2.0.1
        Regex("""[\s_\-\(\[\【]+(\d+\.\d+(?:\.\d+)?[a-zA-Z]?)[\)\]\】\s]*$"""),
        // Chinese release suffixes: 最终版, 修复版, 优化版, 重制版, 第二版, 完整版, 测试版
        Regex("""[\s_\-\(\[\【]*((?:最终|修复|优化|重制|完整|测试|正式|更新|精简|魔改|第[一二三四五六七八九十\d]+)版?\d*)[\)\]\】\s]*$""")
    )

    /**
     * Returns Pair(seriesKey, versionTag)
     */
    fun extractSeriesAndVersion(
        rawName: String,
        explicitVersion: String = ""
    ): Pair<String, String> {
        var base = rawName.substringAfterLast('/').substringBeforeLast('.').trim()
        var foundVersion = explicitVersion.trim()

        // Iteratively strip trailing version/date markers
        for (pass in 0 until 2) {
            var matched = false
            for (pat in VERSION_PATTERNS) {
                val m = pat.find(base)
                if (m != null && m.range.first > 0) {
                    val candidateVer = m.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (foundVersion.isEmpty() && candidateVer.isNotEmpty()) {
                        foundVersion = candidateVer
                    }
                    base = base.substring(0, m.range.first).trimEnd(' ', '_', '-', '.', '(', '[', '【')
                    matched = true
                    break
                }
            }
            if (!matched) break
        }

        val normalizedKey = base.lowercase()
            .replace(Regex("""[\s_\-·•]+"""), "")
            .ifBlank { rawName.lowercase() }

        return normalizedKey to foundVersion
    }

    /**
     * Groups items by seriesKey, choosing the latest item (by addedAt descending) as primary.
     */
    fun <T> groupBySeries(
        items: List<T>,
        groupEnabled: Boolean,
        seriesKeyOf: (T) -> String,
        timestampOf: (T) -> Long
    ): List<GroupedItem<T>> {
        if (!groupEnabled) {
            return items.map { GroupedItem(it, 1) }
        }
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val key = seriesKeyOf(item).ifBlank { item.hashCode().toString() }
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        return buckets.values.map { list ->
            val sorted = list.sortedByDescending(timestampOf)
            GroupedItem(primary = sorted.first(), versionCount = sorted.size)
        }.sortedByDescending { timestampOf(it.primary) }
    }
}
