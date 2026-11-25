package com.stvault.app.core

import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight zero-config foreign-to-Chinese translator for previewing Presets, WorldBooks,
 * and Character Cards. Uses an in-memory LRU cache and falls back cleanly across public endpoints.
 */
object TextTranslator {

    private val cache = LruCache<String, String>(120)
    private const val MAX_TOTAL_CHARS = 12_000
    private const val CHUNK_SIZE = 1_400

    fun getCached(raw: String): String? {
        val key = cacheKey(raw)
        return synchronized(cache) { cache.get(key) }
    }

    /** Translates text to Simplified Chinese. Must be called off the main thread. */
    fun translateToZh(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.success("")
        val key = cacheKey(trimmed)
        synchronized(cache) { cache.get(key) }?.let { return Result.success(it) }

        val clipped = if (trimmed.length > MAX_TOTAL_CHARS) trimmed.take(MAX_TOTAL_CHARS) else trimmed
        val chunks = splitIntoChunks(clipped, CHUNK_SIZE)
        val out = StringBuilder()

        for ((idx, chunk) in chunks.withIndex()) {
            val translated = translateChunkGoogle(chunk)
                ?: translateChunkMyMemory(chunk)
                ?: return Result.failure(Exception("网络不可达，请检查网络或代理后重试"))
            if (idx > 0) out.append('\n')
            out.append(translated)
        }
        if (trimmed.length > MAX_TOTAL_CHARS) {
            out.append("\n\n……（原文较长，已翻译前 12,000 字）")
        }
        val result = out.toString().trim()
        synchronized(cache) { cache.put(key, result) }
        return Result.success(result)
    }

    private fun cacheKey(s: String): String = "${s.length}:${s.hashCode()}:${s.take(32)}"

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            if (sb.length + line.length + 1 > maxLen && sb.isNotEmpty()) {
                chunks.add(sb.toString())
                sb.setLength(0)
            }
            if (line.length > maxLen) {
                var i = 0
                while (i < line.length) {
                    chunks.add(line.substring(i, minOf(i + maxLen, line.length)))
                    i += maxLen
                }
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        return chunks
    }

    private fun translateChunkGoogle(chunk: String): String? {
        return try {
            val q = URLEncoder.encode(chunk, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=$q")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6500
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONArray(i)?.optString(0, "").orEmpty()
                sb.append(seg)
            }
            sb.toString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun translateChunkMyMemory(chunk: String): String? {
        return try {
            // MyMemory limits query to ~500 bytes, so split smaller if needed
            val subChunks = splitIntoChunks(chunk, 420)
            val sb = StringBuilder()
            for ((idx, sub) in subChunks.withIndex()) {
                val q = URLEncoder.encode(sub, "UTF-8")
                val url = URL("https://api.mymemory.translated.net/get?q=$q&langpair=en|zh-CN")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6500
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val translated = JSONObject(body).optJSONObject("responseData")?.optString("translatedText", "").orEmpty()
                if (translated.isBlank()) return null
                if (idx > 0) sb.append('\n')
                sb.append(translated)
            }
            sb.toString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
