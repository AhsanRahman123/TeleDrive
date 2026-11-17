package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.local.entity.FolderTombstoneEntity

class FolderMerger(
    private val folderDao: FolderDao,
    private val fileDao: FileDao,
    private val tombstoneDao: FolderTombstoneDao,
    private val database: TeleDriveDatabase
) {

    suspend fun mergeDuplicates(chatId: Long): Int {
        val merged = database.inImmediateTransaction { mergeAll(chatId) }
        if (merged > 0) SafeLog.d(TAG, "Merged $merged duplicate folders")
        return merged
    }

    private suspend fun mergeAll(chatId: Long): Int {
        var merged = 0
        repeat(MAX_PASSES) {
            val groups = folderDao.allFolders(chatId)
                .filter { it.trashedAt == null }
                .groupBy { it.parentId to it.name.lowercase() }
                .values
                .filter { it.size > 1 }
            if (groups.isEmpty()) return merged
            val now = System.currentTimeMillis()
            for (group in groups) {
                val ordered = group.sortedWith(compareBy<FolderEntity>({ it.createdAt }, { it.id }))
                val survivor = ordered.first()
                ordered.drop(1).forEach { loser ->
                    followBaselineForRemap(loser.id, survivor.id)
                    fileDao.moveAllBetweenFolders(loser.id, survivor.id)
                    fileDao.retargetTrashedBetweenFolders(loser.id, survivor.id)
                    folderDao.reparentChildren(loser.id, survivor.id, now)
                    folderDao.retargetTrashedChildren(loser.id, survivor.id, now)
                    tombstoneDao.upsert(listOf(FolderTombstoneEntity(loser.id, chatId, now)))
                    folderDao.delete(loser.id)
                    merged++
                }
            }
            folderDao.markPendingPublish()
        }
        return merged
    }

    /**
     * A merge remap is the app reorganizing its own folders, not a user edit: the
     * caption on Telegram still names the loser folder, and every client resolves
     * that dead id to the survivor by path. The published baseline must follow the
     * remap inside the same transaction, or the sync recovery check reads every
     * remapped row as an unpublished local move and republishes all of them.
     * Rows whose baseline names any other folder keep it — that divergence is a
     * real unpublished state, not the merger's doing.
     */
    private suspend fun followBaselineForRemap(from: String, to: String) {
        fileDao.rowsReferencingFolder(from).forEach { row ->
            val base = FileBase.fromText(row.remoteBase) ?: return@forEach
            if (base.folderId == from) {
                fileDao.setRemoteBase(row.id, base.copy(folderId = to).toText())
            }
        }
    }

    private companion object {
        const val TAG = "FolderMerger"
        const val MAX_PASSES = 64
    }
}
