package com.drdisagree.teledrive.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("folderId"),
        Index("name"),
        Index("category"),
        Index("backupState"),
        Index("trashedAt"),
        Index("remoteUniqueId"),
        Index(value = ["localPath"], unique = false),
        Index(value = ["contentHash"]),
        Index("pendingPublish")
    ]
)
data class FileEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val category: FileCategory,
    val localPath: String?,
    val contentHash: String?,
    val chatId: Long?,
    val messageId: Long?,
    val remoteFileId: String?,
    val remoteUniqueId: String?,
    val backupState: BackupState,
    val isHidden: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val isAvailableOffline: Boolean = false,
    val isEncrypted: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val trashedAt: Long? = null,
    val preTrashFolderId: String? = null,
    val pendingPublish: Boolean = false,
    /**
     * PC-37: the row's Telegram copy is unrecoverable (its stored
     * message is gone or not editable, no channel message carries its
     * manifest, and no local copy exists to re-upload). The row is
     * kept — with its pending intent recorded by the repair ladder's
     * surfacing — but excluded from the publish retry cycle so drains
     * stop re-attempting a binding that can never succeed.
     */
    val remoteMissing: Boolean = false,
    val partCount: Int = 0,
    val iconFileId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val addedAt: Long,
    val manifestExtra: String? = null,
    val remoteBase: String? = null
)
