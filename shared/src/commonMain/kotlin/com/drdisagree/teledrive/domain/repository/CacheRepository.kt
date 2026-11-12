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

    suspend fun cacheManagerSummary(): CacheManagerSummary = CacheManagerSummary()

    suspend fun cacheFolders(
        parentId: String?,
        sortOrder: String = "LARGEST"
    ): List<CacheFolderUsage> = emptyList()

    suspend fun cacheEntries(
        folderId: String?,
        offset: Int,
        limit: Int,
        typeFilter: String = "ALL",
        searchQuery: String = "",
        sortOrder: String = "LARGEST"
    ): List<CacheEntryUsage> = emptyList()

    suspend fun cacheEntryPathsMatching(
        folderId: String?,
        typeFilter: String,
        searchQuery: String
    ): List<String> = emptyList()

    suspend fun cacheEntryPathsInFolderTree(
        folderId: String,
        typeFilter: String = "ALL",
        searchQuery: String = ""
    ): List<String> = emptyList()

    suspend fun removeCacheEntries(paths: List<String>): CacheRemovalResult = CacheRemovalResult()

    suspend fun clearUntrackedCache() = Unit

    suspend fun thumbnailManagerSummary(): ThumbnailManagerSummary =
        ThumbnailManagerSummary()

    suspend fun thumbnailFolders(
        parentId: String?,
        sortOrder: String = "MOST_MISSING"
    ): List<ThumbnailFolderUsage> = emptyList()

    suspend fun thumbnailFiles(
        folderId: String?,
        offset: Int,
        limit: Int,
        cacheFilter: String = "ALL",
        policyFilter: String = "ALL",
        effectivePolicyFilter: String = "ALL",
        searchQuery: String = "",
        sortOrder: String = "THUMBNAIL_LARGEST"
    ): List<ThumbnailFileUsage> = emptyList()

    /** One consistent snapshot of every file matching the active inventory filters. */
    suspend fun thumbnailFileIdsMatching(
        folderId: String?,
        cacheFilter: String,
        policyFilter: String,
        effectivePolicyFilter: String,
        searchQuery: String
    ): List<String> = emptyList()

    /** All eligible files without a cached preview across the entire drive. */
    suspend fun thumbnailAllMissingFileIds(): List<String> = emptyList()

    /** Fetch IDs only when the user explicitly selects every file in a folder. */
    suspend fun thumbnailFileIds(
        folderId: String?,
        offset: Int,
        limit: Int
    ): List<String> = emptyList()

    suspend fun setThumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String,
        policy: ThumbnailPolicy
    ) = Unit

    /** Batch apply an explicit or inherited file rule to selected inventory rows. */
    suspend fun setThumbnailPolicies(
        fileIds: List<String>,
        policy: ThumbnailPolicy
    ) = Unit

    suspend fun thumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String
    ): ThumbnailPolicy = ThumbnailPolicy.INHERIT

    suspend fun generateThumbnail(fileId: String) = Unit

    /** Submit selected missing previews; existing queued rows are refreshed rather than duplicated. */
    suspend fun generateThumbnails(fileIds: List<String>): ThumbnailEnqueueResult =
        ThumbnailEnqueueResult()

    /** Submit missing previews in a folder tree; existing queued rows are refreshed rather than duplicated. */
    suspend fun generateFolderThumbnails(folderId: String?): ThumbnailEnqueueResult =
        ThumbnailEnqueueResult()

    /** Queue every eligible uncached file in the drive, regardless of folder. */
    suspend fun generateAllThumbnails(): ThumbnailEnqueueResult =
        ThumbnailEnqueueResult()

    suspend fun thumbnailQueue(
        offset: Int,
        limit: Int,
        sortOrder: String = "OLDEST_FIRST"
    ): List<ThumbnailQueueItem> = emptyList()
    suspend fun thumbnailQueueCount(): Int = 0

    /** Active workers that still have a durable queue row; excludes completion-cleanup leftovers. */
    suspend fun thumbnailQueueActiveFileIds(): Set<String> = emptySet()

    suspend fun thumbnailQueueFileIds(offset: Int, limit: Int): List<String> = emptyList()

    /** Stable snapshot of all queued IDs, suitable for selecting across changing queue pages. */
    suspend fun thumbnailQueueAllFileIds(): List<String> = emptyList()

    suspend fun thumbnailQueueFailedFileIds(): List<String> = emptyList()

    suspend fun thumbnailQueuePaused(): Boolean = false

    fun observeThumbnailActiveJobs(): Flow<Set<String>> = flowOf(emptySet())

    suspend fun pauseThumbnailJobs(fileIds: List<String>) = Unit

    suspend fun resumeThumbnailJobs(fileIds: List<String>) = Unit

    suspend fun pauseAllThumbnailJobs() = Unit

    suspend fun resumeAllThumbnailJobs() = Unit

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
