package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.domain.model.ThumbnailPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface CacheRepository {

    fun observeStats(): Flow<CacheStats>

    suspend fun refreshStats()

    suspend fun clearThumbnails()

    suspend fun clearLinkThumbnails()

    suspend fun clearTemp()

    suspend fun clearAll()

    suspend fun enforceLimit(maxBytes: Long)

    suspend fun thumbnailManagerSummary(): ThumbnailManagerSummary =
        ThumbnailManagerSummary()

    suspend fun thumbnailFolders(parentId: String?): List<ThumbnailFolderUsage> = emptyList()

    suspend fun thumbnailFiles(
        folderId: String?,
        offset: Int,
        limit: Int
    ): List<ThumbnailFileUsage> = emptyList()

    /** Fetch IDs only when the user explicitly selects every file in a folder. */
    suspend fun thumbnailFileIds(folderId: String?): List<String> = emptyList()

    suspend fun setThumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String,
        policy: ThumbnailPolicy
    ) = Unit

    suspend fun thumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String
    ): ThumbnailPolicy = ThumbnailPolicy.INHERIT

    suspend fun generateThumbnail(fileId: String) = Unit

    suspend fun generateThumbnails(fileIds: List<String>) = Unit

    suspend fun generateFolderThumbnails(folderId: String?) = Unit

    /** Queue every eligible uncached file in the drive, regardless of folder. */
    suspend fun generateAllThumbnails() = Unit

    suspend fun thumbnailQueue(offset: Int, limit: Int): List<ThumbnailQueueItem> = emptyList()

    fun observeThumbnailActiveJobs(): Flow<Set<String>> = flowOf(emptySet())

    suspend fun cancelThumbnailJobs(fileIds: List<String>) = Unit

    suspend fun cancelAllThumbnailJobs() = Unit

    suspend fun retryThumbnailJobs(fileIds: List<String>) = Unit

    suspend fun removeThumbnail(fileId: String) = Unit

    suspend fun removeThumbnails(fileIds: List<String>) {
        fileIds.forEach { removeThumbnail(it) }
    }

    suspend fun removeFolderThumbnails(folderId: String) = Unit
    suspend fun clearOrphanedThumbnails() = Unit
}
