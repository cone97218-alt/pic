package com.stvault.app.core

import android.content.Context

data class DedupCandidate(
    val id: String,
    val name: String,
    val meta: String,
    val timestamp: Long,
    val seriesKey: String = ""
)

data class DuplicateCluster(
    val clusterKey: String,
    val module: String, // "themes" | "characters" | "worldbooks" | "presets" | "components"
    val moduleLabel: String,
    val reason: String,
    val items: List<DedupCandidate> // sorted newest first
)

/**
 * Scans all 5 modules to detect exact same-name duplicates and near-duplicate names
 * (e.g., author tweaked suffix, added version tag, or user manually modified a few chars).
 */
object SimilarityDeduplicator {

    private const val PREFS_NAME = "st_vault_dedup"
    private const val KEY_IGNORED = "ignored_clusters"

    fun isIgnored(ctx: Context, clusterKey: String): Boolean {
        val set = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()
        return clusterKey in set
    }

    fun markIgnored(ctx: Context, clusterKey: String) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cur = HashSet(prefs.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet())
        cur.add(clusterKey)
        prefs.edit().putStringSet(KEY_IGNORED, cur).apply()
    }

    fun clearIgnored(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_IGNORED).apply()
    }

    fun scanAll(
        ctx: Context,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository
    ): List<DuplicateCluster> {
        val result = mutableListOf<DuplicateCluster>()

        // 1. Themes
        val themeItems = vaultRepo.getAllSources().map { s ->
            DedupCandidate(
                id = s.id,
                name = s.name,
                meta = "图床 ${s.urls.size}",
                timestamp = s.scannedAt
            )
        }
        result.addAll(findClusters(ctx, "themes", "美化", themeItems))

        // 2. Characters
        val charItems = libRepo.getAllCharacters().map { c ->
            DedupCandidate(
                id = c.id,
                name = c.name,
                meta = if (c.isPng) "PNG · ${formatSize(c.fileSize)}" else "JSON · ${formatSize(c.fileSize)}",
                timestamp = c.addedAt,
                seriesKey = c.seriesKey
            )
        }
        result.addAll(findClusters(ctx, "characters", "角色卡", charItems))

        // 3. WorldBooks
        val wbItems = libRepo.getAllWorldBooks().map { w ->
            DedupCandidate(
                id = w.id,
                name = w.name,
                meta = "${w.entryCount} 条目",
                timestamp = w.addedAt,
                seriesKey = w.seriesKey
            )
        }
        result.addAll(findClusters(ctx, "worldbooks", "世界书", wbItems))

        // 4. Presets
        val presetItems = libRepo.getAllPresets().map { p ->
            DedupCandidate(
                id = p.id,
                name = p.name,
                meta = "启用 ${p.enabledPromptCount}/${p.promptCount}",
                timestamp = p.addedAt,
                seriesKey = p.seriesKey
            )
        }
        result.addAll(findClusters(ctx, "presets", "预设", presetItems))

        // 5. Components
        val compItems = libRepo.getAllComponents().map { c ->
            DedupCandidate(
                id = c.id,
                name = c.name,
                meta = c.summary,
                timestamp = c.addedAt,
                seriesKey = c.seriesKey
            )
        }
        result.addAll(findClusters(ctx, "components", "组件", compItems))

        return result
    }

    private fun findClusters(
        ctx: Context,
        module: String,
        moduleLabel: String,
        items: List<DedupCandidate>
    ): List<DuplicateCluster> {
        if (items.size < 2) return emptyList()

        val n = items.size
        val stems = Array(n) { normalizeStem(items[it].name) }
        val bigrams = Array(n) { buildBigrams(stems[it]) }

        // Union-Find to group pairwise similar items
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var cur = x
            while (parent[cur] != r) {
                val next = parent[cur]
                parent[cur] = r
                cur = next
            }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val bestScore = HashMap<Int, Double>()

        for (i in 0 until n) {
            if (stems[i].length < 2) continue
            for (j in i + 1 until n) {
                if (stems[j].length < 2) continue
                // Skip if already grouped into the same version series
                if (items[i].seriesKey.isNotEmpty() && items[i].seriesKey == items[j].seriesKey) continue
                // Skip color/form sibling variants for Themes (e.g. 黑/蓝/绿, 日间/夜间, 手机端/电脑端)
                if (module == "themes" && ThemeVariantGrouper.areSiblingVariants(items[i].name, items[j].name)) continue

                val exactName = items[i].name.trim().equals(items[j].name.trim(), ignoreCase = true)
                val sameStem = stems[i] == stems[j]
                val sim = if (exactName || sameStem) 1.0 else diceSimilarity(bigrams[i], bigrams[j])

                if (exactName || sameStem || sim >= 0.68) {
                    union(i, j)
                    val r = find(i)
                    val prev = bestScore[r] ?: 0.0
                    if (sim > prev) bestScore[r] = sim
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<DedupCandidate>>()
        for (i in 0 until n) {
            val r = find(i)
            groups.getOrPut(r) { mutableListOf() }.add(items[i])
        }

        val out = mutableListOf<DuplicateCluster>()
        for ((rootIdx, list) in groups) {
            if (list.size < 2) continue
            val sorted = list.sortedByDescending { it.timestamp }
            val clusterKey = "$module:" + sorted.map { it.id }.sorted().joinToString(",")
            if (isIgnored(ctx, clusterKey)) continue

            val allSameExact = sorted.map { it.name.trim().lowercase() }.distinct().size == 1
            val scorePct = ((bestScore[rootIdx] ?: 0.7) * 100).toInt().coerceIn(68, 99)
            val reason = when {
                allSameExact -> "完全同名"
                scorePct >= 99 -> "同主干近似版本"
                else -> "近似名称 ($scorePct%)"
            }
            out.add(DuplicateCluster(clusterKey, module, moduleLabel, reason, sorted))
        }
        return out
    }

    fun computeNameSimilarity(a: String, b: String): Double {
        if (a.trim().equals(b.trim(), ignoreCase = true)) return 1.0
        val sa = normalizeStem(a)
        val sb = normalizeStem(b)
        if (sa.length < 2 || sb.length < 2) return 0.0
        if (sa == sb) return 0.99
        return diceSimilarity(buildBigrams(sa), buildBigrams(sb))
    }

    private fun normalizeStem(raw: String): String {
        var s = raw.trim().lowercase()
        // Strip file extensions
        s = s.replace(Regex("\\.(json|png|css|html|htm|txt|js)$"), "")
        // Strip bracketed annotations like (修复版), 【自改】, [v2]
        s = s.replace(Regex("[(\\[【（][^)\\]】）]{1,18}[)\\]】）]"), " ")
        // Strip version markers like v1.2, V2, rev3, beta, final, 修复, 自改, 汉化, 备份
        s = s.replace(Regex("\\b(v|ver|version|rev|r)[\\s._-]*\\d+(\\.\\d+)*[a-z]?\\b"), " ")
        s = s.replace(Regex("(修复版|复活版|自改版|汉化版|测试版|正式版|新版|旧版|备份|副本|copy|fixed|mod|edit)"), " ")
        // Keep only CJK, letters, and digits
        s = s.replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        return s
    }

    private fun buildBigrams(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        val set = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) {
            set.add(s.substring(i, i + 2))
        }
        return set
    }

    private fun diceSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var common = 0
        for (x in a) {
            if (x in b) common++
        }
        return (2.0 * common) / (a.size + b.size)
    }

    private fun formatSize(b: Long): String {
        if (b < 1024) return "$b B"
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0)
        return String.format("%.2f MB", b / (1024.0 * 1024.0))
    }
}
