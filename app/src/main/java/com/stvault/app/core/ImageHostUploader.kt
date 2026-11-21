package com.stvault.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HostConfig(
    val provider: String = "auto", // auto | catbox | sda1 | postimages | imgbb | github | custom
    val catboxUserHash: String = "",
    val postimagesKey: String = "",
    val postimagesGallery: String = "",
    val imgbbApiKey: String = "",
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val githubDir: String = "images",
    val githubToken: String = "",
    val customEndpoint: String = "",
    val customField: String = "file",
    val customAuthHeader: String = ""
) {
    fun displayName(): String = when (provider) {
        "catbox" -> "Catbox"
        "sda1" -> "流浪图床 (sda1)"
        "postimages" -> "Postimages"
        "imgbb" -> "ImgBB"
        "github" -> "GitHub (${githubRepo.ifBlank { "未配置" }})"
        "custom" -> "自定义图床"
        else -> "自动 (Catbox / 备用)"
    }

    companion object {
        private const val PREF_NAME = "st_host_prefs"

        fun load(context: Context): HostConfig {
            val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return HostConfig(
                provider = p.getString("provider", "auto") ?: "auto",
                catboxUserHash = p.getString("catbox_hash", "") ?: "",
                postimagesKey = p.getString("pi_key", "") ?: "",
                postimagesGallery = p.getString("pi_gallery", "") ?: "",
                imgbbApiKey = p.getString("imgbb_key", "") ?: "",
                githubRepo = p.getString("gh_repo", "") ?: "",
                githubBranch = p.getString("gh_branch", "main") ?: "main",
                githubDir = p.getString("gh_dir", "images") ?: "images",
                githubToken = p.getString("gh_token", "") ?: "",
                customEndpoint = p.getString("custom_url", "") ?: "",
                customField = p.getString("custom_field", "file") ?: "file",
                customAuthHeader = p.getString("custom_auth", "") ?: ""
            )
        }

        fun save(context: Context, cfg: HostConfig) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString("provider", cfg.provider)
                .putString("catbox_hash", cfg.catboxUserHash.trim())
                .putString("pi_key", cfg.postimagesKey.trim())
                .putString("pi_gallery", cfg.postimagesGallery.trim())
                .putString("imgbb_key", cfg.imgbbApiKey.trim())
                .putString("gh_repo", cfg.githubRepo.trim())
                .putString("gh_branch", cfg.githubBranch.trim().ifBlank { "main" })
                .putString("gh_dir", cfg.githubDir.trim().ifBlank { "images" })
                .putString("gh_token", cfg.githubToken.trim())
                .putString("custom_url", cfg.customEndpoint.trim())
                .putString("custom_field", cfg.customField.trim().ifBlank { "file" })
                .putString("custom_auth", cfg.customAuthHeader.trim())
                .apply()
        }
    }
}

object ImageHostUploader {

    private const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

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

    fun upload(context: Context, file: File, mime: String): String {
        val cfg = HostConfig.load(context)
        return when (cfg.provider) {
            "catbox" -> uploadCatboxWithRetry(file, mime, cfg.catboxUserHash)
            "sda1" -> uploadSda1(file, mime)
            "postimages" -> uploadPostimages(file, mime, cfg)
            "imgbb" -> uploadImgBB(file, cfg.imgbbApiKey)
            "github" -> uploadGitHub(file, cfg)
            "custom" -> uploadCustom(file, mime, cfg)
            else -> uploadAuto(file, mime, cfg.catboxUserHash)
        }
    }

    private fun uploadAuto(file: File, mime: String, userHash: String): String {
        var lastError: Exception? = null
        try {
            return uploadCatboxWithRetry(file, mime, userHash)
        } catch (e: Exception) {
            lastError = e
        }
        val isImage = mime.lowercase().startsWith("image/") && !mime.lowercase().contains("svg")
        if (isImage) {
            try {
                return uploadSda1(file, mime)
            } catch (_: Exception) {
            }
        }
        val reason = lastError?.message ?: "连接失败"
        throw IOException("图床上传失败 ($reason)，可在「图床设置」中切换其他图床或改用压缩内嵌")
    }

    private fun uploadCatboxWithRetry(file: File, mime: String, userHash: String): String {
        var lastErr: Exception? = null
        for (attempt in 1..2) {
            try {
                val mediaType = (mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("reqtype", "fileupload")
                if (userHash.isNotBlank()) {
                    builder.addFormDataPart("userhash", userHash)
                }
                builder.addFormDataPart("fileToUpload", file.name, file.asRequestBody(mediaType))

                val req = Request.Builder()
                    .url("https://catbox.moe/user/api.php")
                    .header("User-Agent", CHROME_UA)
                    .header("Origin", "https://catbox.moe")
                    .header("Referer", "https://catbox.moe/")
                    .post(builder.build())
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Catbox HTTP ${resp.code}")
                    val url = resp.body?.string()?.trim().orEmpty()
                    if (!url.startsWith("http")) throw IOException("Catbox 返回异常: $url")
                    return url
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IOException("Catbox 连接失败")
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
            if (!resp.isSuccessful) throw IOException("流浪图床 HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: throw IOException("流浪图床空响应"))
            val url = json.optJSONObject("data")?.optString("url", "").orEmpty()
            if (!url.startsWith("http")) throw IOException("流浪图床返回异常")
            return url
        }
    }

    private fun uploadPostimages(file: File, mime: String, cfg: HostConfig): String {
        val returnedUrl = if (cfg.postimagesKey.isNotBlank()) {
            uploadPostimagesWithApiKey(file, mime, cfg)
        } else {
            uploadPostimagesAnonymous(file, mime)
        }
        return resolvePostimagesDirectLink(returnedUrl)
    }

    private fun uploadPostimagesWithApiKey(file: File, mime: String, cfg: HostConfig): String {
        val mediaType = (mime.ifBlank { "image/png" }).toMediaTypeOrNull()
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", cfg.postimagesKey)
            .addFormDataPart("numfiles", "1")
            .addFormDataPart("expire", "0")
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
        if (cfg.postimagesGallery.isNotBlank()) {
            builder.addFormDataPart("gallery", cfg.postimagesGallery)
        }

        val req = Request.Builder()
            .url("https://api.postimage.org/1/upload")
            .header("User-Agent", CHROME_UA)
            .header("Accept", "application/json")
            .post(builder.build())
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Postimages HTTP ${resp.code}")
            val text = resp.body?.string()?.trim().orEmpty()
            return extractFirstHttpUrlFromJson(text)
                ?: Regex("""https?://[^\s"'<>]+""").find(text)?.value
                ?: throw IOException("Postimages 返回异常")
        }
    }

    private fun uploadPostimagesAnonymous(file: File, mime: String): String {
        // 1. Fetch homepage to obtain anonymous upload token & cookies
        val homeReq = Request.Builder()
            .url("https://postimages.org/")
            .header("User-Agent", CHROME_UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .get()
            .build()

        var token = ""
        val cookieHeader = StringBuilder()
        client.newCall(homeReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Postimages 页面访问失败 HTTP ${resp.code}")
            for (c in resp.headers("Set-Cookie")) {
                val pair = c.substringBefore(';').trim()
                if (pair.isNotEmpty()) {
                    if (cookieHeader.isNotEmpty()) cookieHeader.append("; ")
                    cookieHeader.append(pair)
                }
            }
            val html = resp.body?.string().orEmpty()
            val patterns = listOf(
                Regex("""["']token["']\s*[,:]\s*["']([a-fA-F0-9]{16,64})["']"""),
                Regex("""name=["']token["'][^>]*value=["']([a-fA-F0-9]{16,64})["']"""),
                Regex("""token\s*=\s*["']([a-fA-F0-9]{16,64})["']""")
            )
            for (p in patterns) {
                val m = p.find(html)
                if (m != null) {
                    token = m.groupValues[1]
                    break
                }
            }
        }
        if (token.isBlank()) {
            throw IOException("未能获取 Postimages 匿名上传令牌，请检查网络或填写 API Key")
        }

        // 2. Upload anonymously to https://postimages.org/json/rr
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sessionId = (1..32).map { chars.random() }.joinToString("")
        val mediaType = (mime.ifBlank { "image/png" }).toMediaTypeOrNull()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("upload_session", sessionId)
            .addFormDataPart("numfiles", "1")
            .addFormDataPart("gallery", "")
            .addFormDataPart("optsize", "0")
            .addFormDataPart("expire", "0")
            .addFormDataPart("session_upload", System.currentTimeMillis().toString())
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()

        val uploadReqBuilder = Request.Builder()
            .url("https://postimages.org/json/rr")
            .header("User-Agent", CHROME_UA)
            .header("Origin", "https://postimages.org")
            .header("Referer", "https://postimages.org/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
            .post(body)
        if (cookieHeader.isNotEmpty()) {
            uploadReqBuilder.header("Cookie", cookieHeader.toString())
        }

        client.newCall(uploadReqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Postimages 匿名上传失败 HTTP ${resp.code}")
            val text = resp.body?.string()?.trim().orEmpty()
            return extractFirstHttpUrlFromJson(text)
                ?: Regex("""https?://[^\s"'<>]+""").find(text)?.value
                ?: throw IOException("Postimages 匿名上传返回异常")
        }
    }

    private fun resolvePostimagesDirectLink(returnedUrl: String): String {
        if (returnedUrl.contains("postimg.cc/") && !returnedUrl.contains("i.postimg.cc/")) {
            try {
                val pageReq = Request.Builder().url(returnedUrl).header("User-Agent", CHROME_UA).get().build()
                client.newCall(pageReq).execute().use { pResp ->
                    val html = pResp.body?.string().orEmpty()
                    val direct = Regex("""id=["']code_direct["'][^>]*value=["'](https://i\.postimg\.cc/[^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)
                        ?: Regex("""property=["']og:image["']\s+content=["'](https://i\.postimg\.cc/[^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.getOrNull(1)
                        ?: Regex("""https://i\.postimg\.cc/[^\s"'<>]+""").find(html)?.value
                    if (!direct.isNullOrBlank()) return direct
                }
            } catch (_: Exception) {
            }
        }
        return returnedUrl
    }

    private fun uploadImgBB(file: File, apiKey: String): String {
        if (apiKey.isBlank()) throw IOException("请先在「图床设置」中填写 ImgBB API Key")
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", apiKey)
            .addFormDataPart("image", b64)
            .build()
        val req = Request.Builder()
            .url("https://api.imgbb.com/1/upload")
            .header("User-Agent", CHROME_UA)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("ImgBB HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: throw IOException("ImgBB 空响应"))
            val url = json.optJSONObject("data")?.optString("url", "").orEmpty()
            if (!url.startsWith("http")) throw IOException("ImgBB 返回异常")
            return url
        }
    }

    private fun uploadGitHub(file: File, cfg: HostConfig): String {
        if (cfg.githubRepo.isBlank() || cfg.githubToken.isBlank()) {
            throw IOException("请先在「图床设置」中填写 GitHub 仓库与 Token")
        }
        val cleanDir = cfg.githubDir.trim('/')
        val remotePath = if (cleanDir.isEmpty()) file.name else "$cleanDir/${file.name}"
        val apiUrl = "https://api.github.com/repos/${cfg.githubRepo}/contents/$remotePath"

        // Check if file already exists (re-use CDN link directly if already uploaded!)
        val checkReq = Request.Builder()
            .url("$apiUrl?ref=${cfg.githubBranch}")
            .header("Authorization", "Bearer ${cfg.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", CHROME_UA)
            .get()
            .build()

        client.newCall(checkReq).execute().use { resp ->
            if (resp.isSuccessful) {
                return "https://cdn.jsdelivr.net/gh/${cfg.githubRepo}@${cfg.githubBranch}/$remotePath"
            }
        }

        val payload = JSONObject().apply {
            put("message", "upload ${file.name}")
            put("branch", cfg.githubBranch)
            put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }.toString()

        val putReq = Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer ${cfg.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", CHROME_UA)
            .put(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        client.newCall(putReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub 上传失败 HTTP ${resp.code}")
            return "https://cdn.jsdelivr.net/gh/${cfg.githubRepo}@${cfg.githubBranch}/$remotePath"
        }
    }

    private fun uploadCustom(file: File, mime: String, cfg: HostConfig): String {
        if (cfg.customEndpoint.isBlank()) {
            throw IOException("请先在「图床设置」中填写自定义图床接口地址")
        }
        val mediaType = (mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(cfg.customField.ifBlank { "file" }, file.name, file.asRequestBody(mediaType))
            .build()
        val reqBuilder = Request.Builder()
            .url(cfg.customEndpoint)
            .header("User-Agent", CHROME_UA)
            .header("Accept", "application/json")
            .post(body)
        if (cfg.customAuthHeader.isNotBlank()) {
            reqBuilder.header("Authorization", cfg.customAuthHeader)
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("自定义图床 HTTP ${resp.code}")
            val text = resp.body?.string()?.trim().orEmpty()
            if (text.startsWith("http://") || text.startsWith("https://")) return text
            val found = extractFirstHttpUrlFromJson(text)
            if (found != null) return found
            throw IOException("未在接口返回中识别到图片链接")
        }
    }

    private fun extractFirstHttpUrlFromJson(raw: String): String? {
        fun walk(node: Any?): String? {
            when (node) {
                is String -> if (node.startsWith("http://") || node.startsWith("https://")) return node
                is JSONObject -> {
                    // Prefer common URL keys first (Lsky Pro, SM.MS, Chevereto, PicGo, etc.)
                    val preferred = listOf("url", "link", "src", "image_url", "direct_url")
                    for (k in preferred) {
                        val v = node.opt(k)
                        if (v is String && (v.startsWith("http://") || v.startsWith("https://"))) return v
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val found = walk(node.opt(keys.next()))
                        if (found != null) return found
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        val found = walk(node.opt(i))
                        if (found != null) return found
                    }
                }
            }
            return null
        }
        return try {
            walk(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }
}
