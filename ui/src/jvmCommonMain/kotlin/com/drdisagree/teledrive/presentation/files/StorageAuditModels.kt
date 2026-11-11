package com.drdisagree.teledrive.presentation.files

data class StorageAuditRoot(
    val label: String,
    val path: String,
    val exists: Boolean,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null
)

data class StorageAuditFile(
    val id: String,
    val name: String,
    val expectedSizeBytes: Long,
    val localPath: String?,
    val localExists: Boolean,
    val localSizeBytes: Long?,
    val localIsTeleDriveManaged: Boolean,
    val telegramChatId: Long?,
    val telegramMessageId: Long?,
    val telegramRemoteFileId: String?,
    val tdlibFileId: Int?,
    val tdlibPath: String?,
    val tdlibPathExists: Boolean,
    val tdlibPathIsTeleDriveManaged: Boolean,
    val tdlibSizeBytes: Long?,
    val tdlibDownloadComplete: Boolean?,
    val tdlibError: String?
)

data class StorageAuditSnapshot(
    val loading: Boolean,
    val instanceRoot: String,
    val roots: List<StorageAuditRoot>,
    val files: List<StorageAuditFile>,
    val lastCheckedAt: Long,
    val error: String? = null
)
