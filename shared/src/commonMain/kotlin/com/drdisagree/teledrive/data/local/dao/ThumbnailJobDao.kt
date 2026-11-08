package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.ThumbnailJobEntity
import com.drdisagree.teledrive.domain.repository.ThumbnailQueueItem

@Dao
interface ThumbnailJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(job: ThumbnailJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(jobs: List<ThumbnailJobEntity>)

    @Query("SELECT * FROM thumbnail_jobs ORDER BY queuedAt ASC, fileId ASC LIMIT :limit")
    suspend fun next(limit: Int): List<ThumbnailJobEntity>

    @Query("SELECT * FROM thumbnail_jobs WHERE fileId = :fileId LIMIT 1")
    suspend fun byFileId(fileId: String): ThumbnailJobEntity?


    @Query(
        """SELECT * FROM thumbnail_jobs
            WHERE queuedAt <= :now AND attempts < :maxAttempts
              AND NOT EXISTS (
                  SELECT 1 FROM thumbnail_policies p
                   WHERE p.targetType = 'QUEUE_ITEM'
                     AND p.targetId = thumbnail_jobs.fileId
                     AND p.policy = 'PAUSED'
              )
            ORDER BY queuedAt ASC, fileId ASC LIMIT :limit"""
    )
    suspend fun nextReady(
        limit: Int,
        now: Long,
        maxAttempts: Int
    ): List<ThumbnailJobEntity>

    @Query(
        """SELECT MIN(j.queuedAt) FROM thumbnail_jobs j
            WHERE j.attempts < :maxAttempts
              AND NOT EXISTS (
                  SELECT 1 FROM thumbnail_policies p
                   WHERE p.targetType = 'QUEUE_ITEM'
                     AND p.targetId = j.fileId
                     AND p.policy = 'PAUSED'
              )"""
    )
    suspend fun nextQueuedAt(maxAttempts: Int): Long?

    @Query(
        "UPDATE thumbnail_jobs SET attempts = attempts + 1, queuedAt = :retryAt WHERE fileId = :fileId"
    )
    suspend fun recordAttempt(fileId: String, retryAt: Long)

    @Query(
        """WITH RECURSIVE queued_page(fileId, queuedAt, sortName, fileSize, attempts) AS (
               SELECT j.fileId,
                      j.queuedAt,
                      COALESCE(f.name, j.fileId),
                      COALESCE(f.sizeBytes, 0),
                      j.attempts
                 FROM thumbnail_jobs j
                 LEFT JOIN files f ON f.id = j.fileId
                ORDER BY
                    CASE WHEN :sortOrder = 'NEWEST_FIRST' THEN j.queuedAt END DESC,
                    CASE WHEN :sortOrder = 'NAME_ASC' THEN COALESCE(f.name, j.fileId) END COLLATE NOCASE ASC,
                    CASE WHEN :sortOrder = 'NAME_DESC' THEN COALESCE(f.name, j.fileId) END COLLATE NOCASE DESC,
                    CASE WHEN :sortOrder = 'SOURCE_LARGEST' THEN COALESCE(f.sizeBytes, 0) END DESC,
                    CASE WHEN :sortOrder = 'SOURCE_SMALLEST' THEN COALESCE(f.sizeBytes, 0) END ASC,
                    CASE WHEN :sortOrder = 'FAILED_FIRST'
                         THEN CASE WHEN j.attempts >= 3 THEN 0 ELSE 1 END END ASC,
                    j.queuedAt ASC,
                    j.fileId ASC
                LIMIT :limit OFFSET :offset
           ),
           folder_paths(fileId, parentId, path, depth) AS (
               SELECT f.id, d.parentId, d.name, 0
                 FROM queued_page q
                 JOIN files f ON f.id = q.fileId
                 JOIN folders d ON d.id = COALESCE(f.folderId, f.preTrashFolderId)
               UNION ALL
               SELECT fp.fileId, d.parentId, d.name || ' / ' || fp.path, fp.depth + 1
                 FROM folder_paths fp
                 JOIN folders d ON d.id = fp.parentId
           )
           SELECT j.fileId AS fileId,
                  COALESCE(f.name, j.fileId) AS name,
                  COALESCE(f.mimeType, '') AS mimeType,
                  COALESCE(f.sizeBytes, 0) AS fileSizeBytes,
                  COALESCE(
                    (SELECT fp.path FROM folder_paths fp
                      WHERE fp.fileId = j.fileId
                      ORDER BY fp.depth DESC LIMIT 1),
                    'Drive root'
                  ) AS folderPath,
                  j.queuedAt AS queuedAt,
                  j.attempts AS attempts,
                  j.force AS isForced,
                  0 AS isProcessing,
                  EXISTS (
                      SELECT 1 FROM thumbnail_policies p
                       WHERE p.targetType = 'QUEUE_ITEM'
                         AND p.targetId = j.fileId
                         AND p.policy = 'PAUSED'
                  ) AS isPaused
             FROM queued_page q
             JOIN thumbnail_jobs j ON j.fileId = q.fileId
             LEFT JOIN files f ON f.id = j.fileId
            ORDER BY
                CASE WHEN :sortOrder = 'NEWEST_FIRST' THEN q.queuedAt END DESC,
                CASE WHEN :sortOrder = 'NAME_ASC' THEN q.sortName END COLLATE NOCASE ASC,
                CASE WHEN :sortOrder = 'NAME_DESC' THEN q.sortName END COLLATE NOCASE DESC,
                CASE WHEN :sortOrder = 'SOURCE_LARGEST' THEN q.fileSize END DESC,
                CASE WHEN :sortOrder = 'SOURCE_SMALLEST' THEN q.fileSize END ASC,
                CASE WHEN :sortOrder = 'FAILED_FIRST'
                     THEN CASE WHEN q.attempts >= 3 THEN 0 ELSE 1 END END ASC,
                q.queuedAt ASC,
                q.fileId ASC"""
    )
    suspend fun page(
        offset: Int,
        limit: Int,
        sortOrder: String
    ): List<ThumbnailQueueItem>

    @Query(
        """SELECT fileId FROM thumbnail_jobs
            ORDER BY queuedAt ASC, fileId ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun fileIdsPage(offset: Int, limit: Int): List<String>

    /** Return selected IDs that already have a durable queue row. */
    @Query("SELECT fileId FROM thumbnail_jobs WHERE fileId IN (:fileIds)")
    suspend fun existingFileIds(fileIds: List<String>): List<String>

    /** A single read gives Select all one consistent queue snapshot. */
    @Query("SELECT fileId FROM thumbnail_jobs ORDER BY fileId ASC")
    suspend fun allFileIds(): List<String>

    @Query("SELECT fileId FROM thumbnail_jobs WHERE attempts >= 3")
    suspend fun failedFileIds(): List<String>


    @Query("UPDATE thumbnail_jobs SET attempts = 0, queuedAt = :now WHERE fileId IN (:fileIds)")
    suspend fun retry(fileIds: List<String>, now: Long)

    @Query("DELETE FROM thumbnail_jobs WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    @Query("DELETE FROM thumbnail_jobs WHERE fileId IN (:fileIds)")
    suspend fun delete(fileIds: List<String>)

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           DELETE FROM thumbnail_jobs
            WHERE fileId IN (
                SELECT id FROM files WHERE COALESCE(folderId, preTrashFolderId) IN (SELECT id FROM child_folders)
            )"""
    )
    suspend fun deleteInFolderTree(folderId: String): Int

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           SELECT j.fileId FROM thumbnail_jobs j
           JOIN files f ON f.id = j.fileId
           WHERE COALESCE(f.folderId, f.preTrashFolderId) IN (SELECT id FROM child_folders)
             AND (:afterFileId IS NULL OR j.fileId > :afterFileId)
           ORDER BY j.fileId ASC
           LIMIT :limit"""
    )
    suspend fun queuedFileIdsInFolderTreePage(
        folderId: String,
        afterFileId: String?,
        limit: Int
    ): List<String>

    @Query("DELETE FROM thumbnail_jobs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM thumbnail_jobs")
    suspend fun count(): Int
}
