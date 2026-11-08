package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.ThumbnailEntity

@Dao
interface ThumbnailDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thumbnail: ThumbnailEntity)

    @Query("SELECT * FROM thumbnails WHERE fileId = :fileId")
    suspend fun byFileId(fileId: String): ThumbnailEntity?

    @Query("UPDATE thumbnails SET lastAccessAt = :accessedAt WHERE fileId = :fileId")
    suspend fun touch(fileId: String, accessedAt: Long)

    @Query("SELECT * FROM thumbnails ORDER BY lastAccessAt ASC LIMIT :limit")
    suspend fun leastRecentlyUsed(limit: Int): List<ThumbnailEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM thumbnails")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COUNT(*) FROM thumbnails")
    suspend fun count(): Int

    @Query(
        """SELECT COUNT(*) AS cachedFiles,
                  COALESCE(SUM(t.sizeBytes), 0) AS totalBytes
             FROM thumbnails t
             LEFT JOIN files f ON f.id = t.fileId
            WHERE f.id IS NULL"""
    )
    suspend fun orphanedAggregate(): ThumbnailUnfiledAggregate

    @Query(
        """SELECT t.fileId AS fileId, t.path AS path
             FROM thumbnails t
             LEFT JOIN files f ON f.id = t.fileId
            WHERE f.id IS NULL
              AND (:afterFileId IS NULL OR t.fileId > :afterFileId)
            ORDER BY t.fileId ASC
            LIMIT :limit"""
    )
    suspend fun orphanedFilePathsPage(
        afterFileId: String?,
        limit: Int
    ): List<ThumbnailPathRef>

    @Query(
        """SELECT COUNT(*) AS cachedFiles,
                  COALESCE(SUM(t.sizeBytes), 0) AS totalBytes
             FROM thumbnails t
             JOIN files f ON f.id = t.fileId
            WHERE COALESCE(f.folderId, f.preTrashFolderId) IS NULL"""
    )
    suspend fun unfiledAggregate(): ThumbnailUnfiledAggregate

    @Query(
        """
        SELECT t.* FROM thumbnails t
        INNER JOIN files f ON f.id = t.fileId
        WHERE f.mimeType LIKE 'text/%'
            OR f.mimeType IN ('application/json', 'application/xml', 'application/yaml')
        """
    )
    suspend fun textFileThumbnails(): List<ThumbnailEntity>

    @Query(
        """SELECT COALESCE(f.folderId, f.preTrashFolderId) AS folderId,
                  COUNT(*) AS cachedFiles,
                  COALESCE(SUM(t.sizeBytes), 0) AS totalBytes
             FROM thumbnails t
             JOIN files f ON f.id = t.fileId
            GROUP BY COALESCE(f.folderId, f.preTrashFolderId)"""
    )
    suspend fun folderAggregates(): List<ThumbnailFolderAggregate>

    @Query(
        """SELECT f.id AS fileId,
                  f.name AS name,
                  f.mimeType AS mimeType,
                  f.category AS category,
                  f.sizeBytes AS fileSizeBytes,
                  COALESCE(f.folderId, f.preTrashFolderId) AS folderId,
                  t.sizeBytes AS thumbnailSizeBytes,
                  t.generatedAt AS generatedAt,
                  t.lastAccessAt AS lastAccessAt,
                  t.encrypted AS encrypted,
                  CASE WHEN f.trashedAt IS NULL THEN 0 ELSE 1 END AS isTrashed
             FROM files f
             LEFT JOIN thumbnails t ON t.fileId = f.id
            WHERE COALESCE(f.folderId, f.preTrashFolderId) IS :folderId
              AND (
                    f.trashedAt IS NULL
                    OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                  )
              AND (
                    f.category IN ('IMAGE', 'VIDEO')
                    OR f.mimeType LIKE 'text/%'
                    OR f.mimeType IN (
                        'application/json', 'application/xml', 'application/yaml',
                        'application/vnd.android.package-archive'
                    )
                    OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                  )
            ORDER BY CASE WHEN t.fileId IS NULL THEN 1 ELSE 0 END,
                     t.sizeBytes DESC,
                     f.name COLLATE NOCASE ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun filesInFolder(
        folderId: String?,
        limit: Int,
        offset: Int
    ): List<ThumbnailFileRow>

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           SELECT t.fileId AS fileId, t.path AS path
             FROM thumbnails t
             JOIN files f ON f.id = t.fileId
            WHERE COALESCE(f.folderId, f.preTrashFolderId) IN (SELECT id FROM child_folders)
              AND (:afterFileId IS NULL OR t.fileId > :afterFileId)
            ORDER BY t.fileId ASC
            LIMIT :limit"""
    )
    suspend fun cachedFilePathsInFolderTreePage(
        folderId: String,
        afterFileId: String?,
        limit: Int
    ): List<ThumbnailPathRef>

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           SELECT f.id AS fileId, f.addedAt AS addedAt FROM files f
           WHERE f.folderId IN (SELECT id FROM child_folders)
             AND f.trashedAt IS NULL
             AND (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType LIKE 'text/%'
                  OR f.mimeType IN (
                    'application/json', 'application/xml', 'application/yaml',
                    'application/vnd.android.package-archive'
                  ))
             AND (:onlyMissing = 0 OR NOT EXISTS (
                    SELECT 1 FROM thumbnails t WHERE t.fileId = f.id
                 ))
             AND (
                 :afterAddedAt IS NULL
                 OR f.addedAt < :afterAddedAt
                 OR (f.addedAt = :afterAddedAt AND f.id > :afterFileId)
             )
           ORDER BY f.addedAt DESC, f.id ASC
           LIMIT :limit"""
    )
    suspend fun fileIdsInFolderTreePage(
        folderId: String,
        onlyMissing: Boolean,
        afterAddedAt: Long?,
        afterFileId: String?,
        limit: Int
    ): List<ThumbnailFileCursor>

    @Query(
        """SELECT f.id AS fileId, f.addedAt AS addedAt FROM files f
           WHERE f.folderId IS NULL AND f.trashedAt IS NULL
             AND (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType LIKE 'text/%'
                  OR f.mimeType IN (
                    'application/json', 'application/xml', 'application/yaml',
                    'application/vnd.android.package-archive'
                  ))
             AND (:onlyMissing = 0 OR NOT EXISTS (
                    SELECT 1 FROM thumbnails t WHERE t.fileId = f.id
                 ))
             AND (
                 :afterAddedAt IS NULL
                 OR f.addedAt < :afterAddedAt
                 OR (f.addedAt = :afterAddedAt AND f.id > :afterFileId)
             )
           ORDER BY f.addedAt DESC, f.id ASC
           LIMIT :limit"""
    )
    suspend fun unfiledFileIdsPage(
        onlyMissing: Boolean,
        afterAddedAt: Long?,
        afterFileId: String?,
        limit: Int
    ): List<ThumbnailFileCursor>

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           DELETE FROM thumbnails
            WHERE fileId IN (
                SELECT id FROM files WHERE COALESCE(folderId, preTrashFolderId) IN (SELECT id FROM child_folders)
            )"""
    )
    suspend fun deleteFolderTree(folderId: String): Int

    @Query("DELETE FROM thumbnails WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    @Query("DELETE FROM thumbnails WHERE fileId IN (:fileIds)")
    suspend fun deleteAll(fileIds: List<String>)

    @Query("DELETE FROM thumbnails")
    suspend fun deleteAll()
}
