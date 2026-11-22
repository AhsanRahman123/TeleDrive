package com.drdisagree.teledrive.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Update
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.data.local.entity.AlbumSummary
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.local.entity.HomeAggregates
import com.drdisagree.teledrive.data.local.entity.LocalCopyRef
import com.drdisagree.teledrive.domain.model.BackupState
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileEntity>)

    @Update
    suspend fun update(file: FileEntity)

    @Query(
        """SELECT * FROM files
            WHERE sizeBytes = :sizeBytes AND chatId IS :chatId
              AND trashedAt IS NULL AND contentHash IS NOT NULL
              AND messageId IS NOT NULL"""
    )
    suspend fun liveMatchesBySize(sizeBytes: Long, chatId: Long?): List<FileEntity>

    @Query(
        """SELECT category, COUNT(*) AS fileCount, SUM(sizeBytes) AS totalBytes
             FROM files
            WHERE chatId IS :chatId AND trashedAt IS NULL
              AND isHidden = 0 AND isArchived = 0
            GROUP BY category
            ORDER BY totalBytes DESC"""
    )
    fun observeUsageByCategory(chatId: Long?): Flow<List<CategoryUsage>>

    @Query("UPDATE files SET pendingPublish = 1 WHERE id IN (:ids)")
    suspend fun markPendingPublish(ids: List<String>)

    @Query("UPDATE files SET pendingPublish = 1 WHERE folderId IN (:folderIds)")
    suspend fun markPendingPublishInFolders(folderIds: List<String>)

    @Query("UPDATE files SET partCount = :partCount WHERE id = :id")
    suspend fun setPartCount(id: String, partCount: Int)

    @Query("UPDATE files SET pendingPublish = 0 WHERE id = :id")
    suspend fun clearPendingPublish(id: String)

    /**
     * Rows still owed a publish in ONE drive. Remote-missing rows
     * (PC-37) are out of the publish cycle for good; counting them
     * would pin the Home banner at "N files left" forever for work
     * that can never drain. PC-38: the outbox is per-drive state —
     * an inactive drive's backlog must never leak into the active
     * drive's banner, drains or sweeps (field: after a fresh drive
     * was created, Home showed the old drive's 12,922-row backlog).
     */
    @Query(
        "SELECT COUNT(*) FROM files WHERE pendingPublish = 1 AND remoteMissing = 0 " +
            "AND chatId IS :chatId"
    )
    suspend fun pendingPublishCount(chatId: Long?): Int

    @Query("UPDATE files SET folderId = :to WHERE folderId = :from")
    suspend fun moveAllBetweenFolders(from: String, to: String)

    @Query("UPDATE files SET preTrashFolderId = :to WHERE preTrashFolderId = :from")
    suspend fun retargetTrashedBetweenFolders(from: String, to: String)

    /** Every row whose live or pre-trash folder reference is [folderId]. */
    @Query("SELECT * FROM files WHERE folderId = :folderId OR preTrashFolderId = :folderId")
    suspend fun rowsReferencingFolder(folderId: String): List<FileEntity>

    @Query(
        "SELECT COUNT(*) FROM files WHERE pendingPublish = 1 AND remoteMissing = 0 " +
            "AND chatId IS :chatId"
    )
    fun observePendingPublishCount(chatId: Long?): Flow<Int>

    @Query("SELECT * FROM files WHERE pendingPublish = 1 LIMIT :limit")
    suspend fun pendingPublish(limit: Int): List<FileEntity>

    /**
     * Pending rows that can actually be published right now: a manifest
     * publish edits the caption of the file's channel message, so a row
     * without one (a local-only import whose upload has not landed — or
     * was cancelled) has nothing to edit yet. The publish drainer fetches
     * ONLY these (PC-32): fetching the unpublishable ones too let a head
     * of local-only rows fill every batch, publish nothing, and wedge
     * the whole backlog behind them while the pending count never moved.
     */
    @Query(
        "SELECT * FROM files WHERE pendingPublish = 1 AND remoteMissing = 0 " +
            "AND chatId IS :chatId AND messageId IS NOT NULL LIMIT :limit"
    )
    suspend fun pendingPublishable(chatId: Long?, limit: Int): List<FileEntity>

    /**
     * Pending rows of [chatId]'s drive with no channel message — see
     * [clearPendingPublishForLocalOnly]. PC-38 scoping: rows owned by
     * no drive yet (chatId NULL) are NOT any drive's local-only rows;
     * sync's ownership claim assigns them to a drive first.
     */
    @Query(
        "SELECT COUNT(*) FROM files WHERE pendingPublish = 1 " +
            "AND chatId IS :chatId AND messageId IS NULL " +
            "AND id NOT IN (SELECT fileId FROM transfers " +
            "WHERE fileId IS NOT NULL AND state IN ('QUEUED', 'RUNNING', 'PAUSED'))"
    )
    suspend fun pendingLocalOnlyCount(chatId: Long?): Int

    /**
     * Resolves the pending flag of rows that have no channel message.
     * The flag means "the caption on Telegram is stale", and a
     * local-only row has no caption at all: when its upload eventually
     * lands, the transfer writes the manifest as the message's initial
     * caption from the row's then-current state, so there is never a
     * stale caption for sync to correct. Keeping the flag (the round-7
     * behaviour) made those rows a permanent, unreachable backlog: the
     * Home banner counted them as "files left", every drain completed
     * "successfully" without touching them, the count stalled at
     * exactly their number (his 4,370 → 1,056), and Sync now was an
     * instant no-op. The ROWS are untouched — the files stay in the
     * library as local files — and a later change re-marks the flag
     * through the normal update paths; by then an uploaded row is
     * publishable and the drain handles it. Returns the rows resolved.
     *
     * PC-37 guard: a row with an upload transfer still in flight is
     * NOT resolved here. The mis-bound repair ladder detaches a row's
     * dead binding and queues its re-upload; until that upload lands
     * the row is shaped exactly like a local-only row, but its pending
     * flag is the only thing that makes the transfer's caption gap
     * (the engine's upload manifest carries no trash/favourite state)
     * get corrected by a publish against the new message — and the
     * only thing that keeps a concurrent sync reconcile from adopting
     * that caption over the row's local state. A row whose upload is
     * queued or running is mid-flight, not local-only residue.
     *
     * PC-38: scoped to one drive ([chatId]). Before the scoping this
     * sweep was global, so a drain on a brand-new empty drive would
     * silently resolve the OLD drive's local-only flags — the sweep
     * now only ever touches the active drive's own rows.
     */
    @Query(
        "UPDATE files SET pendingPublish = 0 WHERE pendingPublish = 1 " +
            "AND chatId IS :chatId AND messageId IS NULL " +
            "AND id NOT IN (SELECT fileId FROM transfers " +
            "WHERE fileId IS NOT NULL AND state IN ('QUEUED', 'RUNNING', 'PAUSED'))"
    )
    suspend fun clearPendingPublishForLocalOnly(chatId: Long?): Int

    /**
     * PC-37: the repair ladder's terminal mark for a row whose Telegram
     * copy is unrecoverable and which has no local copy to re-upload.
     * The pending flag is cleared ONLY together with this durable mark
     * (never silently): the row stays in the library with its state
     * intact, is named in the sync outcome, and — via the
     * `remoteMissing = 0` filters on the publish fetches and counts —
     * leaves the retry cycle permanently. The stored (dead) mapping is
     * deliberately kept on the row as the record of what was bound.
     */
    @Query("UPDATE files SET remoteMissing = 1, pendingPublish = 0 WHERE id = :id")
    suspend fun markRemoteMissing(id: String)

    /** Every row carrying the durable remote-missing mark, by name. */
    @Query("SELECT * FROM files WHERE remoteMissing = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun remoteMissingRows(): List<FileEntity>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun byId(id: String): FileEntity?

    @Query("SELECT * FROM files WHERE id = :id")
    fun observeById(id: String): Flow<FileEntity?>

    @Query("SELECT * FROM files WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<FileEntity>

    @Query("SELECT * FROM files WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE localPath = :path AND chatId IS :chatId LIMIT 1")
    suspend fun byLocalPath(path: String, chatId: Long?): FileEntity?

    @Query(
        """SELECT * FROM files
           WHERE trashedAt IS NOT NULL AND name = :name AND sizeBytes = :sizeBytes
             AND chatId IS :chatId"""
    )
    suspend fun trashedMatches(
        name: String,
        sizeBytes: Long,
        chatId: Long?
    ): List<FileEntity>

    @RawQuery(observedEntities = [FileEntity::class, FolderEntity::class])
    fun pagingSource(query: RoomRawQuery): PagingSource<Int, FileEntity>

    @RawQuery(observedEntities = [FileEntity::class, FolderEntity::class])
    fun observeList(query: RoomRawQuery): Flow<List<FileEntity>>

    @RawQuery
    suspend fun idList(query: RoomRawQuery): List<String>

    @Query(
        """SELECT * FROM files
           WHERE trashedAt IS NULL AND chatId IS :chatId AND isHidden = 0 AND isArchived = 0
           ORDER BY addedAt DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int, chatId: Long?): Flow<List<FileEntity>>

    @Query(
        """SELECT * FROM files
           WHERE trashedAt IS NULL AND isFavorite = 1 AND chatId IS :chatId
           ORDER BY name COLLATE NOCASE ASC"""
    )
    fun observeFavorites(chatId: Long?): Flow<List<FileEntity>>

    @Query("UPDATE files SET chatId = :chatId WHERE chatId IS NULL")
    suspend fun claimUnownedRows(chatId: Long)

    @Query(
        """SELECT folderId AS folderId, chatId AS chatId FROM files
           WHERE folderId IS NOT NULL AND chatId IS NOT NULL AND trashedAt IS NULL
           GROUP BY folderId, chatId"""
    )
    suspend fun folderOwners(): List<FolderOwner>

    @Query("SELECT COUNT(*) FROM files WHERE trashedAt IS NULL AND chatId IS :chatId")
    suspend fun fileCount(chatId: Long?): Int

    @Query("DELETE FROM files WHERE chatId = :chatId AND localPath IS NULL")
    suspend fun deleteRemoteOnlyInChat(chatId: Long): Int

    @Query(
        """UPDATE files SET chatId = NULL, messageId = NULL, remoteFileId = NULL,
           remoteUniqueId = NULL, backupState = 'NONE' WHERE chatId = :chatId"""
    )
    suspend fun detachChat(chatId: Long): Int

    @Query(
        """SELECT * FROM files
           WHERE name = :name AND sizeBytes = :sizeBytes AND chatId IS :chatId
             AND messageId IS NOT NULL AND localPath IS NULL AND trashedAt IS NULL"""
    )
    suspend fun unlinkedRemoteMatches(
        name: String,
        sizeBytes: Long,
        chatId: Long?
    ): List<FileEntity>

    @Query("SELECT * FROM files WHERE remoteUniqueId IN (:uniqueIds)")
    suspend fun byRemoteUniqueIds(uniqueIds: List<String>): List<FileEntity>

    /** Rows bound to exact channel messages — the primary identity sync matches on. */
    @Query("SELECT * FROM files WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun byMessageIds(chatId: Long, messageIds: List<Long>): List<FileEntity>

    /** Every row in the chat that is bound to a channel message. */
    @Query("SELECT * FROM files WHERE chatId = :chatId AND messageId IS NOT NULL")
    suspend fun boundRowsInChat(chatId: Long): List<FileEntity>

    /**
     * One pass for every home screen number: separate observed queries each rescan the table on any
     * write.
     */
    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN messageId IS NOT NULL THEN sizeBytes END), 0)
                      AS remoteBytes,
                  COALESCE(SUM(CASE WHEN backupState = 'BACKED_UP' THEN 1 END), 0)
                      AS backedUp,
                  COALESCE(SUM(CASE WHEN backupState = 'QUEUED' THEN 1 END), 0) AS queued,
                  COALESCE(SUM(CASE WHEN backupState = 'FAILED' THEN 1 END), 0) AS failed,
                  COALESCE(SUM(
                      CASE WHEN localPath IS NOT NULL AND messageId IS NULL
                                AND backupState IN ('NONE', 'FAILED') THEN 1 END
                  ), 0) AS localOnly,
                  COALESCE(SUM(
                      CASE WHEN localPath IS NOT NULL
                                AND (isAvailableOffline = 1
                                     OR folderId IN (SELECT id FROM offline_folders))
                           THEN sizeBytes END
                  ), 0) AS offlineBytes
           FROM files
           WHERE trashedAt IS NULL AND chatId IS :chatId"""
    )
    fun observeHomeAggregates(chatId: Long?): Flow<HomeAggregates>

    @Query("UPDATE files SET name = :name, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, modifiedAt: Long)

    @Query("UPDATE files SET folderId = :folderId, modifiedAt = :modifiedAt WHERE id IN (:ids)")
    suspend fun move(ids: List<String>, folderId: String?, modifiedAt: Long)

    /**
     * A sync pass must never observe a new folderId without the corresponding dirty flag.
     * Otherwise an old Telegram manifest can immediately reconcile the file back to its
     * previous folder before the publish worker has a chance to run.
     */
    @Transaction
    suspend fun moveAndMarkPendingPublish(
        ids: List<String>,
        folderId: String?,
        modifiedAt: Long
    ) {
        ids.distinct().chunked(400).forEach { batch ->
            move(batch, folderId, modifiedAt)
            markPendingPublish(batch)
        }
    }

    @Query("UPDATE files SET isFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT EXISTS(
               SELECT 1 FROM files
               WHERE id = :id
                 AND (isAvailableOffline = 1
                      OR (folderId IS NOT NULL
                          AND folderId IN (SELECT id FROM offline_folders)))
           )"""
    )
    suspend fun isKeptOffline(id: String): Boolean

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT id FROM files
           WHERE id IN (:ids)
             AND (isAvailableOffline = 1
                  OR (folderId IS NOT NULL
                      AND folderId IN (SELECT id FROM offline_folders)))"""
    )
    suspend fun keptOfflineIds(ids: List<String>): List<String>

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT id FROM files
           WHERE localPath IS NULL
             AND messageId IS NOT NULL
             AND trashedAt IS NULL
             AND chatId IS :chatId
             AND (isAvailableOffline = 1
                  OR (folderId IS NOT NULL
                      AND folderId IN (SELECT id FROM offline_folders)))"""
    )
    fun observeAvailableOfflineMissingIds(chatId: Long?): Flow<List<String>>

    @Query("UPDATE files SET contentHash = :contentHash WHERE id = :id")
    suspend fun setContentHash(id: String, contentHash: String)

    @Query("UPDATE files SET isAvailableOffline = :available WHERE id IN (:ids)")
    suspend fun setAvailableOffline(ids: List<String>, available: Boolean)

    @Query("UPDATE files SET isHidden = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<String>, hidden: Boolean)

    @Query("UPDATE files SET isArchived = :archived WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, archived: Boolean)

    @Query("UPDATE files SET backupState = :state WHERE id = :id")
    suspend fun setBackupState(id: String, state: BackupState)

    @Query("UPDATE files SET backupState = :state WHERE id IN (:ids)")
    suspend fun setBackupStates(ids: List<String>, state: BackupState)

    /**
     * Only local-only rows are downgraded, so a file is never shown unsaved while its copy is in
     * the channel.
     */
    @Query("UPDATE files SET backupState = :state WHERE id = :id AND messageId IS NULL")
    suspend fun setBackupStateIfLocalOnly(id: String, state: BackupState)

    @Query(
        """UPDATE files SET backupState = 'BACKED_UP'
           WHERE messageId IS NOT NULL
             AND backupState NOT IN ('BACKED_UP', 'UPLOADING')"""
    )
    suspend fun repairBackedUpStates(): Int

    @Query(
        """UPDATE files SET backupState = 'NONE'
           WHERE backupState IN ('QUEUED', 'UPLOADING')
             AND id NOT IN (
                 SELECT fileId FROM transfers
                  WHERE fileId IS NOT NULL
                    AND state IN ('QUEUED', 'RUNNING', 'PAUSED')
             )"""
    )
    suspend fun clearStaleQueuedStates(): Int

    @Query(
        """UPDATE files SET backupState = 'NONE'
           WHERE backupState = 'FAILED'
             AND id NOT IN (
                 SELECT fileId FROM transfers
                  WHERE fileId IS NOT NULL AND state = 'FAILED'
             )"""
    )
    suspend fun clearStaleFailedStates(): Int

    @Query(
        """DELETE FROM files
           WHERE messageId IS NULL AND trashedAt IS NULL
             AND id IN (
                 SELECT fileId FROM transfers
                  WHERE fileId IS NOT NULL
                    AND state = 'CANCELLED'
             )
             AND id NOT IN (
                 SELECT fileId FROM transfers
                  WHERE fileId IS NOT NULL AND state != 'CANCELLED'
             )"""
    )
    suspend fun deleteCancelledBackupEntries(): Int

    @Query(
        """UPDATE files SET chatId = :chatId, messageId = :messageId,
           remoteFileId = :remoteFileId, remoteUniqueId = :remoteUniqueId,
           iconFileId = COALESCE(:iconFileId, iconFileId),
           modifiedAt = CASE WHEN :modifiedAt > 0 THEN :modifiedAt ELSE modifiedAt END,
           backupState = :state, remoteMissing = 0 WHERE id = :id"""
    )
    suspend fun setRemoteMapping(
        id: String,
        chatId: Long?,
        messageId: Long?,
        remoteFileId: String?,
        remoteUniqueId: String?,
        state: BackupState,
        iconFileId: String? = null,
        // Upload completion stamps the file as modified now, so DATE_MODIFIED
        // sorting puts fresh uploads on top instead of using the source mtime.
        modifiedAt: Long = 0
    )

    /**
     * [setRemoteMapping] with the write logged (PC-37). The 144
     * mis-bound rows of the 2026-11-21 meltdown could never be traced
     * to the write that bound them because no mapping write was ever
     * logged; every production binding write now leaves exactly one
     * line carrying the file uuid and the message it binds, so a
     * future mis-binding is joinable from the log alone.
     */
    suspend fun setRemoteMappingLogged(
        id: String,
        chatId: Long?,
        messageId: Long?,
        remoteFileId: String?,
        remoteUniqueId: String?,
        state: BackupState,
        iconFileId: String? = null,
        modifiedAt: Long = 0
    ) {
        SafeLog.w(
            "FileDao",
            "setRemoteMapping: file=$id chatId=$chatId messageId=$messageId " +
                "remoteUniqueId=$remoteUniqueId"
        )
        setRemoteMapping(
            id, chatId, messageId, remoteFileId, remoteUniqueId, state,
            iconFileId, modifiedAt
        )
    }

    @Query(
        """UPDATE files
           SET messageId = NULL, remoteFileId = NULL, remoteUniqueId = NULL,
               backupState = 'NONE'
           WHERE id = :id"""
    )
    suspend fun detachRemote(id: String)

    @Query("UPDATE files SET remoteBase = :remoteBase WHERE id = :id")
    suspend fun setRemoteBase(id: String, remoteBase: String?)

    @Query("UPDATE files SET localPath = :localPath WHERE id = :id")
    suspend fun setLocalPath(id: String, localPath: String?)

    @Query(
        """UPDATE files
           SET trashedAt = :trashedAt,
               preTrashFolderId = folderId,
               folderId = NULL,
               modifiedAt = :trashedAt
           WHERE id IN (:ids)"""
    )
    suspend fun moveToTrash(ids: List<String>, trashedAt: Long)

    /**
     * Trash mutations must become visible to sync as one atomic change. Otherwise a remote
     * reconciliation can observe the trashed row before pendingPublish is set and restore its
     * old folder state from the still-current Telegram manifest.
     */
    @Transaction
    suspend fun moveToTrashAndMarkPendingPublish(ids: List<String>, trashedAt: Long) {
        ids.distinct().chunked(400).forEach { batch ->
            moveToTrash(batch, trashedAt)
            markPendingPublish(batch)
        }
    }

    /** Same atomicity rule as move/trash: the rename and its marker land together. */
    @Transaction
    suspend fun renameAndMarkPendingPublish(id: String, name: String, modifiedAt: Long) {
        rename(id, name, modifiedAt)
        markPendingPublish(listOf(id))
    }

    @Transaction
    suspend fun setFavoriteAndMarkPendingPublish(ids: List<String>, favorite: Boolean) {
        ids.distinct().chunked(400).forEach { batch ->
            setFavorite(batch, favorite)
            markPendingPublish(batch)
        }
    }

    @Transaction
    suspend fun setHiddenAndMarkPendingPublish(ids: List<String>, hidden: Boolean) {
        ids.distinct().chunked(400).forEach { batch ->
            setHidden(batch, hidden)
            markPendingPublish(batch)
        }
    }

    @Transaction
    suspend fun setArchivedAndMarkPendingPublish(ids: List<String>, archived: Boolean) {
        ids.distinct().chunked(400).forEach { batch ->
            setArchived(batch, archived)
            markPendingPublish(batch)
        }
    }

    /**
     * Restoring is a move + un-trash and needs the same atomic visibility as one:
     * a reconcile that observes the restored row before its marker lands treats
     * the still-current manifest as truth and trashes it again. [orphanedIds] are
     * the restored rows whose pre-trash folder no longer exists; they land at the
     * drive root inside the same transaction.
     */
    @Transaction
    suspend fun restoreAndMarkPendingPublish(
        ids: List<String>,
        orphanedIds: List<String>,
        modifiedAt: Long
    ) {
        val distinct = ids.distinct()
        distinct.chunked(400).forEach { batch -> restoreFromTrash(batch) }
        if (orphanedIds.isNotEmpty()) {
            move(orphanedIds.distinct(), null, modifiedAt)
        }
        distinct.chunked(400).forEach { batch -> markPendingPublish(batch) }
    }

    @Transaction
    suspend fun restoreMoveAndMarkPendingPublish(
        id: String,
        folderId: String?,
        modifiedAt: Long
    ) {
        restoreFromTrash(listOf(id))
        move(listOf(id), folderId, modifiedAt)
        markPendingPublish(listOf(id))
    }

    @Query(
        """UPDATE files SET folderId = preTrashFolderId, trashedAt = NULL, preTrashFolderId = NULL
           WHERE id IN (:ids)"""
    )
    suspend fun restoreFromTrash(ids: List<String>)

    @Query(
        """SELECT * FROM files
           WHERE trashedAt IS NOT NULL AND chatId IS :chatId
             AND (preTrashFolderId IS NULL
                  OR preTrashFolderId NOT IN
                     (SELECT id FROM folders WHERE trashedAt IS NOT NULL))
           ORDER BY trashedAt DESC"""
    )
    fun observeTrashRoots(chatId: Long?): Flow<List<FileEntity>>

    @Query(
        """SELECT * FROM files
           WHERE trashedAt IS NOT NULL AND preTrashFolderId IN (:folderIds)
             AND (chatId IS :chatId OR chatId IS NULL)"""
    )
    suspend fun trashedInFolders(folderIds: List<String>, chatId: Long?): List<FileEntity>

    @Query(
        """SELECT preTrashFolderId AS parentId, COUNT(*) AS childCount FROM files
           WHERE trashedAt IS NOT NULL AND preTrashFolderId IN (:folderIds)
             AND (chatId IS :chatId OR chatId IS NULL)
           GROUP BY preTrashFolderId"""
    )
    suspend fun trashedFileCounts(folderIds: List<String>, chatId: Long?): List<TrashChildCount>

    @Query("SELECT * FROM files WHERE trashedAt IS NOT NULL AND trashedAt < :threshold")
    suspend fun trashOlderThan(threshold: Long): List<FileEntity>

    @Query("SELECT * FROM files WHERE trashedAt IS NOT NULL AND chatId IS :chatId")
    suspend fun trashInChat(chatId: Long?): List<FileEntity>

    @Query("DELETE FROM files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query(
        """SELECT * FROM files
           WHERE folderId = :folderId AND trashedAt IS NULL
             AND (chatId IS :chatId OR chatId IS NULL)"""
    )
    suspend fun filesInFolder(folderId: String, chatId: Long?): List<FileEntity>

    @Query(
        """SELECT files.* FROM files
           JOIN folders ON folders.id = COALESCE(files.folderId, files.preTrashFolderId)
           WHERE files.chatId IS NOT NULL AND folders.chatId IS NOT NULL
             AND files.chatId != folders.chatId"""
    )
    suspend fun strayFiles(): List<FileEntity>

    @Query("UPDATE files SET preTrashFolderId = :folderId WHERE id = :id")
    suspend fun setPreTrashFolder(id: String, folderId: String?)

    @Query("SELECT COUNT(*) FROM files WHERE folderId = :folderId OR preTrashFolderId = :folderId")
    suspend fun countInFolder(folderId: String): Int

    @Query("SELECT * FROM files WHERE messageId IS NOT NULL")
    suspend fun filesWithRemote(): List<FileEntity>

    @Query("SELECT localPath FROM files WHERE localPath IS NOT NULL")
    suspend fun allLocalPaths(): List<String>

    /**
     * A still-local row for the same content in the same folder: same
     * name and size, no remote copy yet, holding a local path. The
     * folder-import idempotency check (round 6, PC-26) — an interrupted
     * or repeated import finds the row its first pass created instead
     * of importing the file a second time.
     */
    @Query(
        """SELECT * FROM files
           WHERE folderId IS :folderId AND name = :name AND sizeBytes = :sizeBytes
             AND chatId IS :chatId AND trashedAt IS NULL
             AND messageId IS NULL AND localPath IS NOT NULL
           LIMIT 1"""
    )
    suspend fun localOnlyMatch(
        folderId: String?,
        name: String,
        sizeBytes: Long,
        chatId: Long?
    ): FileEntity?

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT COALESCE(SUM(sizeBytes), 0) FROM files
           WHERE localPath IS NOT NULL
             AND messageId IS NOT NULL
             AND backupState = 'BACKED_UP'
             AND chatId IS :chatId
             AND isAvailableOffline = 0
             AND (folderId IS NULL OR folderId NOT IN (SELECT id FROM offline_folders))"""
    )
    fun observeReclaimableBytes(chatId: Long?): Flow<Long>

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT id FROM files
           WHERE localPath IS NOT NULL
             AND messageId IS NOT NULL
             AND backupState = 'BACKED_UP'
             AND chatId IS :chatId
             AND isAvailableOffline = 0
             AND (folderId IS NULL OR folderId NOT IN (SELECT id FROM offline_folders))"""
    )
    suspend fun reclaimableFileIds(chatId: Long?): List<String>

    @Query(
        """WITH RECURSIVE offline_folders(id) AS (
               SELECT id FROM folders WHERE isAvailableOffline = 1
               UNION
               SELECT folders.id FROM folders
               JOIN offline_folders ON folders.parentId = offline_folders.id
           )
           SELECT id, localPath FROM files
           WHERE localPath IS NOT NULL
             AND messageId IS NOT NULL
             AND backupState = 'BACKED_UP'
             AND isAvailableOffline = 0
             AND (folderId IS NULL OR folderId NOT IN (SELECT id FROM offline_folders))"""
    )
    suspend fun reclaimableLocalCopies(): List<LocalCopyRef>

    /** A backup scan only walks device folders, so these need queueing by id. */
    @Query(
        """SELECT id FROM files
           WHERE trashedAt IS NULL
             AND localPath IS NOT NULL
             AND messageId IS NULL
             AND backupState IN ('NONE', 'FAILED')
             AND chatId IS :chatId"""
    )
    suspend fun localOnlyFileIds(chatId: Long?): List<String>

    @Query(
        """SELECT id FROM files
           WHERE trashedAt IS NULL
             AND messageId IS NULL
             AND backupState = 'QUEUED'
             AND chatId IS :chatId
             AND id NOT IN (
               SELECT fileId FROM transfers
               WHERE state IN ('QUEUED', 'RUNNING', 'PAUSED')
             )"""
    )
    suspend fun queuedWithoutTransferFileIds(chatId: Long?): List<String>

    @Query(
        """SELECT album.folderId AS folderId,
                  album.name AS name,
                  album.itemCount AS itemCount,
                  album.coverFileId AS coverFileId,
                  album.latestAt AS latestAt
           FROM (
             SELECT f.folderId AS folderId,
                    fo.name AS name,
                    COUNT(*) AS itemCount,
                    MAX(f.modifiedAt) AS latestAt,
                    (SELECT c.id FROM files c
                       WHERE c.folderId IS f.folderId
                         AND c.chatId IS :chatId
                         AND c.trashedAt IS NULL
                         AND c.category IN ('IMAGE', 'VIDEO')
                         AND (:showHidden = 1 OR c.isHidden = 0)
                         AND (:showArchived = 1 OR c.isArchived = 0)
                       ORDER BY c.modifiedAt DESC LIMIT 1) AS coverFileId
             FROM files f
             LEFT JOIN folders fo ON fo.id = f.folderId
             WHERE f.trashedAt IS NULL AND f.chatId IS :chatId
               AND f.category IN ('IMAGE', 'VIDEO')
               AND (:showHidden = 1 OR f.isHidden = 0)
               AND (:showArchived = 1 OR f.isArchived = 0)
             GROUP BY f.folderId

             UNION ALL

             SELECT fo.id AS folderId,
                    fo.name AS name,
                    0 AS itemCount,
                    fo.modifiedAt AS latestAt,
                    NULL AS coverFileId
             FROM folders fo
             WHERE fo.chatId IS :chatId
               AND fo.trashedAt IS NULL
               AND (:showHidden = 1 OR fo.isHidden = 0)
               AND (:showArchived = 1 OR fo.isArchived = 0)
               AND NOT EXISTS (
                 SELECT 1 FROM files f
                 WHERE f.folderId = fo.id
                   AND f.chatId IS :chatId
                   AND f.trashedAt IS NULL
               )
               AND NOT EXISTS (
                 SELECT 1 FROM folders child
                 WHERE child.parentId = fo.id
                   AND child.chatId IS :chatId
                   AND child.trashedAt IS NULL
               )
           ) AS album
           ORDER BY album.latestAt DESC"""
    )
    fun observeAlbumsScoped(
        showHidden: Boolean,
        showArchived: Boolean,
        chatId: Long?
    ): Flow<List<AlbumSummary>>

    @Query("SELECT name FROM files WHERE folderId IS :folderId AND trashedAt IS NULL")
    suspend fun namesInFolder(folderId: String?): List<String>

    @Query(
        """SELECT name FROM files
           WHERE folderId IS :folderId AND trashedAt IS NULL AND id != :excludeId"""
    )
    suspend fun namesInFolderExcluding(folderId: String?, excludeId: String): List<String>
}
