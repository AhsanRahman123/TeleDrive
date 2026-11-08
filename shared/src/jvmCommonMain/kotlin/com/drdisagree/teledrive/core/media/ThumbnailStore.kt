package com.drdisagree.teledrive.core.media

import java.io.File

interface ThumbnailStore {

    suspend fun thumbnailBytes(fileId: String): ByteArray?

    suspend fun uploadThumbnailFile(fileId: String): File?

    /** Explicit user request; bypasses the manual-only viewing policy. */
    suspend fun generateNow(fileId: String): ByteArray? = thumbnailBytes(fileId)

    /** Durable, non-blocking preparation after a successful upload. */
    suspend fun enqueueAfterUpload(fileId: String) = Unit

    /** Durable, non-blocking preparation explicitly requested by the user. */
    suspend fun enqueueManual(fileId: String) = Unit

    suspend fun enqueueManual(fileIds: List<String>) {
        fileIds.forEach { enqueueManual(it) }
    }

}
