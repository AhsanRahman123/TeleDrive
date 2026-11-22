package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.transfer.FileParts
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.remote.telegram.ExtraFields
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest

/** Local rows are the fast path; the caption is the durable copy that survives a wipe. */
class FileManifestPublisher(
    private val telegramClient: TelegramClient,
    private val manifestCodec: ManifestCodec,
    private val folderPathResolver: FolderPathResolver,
    private val filePartDao: FilePartDao
) {

    suspend fun publish(
        entity: FileEntity,
        onWaiting: suspend (secondsLeft: Int) -> Unit = {}
    ): AppResult<Unit> {
        // A local-only file has no channel message whose caption could carry
        // its manifest, so there is nothing to publish *yet*. Reporting success
        // here used to seal the row (pending cleared, baseline adopted) while
        // Telegram still held the old caption — a move of such files looked
        // published and never was. The drainer defers these rows instead.
        val chatId = entity.chatId ?: return AppResult.Failure(
            AppError.TelegramError(LOCAL_ONLY_CODE, "File has no channel message yet")
        )
        val messageId = entity.messageId ?: return AppResult.Failure(
            AppError.TelegramError(LOCAL_ONLY_CODE, "File has no channel message yet")
        )
        val fileManifest = RemoteFileManifest(
            fileId = entity.id,
            name = entity.name,
            folderPath = folderPathResolver.pathOf(entity.folderId ?: entity.preTrashFolderId),
            folderId = entity.folderId ?: entity.preTrashFolderId,
            mimeType = entity.mimeType,
            sizeBytes = entity.sizeBytes,
            contentHash = entity.contentHash,
            hidden = entity.isHidden,
            archived = entity.isArchived,
            favorite = entity.isFavorite,
            trashedAt = entity.trashedAt,
            encrypted = entity.isEncrypted,
            createdAt = entity.createdAt,
            modifiedAt = entity.modifiedAt,
            width = entity.width,
            height = entity.height,
            durationMs = entity.durationMs,
            iconFileId = entity.iconFileId,
            extra = ExtraFields.fromText(entity.manifestExtra)
        )
        val partCount = maxOf(filePartDao.countOf(entity.id), entity.partCount)
        val manifest = if (partCount > 1) {
            FileParts.asFirstPart(fileManifest, partCount)
        } else {
            fileManifest
        }
        return try {
            telegramClient.editCaption(
                chatId,
                messageId,
                manifestCodec.encode(manifest, entity.isEncrypted),
                onWaiting
            )
            AppResult.Success(Unit)
        } catch (e: TelegramException) {
            AppResult.Failure(
                when {
                    e.isRateLimit -> AppError.RateLimited(e.retryAfterSeconds ?: 0)
                    // PC-37: the two 400s that mean "the stored message
                    // id is not this file's own editable message" get
                    // their own outcome so the drainer runs the repair
                    // ladder instead of treating them as generic
                    // permanent rejections. Every other error — other
                    // 400 texts included — keeps its existing mapping.
                    e.code == 400 && e.message.trim() in MISBOUND_TEXTS ->
                        AppError.MisboundRemote(e.message)

                    else -> AppError.TelegramError(e.code, e.message)
                }
            )
        }
    }

    companion object {
        /** Synthetic code: the file has no channel message to caption-edit yet. */
        const val LOCAL_ONLY_CODE = -1

        /** Telegram's texts for "this id is not the file's editable message" (PC-37). */
        const val MESSAGE_ID_INVALID_TEXT = "MESSAGE_ID_INVALID"
        const val MESSAGE_CANT_BE_EDITED_TEXT = "Message can't be edited"
        val MISBOUND_TEXTS = setOf(MESSAGE_ID_INVALID_TEXT, MESSAGE_CANT_BE_EDITED_TEXT)
    }
}
