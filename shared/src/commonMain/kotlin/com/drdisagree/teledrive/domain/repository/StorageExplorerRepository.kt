package com.drdisagree.teledrive.domain.repository

data class StorageExplorerRoot(
    val id: String,
    val name: String,
    val path: String,
    val category: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val fileCount: Int,
    val directoryCount: Int,
    val canDeleteContents: Boolean,
    val protectionReason: String? = null
)

data class StorageExplorerEntry(
    val path: String,
    val rootId: String,
    val parentPath: String,
    val name: String,
    val category: String,
    val sizeBytes: Long,
    val lastModifiedAt: Long,
    val isDirectory: Boolean,
    val fileCount: Int = 0,
    val canDelete: Boolean = false,
    val protectionReason: String? = null
)

data class StorageExplorerSnapshot(
    val scannedAt: Long = 0L,
    val totalBytes: Long = 0L,
    val fileCount: Int = 0,
    val directoryCount: Int = 0,
    val roots: List<StorageExplorerRoot> = emptyList(),
    val entries: List<StorageExplorerEntry> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class StorageExplorerDeleteResult(
    val deletedFiles: Int = 0,
    val deletedBytes: Long = 0L,
    val skippedItems: Int = 0,
    val failedPaths: List<String> = emptyList()
)

interface StorageExplorerRepository {

    /** Scans physical app-owned paths once; overlapping roots are excluded from the totals. */
    suspend fun scan(): StorageExplorerSnapshot

    /** Deletes only paths inside the explicit cache and TeleDrive-download allowlist. */
    suspend fun delete(paths: List<String>): StorageExplorerDeleteResult

    /** Clears TDLib's downloaded cache through TDLib, never by deleting its files directly. */
    suspend fun clearTelegramDownloads(): Boolean
}
