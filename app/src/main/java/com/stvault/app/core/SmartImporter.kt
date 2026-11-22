package com.stvault.app.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class SmartImportSummary(
    var themes: Int = 0,
    var characters: Int = 0,
    var worldBooks: Int = 0,
    var presets: Int = 0,
    var components: Int = 0,
    var duplicates: Int = 0,
    var newUrls: Int = 0,
    var skipped: Int = 0
) {
    val totalImported: Int get() = themes + characters + worldBooks + presets + components

    fun formatMessage(): String {
        val parts = mutableListOf<String>()
        if (characters > 0) parts.add("角色卡 $characters")
        if (worldBooks > 0) parts.add("世界书 $worldBooks")
        if (presets > 0) parts.add("预设 $presets")
        if (themes > 0) parts.add("美化 $themes")
        if (components > 0) parts.add("组件 $components")
        if (parts.isEmpty()) {
            return if (duplicates > 0) "所选资源已在库中（重复 $duplicates）"
            else "未识别到有效的酒馆资源文件"
        }
        val base = "已导入：" + parts.joinToString(" · ")
        val tail = buildString {
            if (duplicates > 0) append("（重复 $duplicates）")
            if (newUrls > 0) append("，提取 $newUrls 图床")
        }
        return base + tail
    }
}

/**
 * Smart auto-router & streaming ZIP bundle importer.
 * Handles cross-profile Share Intents (ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW),
 * local Files from the built-in File Browser / Download Inbox, Discord sync bytes, and .zip archives.
 */
object SmartImporter {

    private fun isZipBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte())

    fun importUris(
        context: Context,
        uris: List<Uri>,
        preferredModule: String = "", // "" = auto-detect all, or "themes"/"characters"/"worldbooks"/"presets"/"components"
        autoTag: String = "",
        onProgress: (processed: Int, label: String) -> Unit = { _, _ -> }
    ): SmartImportSummary {
        val vaultRepo = VaultRepository.getInstance(context)
        val libRepo = ResourceLibraryRepository.getInstance(context)
        val catMgr = CategoryManager.getInstance(context)
        val summary = SmartImportSummary()
        var count = 0

        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(context, uri)
            val fileName = doc?.name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "shared_${System.currentTimeMillis()}"
            count++
            onProgress(count, fileName)

            try {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    if (fileName.endsWith(".zip", ignoreCase = true)) {
                        importZipStream(inp, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary, onProgress)
                    } else {
                        val bytes = inp.readBytes()
                        if (isZipBytes(bytes)) {
                            ByteArrayInputStream(bytes).use { bis ->
                                importZipStream(bis, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary, onProgress)
                            }
                        } else {
                            routeBytes(bytes, fileName, uri.toString(), vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary)
                        }
                    }
                }
            } catch (_: Exception) {
                summary.skipped++
            }
        }
        return summary
    }

    fun importLocalFiles(
        context: Context,
        files: List<File>,
        preferredModule: String = "",
        autoTag: String = "",
        onProgress: (processed: Int, label: String) -> Unit = { _, _ -> }
    ): SmartImportSummary {
        val vaultRepo = VaultRepository.getInstance(context)
        val libRepo = ResourceLibraryRepository.getInstance(context)
        val catMgr = CategoryManager.getInstance(context)
        val summary = SmartImportSummary()
        var count = 0

        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            count++
            onProgress(count, file.name)
            try {
                if (file.name.endsWith(".zip", ignoreCase = true)) {
                    file.inputStream().use { inp ->
                        importZipStream(inp, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary, onProgress)
                    }
                } else {
                    if (file.length() > 45L * 1024L * 1024L) {
                        summary.skipped++
                        continue
                    }
                    val bytes = file.readBytes()
                    if (isZipBytes(bytes)) {
                        ByteArrayInputStream(bytes).use { bis ->
                            importZipStream(bis, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary, onProgress)
                        }
                    } else {
                        routeBytes(bytes, file.name, "file://${file.absolutePath}", vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary)
                    }
                }
            } catch (_: Exception) {
                summary.skipped++
            }
        }
        return summary
    }

    fun importBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        sourceRef: String = "",
        preferredModule: String = "",
        autoTag: String = "",
        summary: SmartImportSummary = SmartImportSummary(),
        onProgress: (processed: Int, label: String) -> Unit = { _, _ -> }
    ): SmartImportSummary {
        if (bytes.isEmpty()) {
            summary.skipped++
            return summary
        }
        val vaultRepo = VaultRepository.getInstance(context)
        val libRepo = ResourceLibraryRepository.getInstance(context)
        val catMgr = CategoryManager.getInstance(context)
        try {
            if (fileName.endsWith(".zip", ignoreCase = true) || isZipBytes(bytes)) {
                ByteArrayInputStream(bytes).use { bis ->
                    importZipStream(bis, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary, onProgress)
                }
            } else {
                routeBytes(bytes, fileName, sourceRef, vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary)
            }
        } catch (_: Exception) {
            summary.skipped++
        }
        return summary
    }

    private fun importZipStream(
        inputStream: InputStream,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository,
        catMgr: CategoryManager,
        preferredModule: String,
        autoTag: String,
        summary: SmartImportSummary,
        onProgress: (processed: Int, label: String) -> Unit
    ) {
        var zipCount = 0
        ZipInputStream(inputStream).use { zis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val entry = try { zis.nextEntry } catch (_: Exception) { null } ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val entryName = entry.name.substringAfterLast('/')
                if (entryName.isBlank() || entryName.startsWith(".") || entry.name.contains("__MACOSX")) {
                    zis.closeEntry()
                    continue
                }
                val ext = entryName.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("png", "json", "css", "js", "txt", "html", "htm")) {
                    zis.closeEntry()
                    continue
                }

                val baos = ByteArrayOutputStream()
                var totalRead = 0L
                var tooBig = false
                while (true) {
                    val n = zis.read(buf)
                    if (n <= 0) break
                    totalRead += n
                    if (totalRead > 40L * 1024L * 1024L) {
                        tooBig = true
                        break
                    }
                    baos.write(buf, 0, n)
                }
                zis.closeEntry()
                if (tooBig || totalRead == 0L) continue

                zipCount++
                if (zipCount % 3 == 0 || zipCount == 1) {
                    onProgress(zipCount, entryName)
                }
                routeBytes(baos.toByteArray(), entryName, "zip://$entryName", vaultRepo, libRepo, catMgr, preferredModule, autoTag, summary)
            }
        }
    }

    private fun routeBytes(
        bytes: ByteArray,
        fileName: String,
        sourceRef: String,
        vaultRepo: VaultRepository,
        libRepo: ResourceLibraryRepository,
        catMgr: CategoryManager,
        preferredModule: String,
        autoTag: String,
        summary: SmartImportSummary
    ) {
        if (bytes.isEmpty()) return
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val cleanTag = autoTag.trim()

        // 1. PNG -> Character Card
        if (ext == "png" || CharacterCardParser.isPng(bytes)) {
            if (preferredModule.isNotEmpty() && preferredModule != "characters") {
                summary.skipped++
                return
            }
            val (rec, isDup, addedUrls) = libRepo.importCharacterFromBytes(bytes, fileName, sourceRef)
            when {
                isDup -> summary.duplicates++
                rec != null -> {
                    summary.characters++
                    summary.newUrls += addedUrls
                    if (cleanTag.isNotEmpty()) {
                        catMgr.addCategory(CategoryManager.SCOPE_CHARACTERS, cleanTag)
                        val merged = (rec.categories + cleanTag).distinct()
                        libRepo.setCharacterCategories(rec.id, merged)
                    }
                }
                else -> summary.skipped++
            }
            return
        }

        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            summary.skipped++
            return
        }
        if (text.isBlank()) return

        // 2. Standalone JS -> Component (Tavern Helper)
        if (ext == "js") {
            val (rec, isDup, addedUrls) = libRepo.importComponentFromText(text, fileName)
            when {
                isDup -> summary.duplicates++
                rec != null -> {
                    summary.components++
                    summary.newUrls += addedUrls
                }
                else -> summary.skipped++
            }
            return
        }

        // 3. CSS / HTML -> Theme
        if (ext == "css" || ext == "html" || ext == "htm") {
            val res = vaultRepo.importRawThemeText(text, fileName, "导入", initialCategory = cleanTag)
            if (res == null) summary.skipped++
            else if (res.second) summary.duplicates++
            else {
                summary.themes++
                summary.newUrls += res.first
                if (cleanTag.isNotEmpty()) catMgr.addCategory(CategoryManager.SCOPE_THEMES, cleanTag)
            }
            return
        }

        // 4. JSON / TXT -> Smart classification
        if (PresetParser.isPresetJson(text)) {
            val (rec, isDup) = libRepo.importPresetFromText(text, fileName)
            when {
                isDup -> summary.duplicates++
                rec != null -> summary.presets++
                else -> summary.skipped++
            }
            return
        }

        if (CharacterCardParser.isCharacterCardJson(text)) {
            val (rec, isDup, addedUrls) = libRepo.importCharacterFromBytes(bytes, fileName, sourceRef)
            when {
                isDup -> summary.duplicates++
                rec != null -> {
                    summary.characters++
                    summary.newUrls += addedUrls
                    if (cleanTag.isNotEmpty()) {
                        catMgr.addCategory(CategoryManager.SCOPE_CHARACTERS, cleanTag)
                        val merged = (rec.categories + cleanTag).distinct()
                        libRepo.setCharacterCategories(rec.id, merged)
                    }
                }
                else -> summary.skipped++
            }
            return
        }

        if (WorldBookParser.isWorldBookJson(text)) {
            val (rec, isDup, addedUrls) = libRepo.importWorldBookFromText(text, fileName)
            when {
                isDup -> summary.duplicates++
                rec != null -> {
                    summary.worldBooks++
                    summary.newUrls += addedUrls
                }
                else -> summary.skipped++
            }
            return
        }

        if (ComponentParser.parse(text, fileName) != null) {
            val (rec, isDup, addedUrls) = libRepo.importComponentFromText(text, fileName)
            when {
                isDup -> summary.duplicates++
                rec != null -> {
                    summary.components++
                    summary.newUrls += addedUrls
                }
                else -> summary.skipped++
            }
            return
        }

        // Fallback: Theme JSON/TXT with external image URLs
        val themeRes = vaultRepo.importRawThemeText(text, fileName, "导入", initialCategory = cleanTag)
        if (themeRes != null) {
            if (themeRes.second) summary.duplicates++
            else {
                summary.themes++
                summary.newUrls += themeRes.first
                if (cleanTag.isNotEmpty()) catMgr.addCategory(CategoryManager.SCOPE_THEMES, cleanTag)
            }
        } else {
            summary.skipped++
        }
    }
}
