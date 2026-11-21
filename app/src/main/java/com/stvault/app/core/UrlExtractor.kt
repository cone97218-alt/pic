package com.stvault.app.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

object UrlExtractor {

    private val KNOWN_HOSTS = listOf(
        "catbox.moe", "imgur.com", "ibb.co", "postimg.cc", "loli.net", "sm.ms",
        "imgse.com", "ax1x.com", "z4a.net", "picui.cn", "imgloc.com", "sda1.dev",
        "cdn.discordapp.com", "media.discordapp.net", "raw.githubusercontent.com",
        "fastly.jsdelivr.net", "cdn.jsdelivr.net", "imagehub.cc", "tc.z.wiki",
        "s21.ax1x.com", "i.pximg.net", "files.catbox.moe", "litter.catbox.moe",
        "img.alicdn.com", "img.imgdd.com", "freeimage.host", "iili.io"
    )

    private val CSS_URL_RE = Regex(
        """url\(\s*(['"]?)(https?:(?:\\\/\\\/|\/\/)[^)'"\s]+)\1\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val HTML_SRC_RE = Regex(
        """(?:src|poster|data-src)\s*=\s*(['"])(https?:(?:\\\/\\\/|\/\/)[^'"]+)\1""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_URL_RE = Regex(
        """https?:(?:\\\/\\\/|\/\/)[^\s"'`<>()\[\]{}]+""",
        RegexOption.IGNORE_CASE
    )
    private val IMG_EXT_RE = Regex(
        """\.(png|jpe?g|gif|webp|avif|svg|bmp|ico|jfif)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val NON_IMAGE_EXT_RE = Regex(
        """\.(woff2?|ttf|otf|eot|css|mjs|js|map|mp3|wav|ogg|flac|m4a|mp4|webm|mov|avi|pdf|zip|rar|7z|json|xml|html?|txt)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val NON_IMAGE_HOST_KEYWORDS = listOf(
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "fonts.loli.net",
        "fonts.font.im",
        "font.sec.miui.com",
        "use.fontawesome.com",
        "at.alicdn.com/t/font",
        "at.alicdn.com/t/c/font",
        "chinese-fonts-cdn",
        "fonts.bunny.net"
    )

    fun isNonImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (NON_IMAGE_EXT_RE.containsMatchIn(lower)) return true
        if (NON_IMAGE_HOST_KEYWORDS.any { lower.contains(it) }) return true
        if (lower.contains("/fonts/") || lower.contains("/font/") || lower.contains("font-awesome")) {
            if (!IMG_EXT_RE.containsMatchIn(lower)) return true
        }
        return false
    }

    fun cleanUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var u = raw.trim().replace("\\/", "/")
        u = u.trimEnd(')', '"', '\'', '`', ';', ',', ' ', '\\', '>')
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
            return ""
        }
        if (isNonImageUrl(u)) return ""
        return try {
            val uri = URI(u)
            if (uri.host.isNullOrBlank()) "" else u
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFromString(str: String, out: LinkedHashSet<String>) {
        if (str.isBlank()) return
        for (m in CSS_URL_RE.findAll(str)) {
            val u = cleanUrl(m.groupValues[2])
            if (u.isNotEmpty()) out.add(u)
        }
        for (m in HTML_SRC_RE.findAll(str)) {
            val u = cleanUrl(m.groupValues[2])
            if (u.isNotEmpty()) out.add(u)
        }
        for (m in GENERIC_URL_RE.findAll(str)) {
            val u = cleanUrl(m.value)
            if (u.isEmpty()) continue
            val lower = u.lowercase()
            if (IMG_EXT_RE.containsMatchIn(lower) || KNOWN_HOSTS.any { lower.contains(it) }) {
                out.add(u)
            }
        }
    }

    private fun walkJson(node: Any?, cb: (String) -> Unit) {
        when (node) {
            is String -> cb(node)
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkJson(node.opt(keys.next()), cb)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkJson(node.opt(i), cb)
                }
            }
        }
    }

    fun replaceInJson(node: Any?, replacer: (String) -> String): Any? {
        return when (node) {
            is String -> replacer(node)
            is JSONObject -> {
                val out = JSONObject()
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    out.put(k, replaceInJson(node.opt(k), replacer))
                }
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until node.length()) {
                    out.put(replaceInJson(node.opt(i), replacer))
                }
                out
            }
            else -> node
        }
    }

    /**
     * Returns Pair(urls, isJson)
     */
    fun extract(content: String): Pair<List<String>, Boolean> {
        val set = LinkedHashSet<String>()
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            try {
                val obj = JSONObject(trimmed)
                walkJson(obj) { s -> extractFromString(s, set) }
                return set.toList() to true
            } catch (_: Exception) {
            }
        } else if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                walkJson(arr) { s -> extractFromString(s, set) }
                return set.toList() to true
            } catch (_: Exception) {
            }
        }
        extractFromString(content, set)
        return set.toList() to false
    }

    /**
     * Extracts SillyTavern character card JSON from PNG tEXt chunk ("chara" / "ccv3")
     */
    fun extractCharaFromPng(bytes: ByteArray): String? {
        if (bytes.size < 16) return null
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte() ||
            bytes[2] != 0x4E.toByte() || bytes[3] != 0x47.toByte()
        ) return null

        var offset = 8
        while (offset + 12 <= bytes.size) {
            val len = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len < 0) break
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val dataEnd = dataStart + len
            if (dataEnd > bytes.size) break

            if (type == "tEXt") {
                var zeroIdx = -1
                for (i in dataStart until dataEnd) {
                    if (bytes[i] == 0.toByte()) {
                        zeroIdx = i
                        break
                    }
                }
                if (zeroIdx > dataStart && zeroIdx < dataEnd) {
                    val key = String(bytes, dataStart, zeroIdx - dataStart, Charsets.ISO_8859_1).lowercase()
                    if (key == "chara" || key == "ccv3") {
                        val b64 = String(bytes, zeroIdx + 1, dataEnd - (zeroIdx + 1), Charsets.UTF_8)
                        return try {
                            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            if (type == "IEND") break
            offset = dataEnd + 4
        }
        return null
    }
}
