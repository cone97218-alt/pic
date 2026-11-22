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

        return ParsedPreset(
            name = fallbackName.substringBeforeLast('.'),
            temperature = temp,
            topP = topP,
            maxTokens = maxTokens,
            maxContext = maxContext,
            prompts = ordered,
            embeddedRegexScripts = regexList.distinctBy { it.name + it.findRegex },
            embeddedHelperScripts = helperList.distinctBy { it.name + it.content.take(40) },
            rawJson = trimmed
        )
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
