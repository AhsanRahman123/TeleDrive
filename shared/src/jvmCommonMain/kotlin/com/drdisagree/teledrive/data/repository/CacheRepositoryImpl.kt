package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.media.ThumbnailMemoryCache
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailJobDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailPolicyDao
import com.drdisagree.teledrive.data.local.entity.ThumbnailJobEntity
import com.drdisagree.teledrive.data.local.entity.ThumbnailPolicyEntity
import com.drdisagree.teledrive.domain.model.ThumbnailPolicy
import com.drdisagree.teledrive.domain.repository.ThumbnailFileUsage
import com.drdisagree.teledrive.domain.repository.ThumbnailFolderUsage
import com.drdisagree.teledrive.domain.repository.ThumbnailManagerSummary
import com.drdisagree.teledrive.domain.repository.ThumbnailTargetType
import com.drdisagree.teledrive.domain.repository.ThumbnailQueueItem
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailDao
import com.drdisagree.teledrive.data.local.entity.CacheEntryType
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.CacheStats
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CacheRepositoryImpl(
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val cacheDao: CacheDao,
    private val thumbnailDao: ThumbnailDao,
    private val storageInspector: StorageInspector,
    private val thumbnailMemoryCache: ThumbnailMemoryCache,
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val thumbnailPolicyDao: ThumbnailPolicyDao,
    private val thumbnailJobDao: ThumbnailJobDao,
    private val thumbnailStore: ThumbnailStore
) : CacheRepository {

    private val stats = MutableStateFlow(CacheStats(0, 0, 0, 0, 0))

    override fun observeStats(): Flow<CacheStats> = stats

    override suspend fun refreshStats() {
        stats.value = CacheStats(
            thumbnailBytes = thumbnailDao.totalSizeBytes(),
            previewBytes = cacheDao.sizeByType(CacheEntryType.PREVIEW),
            streamBytes = cacheDao.sizeByType(CacheEntryType.STREAM),
            tempBytes = cacheDao.sizeByType(CacheEntryType.TEMP) + stagingSize() + partSize(),
            tdlibBytes = storageInspector.directorySize(tdlibFilesDir())
        )
    }

    private suspend fun refreshThumbnailStats() {
        stats.value = stats.value.copy(thumbnailBytes = thumbnailDao.totalSizeBytes())
    }

    override suspend fun clearThumbnails() {
        withThumbnailQueueTemporarilyPaused {
            // Keep durable queue entries and generation policy rules. Only cache files are deleted.
            thumbnailDir().deleteRecursively()
            thumbnailDao.deleteAll()
            thumbnailMemoryCache.clear()
            refreshThumbnailStats()
        }
    }

    override suspend fun clearLinkThumbnails() {
        for (thumbnail in thumbnailDao.textFileThumbnails()) {
            File(thumbnail.path).delete()
            thumbnailDao.delete(thumbnail.fileId)
            thumbnailMemoryCache.remove(thumbnail.fileId)
        }
        refreshThumbnailStats()
    }

    override suspend fun clearTemp() {
        clearType(CacheEntryType.TEMP)
        stagingDir().deleteRecursively()
        assemblyDir().deleteRecursively()
        refreshStats()
    }

    override suspend fun clearAll() {
        clearThumbnails()
        clearType(CacheEntryType.PREVIEW)
        clearType(CacheEntryType.STREAM)
        clearTemp()
        runCatching { telegramClient.clearDownloadedFiles() }
            .onFailure { SafeLog.w(TAG, "Clearing Telegram's downloads failed", it) }
        refreshStats()
    }

    override suspend fun enforceLimit(maxBytes: Long) {
        refreshStats()
        var total = stats.value.totalBytes
        if (total <= maxBytes) return

        while (total > maxBytes) {
            val victims = cacheDao.leastRecentlyUsed(32)
            if (victims.isEmpty()) break
            for (victim in victims) {
                File(victim.path).delete()
                cacheDao.delete(victim.path)
                total -= victim.sizeBytes
                if (total <= maxBytes) break
            }
        }

        while (total > maxBytes) {
            val victims = thumbnailDao.leastRecentlyUsed(64)
            if (victims.isEmpty()) break
            for (victim in victims) {
                // Cache eviction must not rewrite the file's independent generation policy.
                thumbnailJobDao.delete(victim.fileId)
                thumbnailPolicyDao.clearQueueItemPauses(listOf(victim.fileId))
                thumbnailStore.cancelJobs(listOf(victim.fileId))
                val cachedFile = File(victim.path)
                if (cachedFile.exists() && !cachedFile.delete()) continue
                thumbnailDao.delete(victim.fileId)
                thumbnailMemoryCache.remove(victim.fileId)
                total -= victim.sizeBytes
                if (total <= maxBytes) break
            }
        }
        refreshStats()
    }

    override suspend fun thumbnailManagerSummary(): ThumbnailManagerSummary {
        refreshThumbnailStats()
        val unfiled = thumbnailDao.unfiledAggregate()
        val orphaned = thumbnailDao.orphanedAggregate()
        return ThumbnailManagerSummary(
            totalBytes = thumbnailDao.totalSizeBytes(),
            cachedFiles = thumbnailDao.count(),
            pendingJobs = thumbnailJobDao.count(),
            unfiledBytes = unfiled.totalBytes,
            unfiledCachedFiles = unfiled.cachedFiles,
            orphanedBytes = orphaned.totalBytes,
            orphanedCachedFiles = orphaned.cachedFiles
        )
    }

    override suspend fun thumbnailFolders(parentId: String?): List<ThumbnailFolderUsage> {
        val folders = folderDao.allFoldersAnyOwner()
        val byId = folders.associateBy { it.id }
        val direct = thumbnailDao.folderAggregates()
            .filter { it.folderId != null }
            .associateBy { it.folderId!! }
        val totals = mutableMapOf<String, Pair<Long, Int>>()
        for ((folderId, aggregate) in direct) {
            var current: String? = folderId
            while (current != null) {
                val previous = totals[current] ?: (0L to 0)
                totals[current] = (previous.first + aggregate.totalBytes) to
                    (previous.second + aggregate.cachedFiles)
                current = byId[current]?.parentId
            }
        }
        val result = folders.asSequence()
            .filter { folder ->
                folder.parentId == parentId &&
                    (folder.trashedAt == null || (totals[folder.id]?.first ?: 0L) > 0L)
            }
            .map { folder ->
                val usage = totals[folder.id] ?: (0L to 0)
                val own = direct[folder.id]
                ThumbnailFolderUsage(
                    id = folder.id,
                    name = folder.name,
                    parentId = folder.parentId,
                    totalBytes = usage.first,
                    cachedFiles = usage.second,
                    directBytes = own?.totalBytes ?: 0L,
                    directCachedFiles = own?.cachedFiles ?: 0,
                    isTrashed = folder.trashedAt != null
                )
            }
            .toList()
        return result.map { folder ->
            val directPolicy = thumbnailPolicyDao.policy(
                ThumbnailTargetType.FOLDER.name,
                folder.id
            )?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
                ?: ThumbnailPolicy.INHERIT
            val inheritedPolicy = thumbnailPolicyDao.inheritedFolderPolicy(folder.id)
                ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
                ?: ThumbnailPolicy.MANUAL_ONLY
            folder.copy(
                policy = directPolicy,
                effectivePolicy = if (directPolicy == ThumbnailPolicy.INHERIT) {
                    inheritedPolicy
                } else {
                    directPolicy
                }
            )
        }.sortedWith(compareByDescending<ThumbnailFolderUsage> { it.totalBytes }
            .thenBy { it.name.lowercase() })
    }

    override suspend fun thumbnailFileIds(
        folderId: String?,
        offset: Int,
        limit: Int
    ): List<String> = thumbnailDao.allFileIdsInFolder(
        folderId = folderId,
        limit = limit.coerceIn(1, 500),
        offset = offset.coerceAtLeast(0)
    )

    override suspend fun thumbnailFileIdsMatching(
        folderId: String?,
        cacheFilter: String,
        policyFilter: String,
        effectivePolicyFilter: String,
        searchQuery: String
    ): List<String> = thumbnailDao.fileIdsMatching(
        folderId = folderId,
        folderEffectivePolicy = effectiveFolderPolicy(folderId).name,
        cacheFilter = cacheFilter,
        policyFilter = policyFilter,
        effectivePolicyFilter = effectivePolicyFilter,
        searchQuery = searchQuery.trim()
    )

    private suspend fun effectiveFolderPolicy(folderId: String?): ThumbnailPolicy {
        if (folderId == null) return ThumbnailPolicy.MANUAL_ONLY
        val direct = thumbnailPolicyDao.policy(ThumbnailTargetType.FOLDER.name, folderId)
            ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
        if (direct != null && direct != ThumbnailPolicy.INHERIT) return direct
        return thumbnailPolicyDao.inheritedFolderPolicy(folderId)
            ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            ?: ThumbnailPolicy.MANUAL_ONLY
    }

    override suspend fun thumbnailFiles(
        folderId: String?,
        offset: Int,
        limit: Int,
        cacheFilter: String,
        policyFilter: String,
        effectivePolicyFilter: String,
        searchQuery: String,
        sortOrder: String
    ): List<ThumbnailFileUsage> = thumbnailDao.filesInFolder(
        folderId = folderId,
        folderEffectivePolicy = effectiveFolderPolicy(folderId).name,
        cacheFilter = cacheFilter,
        policyFilter = policyFilter,
        effectivePolicyFilter = effectivePolicyFilter,
        searchQuery = searchQuery.trim(),
        sortOrder = sortOrder,
        limit = limit.coerceIn(1, 500),
        offset = offset.coerceAtLeast(0)
    ).map { row ->
        val directPolicy = row.directPolicy
            .let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            ?: ThumbnailPolicy.INHERIT
        val effectivePolicy = row.effectivePolicy
            .let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            ?: ThumbnailPolicy.MANUAL_ONLY
        ThumbnailFileUsage(
            fileId = row.fileId,
            name = row.name,
            mimeType = row.mimeType,
            category = row.category,
            fileSizeBytes = row.fileSizeBytes,
            thumbnailSizeBytes = row.thumbnailSizeBytes,
            generatedAt = row.generatedAt,
            lastAccessAt = row.lastAccessAt,
            encrypted = row.encrypted,
            policy = directPolicy,
            effectivePolicy = effectivePolicy,
            isTrashed = row.isTrashed
        )
    }

    override suspend fun setThumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String,
        policy: ThumbnailPolicy
    ) {
        if (policy == ThumbnailPolicy.INHERIT) {
            thumbnailPolicyDao.delete(targetType.name, targetId)
        } else {
            thumbnailPolicyDao.upsert(
                ThumbnailPolicyEntity(
                    targetType = targetType.name,
                    targetId = targetId,
                    policy = policy.name,
                    changedAt = System.currentTimeMillis()
                )
            )
        }
        if (targetType == ThumbnailTargetType.FILE &&
            policy == ThumbnailPolicy.MANUAL_ONLY
        ) {
            thumbnailJobDao.delete(targetId)
            thumbnailPolicyDao.clearQueueItemPauses(listOf(targetId))
            thumbnailStore.cancelJobs(listOf(targetId))
        }
        if (targetType == ThumbnailTargetType.FILE &&
            policy == ThumbnailPolicy.AUTOMATIC
        ) {
            thumbnailStore.enqueueAutomatic(targetId)
        }
        if (targetType == ThumbnailTargetType.FILE &&
            policy == ThumbnailPolicy.INHERIT
        ) {
            val inherited = thumbnailPolicyDao.effectivePolicy(targetId)
                ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            if (inherited == ThumbnailPolicy.AUTOMATIC) {
                thumbnailStore.enqueueAutomatic(targetId)
            }
        }
        if (targetType == ThumbnailTargetType.FOLDER &&
            policy == ThumbnailPolicy.MANUAL_ONLY
        ) {
            // A folder rule must not erase more-specific child-folder or file overrides.
            // Queued jobs re-check effective policy before generating and will be discarded
            // without generation when their nearest rule now resolves to Manual only.
            thumbnailPolicyDao.upsert(
                ThumbnailPolicyEntity(
                    targetType = ThumbnailTargetType.FOLDER.name,
                    targetId = targetId,
                    policy = ThumbnailPolicy.MANUAL_ONLY.name,
                    changedAt = System.currentTimeMillis()
                )
            )
        }
        if (targetType == ThumbnailTargetType.FOLDER &&
            policy == ThumbnailPolicy.AUTOMATIC
        ) {
            queueAutomaticFolderThumbnails(targetId)
        }
        if (targetType == ThumbnailTargetType.FOLDER &&
            policy == ThumbnailPolicy.INHERIT
        ) {
            val inherited = thumbnailPolicyDao.inheritedFolderPolicy(targetId)
                ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            if (inherited == ThumbnailPolicy.AUTOMATIC) {
                queueAutomaticFolderThumbnails(targetId)
            }
        }
    }

    private suspend fun cancelFolderThumbnailJobs(folderId: String) {
        var afterFileId: String? = null
        while (true) {
            val ids = thumbnailJobDao.queuedFileIdsInFolderTreePage(
                folderId = folderId,
                afterFileId = afterFileId,
                limit = JOB_BATCH_SIZE
            )
            if (ids.isEmpty()) break
            thumbnailPolicyDao.clearQueueItemPauses(ids)
            thumbnailJobDao.delete(ids)
            thumbnailStore.cancelJobs(ids)
            afterFileId = ids.last()
            if (ids.size < JOB_BATCH_SIZE) break
        }
        thumbnailJobDao.deleteInFolderTree(folderId)
    }

    private suspend fun queueAutomaticFolderThumbnails(folderId: String) {
        var afterAddedAt: Long? = null
        var afterFileId: String? = null
        while (true) {
            val page = thumbnailDao.fileIdsInFolderTreePage(
                folderId = folderId,
                onlyMissing = true,
                afterAddedAt = afterAddedAt,
                afterFileId = afterFileId,
                limit = JOB_BATCH_SIZE
            )
            if (page.isEmpty()) break
            thumbnailStore.enqueueAutomatic(page.map { it.fileId })
            val last = page.last()
            afterAddedAt = last.addedAt
            afterFileId = last.fileId
            if (page.size < JOB_BATCH_SIZE) break
        }
    }

    override suspend fun thumbnailPolicy(
        targetType: ThumbnailTargetType,
        targetId: String
    ): ThumbnailPolicy = thumbnailPolicyDao.policy(targetType.name, targetId)
        ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
        ?: ThumbnailPolicy.INHERIT

    override suspend fun setThumbnailPolicies(
        fileIds: List<String>,
        policy: ThumbnailPolicy
    ) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
            val now = System.currentTimeMillis()
            if (policy == ThumbnailPolicy.INHERIT) {
                thumbnailPolicyDao.deleteFileOverrides(batch)
            } else {
                thumbnailPolicyDao.upsert(
                    batch.map { id ->
                        ThumbnailPolicyEntity(
                            targetType = ThumbnailTargetType.FILE.name,
                            targetId = id,
                            policy = policy.name,
                            changedAt = now
                        )
                    }
                )
            }

            when (policy) {
                ThumbnailPolicy.MANUAL_ONLY -> {
                    thumbnailJobDao.delete(batch)
                    thumbnailPolicyDao.clearQueueItemPauses(batch)
                    thumbnailStore.cancelJobs(batch)
                }
                ThumbnailPolicy.AUTOMATIC,
                ThumbnailPolicy.INHERIT -> batch.forEach { thumbnailStore.enqueueAutomatic(it) }
            }
        }
    }

    override suspend fun generateThumbnail(fileId: String) {
        thumbnailPolicyDao.clearQueueItemPauses(listOf(fileId))
        thumbnailStore.enqueueManual(fileId)
    }

    override suspend fun generateThumbnails(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isNotEmpty()) {
            ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
                thumbnailPolicyDao.clearQueueItemPauses(batch)
                thumbnailStore.enqueueManual(batch)
            }
        }
    }

    override suspend fun generateFolderThumbnails(folderId: String?) {
        var afterAddedAt: Long? = null
        var afterFileId: String? = null
        while (true) {
            val page = if (folderId == null) {
                thumbnailDao.unfiledFileIdsPage(
                    onlyMissing = true,
                    afterAddedAt = afterAddedAt,
                    afterFileId = afterFileId,
                    limit = JOB_BATCH_SIZE
                )
            } else {
                thumbnailDao.fileIdsInFolderTreePage(
                    folderId = folderId,
                    onlyMissing = true,
                    afterAddedAt = afterAddedAt,
                    afterFileId = afterFileId,
                    limit = JOB_BATCH_SIZE
                )
            }
            if (page.isEmpty()) break
            val pageIds = page.map { it.fileId }
            thumbnailPolicyDao.clearQueueItemPauses(pageIds)
            thumbnailStore.enqueueManual(pageIds)
            val last = page.last()
            afterAddedAt = last.addedAt
            afterFileId = last.fileId
            if (page.size < JOB_BATCH_SIZE) break
        }
    }

    override suspend fun generateAllThumbnails() {
        var afterAddedAt: Long? = null
        var afterFileId: String? = null
        while (true) {
            val page = thumbnailDao.allEligibleFileIdsPage(
                onlyMissing = true,
                afterAddedAt = afterAddedAt,
                afterFileId = afterFileId,
                limit = JOB_BATCH_SIZE
            )
            if (page.isEmpty()) break
            val pageIds = page.map { it.fileId }
            thumbnailPolicyDao.clearQueueItemPauses(pageIds)
            thumbnailStore.enqueueManual(pageIds)
            val last = page.last()
            afterAddedAt = last.addedAt
            afterFileId = last.fileId
            if (page.size < JOB_BATCH_SIZE) break
        }
    }

    override suspend fun thumbnailQueue(
        offset: Int,
        limit: Int
    ): List<ThumbnailQueueItem> {
        val active = thumbnailStore.observeActiveJobs().first()
        val allPaused = thumbnailPolicyDao.isQueuePaused()
        return thumbnailJobDao.page(
            offset = offset.coerceAtLeast(0),
            limit = limit.coerceIn(1, 200)
        ).map {
            it.copy(
                isProcessing = it.fileId in active,
                isPaused = allPaused || it.isPaused
            )
        }
    }

    override suspend fun thumbnailQueueFileIds(
        offset: Int,
        limit: Int
    ): List<String> = thumbnailJobDao.fileIdsPage(
        offset = offset.coerceAtLeast(0),
        limit = limit.coerceIn(1, 500)
    )

    override suspend fun thumbnailQueueAllFileIds(): List<String> =
        thumbnailJobDao.allFileIds()

    override suspend fun thumbnailQueueFailedFileIds(): List<String> =
        thumbnailJobDao.failedFileIds()

    override suspend fun thumbnailQueuePaused(): Boolean =
        thumbnailPolicyDao.isQueuePaused()

    override fun observeThumbnailActiveJobs(): Flow<Set<String>> =
        thumbnailStore.observeActiveJobs()

    override suspend fun pauseThumbnailJobs(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
            val now = System.currentTimeMillis()
            thumbnailPolicyDao.upsert(
                batch.map { id ->
                    ThumbnailPolicyEntity(
                        targetType = QUEUE_ITEM_TARGET,
                        targetId = id,
                        policy = QUEUE_PAUSED,
                        changedAt = now
                    )
                }
            )
            thumbnailStore.pauseJobs(batch)
        }
    }

    override suspend fun resumeThumbnailJobs(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
            thumbnailPolicyDao.clearQueueItemPauses(batch)
        }
        thumbnailStore.resumeQueue()
    }

    override suspend fun pauseAllThumbnailJobs() {
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = QUEUE_TARGET,
                targetId = QUEUE_GLOBAL_ID,
                policy = QUEUE_PAUSED,
                changedAt = System.currentTimeMillis()
            )
        )
        thumbnailStore.pauseAllJobs()
    }

    override suspend fun resumeAllThumbnailJobs() {
        thumbnailPolicyDao.clearGlobalQueuePause()
        thumbnailStore.resumeQueue()
    }

    override suspend fun cancelThumbnailJobs(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
            thumbnailJobDao.delete(batch)
            thumbnailPolicyDao.clearQueueItemPauses(batch)
            thumbnailStore.cancelJobs(batch)
        }
    }

    override suspend fun retryThumbnailJobs(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
            thumbnailPolicyDao.clearQueueItemPauses(batch)
            thumbnailJobDao.retry(batch, System.currentTimeMillis())
            thumbnailStore.retryJobs(batch)
        }
    }

    override suspend fun cancelAllThumbnailJobs() {
        thumbnailStore.cancelAllJobs()
        thumbnailJobDao.deleteAll()
        thumbnailPolicyDao.clearGlobalQueuePause()
        thumbnailPolicyDao.clearAllQueueItemPauses()
    }

    override suspend fun removeThumbnail(fileId: String) {
        removeThumbnails(listOf(fileId))
    }

    override suspend fun removeThumbnails(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        withThumbnailQueueTemporarilyPaused {
            ids.chunked(JOB_BATCH_SIZE).forEach { batch ->
                val removedIds = thumbnailDao.pathsForFiles(batch).filter { ref ->
                    val cachedFile = File(ref.path)
                    !cachedFile.exists() || cachedFile.delete()
                }.map { it.fileId }

                if (removedIds.isNotEmpty()) {
                    thumbnailDao.deleteAll(removedIds)
                    removedIds.forEach(thumbnailMemoryCache::remove)
                }
            }
            refreshThumbnailStats()
        }
    }

    override suspend fun removeFolderThumbnails(folderId: String) {
        // Keep queue entries and policies intact; only cache files are swept.
        withThumbnailQueueTemporarilyPaused {
            var afterFileId: String? = null
            while (true) {
                val batch = thumbnailDao.cachedFilePathsInFolderTreePage(
                    folderId = folderId,
                    afterFileId = afterFileId,
                    limit = JOB_BATCH_SIZE
                )
                if (batch.isEmpty()) break
                val removedIds = batch.filter { ref ->
                    val cachedFile = File(ref.path)
                    !cachedFile.exists() || cachedFile.delete()
                }.map { it.fileId }
                if (removedIds.isNotEmpty()) {
                    thumbnailDao.deleteAll(removedIds)
                    removedIds.forEach(thumbnailMemoryCache::remove)
                }
                afterFileId = batch.last().fileId
                if (batch.size < JOB_BATCH_SIZE) break
            }
            refreshThumbnailStats()
        }
    }

    /**
     * Temporarily pauses the durable queue while cache files are removed. Preserve an existing
     * user pause and always restore the previous state, including when filesystem cleanup fails.
     */
    private suspend fun <T> withThumbnailQueueTemporarilyPaused(
        action: suspend () -> T
    ): T {
        val wasQueuePaused = thumbnailPolicyDao.isQueuePaused()
        if (!wasQueuePaused) {
            thumbnailPolicyDao.upsert(
                ThumbnailPolicyEntity(
                    targetType = QUEUE_TARGET,
                    targetId = QUEUE_GLOBAL_ID,
                    policy = QUEUE_PAUSED,
                    changedAt = System.currentTimeMillis()
                )
            )
        }
        thumbnailStore.pauseAllJobs()
        return try {
            action()
        } finally {
            if (!wasQueuePaused) {
                thumbnailPolicyDao.clearGlobalQueuePause()
                thumbnailStore.resumeQueue()
            }
        }
    }

    override suspend fun clearOrphanedThumbnails() {
        var afterFileId: String? = null
        while (true) {
            val batch = thumbnailDao.orphanedFilePathsPage(
                afterFileId = afterFileId,
                limit = JOB_BATCH_SIZE
            )
            if (batch.isEmpty()) break
            val removedIds = batch.filter { ref ->
                val cachedFile = File(ref.path)
                !cachedFile.exists() || cachedFile.delete()
            }.map { it.fileId }
            if (removedIds.isNotEmpty()) {
                thumbnailJobDao.delete(removedIds)
                thumbnailPolicyDao.clearQueueItemPauses(removedIds)
                thumbnailStore.cancelJobs(removedIds)
                thumbnailDao.deleteAll(removedIds)
                thumbnailPolicyDao.deleteFileOverrides(removedIds)
                removedIds.forEach(thumbnailMemoryCache::remove)
            }
            afterFileId = batch.last().fileId
            if (batch.size < JOB_BATCH_SIZE) break
        }
        refreshThumbnailStats()
    }

    private suspend fun clearType(type: CacheEntryType) {
        for (entry in cacheDao.byType(type)) {
            File(entry.path).delete()
            cacheDao.delete(entry.path)
        }
    }

    private fun thumbnailDir(): File = File(storagePaths.cacheDir, "thumbnails")

    private fun stagingDir(): File = File(storagePaths.cacheDir, "staging")

    private fun stagingSize(): Long = storageInspector.directorySize(stagingDir())

    private fun partsDir(): File = File(storagePaths.cacheDir, "parts")

    private fun assemblyDir(): File = File(storagePaths.cacheDir, "assembly")

    private fun partSize(): Long =
        storageInspector.directorySize(partsDir()) +
                storageInspector.directorySize(assemblyDir())

    private fun tdlibFilesDir(): File = File(File(storagePaths.filesDir, "tdlib"), "files")

    private companion object {
        const val TAG = "CacheRepository"
        const val JOB_BATCH_SIZE = 250
        const val QUEUE_TARGET = "QUEUE"
        const val QUEUE_GLOBAL_ID = "GLOBAL"
        const val QUEUE_ITEM_TARGET = "QUEUE_ITEM"
        const val QUEUE_PAUSED = "PAUSED"
    }
}
