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
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailDao
import com.drdisagree.teledrive.data.local.entity.CacheEntryType
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.CacheStats
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
        thumbnailDir().deleteRecursively()
        thumbnailDao.deleteAll()
        thumbnailJobDao.deleteAll()
        thumbnailMemoryCache.clear()
        refreshThumbnailStats()
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
                val cachedFile = File(victim.path)
                if (cachedFile.exists() && !cachedFile.delete()) continue
                thumbnailDao.delete(victim.fileId)
                thumbnailPolicyDao.upsert(
                    ThumbnailPolicyEntity(
                        targetType = ThumbnailTargetType.FILE.name,
                        targetId = victim.fileId,
                        policy = ThumbnailPolicy.MANUAL_ONLY.name,
                        changedAt = System.currentTimeMillis()
                    )
                )
                thumbnailJobDao.delete(victim.fileId)
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
        return ThumbnailManagerSummary(
            totalBytes = thumbnailDao.totalSizeBytes(),
            cachedFiles = thumbnailDao.count(),
            pendingJobs = thumbnailJobDao.count(),
            unfiledBytes = unfiled.totalBytes,
            unfiledCachedFiles = unfiled.cachedFiles
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
            .filter { it.parentId == parentId && it.trashedAt == null }
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
                    directCachedFiles = own?.cachedFiles ?: 0
                )
            }
            .toList()
        return result.map { folder ->
            folder.copy(
                policy = thumbnailPolicyDao.policy(ThumbnailTargetType.FOLDER.name, folder.id)
                    ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
                    ?: ThumbnailPolicy.INHERIT
            )
        }.sortedWith(compareByDescending<ThumbnailFolderUsage> { it.totalBytes }
            .thenBy { it.name.lowercase() })
    }

    override suspend fun thumbnailFiles(
        folderId: String?,
        offset: Int,
        limit: Int
    ): List<ThumbnailFileUsage> = thumbnailDao.filesInFolder(
        folderId = folderId,
        limit = limit.coerceIn(1, 500),
        offset = offset.coerceAtLeast(0)
    ).map { row ->
        val stored = thumbnailPolicyDao.effectivePolicy(row.fileId)
        val policy = stored?.let { runCatching { ThumbnailPolicy.valueOf(it) }.getOrNull() }
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
            policy = policy
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
        }
        if (targetType == ThumbnailTargetType.FILE &&
            policy == ThumbnailPolicy.AUTOMATIC
        ) {
            thumbnailStore.enqueueAutomatic(targetId)
        }
        if (targetType == ThumbnailTargetType.FOLDER &&
            policy == ThumbnailPolicy.MANUAL_ONLY
        ) {
            thumbnailPolicyDao.deleteOverridesInFolderTree(targetId)
            thumbnailJobDao.deleteInFolderTree(targetId)
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

    override suspend fun generateThumbnail(fileId: String) {
        thumbnailStore.enqueueManual(fileId)
    }

    override suspend fun generateThumbnails(fileIds: List<String>) {
        if (fileIds.isNotEmpty()) thumbnailStore.enqueueManual(fileIds)
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
            thumbnailStore.enqueueManual(page.map { it.fileId })
            val last = page.last()
            afterAddedAt = last.addedAt
            afterFileId = last.fileId
            if (page.size < JOB_BATCH_SIZE) break
        }
    }

    override suspend fun removeThumbnail(fileId: String) {
        thumbnailDao.byFileId(fileId)?.let { cached ->
            val cachedFile = File(cached.path)
            if (!cachedFile.exists() || cachedFile.delete()) {
                thumbnailDao.delete(fileId)
                thumbnailMemoryCache.remove(fileId)
            }
        }
        thumbnailJobDao.delete(fileId)
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = ThumbnailTargetType.FILE.name,
                targetId = fileId,
                policy = ThumbnailPolicy.MANUAL_ONLY.name,
                changedAt = System.currentTimeMillis()
            )
        )
        refreshThumbnailStats()
    }

    override suspend fun removeFolderThumbnails(folderId: String) {
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
        thumbnailPolicyDao.deleteOverridesInFolderTree(folderId)
        thumbnailJobDao.deleteInFolderTree(folderId)
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = ThumbnailTargetType.FOLDER.name,
                targetId = folderId,
                policy = ThumbnailPolicy.MANUAL_ONLY.name,
                changedAt = System.currentTimeMillis()
            )
        )
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
    }
}
