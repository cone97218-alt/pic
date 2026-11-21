package com.stvault.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ImageHostUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Compresses static raster images (PNG/JPG/BMP/WebP) to compact WebP for lightweight Base64.
     * Automatically skips GIF (to preserve animation) and SVG/ICO.
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
     * Uploads a cached image to Catbox.moe and returns a direct permanent HTTPS URL.
     * Keeps the theme JSON tiny (~a few KB) so TT SillyTavern loads fast!
     */
    fun uploadToCatbox(file: File, mime: String): String {
        val mediaType = (mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", file.name, file.asRequestBody(mediaType))
            .build()

        val req = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", "STThemeVault-Android/1.0")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Catbox 上传失败 (HTTP ${resp.code})")
            val url = resp.body?.string()?.trim().orEmpty()
            if (!url.startsWith("http")) throw IOException("Catbox 返回异常: $url")
            return url
        }
    }
}
