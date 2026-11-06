package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.mapper.toDomain
import com.drdisagree.teledrive.domain.model.TrashItem
import com.drdisagree.teledrive.domain.repository.TrashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class TrashRepositoryImpl(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val publishScheduler: PublishScheduler,
    private val activeChannel: ActiveChannel
) : TrashRepository {

    override fun observeTrash(): Flow<List<TrashItem>> =
        activeChannel.observe().flatMapLatest { chatId ->
            combine(
                fileDao.observeTrashRoots(chatId),
                folderDao.observeTrashRoots(chatId)
            ) { files, folders ->
                (files.map { TrashItem.File(it.toDomain()) } +
                        folders.map { TrashItem.Folder(it.toDomain()) })
                    .sortedByDescending { it.trashedAt }
            }
        }

    override suspend fun trashedChildCounts(folderIds: List<String>): Map<String, Int> {
        if (folderIds.isEmpty()) return emptyMap()
        val counts = mutableMapOf<String, Int>()
        val chatId = activeChannel.id()
        for (row in folderDao.trashedFolderCounts(folderIds, chatId)) {
            counts[row.parentId] = (counts[row.parentId] ?: 0) + row.childCount
        }
        for (row in fileDao.trashedFileCounts(folderIds, chatId)) {
            counts[row.parentId] = (counts[row.parentId] ?: 0) + row.childCount
        }
        return counts
    }

    override suspend fun trashedChildren(folderId: String): List<TrashItem> {
        val chatId = activeChannel.id()
        val folders = folderDao.trashedChildrenOf(listOf(folderId), chatId)
            .map { TrashItem.Folder(it.toDomain()) }
            .sortedBy { it.name.lowercase() }
        val files = fileDao.trashedInFolders(listOf(folderId), chatId)
            .map { TrashItem.File(it.toDomain()) }
            .sortedBy { it.name.lowercase() }
        return folders + files
    }

    override suspend fun moveFilesToTrash(ids: List<String>): AppResult<Unit> {
        fileDao.moveToTrash(ids, System.currentTimeMillis())
        markFilesDirty(ids)
        return AppResult.Success(Unit)
    }

    override suspend fun moveFolderToTrash(id: String): AppResult<Unit> {
        val descendants = collectDescendantFolderIds(id)
        val chatId = activeChannel.id()
        val fileIds = descendants.flatMap { fileDao.filesInFolder(it, chatId) }.map { it.id }
        val trashedAt = System.currentTimeMillis()
        fileIds.chunked(SQL_BATCH).forEach { fileDao.moveToTrash(it, trashedAt) }
        descendants.reversed().forEach { folderDao.moveToTrash(it, trashedAt) }
        markFolderStateDirty()
        return AppResult.Success(Unit)
    }

    private suspend fun markFilesDirty(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQL_BATCH).forEach { fileDao.markPendingPublish(it) }
        publishScheduler.kick()
    }

    private suspend fun markFolderStateDirty() {
        folderDao.markPendingPublish()
        publishScheduler.kick()
    }

    override suspend fun repairTrashTree() {
        var repaired: Int
        var guard = 0
        do {
            val orphans = folderDao.untrashedChildrenOfTrashed(activeChannel.id())
            val trashedAt = System.currentTimeMillis()
            orphans.forEach { folder -> folderDao.moveToTrash(folder.id, trashedAt) }
            repaired = orphans.size
        } while (repaired > 0 && guard++ < MAX_FOLDER_DEPTH)
    }

    override suspend fun restoreFiles(ids: List<String>): AppResult<Unit> {
        if (ids.isEmpty()) return AppResult.Success(Unit)
        val entities = ids.chunked(SQL_BATCH).flatMap { fileDao.byIds(it) }
        entities.mapNotNull { it.preTrashFolderId }.distinct().forEach { restoreAncestors(it) }
        val orphaned = entities.filter { entity ->
            val parent = entity.preTrashFolderId
            parent != null && folderDao.byId(parent) == null
        }
        ids.chunked(SQL_BATCH).forEach { fileDao.restoreFromTrash(it) }
        if (orphaned.isNotEmpty()) {
            fileDao.move(orphaned.map { it.id }, null, System.currentTimeMillis())
        }
        markFolderStateDirty()
        markFilesDirty(ids)
        return AppResult.Success(Unit)
    }

    override suspend fun restoreFolder(id: String): AppResult<Unit> {
        val folderIds = collectTrashedDescendantFolderIds(id)
        val fileIds = fileDao.trashedInFolders(folderIds, activeChannel.id()).map { it.id }
        val restoredAt = System.currentTimeMillis()
        folderIds.reversed().forEach { folderDao.restoreFromTrash(it, restoredAt) }
        restoreAncestors(folderDao.byId(id)?.parentId)
        val restoredParent = folderDao.byId(id)?.parentId
        if (restoredParent != null && folderDao.byId(restoredParent) == null) {
            folderDao.move(id, null, System.currentTimeMillis())
        }
        fileIds.chunked(SQL_BATCH).forEach { fileDao.restoreFromTrash(it) }
        markFolderStateDirty()
        markFilesDirty(fileIds)
        return AppResult.Success(Unit)
    }

    override suspend fun deleteFilesPermanently(ids: List<String>): AppResult<Unit> =
        AppResult.Failure(AppError.RemoteDeletionDisabled)

    override suspend fun deleteFolderPermanently(id: String): AppResult<Unit> =
        AppResult.Failure(AppError.RemoteDeletionDisabled)

    override suspend fun emptyTrash(): AppResult<Unit> =
        AppResult.Failure(AppError.RemoteDeletionDisabled)

    /**
     * Automatic expiry is deliberately disabled. Older preference values may still exist on
     * upgraded installations, but they must never trigger remote file deletion.
     */
    override suspend fun clearExpired(days: Int): AppResult<Int> = AppResult.Success(0)

    /** A folder whose parent row is gone for good falls back to the drive root. */
    private suspend fun restoreAncestors(folderId: String?) {
        var cursor = folderId
        var guard = 0
        while (cursor != null && guard++ < MAX_FOLDER_DEPTH) {
            val folder = folderDao.byId(cursor) ?: return
            if (folder.trashedAt == null) return
            val parentId = folder.preTrashParentId
            folderDao.restoreFromTrash(cursor, System.currentTimeMillis())
            if (parentId != null && folderDao.byId(parentId) == null) {
                folderDao.move(cursor, null, System.currentTimeMillis())
                return
            }
            cursor = parentId
        }
    }

    private suspend fun collectDescendantFolderIds(rootId: String): List<String> {
        val result = mutableListOf(rootId)
        var frontier = listOf(rootId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < MAX_FOLDER_DEPTH) {
            frontier = frontier.flatMap { parent ->
                folderDao.childrenOf(parent, activeChannel.id()).map { it.id }
            }
            result.addAll(frontier)
        }
        return result
    }

    companion object {
        private const val SQL_BATCH = 500
        private const val MAX_FOLDER_DEPTH = 64
    }
}
