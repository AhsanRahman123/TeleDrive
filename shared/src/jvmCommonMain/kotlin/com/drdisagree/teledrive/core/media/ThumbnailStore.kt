package com.drdisagree.teledrive.core.media

import java.io.File

interface ThumbnailStore {

    suspend fun thumbnailBytes(fileId: String): ByteArray?

    suspend fun uploadThumbnailFile(fileId: String): File?

    /** Promote the temporary preview created for Telegram into the persistent local cache. */
    suspend fun cacheUploadedThumbnail(fileId: String, temporaryPath: String): Boolean = false

    /** Explicit user request; bypasses the manual-only viewing policy. */
    suspend fun generateNow(fileId: String): ByteArray? = thumbnailBytes(fileId)

    /** Durable, non-blocking preparation after a successful upload. */
    suspend fun enqueueAfterUpload(fileId: String) = Unit

    /** Durable, non-blocking preparation explicitly requested by the user. */
    suspend fun enqueueManual(fileId: String) = Unit

    /** Queue an item only when its effective file/folder policy is Automatic. */
    suspend fun enqueueAutomatic(fileId: String) = Unit

    suspend fun enqueueAutomatic(fileIds: List<String>) {
        fileIds.forEach { enqueueAutomatic(it) }
    }

    suspend fun enqueueManual(fileIds: List<String>) {
        fileIds.forEach { enqueueManual(it) }
    }

}
