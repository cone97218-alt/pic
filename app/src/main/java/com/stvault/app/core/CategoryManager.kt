package com.stvault.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages 1-level / 2-level / 3-level custom categories (e.g. "古风", "古风/仙侠", "古风/仙侠/师尊")
 * for Themes and Characters, supporting multi-select assignment and hierarchical prefix filtering.
 */
class CategoryManager private constructor(context: Context) {

    private val lock = Any()
    private val file = File(VaultRepository.getInstance(context).getVaultRootDir(), "categories.json")
    private val scopeMap = LinkedHashMap<String, MutableList<String>>()

    init {
        load()
    }

    private fun load() {
        synchronized(lock) {
            scopeMap.clear()
            if (!file.exists()) return
            try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val keys = root.keys()
                while (keys.hasNext()) {
                    val scope = keys.next()
                    val arr = root.optJSONArray(scope) ?: continue
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val p = normalizePath(arr.optString(i, ""))
                        if (p.isNotEmpty() && p !in list) list.add(p)
                    }
                    scopeMap[scope] = list
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun save() {
        val json = synchronized(lock) {
            val root = JSONObject()
            for ((scope, list) in scopeMap) {
                root.put(scope, JSONArray(list.sorted()))
            }
            root.toString(2)
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "categories.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun normalizePath(raw: String): String {
        val parts = raw.split("/", "／", "\\", ">")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3) // max 3 levels: 一级 / 二级 / 三级
        return parts.joinToString("/")
    }

    fun getCategories(scope: String): List<String> = synchronized(lock) {
        scopeMap[scope]?.sorted() ?: emptyList()
    }

    /**
     * Resolves a shorthand tag (e.g. "某某老师" or "仙侠" or unique substring "白月光")
     * to its existing full hierarchical path (e.g. "作者/某某老师", "风格/古风/仙侠").
     * Falls back to normalized rawInput if no unambiguous existing category matches.
     */
    fun resolveTagPath(scope: String, rawInput: String): String {
        val normalized = normalizePath(rawInput)
        if (normalized.isEmpty()) return ""
        val existing = getCategories(scope)
        if (existing.isEmpty()) return normalized

        // 1. Exact full-path match (case-insensitive)
        existing.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }

        // If user explicitly provided a multi-level path, respect it as-is
        if (normalized.contains('/')) return normalized

        // 2. Exact leaf-name match (e.g. "某某" -> "作者/某某"), preferring shallower paths
        val exactLeafMatches = existing
            .filter { it.substringAfterLast('/').equals(normalized, ignoreCase = true) }
            .sortedBy { it.count { ch -> ch == '/' } }
        if (exactLeafMatches.isNotEmpty()) return exactLeafMatches.first()

        // 3. Unique substring match on leaf name (length >= 2 to avoid noisy 1-char hits)
        if (normalized.length >= 2) {
            val subMatches = existing.filter {
                it.substringAfterLast('/').contains(normalized, ignoreCase = true)
            }
            if (subMatches.size == 1) return subMatches.first()
        }

        return normalized
    }

    /**
     * Adds a category path (up to 3 levels) and automatically ensures its parent levels exist.
     */
    fun addCategory(scope: String, rawPath: String): String? {
        val normalized = normalizePath(rawPath)
        if (normalized.isEmpty()) return null
        val parts = normalized.split("/")
        synchronized(lock) {
            val list = scopeMap.getOrPut(scope) { mutableListOf() }
            var prefix = ""
            for (part in parts) {
                prefix = if (prefix.isEmpty()) part else "$prefix/$part"
                if (prefix !in list) list.add(prefix)
            }
        }
        save()
        return normalized
    }

    fun deleteCategory(scope: String, path: String) {
        synchronized(lock) {
            val list = scopeMap[scope] ?: return
            list.removeAll { it == path || it.startsWith("$path/") }
        }
        save()
    }

    /**
     * Hierarchical match: selecting "古风" matches items tagged with "古风", "古风/仙侠", "古风/仙侠/师尊".
     */
    fun matchesFilter(itemCategories: List<String>, selectedFilter: String): Boolean {
        if (selectedFilter.isBlank()) return true
        return itemCategories.any { cat ->
            cat == selectedFilter || cat.startsWith("$selectedFilter/")
        }
    }

    fun formatIndentedLabel(path: String): String {
        val depth = path.count { it == '/' }
        val leaf = path.substringAfterLast('/')
        return when (depth) {
            0 -> leaf
            1 -> "   └ $leaf"
            else -> "      └ $leaf"
        }
    }

    companion object {
        const val SCOPE_THEMES = "themes"
        const val SCOPE_CHARACTERS = "characters"

        @Volatile
        private var INSTANCE: CategoryManager? = null

        fun getInstance(context: Context): CategoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CategoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
