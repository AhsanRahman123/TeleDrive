package com.drdisagree.teledrive.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class DuplicateScanStatus {
    WAITING,
    GENERATING,
    SCANNED,
    FAILED,
    PAUSED,
    CANCELLED
}

enum class DuplicateFileSort {
    NAME_ASC,
    NAME_DESC,
    SIZE_LARGEST,
    SIZE_SMALLEST,
    OLDEST_FIRST,
    NEWEST_FIRST
}

enum class DuplicateGroupSort {
    MOST_COPIES,
    LARGEST_WASTED,
    NAME_ASC
}

data class DuplicateScanFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val folderPath: String,
    val modifiedAt: Long,
    val addedAt: Long,
    val contentHash: String?,
    val isEncrypted: Boolean
)

data class DuplicateScanQueueItem(
    val file: DuplicateScanFile,
    val status: DuplicateScanStatus,
    val error: String? = null
)

data class DuplicateFileGroup(
    val contentHash: String,
    val files: List<DuplicateScanFile>
) {
    val duplicateCount: Int get() = (files.size - 1).coerceAtLeast(0)
    val wastedBytes: Long
        get() = (files.sumOf { it.sizeBytes } - (files.maxOfOrNull { it.sizeBytes } ?: 0L))
            .coerceAtLeast(0L)
}

data class DuplicateManagerState(
    val folderId: String? = null,
    val queue: List<DuplicateScanQueueItem> = emptyList(),
    val groups: List<DuplicateFileGroup> = emptyList(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val activeFileId: String? = null,
    val error: String? = null,
    val scannedFiles: Int = 0,
    val totalFiles: Int = 0
) {
    val waitingCount: Int
        get() = queue.count { it.status == DuplicateScanStatus.WAITING }
    val generatingCount: Int
        get() = queue.count { it.status == DuplicateScanStatus.GENERATING }
    val failedCount: Int
        get() = queue.count { it.status == DuplicateScanStatus.FAILED }
    val cancelledCount: Int
        get() = queue.count { it.status == DuplicateScanStatus.CANCELLED }
}

interface DuplicateManagerRepository {
    val state: StateFlow<DuplicateManagerState>

    suspend fun startScan(folderId: String, resetQueue: Boolean = false)

    fun pauseAll()
    fun resumeAll()
    fun cancelAll()

    fun pauseFiles(fileIds: Set<String>)
    fun resumeFiles(fileIds: Set<String>)
    fun cancelFiles(fileIds: Set<String>)
    fun removeTrashedFiles(fileIds: Set<String>)
    fun retryFiles(fileIds: Set<String>)
    fun retryFailed()
}
