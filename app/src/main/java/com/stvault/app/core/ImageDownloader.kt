package com.stvault.app.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

object ImageDownloader {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 35L * 1024L * 1024L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 20
        })
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class DownloadResult(val bytes: ByteArray, val mime: String)

    fun download(url: String): DownloadResult {
        return try {
            fetchOnce(url, useReferer = true)
        } catch (e: HttpCodeException) {
            if (e.code == 401 || e.code == 403) {
                fetchOnce(url, useReferer = false)
            } else {
                throw e
            }
        }
    }

    private fun fetchOnce(url: String, useReferer: Boolean): DownloadResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        if (useReferer) {
            try {
                val uri = URI(url)
                if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                    builder.header("Referer", "${uri.scheme}://${uri.host}/")
                }
            } catch (_: Exception) {
            }
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HttpCodeException(resp.code, "HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("空响应")
            val contentLen = body.contentLength()
            if (contentLen > MAX_BYTES) {
                throw IOException("超过 35MB 上限")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) {
                throw IOException("超过 35MB 上限")
            }
            val mime = detectAssetMime(bytes, url)
                ?: throw IOException("非有效图片或字体")
            return DownloadResult(bytes, mime)
        }
    }

    fun detectAssetMime(buf: ByteArray, url: String = ""): String? {
        detectImageMime(buf)?.let { return it }
        if (buf.size < 4) return null
        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xFF
        val b2 = buf[2].toInt() and 0xFF
        val b3 = buf[3].toInt() and 0xFF

        // WOFF2: 'wOF2'
        if (b0 == 0x77 && b1 == 0x4F && b2 == 0x46 && b3 == 0x32) return "font/woff2"
        // WOFF: 'wOFF'
        if (b0 == 0x77 && b1 == 0x4F && b2 == 0x46 && b3 == 0x46) return "font/woff"
        // OTF: 'OTTO'
        if (b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F) return "font/otf"
        // TTF: 00 01 00 00 or 'true'
        if ((b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) ||
            (b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65)
        ) return "font/ttf"
        // EOT fallback (only if URL ends in .eot and body isn't HTML)
        if (url.lowercase().contains(".eot")) {
            val head = String(buf, 0, minOf(buf.size, 64), Charsets.UTF_8).trim().lowercase()
            if (!head.startsWith("<html") && !head.startsWith("<!doc")) {
                return "application/vnd.ms-fontobject"
            }
        }
        return null
    }

    fun detectImageMime(buf: ByteArray): String? {
        if (buf.size < 4) return null
        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xFF
        val b2 = buf[2].toInt() and 0xFF
        val b3 = buf[3].toInt() and 0xFF

        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png"
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "image/jpeg"
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return "image/gif"
        if (buf.size >= 12 &&
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
            (buf[8].toInt() and 0xFF) == 0x57 && (buf[9].toInt() and 0xFF) == 0x45 &&
            (buf[10].toInt() and 0xFF) == 0x42 && (buf[11].toInt() and 0xFF) == 0x50
        ) return "image/webp"
        if (b0 == 0x42 && b1 == 0x4D) return "image/bmp"
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && b3 == 0x00) return "image/x-icon"
        if (buf.size >= 12 &&
            (buf[4].toInt() and 0xFF) == 0x66 && (buf[5].toInt() and 0xFF) == 0x74 &&
            (buf[6].toInt() and 0xFF) == 0x79 && (buf[7].toInt() and 0xFF) == 0x70
        ) return "image/avif"

        val headLen = minOf(buf.size, 512)
        val head = String(buf, 0, headLen, Charsets.UTF_8).trim().lowercase()
        if ((head.startsWith("<svg") || head.startsWith("<?xml")) && head.contains("<svg")) {
            return "image/svg+xml"
        }
        return null
    }

    fun mimeToExt(mime: String, fallbackUrl: String = ""): String {
        val clean = mime.lowercase().substringBefore(';').trim()
        return when (clean) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "image/avif" -> "avif"
            "image/bmp" -> "bmp"
            "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
            "font/woff2" -> "woff2"
            "font/woff" -> "woff"
            "font/ttf" -> "ttf"
            "font/otf" -> "otf"
            "application/vnd.ms-fontobject" -> "eot"
            else -> {
                val m = Regex("""\.([a-zA-Z0-9]{2,5})(?:[?#]|$)""").find(fallbackUrl)
                m?.groupValues?.getOrNull(1)?.lowercase() ?: "png"
            }
        }
    }

    class HttpCodeException(val code: Int, message: String) : IOException(message)
}
