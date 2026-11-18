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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

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
        encrypt: Boolean,
        checkControl: (suspend () -> Unit)? = null
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
            // even started, unreachable by Pause/Cancel. The wait is bounded like
            // the folder-state upload (on timeout or failure, proceed with no
            // icon), and it polls the transfer's control every CONTROL_POLL_MS so
            // a Pause/Cancel lands here immediately — the executor's control check
            // throws TransferControlException, which propagates out of the upload
            // with exactly the in-loop semantics — instead of only being noticed
            // once the full 30 s bound has burned.
            var failure: Throwable? = null
            val completed = try {
                coroutineScope {
                    val wait = async {
                        runCatching {
                            telegramClient.uploadDocument(
                                chatId = chatId,
                                localPath = iconUploadPath,
                                fileName = iconFileName,
                                mimeType = iconMimeType,
                                caption = ICON_CAPTION,
                                thumbnailPath = null
                            ).first {
                                it is TelegramUploadEvent.Completed
                            } as TelegramUploadEvent.Completed
                        }
                    }
                    val deadline = System.nanoTime() +
                        ICON_UPLOAD_TIMEOUT_MS * NANOS_PER_MILLISECOND
                    var result: TelegramUploadEvent.Completed? = null
                    while (result == null) {
                        if (wait.isCompleted) {
                            val outcome = wait.await()
                            result = outcome.getOrNull()
                            if (result == null) {
                                val error = outcome.exceptionOrNull()
                                if (error is CancellationException) throw error
                                failure = error
                                break
                            }
                        } else if (System.nanoTime() >= deadline) {
                            wait.cancel()
                            break
                        } else {
                            checkControl?.invoke()
                            delay(CONTROL_POLL_MS)
                        }
                    }
                    result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferControlException) {
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
        private const val CONTROL_POLL_MS = 250L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
