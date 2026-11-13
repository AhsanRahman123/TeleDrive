package com.drdisagree.teledrive.core.media

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ThumbnailStore {

    suspend fun thumbnailBytes(fileId: String): ByteArray?

    suspend fun uploadThumbnailFile(fileId: String): File?

    /** Promote the temporary preview created for Telegram into the persistent local cache. */
    suspend fun cacheUploadedThumbnail(fileId: String, temporaryPath: String): Boolean = false

    /** Explicit user request; bypasses the manual-only viewing policy. */
    suspend fun generateNow(fileId: String): ByteArray? = thumbnailBytes(fileId)

    /** Refresh an existing automatic image/video thumbnail after its original is downloaded. */
    suspend fun refreshCachedThumbnailFromLocalOriginal(fileId: String) = Unit

    /** Durable, non-blocking preparation after a successful upload. */
    suspend fun enqueueAfterUpload(fileId: String) = Unit

    /** Currently executing durable preparation jobs, for the manager's live queue view. */
    fun observeActiveJobs(): Flow<Set<String>> = flowOf(emptySet())

    /** Cancel queued and currently running preparation jobs. */
    suspend fun cancelJobs(fileIds: List<String>) = Unit

    /** Pause without deleting durable queue records. */
    suspend fun pauseJobs(fileIds: List<String>) = Unit

    suspend fun pauseAllJobs() = Unit

    suspend fun resumeQueue() = Unit

    suspend fun cancelAllJobs() = Unit

    suspend fun retryJobs(fileIds: List<String>) = Unit

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
