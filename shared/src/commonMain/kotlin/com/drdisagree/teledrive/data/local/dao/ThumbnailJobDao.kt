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

    @Query("SELECT * FROM thumbnail_jobs ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun next(limit: Int): List<ThumbnailJobEntity>

    @Query(
        """SELECT * FROM thumbnail_jobs
            WHERE queuedAt <= :now AND attempts < :maxAttempts
            ORDER BY queuedAt ASC LIMIT :limit"""
    )
    suspend fun nextReady(
        limit: Int,
        now: Long,
        maxAttempts: Int
    ): List<ThumbnailJobEntity>

    @Query("SELECT MIN(queuedAt) FROM thumbnail_jobs")
    suspend fun nextQueuedAt(): Long?

    @Query(
        "UPDATE thumbnail_jobs SET attempts = attempts + 1, queuedAt = :retryAt WHERE fileId = :fileId"
    )
    suspend fun recordAttempt(fileId: String, retryAt: Long)

    @Query(
        """WITH RECURSIVE folder_paths(fileId, parentId, path, depth) AS (
               SELECT f.id, d.parentId, d.name, 0
                 FROM files f
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
                  j.force AS isForced
             FROM thumbnail_jobs j
             LEFT JOIN files f ON f.id = j.fileId
            ORDER BY j.queuedAt ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun page(offset: Int, limit: Int): List<ThumbnailQueueItem>

    @Query(
        """SELECT fileId FROM thumbnail_jobs
            ORDER BY queuedAt ASC, fileId ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun fileIdsPage(offset: Int, limit: Int): List<String>

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
