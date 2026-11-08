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

    override suspend fun clearThumbnails() {
        thumbnailDir().deleteRecursively()
        thumbnailDao.deleteAll()
        thumbnailJobDao.deleteAll()
        thumbnailMemoryCache.clear()
        refreshStats()
    }

    override suspend fun clearLinkThumbnails() {
        for (thumbnail in thumbnailDao.textFileThumbnails()) {
            File(thumbnail.path).delete()
            thumbnailDao.delete(thumbnail.fileId)
            thumbnailMemoryCache.remove(thumbnail.fileId)
        }
        refreshStats()
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
                File(victim.path).delete()
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
                total -= victim.sizeBytes
                if (total <= maxBytes) break
            }
        }
        refreshStats()
    }

    override suspend fun thumbnailManagerSummary(): ThumbnailManagerSummary {
        val aggregates = thumbnailDao.folderAggregates()
        val unfiled = aggregates.firstOrNull { it.folderId == null }
        return ThumbnailManagerSummary(
            totalBytes = thumbnailDao.totalSizeBytes(),
            cachedFiles = aggregates.sumOf { it.cachedFiles },
            pendingJobs = thumbnailJobDao.count(),
            unfiledBytes = unfiled?.totalBytes ?: 0L,
            unfiledCachedFiles = unfiled?.cachedFiles ?: 0
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
        return folders.asSequence()
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
            .sortedWith(compareByDescending<ThumbnailFolderUsage> { it.totalBytes }
                .thenBy { it.name.lowercase() })
            .toList()
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
        if (targetType == ThumbnailTargetType.FOLDER &&
            policy == ThumbnailPolicy.MANUAL_ONLY
        ) {
            val fileIds = thumbnailDao.fileIdsInFolderTree(targetId, onlyMissing = false)
            if (fileIds.isNotEmpty()) {
                thumbnailPolicyDao.deleteFileOverrides(fileIds)
                thumbnailJobDao.delete(fileIds)
            }
        }
    }

    override suspend fun generateThumbnail(fileId: String) {
        thumbnailStore.enqueueManual(fileId)
    }

    override suspend fun generateThumbnails(fileIds: List<String>) {
        if (fileIds.isNotEmpty()) thumbnailStore.enqueueManual(fileIds)
    }

    override suspend fun generateFolderThumbnails(folderId: String?) {
        val fileIds = if (folderId == null) {
            thumbnailDao.unfiledFileIds(onlyMissing = true)
        } else {
            thumbnailDao.fileIdsInFolderTree(folderId, onlyMissing = true)
        }
        if (fileIds.isNotEmpty()) thumbnailStore.enqueueManual(fileIds)
    }

    override suspend fun removeThumbnail(fileId: String) {
        thumbnailDao.byFileId(fileId)?.let { cached ->
            File(cached.path).delete()
            thumbnailDao.delete(fileId)
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
        thumbnailMemoryCache.remove(fileId)
        refreshStats()
    }

    override suspend fun removeFolderThumbnails(folderId: String) {
        val cached = thumbnailDao.cachedFilesInFolderTree(folderId)
        cached.forEach { File(it.path).delete() }
        val allFileIds = thumbnailDao.fileIdsInFolderTree(folderId, onlyMissing = false)
        if (cached.isNotEmpty()) thumbnailDao.deleteAll(cached.map { it.fileId })
        if (allFileIds.isNotEmpty()) {
            thumbnailPolicyDao.deleteFileOverrides(allFileIds)
            thumbnailJobDao.delete(allFileIds)
            allFileIds.forEach(thumbnailMemoryCache::remove)
        }
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = ThumbnailTargetType.FOLDER.name,
                targetId = folderId,
                policy = ThumbnailPolicy.MANUAL_ONLY.name,
                changedAt = System.currentTimeMillis()
            )
        )
        refreshStats()
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
    }
}
