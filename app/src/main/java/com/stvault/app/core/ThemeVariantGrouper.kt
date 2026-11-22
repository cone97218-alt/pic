package com.stvault.app.core

import android.content.Context

/**
 * Recognizes color / dark-light / layout / device variant tokens in SillyTavern theme names
 * so sibling themes (e.g. 叠金玉良缘-宽黑 / 宽蓝 / 窄白) are grouped as coexisting variants
 * rather than flagged as duplicate conflicts.
 */
object ThemeVariantGrouper {

    private const val PREFS_NAME = "st_vault_theme_variant_prefs"

    private val MULTI_CHAR_VARIANTS = listOf(
        "手机端", "移动端", "电脑端", "桌面端", "平板端",
        "深色版", "浅色版", "暗色版", "亮色版", "夜间版", "日间版", "白天版", "暗黑版", "护眼版",
        "黑色版", "白色版", "蓝色版", "绿色版", "粉色版", "紫色版", "红色版", "灰色版", "金色版", "棕色版", "黄色版",
        "宽屏版", "窄屏版", "简约版", "极简版", "华丽版", "透明版", "磨砂版", "玻璃版", "圆角版", "方角版",
        "纯黑", "纯白", "深色", "浅色", "暗色", "亮色", "暗黑", "夜间", "日间", "白天", "黑夜", "护眼",
        "手机", "移动", "电脑", "桌面", "平板", "横屏", "竖屏", "宽屏", "窄屏", "圆角", "方角",
        "气泡", "无气泡", "简约", "极简", "华丽", "透明", "磨砂", "玻璃", "渐变", "毛玻璃",
        "宽黑", "宽白", "窄黑", "窄白", "宽蓝", "窄蓝", "宽粉", "窄粉", "宽绿", "窄绿",
        "夜间黑", "夜间蓝", "夜间粉", "夜间紫", "日间白", "日间粉", "日间蓝",
        "dark", "light", "night", "day", "black", "white", "blue", "green", "pink", "purple",
        "red", "gold", "grey", "gray", "mobile", "desktop", "wide", "narrow", "compact", "pc"
    ).sortedByDescending { it.length }

    private val SINGLE_CHAR_VARIANTS = setOf(
        '黑', '白', '灰', '红', '蓝', '绿', '粉', '紫', '黄', '棕', '橙', '青', '金', '银',
        '暗', '亮', '深', '浅', '宽', '窄', '日', '夜', '简', '繁', '透', '雾', '奶', '茶', '墨', '玄', '素'
    )

    private val EXT_RE = Regex("\\.(json|css|html|htm|txt)$", RegexOption.IGNORE_CASE)
    private val SPLIT_RE = Regex("[/\\-_·•\\s()（）\\[\\]【】]+")
    private val NORM_KEY_RE = Regex("[\\s_\\-/·•()（）\\[\\]【】]+")

    fun extractDisplaySeriesName(rawName: String): String {
        val cleaned = EXT_RE.replace(rawName.trim(), "").trim()
        if (cleaned.isEmpty()) return ""
        val tokens = cleaned.split(SPLIT_RE).filter { it.isNotBlank() }
        if (tokens.size >= 2) {
            val baseParts = mutableListOf<String>()
            var hitVar = false
            for ((idx, tok) in tokens.withIndex()) {
                if (idx > 0 && isPureVariantToken(tok)) {
                    hitVar = true
                } else {
                    baseParts.add(tok)
                }
            }
            if (hitVar && baseParts.isNotEmpty()) {
                val joined = baseParts.joinToString(" ").trim()
                if (joined.length >= 2) return joined
            }
        }
        var stem = cleaned
        for (pass in 0 until 2) {
            var matched = false
            for (v in MULTI_CHAR_VARIANTS) {
                if (stem.length > v.length + 1 && stem.endsWith(v, ignoreCase = true)) {
                    val cut = stem.substring(0, stem.length - v.length).trimEnd(' ', '-', '_', '/', '·', '(', '（')
                    if (cut.length >= 2) {
                        stem = cut
                        matched = true
                        break
                    }
                }
            }
            if (!matched && stem.length >= 4 && stem.last() in SINGLE_CHAR_VARIANTS) {
                val prev = stem[stem.length - 2]
                if (prev in setOf('-', '_', '/', ' ', '·') || prev in SINGLE_CHAR_VARIANTS) {
                    stem = stem.substring(0, stem.length - 1).trimEnd(' ', '-', '_', '/', '·')
                    matched = true
                }
            }
            if (!matched) break
        }
        return stem.ifBlank { cleaned }
    }

    /**
     * Returns Pair(normalizedSeriesKey, displayVariantTag).
     * Example: "叠金玉良缘/手机端/宽/黑" -> ("叠金玉良缘", "手机端·宽·黑")
     *          "樱花玻璃-夜间粉" -> ("樱花玻璃", "夜间粉")
     */
    fun extractSeriesAndVariant(rawName: String): Pair<String, String> {
        val cleaned = EXT_RE.replace(rawName.trim(), "").trim()
        if (cleaned.isEmpty()) return "" to ""

        val foundVariants = mutableListOf<String>()

        // 1. Split by common separators (/, -, _, space, ·, parentheses)
        val tokens = cleaned.split(SPLIT_RE).filter { it.isNotBlank() }
        if (tokens.size >= 2) {
            val baseParts = mutableListOf<String>()
            for ((idx, tok) in tokens.withIndex()) {
                if (idx > 0 && isPureVariantToken(tok)) {
                    foundVariants.add(tok)
                } else {
                    baseParts.add(tok)
                }
            }
            if (foundVariants.isNotEmpty() && baseParts.isNotEmpty()) {
                val baseJoined = baseParts.joinToString("")
                val seriesKey = normalizeKey(baseJoined)
                if (seriesKey.length >= 2) {
                    return seriesKey to foundVariants.joinToString("·")
                }
            }
        }

        // 2. Fallback: strip trailing multi-char or single-char variant suffix directly attached to title
        var stem = cleaned
        for (pass in 0 until 2) {
            var matched = false
            for (v in MULTI_CHAR_VARIANTS) {
                if (stem.length > v.length + 1 && stem.endsWith(v, ignoreCase = true)) {
                    val cut = stem.substring(0, stem.length - v.length).trimEnd(' ', '-', '_', '/', '·', '(', '（')
                    if (cut.length >= 2) {
                        foundVariants.add(0, stem.substring(stem.length - v.length))
                        stem = cut
                        matched = true
                        break
                    }
                }
            }
            if (!matched && stem.length >= 4 && stem.last() in SINGLE_CHAR_VARIANTS) {
                // Only strip single char if preceded by a separator or another variant char
                val prev = stem[stem.length - 2]
                if (prev in setOf('-', '_', '/', ' ', '·') || prev in SINGLE_CHAR_VARIANTS) {
                    foundVariants.add(0, stem.last().toString())
                    stem = stem.substring(0, stem.length - 1).trimEnd(' ', '-', '_', '/', '·')
                    matched = true
                }
            }
            if (!matched) break
        }

        val key = normalizeKey(stem).ifBlank { normalizeKey(cleaned) }
        return key to foundVariants.joinToString("·")
    }

    private fun isPureVariantToken(token: String): Boolean {
        val low = token.trim().lowercase()
        if (low.isEmpty()) return false
        if (low.length == 1 && low[0] in SINGLE_CHAR_VARIANTS) return true
        if (MULTI_CHAR_VARIANTS.any { it.equals(low, ignoreCase = true) }) return true
        // Composite variant token like "手机端宽黑" or "夜间粉"
        var rem = low
        var hits = 0
        while (rem.isNotEmpty()) {
            val m = MULTI_CHAR_VARIANTS.firstOrNull { rem.startsWith(it, ignoreCase = true) }
            if (m != null) {
                rem = rem.substring(m.length)
                hits++
            } else if (rem[0] in SINGLE_CHAR_VARIANTS) {
                rem = rem.substring(1)
                hits++
            } else {
                return false
            }
        }
        return hits > 0
    }

    /**
     * Returns true when two theme names share the same series stem and differ only in color/form variants.
     */
    fun areSiblingVariants(nameA: String, nameB: String): Boolean {
        if (nameA.trim().equals(nameB.trim(), ignoreCase = true)) return false
        val (sA, vA) = extractSeriesAndVariant(nameA)
        val (sB, vB) = extractSeriesAndVariant(nameB)
        if (sA.length < 2 || sA != sB) return false
        return vA != vB && (vA.isNotEmpty() || vB.isNotEmpty())
    }

    private val EMPTY_BRACKET_RE = Regex("\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】")
    private val TRIM_SEP_CHARS = charArrayOf(' ', '\t', '-', '_', '/', '\\', '·', '•', '|', ':', '：', '(', ')', '（', '）', '[', ']', '【', '】', '~', '—')

    /**
     * Derives clean variant tags for all themes in a series by:
     * 1. Removing the user-defined [seriesName] from each member's original name.
     * 2. Removing tokens and common prefixes/suffixes shared across all members.
     * 3. Keeping only the distinguishing part as each member's variant tag.
     */
    fun deriveVariantTags(seriesName: String, memberNames: List<String>): List<String> {
        if (memberNames.isEmpty()) return emptyList()
        if (memberNames.size == 1) {
            val single = stripSeriesFromName(memberNames[0], seriesName)
            return listOf(single.ifBlank { extractSeriesAndVariant(memberNames[0]).second })
        }

        // Step 1: strip file extension and the seriesName itself
        val afterSeries = memberNames.map { stripSeriesFromName(it, seriesName) }

        // Step 2a: token-level common part removal
        val tokenLists = afterSeries.map { s ->
            s.split(SPLIT_RE).map { it.trim(*TRIM_SEP_CHARS) }.filter { it.isNotEmpty() }
        }
        val nonEmptyTokenLists = tokenLists.filter { it.isNotEmpty() }
        val step2a = if (nonEmptyTokenLists.size >= 2) {
            var common = nonEmptyTokenLists.first().map { it.lowercase() }.toSet()
            for (i in 1 until nonEmptyTokenLists.size) {
                common = common intersect nonEmptyTokenLists[i].map { it.lowercase() }.toSet()
                if (common.isEmpty()) break
            }
            if (common.isNotEmpty()) {
                val reduced = tokenLists.map { list ->
                    list.filterNot { it.lowercase() in common }.joinToString("·")
                }
                // Only apply if at least one member remains non-empty
                if (reduced.any { it.isNotEmpty() }) reduced else afterSeries
            } else {
                afterSeries
            }
        } else {
            afterSeries
        }

        // Step 2b: character-level common prefix & suffix removal (length >= 2 to avoid chopping single-char suffixes like "版")
        var step2b = step2a.map { it.trim(*TRIM_SEP_CHARS) }
        val nonEmptyIndices = step2b.indices.filter { step2b[it].isNotEmpty() }
        if (nonEmptyIndices.size >= 2) {
            val lcpLen = commonPrefixLengthIgnoreCase(nonEmptyIndices.map { step2b[it] })
            if (lcpLen >= 2) {
                val cand = step2b.mapIndexed { idx, s ->
                    if (idx in nonEmptyIndices) s.substring(lcpLen).trim(*TRIM_SEP_CHARS) else s
                }
                if (cand.any { it.isNotEmpty() }) {
                    step2b = cand
                }
            }
        }
        val nonEmptyAfterPrefix = step2b.indices.filter { step2b[it].isNotEmpty() }
        if (nonEmptyAfterPrefix.size >= 2) {
            val lcsLen = commonSuffixLengthIgnoreCase(nonEmptyAfterPrefix.map { step2b[it] })
            if (lcsLen >= 2) {
                val cand = step2b.mapIndexed { idx, s ->
                    if (idx in nonEmptyAfterPrefix && s.length > lcsLen) {
                        s.substring(0, s.length - lcsLen).trim(*TRIM_SEP_CHARS)
                    } else s
                }
                // Apply suffix strip only if all previously non-empty members remain non-empty
                if (nonEmptyAfterPrefix.all { cand[it].isNotEmpty() }) {
                    step2b = cand
                }
            }
        }

        // Step 3: finalize & fallback
        val hasAnyNonEmpty = step2b.any { it.isNotEmpty() }
        return step2b.mapIndexed { idx, tag ->
            val clean = EMPTY_BRACKET_RE.replace(tag, "").trim(*TRIM_SEP_CHARS)
            when {
                clean.isNotEmpty() -> clean
                hasAnyNonEmpty -> "默认"
                else -> EXT_RE.replace(memberNames[idx].trim(), "").trim()
            }
        }
    }

    private fun stripSeriesFromName(rawName: String, seriesName: String): String {
        var s = EXT_RE.replace(rawName.trim(), "").trim()
        val series = seriesName.trim()
        if (series.isNotEmpty()) {
            val exactRe = Regex(Regex.escape(series), RegexOption.IGNORE_CASE)
            val removed = exactRe.replace(s, " ")
            if (removed != s) {
                s = removed
            } else {
                // Fallback: remove multi-char tokens of seriesName (e.g. series has slightly different separators)
                val sTokens = series.split(SPLIT_RE).filter { it.length >= 2 }
                for (tok in sTokens) {
                    s = Regex(Regex.escape(tok), RegexOption.IGNORE_CASE).replace(s, " ")
                }
            }
        }
        s = EMPTY_BRACKET_RE.replace(s, " ")
        return s.trim(*TRIM_SEP_CHARS)
    }

    private fun commonPrefixLengthIgnoreCase(list: List<String>): Int {
        if (list.size < 2) return 0
        val minLen = list.minOf { it.length }
        var len = 0
        while (len < minLen) {
            val c = list[0][len].lowercaseChar()
            if (list.all { it[len].lowercaseChar() == c }) len++ else break
        }
        return len
    }

    private fun commonSuffixLengthIgnoreCase(list: List<String>): Int {
        if (list.size < 2) return 0
        val minLen = list.minOf { it.length }
        var len = 0
        while (len < minLen) {
            val c = list[0][list[0].length - 1 - len].lowercaseChar()
            if (list.all { it[it.length - 1 - len].lowercaseChar() == c }) len++ else break
        }
        return len
    }

    fun normalizeKey(raw: String): String {
        return NORM_KEY_RE.replace(raw.trim().lowercase(), "")
    }

    fun setPreferredVariant(ctx: Context, seriesKey: String, sourceId: String) {
        if (seriesKey.isBlank() || sourceId.isBlank()) return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("pref_theme_$seriesKey", sourceId)
            .apply()
    }

    fun getPreferredVariant(ctx: Context, seriesKey: String): String? {
        if (seriesKey.isBlank()) return null
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("pref_theme_$seriesKey", null)
    }
}
