package com.stvault.app.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class ParsedWorldEntry(
    val uid: Int,
    val title: String,
    val keys: List<String>,
    val secondaryKeys: List<String>,
    val content: String,
    val constant: Boolean,
    val enabled: Boolean,
    val order: Int,
    val depth: Int
)

data class ParsedWorldBook(
    val name: String,
    val entries: List<ParsedWorldEntry>,
    val rawJson: String
)

data class ParsedRegexScript(
    val name: String,
    val findRegex: String,
    val replaceString: String,
    val disabled: Boolean,
    val rawJson: String
)

data class ParsedHelperScript(
    val name: String,
    val enabled: Boolean,
    val info: String,
    val content: String,
    val rawJson: String
)

data class ParsedCharacterCard(
    val name: String,
    val creator: String,
    val characterVersion: String,
    val spec: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMes: String,
    val mesExample: String,
    val creatorNotes: String,
    val systemPrompt: String,
    val alternateGreetings: List<String>,
    val tags: List<String>,
    val embeddedWorldBook: ParsedWorldBook?,
    val embeddedRegexScripts: List<ParsedRegexScript>,
    val embeddedHelperScripts: List<ParsedHelperScript>,
    val rawJson: String
)

object CharacterCardParser {

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in 0 until 8) {
            if (bytes[i] != PNG_SIG[i]) return false
        }
        return true
    }

    /**
     * Extracts character card JSON from PNG `ccv3` (preferred) or `chara` tEXt chunk.
     */
    fun extractJsonFromPng(bytes: ByteArray): String? {
        if (!isPng(bytes)) return null
        var charaJson: String? = null
        var ccv3Json: String? = null
        var pos = 8

        while (pos + 12 <= bytes.size) {
            val len = readUInt32BE(bytes, pos)
            if (len < 0 || pos + 12L + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == "tEXt") {
                val dataStart = pos + 8
                val dataEnd = dataStart + len
                var nullIdx = -1
                for (i in dataStart until dataEnd) {
                    if (bytes[i].toInt() == 0) {
                        nullIdx = i
                        break
                    }
                }
                if (nullIdx > dataStart) {
                    val key = String(bytes, dataStart, nullIdx - dataStart, Charsets.ISO_8859_1).lowercase()
                    if (key == "ccv3" || key == "chara") {
                        val b64 = String(bytes, nullIdx + 1, dataEnd - (nullIdx + 1), Charsets.ISO_8859_1)
                        try {
                            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                            if (decoded.trim().startsWith("{")) {
                                if (key == "ccv3") ccv3Json = decoded else charaJson = decoded
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            pos += 12 + len
            if (type == "IEND") break
        }
        return ccv3Json ?: charaJson
    }

    /**
     * Losslessly rewrites `chara` and `ccv3` tEXt chunks in a PNG with updated JSON,
     * preserving all image data and other PNG chunks intact.
     */
    fun rewritePngCardJson(pngBytes: ByteArray, newJson: String): ByteArray {
        if (!isPng(pngBytes)) return pngBytes
        val b64Bytes = Base64.encode(newJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val out = ByteArrayOutputStream(pngBytes.size + 4096)
        out.write(pngBytes, 0, 8)

        var pos = 8
        var wroteChara = false
        while (pos + 12 <= pngBytes.size) {
            val len = readUInt32BE(pngBytes, pos)
            if (len < 0 || pos + 12L + len > pngBytes.size) break
            val type = String(pngBytes, pos + 4, 4, Charsets.ISO_8859_1)

            if (type == "tEXt") {
                val dataStart = pos + 8
                val dataEnd = dataStart + len
                var nullIdx = -1
                for (i in dataStart until dataEnd) {
                    if (pngBytes[i].toInt() == 0) {
                        nullIdx = i
                        break
                    }
                }
                if (nullIdx > dataStart) {
                    val key = String(pngBytes, dataStart, nullIdx - dataStart, Charsets.ISO_8859_1)
                    if (key.equals("chara", ignoreCase = true) || key.equals("ccv3", ignoreCase = true)) {
                        writeTextChunk(out, key, b64Bytes)
                        wroteChara = true
                        pos += 12 + len
                        continue
                    }
                }
            }

            if (type == "IEND" && !wroteChara) {
                writeTextChunk(out, "chara", b64Bytes)
                wroteChara = true
            }

            out.write(pngBytes, pos, 12 + len)
            pos += 12 + len
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun writeTextChunk(out: ByteArrayOutputStream, key: String, valueBytes: ByteArray) {
        val keyBytes = key.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(keyBytes.size + 1 + valueBytes.size)
        System.arraycopy(keyBytes, 0, data, 0, keyBytes.size)
        data[keyBytes.size] = 0
        System.arraycopy(valueBytes, 0, data, keyBytes.size + 1, valueBytes.size)

        val typeBytes = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)

        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array()
        out.write(header)
        out.write(typeBytes)
        out.write(data)
        out.write(crcBytes)
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * Checks whether a JSON string looks like a SillyTavern Character Card (V1/V2/V3).
     */
    fun isCharacterCardJson(rawJson: String): Boolean {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            val root = JSONObject(trimmed)
            val spec = root.optString("spec", "")
            if (spec.contains("chara_card", ignoreCase = true)) return true
            val data = root.optJSONObject("data")
            if (data != null && (data.has("first_mes") || data.has("description") || data.has("personality"))) {
                return true
            }
            // V1 card
            root.has("name") && (root.has("first_mes") || root.has("description")) && !root.has("prompts") && !root.has("entries")
        } catch (_: Exception) {
            false
        }
    }

    fun parseJson(rawJson: String, fallbackName: String = "未命名角色卡"): ParsedCharacterCard? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) return null
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }

        val specRaw = root.optString("spec", "")
        val data = root.optJSONObject("data") ?: root
        val spec = when {
            specRaw.contains("v3", ignoreCase = true) -> "v3"
            specRaw.contains("v2", ignoreCase = true) || root.has("data") -> "v2"
            else -> "v1"
        }

        val name = data.optString("name", root.optString("name", "")).ifBlank { fallbackName }
        val creator = data.optString("creator", "").trim()
        val version = data.optString("character_version", "").trim()
        val description = data.optString("description", "")
        val personality = data.optString("personality", "")
        val scenario = data.optString("scenario", "")
        val firstMes = data.optString("first_mes", "")
        val mesExample = data.optString("mes_example", "")
        val creatorNotes = data.optString("creator_notes", "")
        val systemPrompt = data.optString("system_prompt", "")

        val altGreetings = mutableListOf<String>()
        val altArr = data.optJSONArray("alternate_greetings")
        if (altArr != null) {
            for (i in 0 until altArr.length()) {
                val g = altArr.optString(i, "")
                if (g.isNotBlank()) altGreetings.add(g)
            }
        }

        val tags = mutableListOf<String>()
        val tagArr = data.optJSONArray("tags")
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                val t = tagArr.optString(i, "").trim()
                if (t.isNotEmpty()) tags.add(t)
            }
        }

        // Embedded World Book (data.character_book)
        val embeddedBook = data.optJSONObject("character_book")?.let { cb ->
            parseCharacterBook(cb, "${name} 角色世界书")
        }

        // Embedded Extensions (regex_scripts & tavern_helper / JS-Slash-Runner)
        val extensions = data.optJSONObject("extensions")
        val embeddedRegex = parseEmbeddedRegex(extensions)
        val embeddedHelper = parseEmbeddedHelper(extensions)

        return ParsedCharacterCard(
            name = name,
            creator = creator,
            characterVersion = version,
            spec = spec,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            systemPrompt = systemPrompt,
            alternateGreetings = altGreetings,
            tags = tags,
            embeddedWorldBook = embeddedBook,
            embeddedRegexScripts = embeddedRegex,
            embeddedHelperScripts = embeddedHelper,
            rawJson = trimmed
        )
    }

    private fun parseCharacterBook(cb: JSONObject, fallbackTitle: String): ParsedWorldBook? {
        val entries = WorldBookParser.parseEntriesFromContainer(cb)
        if (entries.isEmpty()) return null
        val bookName = cb.optString("name", "").ifBlank { fallbackTitle }
        val standaloneJson = WorldBookParser.buildStandaloneJson(bookName, entries)
        return ParsedWorldBook(name = bookName, entries = entries, rawJson = standaloneJson)
    }

    fun parseEmbeddedRegex(extensions: JSONObject?): List<ParsedRegexScript> {
        if (extensions == null) return emptyList()
        val list = mutableListOf<ParsedRegexScript>()

        fun collectRegexArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("findRegex") && !o.has("scriptName")) continue
                list.add(
                    ParsedRegexScript(
                        name = o.optString("scriptName", "未命名正则 #${list.size + 1}").ifBlank { "未命名正则 #${list.size + 1}" },
                        findRegex = o.optString("findRegex", ""),
                        replaceString = o.optString("replaceString", ""),
                        disabled = o.optBoolean("disabled", false),
                        rawJson = o.toString(2)
                    )
                )
            }
        }

        collectRegexArray(extensions.optJSONArray("regex_scripts"))
        // Support SPreset / preset-bundled regexes
        extensions.optJSONObject("SPreset")
            ?.optJSONObject("RegexBinding")
            ?.let { collectRegexArray(it.optJSONArray("regexes")) }

        return list
    }

    fun parseEmbeddedHelper(extensions: JSONObject?): List<ParsedHelperScript> {
        if (extensions == null) return emptyList()
        val list = mutableListOf<ParsedHelperScript>()

        // Tavern Helper / JS-Slash-Runner stores scripts under `tavern_helper` or `JS-Slash-Runner`
        val thAny = extensions.opt("tavern_helper")
            ?: extensions.opt("JS-Slash-Runner")
            ?: extensions.opt("tavern_helper_scripts")
        if (thAny is JSONArray) {
            for (i in 0 until thAny.length()) {
                val item = thAny.opt(i)
                if (item is JSONArray && item.length() >= 2 && item.optString(0) == "scripts") {
                    val scriptsArr = item.optJSONArray(1) ?: continue
                    collectHelperScriptsFromArray(scriptsArr, list)
                } else if (item is JSONObject) {
                    collectHelperScriptObject(item, list)
                }
            }
        } else if (thAny is JSONObject) {
            val scriptsArr = thAny.optJSONArray("scripts")
            if (scriptsArr != null) {
                collectHelperScriptsFromArray(scriptsArr, list)
            } else if (thAny.has("content") || thAny.has("script")) {
                collectHelperScriptObject(thAny, list)
            }
        }

        return list
    }

    private fun collectHelperScriptsFromArray(arr: JSONArray, out: MutableList<ParsedHelperScript>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            collectHelperScriptObject(o, out)
        }
    }

    private fun collectHelperScriptObject(o: JSONObject, out: MutableList<ParsedHelperScript>) {
        val name = o.optString("name", "").ifBlank { "酒馆助手脚本 #${out.size + 1}" }
        val enabled = o.optBoolean("enabled", true)
        val info = o.optString("info", "")
        val content = o.optString("content", o.optString("script", ""))
        out.add(
            ParsedHelperScript(
                name = name,
                enabled = enabled,
                info = info,
                content = content,
                rawJson = o.toString(2)
            )
        )
    }
}
