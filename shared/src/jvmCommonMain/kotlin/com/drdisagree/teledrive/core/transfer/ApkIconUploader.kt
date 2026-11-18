package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.local.entity.FileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class ApkIconUploader(
    private val telegramClient: TelegramClient,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val streamCrypto: StreamCrypto,
    private val thumbnailStore: ThumbnailStore,
    private val storagePaths: AppStoragePaths
) {
    private fun stagingDir(): File = storagePaths.cacheDir.resolve("staging").apply { mkdirs() }

    suspend fun uploadIconIfApk(
        entity: FileEntity,
        chatId: Long,
        encrypt: Boolean
    ): String? {
        if (!MimeTypes.isApk(entity.mimeType, entity.name)) return null

        val rawIconPath = thumbnailStore.uploadThumbnailFile(entity.id)?.absolutePath ?: return null

        val iconUploadPath: String
        val iconFileName: String
        val iconMimeType: String
        var stagingIconFile: File? = null

        if (encrypt) {
            val key = runCatching { wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT) }.getOrNull()
                ?: return null
            val rawIconBytes = runCatching { File(rawIconPath).readBytes() }.getOrNull()
                ?: return null

            val encryptedBytes = streamCrypto.encryptBytes(key, rawIconBytes)
            val randomName = "${UUID.randomUUID()}.tde"
            val staging = File(stagingDir(), randomName)
            staging.writeBytes(encryptedBytes)
            stagingIconFile = staging
            iconUploadPath = staging.absolutePath
            iconFileName = randomName
            iconMimeType = "application/octet-stream"
        } else {
            iconUploadPath = rawIconPath
            iconFileName = "${UUID.randomUUID()}.jpg"
            iconMimeType = "image/jpeg"
        }

        SafeLog.d(TAG, "APK icon upload started for ${entity.name}")
        var resultId: String? = null
        try {
            // The icon is an optional decoration: it must never gate the file itself.
            // uploadDocument completes only when Telegram's send-succeeded update
            // arrives, and in a bad session that update may never come — the old
            // unbounded wait parked the whole transfer at 0% before the real upload
            // even started, unreachable by Pause/Cancel. Bound the wait like the
            // folder-state upload: on timeout or failure, proceed with no icon.
            var failure: Throwable? = null
            val completed = try {
                withTimeoutOrNull(ICON_UPLOAD_TIMEOUT_MS.milliseconds) {
                    telegramClient.uploadDocument(
                        chatId = chatId,
                        localPath = iconUploadPath,
                        fileName = iconFileName,
                        mimeType = iconMimeType,
                        caption = ICON_CAPTION,
                        thumbnailPath = null
                    ).first { it is TelegramUploadEvent.Completed } as TelegramUploadEvent.Completed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                null
            }
            when {
                completed != null -> {
                    val doc = completed.document
                    resultId = "${doc.remoteFileId}:${doc.messageId}"
                    SafeLog.d(TAG, "APK icon uploaded for ${entity.name}: $resultId")
                }

                failure != null -> SafeLog.w(
                    TAG,
                    "APK icon upload failed for ${entity.name}; continuing without an icon",
                    failure
                )

                else -> SafeLog.w(
                    TAG,
                    "APK icon upload timed out after ${ICON_UPLOAD_TIMEOUT_MS}ms for " +
                        "${entity.name}; continuing without an icon"
                )
            }
        } finally {
            stagingIconFile?.delete()
        }

        return resultId
    }

    companion object {
        const val ICON_CAPTION = "#teledrive-icon"
        private const val TAG = "ApkIconUploader"
        private const val ICON_UPLOAD_TIMEOUT_MS = 30_000L
    }
}
