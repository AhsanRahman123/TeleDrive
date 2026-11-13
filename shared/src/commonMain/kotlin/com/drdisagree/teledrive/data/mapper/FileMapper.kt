package com.drdisagree.teledrive.data.mapper

import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory

fun FileEntity.toDomain(): DriveFile {
    // Existing database rows may have been indexed before filename-based MIME fallback was
    // available. Normalize at the domain boundary so previews and media queues recognize them
    // immediately, without requiring a destructive database migration.
    val resolvedMimeType = MimeTypes.resolve(name, mimeType)
    return DriveFile(
        id = id,
        folderId = folderId,
        name = name,
        sizeBytes = sizeBytes,
        mimeType = resolvedMimeType,
        category = FileCategory.fromMimeType(resolvedMimeType),
        localPath = localPath,
        contentHash = contentHash,
        chatId = chatId,
        messageId = messageId,
        remoteFileId = remoteFileId,
        remoteUniqueId = remoteUniqueId,
        backupState = backupState,
        isHidden = isHidden,
        isArchived = isArchived,
        isFavorite = isFavorite,
        isAvailableOffline = isAvailableOffline,
        isEncrypted = isEncrypted,
        width = width,
        height = height,
        durationMs = durationMs,
        trashedAt = trashedAt,
        iconFileId = iconFileId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        addedAt = addedAt
    )
}

fun DriveFile.toEntity(preTrashFolderId: String? = null): FileEntity = FileEntity(
    id = id,
    folderId = folderId,
    name = name,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    category = category,
    localPath = localPath,
    contentHash = contentHash,
    chatId = chatId,
    messageId = messageId,
    remoteFileId = remoteFileId,
    remoteUniqueId = remoteUniqueId,
    backupState = backupState,
    isHidden = isHidden,
    isArchived = isArchived,
    isFavorite = isFavorite,
    isAvailableOffline = isAvailableOffline,
    isEncrypted = isEncrypted,
    width = width,
    height = height,
    durationMs = durationMs,
    trashedAt = trashedAt,
    preTrashFolderId = preTrashFolderId,
    iconFileId = iconFileId,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    addedAt = addedAt
)
