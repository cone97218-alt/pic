package com.stvault.app.model

enum class CacheStatus(val raw: String) {
    PENDING("pending"),
    OK("ok"),
    ERROR("error");

    companion object {
        fun from(raw: String?): CacheStatus = when (raw) {
            "ok" -> OK
            "error" -> ERROR
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
    val scannedAt: Long = System.currentTimeMillis()
)

data class VaultStats(
    val totalSources: Int = 0,
    val totalUrls: Int = 0,
    val okUrls: Int = 0,
    val errorUrls: Int = 0,
    val pendingUrls: Int = 0,
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
    val newUrls: Int
)

data class RepairResult(
    val fileName: String,
    val isJson: Boolean,
    val totalUrls: Int,
    val matchedCount: Int,
    val missingCount: Int,
    val repairedText: String
)
