package com.stvault.app.model

enum class CacheStatus(val raw: String) {
    PENDING("pending"),
    OK("ok"),
    ERROR("error"),
    IGNORED("ignored");

    companion object {
        fun from(raw: String?): CacheStatus = when (raw) {
            "ok" -> OK
            "error" -> ERROR
            "ignored" -> IGNORED
            else -> PENDING
        }
    }
}

data class UrlRecord(
    val url: String,
    val hash: String,
    var file: String = "",
    var mime: String = "",
    var size: Long = 0L,
    var status: CacheStatus = CacheStatus.PENDING,
    var error: String = "",
    var updatedAt: Long = System.currentTimeMillis(),
    val sources: MutableList<String> = mutableListOf()
)

data class SourceRecord(
    val id: String,
    val name: String,
    val docUri: String,
    val isJson: Boolean,
    val urls: List<String>,
    val scannedAt: Long = System.currentTimeMillis(),
    val contentHash: String = "",
    val category: String = "ui",
    val folderName: String = "",
    val categories: List<String> = emptyList(),
    val fileName: String = "",
    val seriesKey: String = "",
    val variantTag: String = ""
)

data class SourceRowUi(
    val source: SourceRecord,
    val okCount: Int,
    val errCount: Int,
    val pendingCount: Int,
    val variantCount: Int = 1,
    val variantLabels: List<String> = emptyList()
)

data class VaultStats(
    val totalSources: Int = 0,
    val totalUrls: Int = 0,
    val okUrls: Int = 0,
    val errorUrls: Int = 0,
    val pendingUrls: Int = 0,
    val ignoredUrls: Int = 0,
    val totalBytes: Long = 0L
)

data class DownloadProgress(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val okInRun: Int = 0,
    val errInRun: Int = 0,
    val currentUrl: String = ""
)

data class ScanResult(
    val scannedFiles: Int,
    val matchedFiles: Int,
    val newUrls: Int,
    val duplicateFiles: Int = 0
)

data class RepairResult(
    val fileName: String,
    val isJson: Boolean,
    val totalUrls: Int,
    val matchedCount: Int,
    val missingCount: Int,
    val repairedText: String
)

// ==================== Multi-Module Resource Models ====================

data class CharacterRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val spec: String = "v2",
    val isPng: Boolean = true,
    val cardFile: String = "",
    val thumbFile: String = "",
    val fileSize: Long = 0L,
    val hasEmbeddedBook: Boolean = false,
    val embeddedBookEntryCount: Int = 0,
    val embeddedRegexCount: Int = 0,
    val embeddedHelperCount: Int = 0,
    val urls: List<String> = emptyList(),
    val contentHash: String = "",
    val sourceType: String = "local",
    val sourceRef: String = "",
    val seriesKey: String = "",
    val versionTag: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

data class WorldBookRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val entryCount: Int = 0,
    val constantCount: Int = 0,
    val totalChars: Int = 0,
    val urls: List<String> = emptyList(),
    val bookFile: String = "",
    val fileSize: Long = 0L,
    val contentHash: String = "",
    val originCardId: String = "",
    val originCardName: String = "",
    val seriesKey: String = "",
    val versionTag: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

data class PresetRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val promptCount: Int = 0,
    val enabledPromptCount: Int = 0,
    val embeddedRegexCount: Int = 0,
    val embeddedHelperCount: Int = 0,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val presetFile: String = "",
    val fileSize: Long = 0L,
    val contentHash: String = "",
    val seriesKey: String = "",
    val versionTag: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

data class ComponentRecord(
    val id: String,
    val name: String,
    val fileName: String,
    val type: String = "regex", // "regex" | "helper" | "qr"
    val summary: String = "",
    val urls: List<String> = emptyList(),
    val linkedTargetType: String = "",
    val linkedTargetId: String = "",
    val linkedTargetName: String = "",
    val compFile: String = "",
    val fileSize: Long = 0L,
    val contentHash: String = "",
    val seriesKey: String = "",
    val versionTag: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

data class GroupedItem<T>(
    val primary: T,
    val versionCount: Int
)

data class LibraryCounts(
    val themes: Int = 0,
    val characters: Int = 0,
    val worldBooks: Int = 0,
    val presets: Int = 0,
    val components: Int = 0,
    val totalStorageBytes: Long = 0L
)
