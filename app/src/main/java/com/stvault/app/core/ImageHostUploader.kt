package com.stvault.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ImageHostUploader {

    private const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    // Use HTTP/1.1 to avoid Catbox HTTP/2 Connection Reset issues on mobile networks
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Compresses static raster images (PNG/JPG/BMP/WebP) to compact WebP for lightweight Base64.
     * Automatically skips GIF (to preserve animation), SVG/ICO, and font files.
     */
    fun compressForBase64(file: File, mime: String, maxDim: Int = 1600, quality: Int = 80): Pair<ByteArray, String> {
        val origBytes = file.readBytes()
        val cleanMime = mime.lowercase().substringBefore(';').trim()
        if (cleanMime == "image/gif" || cleanMime == "image/svg+xml" || cleanMime == "image/x-icon" ||
            cleanMime.startsWith("font/") || cleanMime == "application/vnd.ms-fontobject"
        ) {
            return origBytes to cleanMime.ifBlank { "application/octet-stream" }
        }
        return try {
            val bitmap = BitmapFactory.decodeByteArray(origBytes, 0, origBytes.size)
                ?: return origBytes to cleanMime.ifBlank { "image/png" }

            val w = bitmap.width
            val h = bitmap.height
            val targetBitmap = if (maxDim > 0 && (w > maxDim || h > maxDim)) {
                val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
                val nw = (w * scale).toInt().coerceAtLeast(1)
                val nh = (h * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, nw, nh, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            targetBitmap.compress(format, quality, baos)
            targetBitmap.recycle()

            val compressed = baos.toByteArray()
            if (compressed.isNotEmpty() && compressed.size < origBytes.size) {
                compressed to "image/webp"
            } else {
                origBytes to cleanMime.ifBlank { "image/png" }
            }
        } catch (_: Exception) {
            origBytes to cleanMime.ifBlank { "image/png" }
        }
    }

    /**
     * Uploads a cached image/font to Catbox.moe (with HTTP/1.1 + retry),
     * and automatically falls back to p.sda1.dev for images if Catbox is unreachable.
     */
    fun uploadToCatbox(file: File, mime: String): String {
        var lastError: Exception? = null

        // 1. Try Catbox (2 attempts with HTTP/1.1 + realistic Chrome UA)
        for (attempt in 1..2) {
            try {
                return uploadCatboxOnce(file, mime)
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Fallback to p.sda1.dev (China-friendly free image host) for standard images
        val isImage = mime.lowercase().startsWith("image/") && !mime.lowercase().contains("svg")
        if (isImage) {
            try {
                return uploadSda1(file, mime)
            } catch (_: Exception) {
            }
        }

        val reason = lastError?.message ?: "网络连接失败"
        throw IOException("图床连接失败 ($reason)，请检查网络/代理或改用「WebP 压缩内嵌」")
    }

    private fun uploadCatboxOnce(file: File, mime: String): String {
        val mediaType = (mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", file.name, file.asRequestBody(mediaType))
            .build()

        val req = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", CHROME_UA)
            .header("Origin", "https://catbox.moe")
            .header("Referer", "https://catbox.moe/")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Catbox HTTP ${resp.code}")
            val url = resp.body?.string()?.trim().orEmpty()
            if (!url.startsWith("http")) throw IOException("Catbox 返回异常: $url")
            return url
        }
    }

    private fun uploadSda1(file: File, mime: String): String {
        val mediaType = (mime.ifBlank { "image/png" }).toMediaTypeOrNull()
        val encodedName = URLEncoder.encode(file.name, "UTF-8")
        val req = Request.Builder()
            .url("https://p.sda1.dev/api/v1/upload_external_noform?filename=$encodedName")
            .header("User-Agent", CHROME_UA)
            .post(file.asRequestBody(mediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("备用图床 HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: throw IOException("备用图床空响应"))
            val url = json.optJSONObject("data")?.optString("url", "").orEmpty()
            if (!url.startsWith("http")) throw IOException("备用图床返回异常")
            return url
        }
    }
}
