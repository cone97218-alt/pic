package com.stvault.app.core

import org.json.JSONArray
import org.json.JSONObject

object WorldBookParser {

    fun isWorldBookJson(rawJson: String): Boolean {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val root = JSONObject(trimmed)
            val entriesObj = root.optJSONObject("entries")
            val entriesArr = root.optJSONArray("entries")
            (entriesObj != null && entriesObj.length() > 0) || (entriesArr != null && entriesArr.length() > 0)
        } catch (_: Exception) {
            false
        }
    }

    fun parse(rawJson: String, fallbackName: String): ParsedWorldBook? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }
        val entries = parseEntriesFromContainer(root)
        if (entries.isEmpty()) return null
        val name = root.optString("name", "").ifBlank { fallbackName }
        return ParsedWorldBook(name = name, entries = entries, rawJson = trimmed)
    }

    fun parseEntriesFromContainer(container: JSONObject): List<ParsedWorldEntry> {
        val list = mutableListOf<ParsedWorldEntry>()
        val entriesObj = container.optJSONObject("entries")
        val entriesArr = container.optJSONArray("entries")

        if (entriesObj != null) {
            val keys = entriesObj.keys()
            var idx = 0
            while (keys.hasNext()) {
                val k = keys.next()
                val o = entriesObj.optJSONObject(k) ?: continue
                list.add(parseEntry(o, idx++))
            }
        } else if (entriesArr != null) {
            for (i in 0 until entriesArr.length()) {
                val o = entriesArr.optJSONObject(i) ?: continue
                list.add(parseEntry(o, i))
            }
        }
        return list.sortedBy { it.order }
    }

    private fun parseEntry(o: JSONObject, fallbackUid: Int): ParsedWorldEntry {
        val uid = o.optInt("uid", o.optInt("id", fallbackUid))
        val keys = readStringList(o.optJSONArray("key") ?: o.optJSONArray("keys"))
        val secondaryKeys = readStringList(o.optJSONArray("keysecondary") ?: o.optJSONArray("secondary_keys"))
        val title = o.optString("comment", o.optString("name", ""))
            .ifBlank { keys.firstOrNull() ?: "条目 #$uid" }
        val content = o.optString("content", "")
        val constant = o.optBoolean("constant", false)
        val enabled = if (o.has("disable")) !o.optBoolean("disable", false) else o.optBoolean("enabled", true)
        val order = o.optInt("order", o.optInt("insertion_order", 100))
        val depth = o.optInt("depth", 4)
        return ParsedWorldEntry(
            uid = uid,
            title = title,
            keys = keys,
            secondaryKeys = secondaryKeys,
            content = content,
            constant = constant,
            enabled = enabled,
            order = order,
            depth = depth
        )
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /**
     * Converts parsed entries (e.g. from a character card's embedded `character_book`)
     * into a standard SillyTavern standalone World Info JSON file.
     */
    fun buildStandaloneJson(name: String, entries: List<ParsedWorldEntry>): String {
        val root = JSONObject()
        val entriesObj = JSONObject()
        entries.forEachIndexed { index, e ->
            val obj = JSONObject()
            obj.put("uid", index)
            obj.put("key", JSONArray(e.keys))
            obj.put("keysecondary", JSONArray(e.secondaryKeys))
            obj.put("comment", e.title)
            obj.put("content", e.content)
            obj.put("constant", e.constant)
            obj.put("selective", true)
            obj.put("order", e.order)
            obj.put("position", 0)
            obj.put("disable", !e.enabled)
            obj.put("depth", e.depth)
            entriesObj.put(index.toString(), obj)
        }
        root.put("name", name)
        root.put("entries", entriesObj)
        return root.toString(2)
    }
}

data class ParsedPresetGroup(
    val id: String,
    val name: String,
    val order: Int,
    val collapsed: Boolean = true,
    val enabled: Boolean = true
)

data class ParsedPresetPrompt(
    val identifier: String,
    val name: String,
    val role: String,
    val content: String,
    val enabled: Boolean,
    val isMarker: Boolean
)

data class ParsedPreset(
    val name: String,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int?,
    val maxContext: Int?,
    val prompts: List<ParsedPresetPrompt>,
    val promptGroups: List<ParsedPresetGroup> = emptyList(),
    val promptGroupMap: Map<String, String> = emptyMap(),
    val embeddedRegexScripts: List<ParsedRegexScript> = emptyList(),
    val embeddedHelperScripts: List<ParsedHelperScript> = emptyList(),
    val rawJson: String
)

object PresetParser {

    fun isPresetJson(rawJson: String): Boolean {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val root = JSONObject(trimmed)
            root.has("prompts") && (root.has("prompt_order") || root.has("temperature") || root.has("openai_max_tokens"))
        } catch (_: Exception) {
            false
        }
    }

    fun parse(rawJson: String, fallbackName: String): ParsedPreset? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }
        val promptsArr = root.optJSONArray("prompts") ?: return null
        val promptMap = LinkedHashMap<String, ParsedPresetPrompt>()
        for (i in 0 until promptsArr.length()) {
            val o = promptsArr.optJSONObject(i) ?: continue
            val id = o.optString("identifier", "p_$i")
            val name = o.optString("name", id).ifBlank { id }
            val role = o.optString("role", "system")
            val content = o.optString("content", "")
            val enabled = o.optBoolean("enabled", true)
            val isMarker = o.optBoolean("marker", false)
            promptMap[id] = ParsedPresetPrompt(id, name, role, content, enabled, isMarker)
        }

        // Apply prompt_order if present (prefer character_id 100001 default order or last order block)
        val ordered = mutableListOf<ParsedPresetPrompt>()
        val seen = HashSet<String>()
        val orderRoot = root.optJSONArray("prompt_order")
        var chosenOrder: JSONArray? = null
        if (orderRoot != null && orderRoot.length() > 0) {
            for (i in 0 until orderRoot.length()) {
                val block = orderRoot.optJSONObject(i) ?: continue
                if (block.optInt("character_id", -1) == 100001) {
                    chosenOrder = block.optJSONArray("order")
                    break
                }
            }
            if (chosenOrder == null) {
                chosenOrder = orderRoot.optJSONObject(orderRoot.length() - 1)?.optJSONArray("order")
            }
        }
        if (chosenOrder != null) {
            for (i in 0 until chosenOrder.length()) {
                val item = chosenOrder.optJSONObject(i) ?: continue
                val id = item.optString("identifier", "")
                val p = promptMap[id] ?: continue
                if (seen.add(id)) {
                    ordered.add(p.copy(enabled = item.optBoolean("enabled", p.enabled)))
                }
            }
        }
        for ((id, p) in promptMap) {
            if (seen.add(id)) ordered.add(p)
        }

        val temp = if (root.has("temperature")) root.optDouble("temperature") else null
        val topP = if (root.has("top_p")) root.optDouble("top_p") else null
        val maxTokens = if (root.has("openai_max_tokens")) root.optInt("openai_max_tokens") else null
        val maxContext = if (root.has("openai_max_context")) root.optInt("openai_max_context") else null

        // Parse embedded regex & Tavern Helper scripts inside preset extensions (or top-level)
        val extObj = root.optJSONObject("extensions")
        val regexList = mutableListOf<ParsedRegexScript>()
        regexList.addAll(CharacterCardParser.parseEmbeddedRegex(extObj))
        if (root.has("regex_scripts")) {
            regexList.addAll(CharacterCardParser.parseEmbeddedRegex(root))
        }
        val helperList = mutableListOf<ParsedHelperScript>()
        helperList.addAll(CharacterCardParser.parseEmbeddedHelper(extObj))
        if (root.has("tavern_helper") || root.has("JS-Slash-Runner")) {
            helperList.addAll(CharacterCardParser.parseEmbeddedHelper(root))
        }

        val (promptGroups, promptGroupMap) = parseBaiBaiPresetGroups(extObj, ordered.map { it.identifier })

        return ParsedPreset(
            name = fallbackName.substringBeforeLast('.'),
            temperature = temp,
            topP = topP,
            maxTokens = maxTokens,
            maxContext = maxContext,
            prompts = ordered,
            promptGroups = promptGroups,
            promptGroupMap = promptGroupMap,
            embeddedRegexScripts = regexList.distinctBy { it.name + it.findRegex },
            embeddedHelperScripts = helperList.distinctBy { it.name + it.content.take(40) },
            rawJson = trimmed
        )
    }

    /**
     * Parses ST-BaiBai-Tools preset prompt groups (`extensions.baibaiToolkit.presetPromptGroups`)
     * as well as compatible `extensions.entryGrouping` range/member definitions.
     */
    private fun parseBaiBaiPresetGroups(
        extObj: JSONObject?,
        orderedIds: List<String>
    ): Pair<List<ParsedPresetGroup>, Map<String, String>> {
        if (extObj == null || orderedIds.isEmpty()) return emptyList<ParsedPresetGroup>() to emptyMap()
        val validIds = orderedIds.toHashSet()

        // 1. Native ST-BaiBai-Tools format: extensions.baibaiToolkit.presetPromptGroups
        val baibai = extObj.optJSONObject("baibaiToolkit")?.optJSONObject("presetPromptGroups")
        if (baibai != null) {
            val groupsArr = baibai.optJSONArray("groups")
            val promptsObj = baibai.optJSONObject("prompts")
            if (groupsArr != null && groupsArr.length() > 0 && promptsObj != null) {
                val rawGroups = mutableListOf<ParsedPresetGroup>()
                val seenGids = HashSet<String>()
                for (i in 0 until groupsArr.length()) {
                    val g = groupsArr.optJSONObject(i) ?: continue
                    val gid = g.optString("id", "").trim()
                    if (gid.isEmpty() || !seenGids.add(gid)) continue
                    rawGroups.add(
                        ParsedPresetGroup(
                            id = gid,
                            name = g.optString("name", "未命名分组").ifBlank { "未命名分组" },
                            order = g.optInt("order", i),
                            collapsed = g.optBoolean("collapsed", false),
                            enabled = g.optBoolean("enabled", true)
                        )
                    )
                }
                val sortedGroups = rawGroups.sortedBy { it.order }
                val validGids = sortedGroups.map { it.id }.toHashSet()
                val map = LinkedHashMap<String, String>()
                val it = promptsObj.keys()
                while (it.hasNext()) {
                    val pid = it.next()
                    if (pid !in validIds) continue
                    val meta = promptsObj.optJSONObject(pid)
                    val gid = meta?.optString("groupId", "").orEmpty()
                    if (gid.isNotEmpty() && gid in validGids) {
                        map[pid] = gid
                    }
                }
                if (sortedGroups.isNotEmpty() && map.isNotEmpty()) {
                    return sortedGroups to map
                }
            }
        }

        // 2. Compat format: extensions.entryGrouping (range or member list)
        val compatRaw = extObj.opt("entryGrouping")
        val compatEntries: JSONArray? = when (compatRaw) {
            is JSONArray -> compatRaw
            is JSONObject -> compatRaw.optJSONArray("groups")
                ?: compatRaw.optJSONArray("entries")
                ?: compatRaw.optJSONArray("entryGroups")
                ?: compatRaw.optJSONArray("items")
            else -> null
        }
        if (compatEntries != null && compatEntries.length() > 0) {
            val groups = mutableListOf<ParsedPresetGroup>()
            val map = LinkedHashMap<String, String>()
            for (i in 0 until compatEntries.length()) {
                val e = compatEntries.optJSONObject(i) ?: continue
                val gid = e.optString("id", "compat_$i").ifBlank { "compat_$i" }
                val gName = e.optString("name", "未命名分组").ifBlank { "未命名分组" }
                val startId = e.optString("startIdentifier", "")
                val endId = e.optString("endIdentifier", "")
                val matchedIds = mutableListOf<String>()
                if (startId.isNotEmpty() && endId.isNotEmpty()) {
                    val sIdx = orderedIds.indexOf(startId)
                    val eIdx = orderedIds.indexOf(endId)
                    if (sIdx >= 0 && eIdx >= 0) {
                        val excl = e.optString("mode", "inclusive").equals("exclusive", ignoreCase = true)
                        val from = minOf(sIdx, eIdx) + if (excl) 1 else 0
                        val to = maxOf(sIdx, eIdx) - if (excl) 1 else 0
                        if (from <= to) {
                            for (k in from..to) matchedIds.add(orderedIds[k])
                        }
                    }
                } else {
                    val members = e.optJSONArray("members")
                        ?: e.optJSONArray("identifiers")
                        ?: e.optJSONArray("promptIds")
                        ?: e.optJSONArray("prompts")
                    if (members != null) {
                        for (k in 0 until members.length()) {
                            val pid = members.optString(k, "")
                            if (pid in validIds) matchedIds.add(pid)
                        }
                    }
                }
                if (matchedIds.isNotEmpty()) {
                    groups.add(ParsedPresetGroup(gid, gName, i, collapsed = true, enabled = e.optBoolean("enabled", true)))
                    for (pid in matchedIds) {
                        if (pid !in map) map[pid] = gid
                    }
                }
            }
            if (groups.isNotEmpty() && map.isNotEmpty()) {
                return groups to map
            }
        }

        return emptyList<ParsedPresetGroup>() to emptyMap()
    }
}

data class ParsedComponent(
    val name: String,
    val type: String, // "regex" | "helper" | "qr"
    val summary: String,
    val rawContent: String
)

object ComponentParser {

    fun parse(rawContent: String, fileName: String): ParsedComponent? {
        val trimmed = rawContent.trim()
        val baseName = fileName.substringBeforeLast('.')
        if (trimmed.isEmpty()) return null

        // Standalone JS helper script
        if (fileName.endsWith(".js", ignoreCase = true)) {
            return ParsedComponent(
                name = baseName,
                type = "helper",
                summary = "JS 脚本 · ${trimmed.length} 字符",
                rawContent = trimmed
            )
        }
        // Plain text / Markdown note
        if (fileName.endsWith(".txt", ignoreCase = true) || fileName.endsWith(".md", ignoreCase = true)) {
            return ParsedComponent(
                name = baseName,
                type = "doc",
                summary = "文本笔记 · ${trimmed.length} 字",
                rawContent = trimmed
            )
        }

        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("findRegex") && obj.has("replaceString") -> {
                        val name = obj.optString("scriptName", baseName).ifBlank { baseName }
                        val regex = obj.optString("findRegex", "").take(60)
                        ParsedComponent(name, "regex", "正则 · $regex", trimmed)
                    }
                    obj.has("qrList") -> {
                        val qrArr = obj.optJSONArray("qrList")
                        val count = qrArr?.length() ?: 0
                        val name = obj.optString("name", baseName).ifBlank { baseName }
                        ParsedComponent(name, "qr", "快速回复 · $count 项", trimmed)
                    }
                    obj.has("scripts") || (obj.has("type") && obj.optString("type") == "script") || obj.has("script") || (obj.has("content") && obj.has("enabled")) -> {
                        val name = obj.optString("name", baseName).ifBlank { baseName }
                        val info = obj.optString("info", "").take(60).ifBlank { "酒馆助手脚本" }
                        ParsedComponent(name, "helper", info, trimmed)
                    }
                    else -> null
                }
            } else {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return null
                val first = arr.optJSONObject(0) ?: return null
                when {
                    first.has("findRegex") -> ParsedComponent(baseName, "regex", "正则合集 · ${arr.length()} 条", trimmed)
                    first.has("content") || first.has("script") -> ParsedComponent(baseName, "helper", "助手脚本集 · ${arr.length()} 个", trimmed)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
