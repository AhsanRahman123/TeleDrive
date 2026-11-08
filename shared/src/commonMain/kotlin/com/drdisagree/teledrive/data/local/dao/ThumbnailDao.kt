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
        """SELECT COALESCE(f.folderId, f.preTrashFolderId) AS folderId,
                  COUNT(*) AS eligibleFiles,
                  COUNT(t.fileId) AS generatedFiles
             FROM files f
             LEFT JOIN thumbnails t ON t.fileId = f.id
            WHERE f.trashedAt IS NULL
              AND (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
            GROUP BY COALESCE(f.folderId, f.preTrashFolderId)"""
    )
    suspend fun folderStatusAggregates(): List<ThumbnailFolderStatusAggregate>

    @Query(
        """WITH scoped_files AS (
               SELECT f.*
                 FROM files f
                WHERE COALESCE(f.folderId, f.preTrashFolderId) IS :folderId
                  AND (
                        f.trashedAt IS NULL
                        OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                      )
                  AND (
                        (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
                        OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                      )
           ),
           effective_policies(fileId, directPolicy, effectivePolicy) AS (
               SELECT f.id,
                      COALESCE(fp.policy, 'INHERIT'),
                      COALESCE(fp.policy, :folderEffectivePolicy)
                 FROM scoped_files f
                 LEFT JOIN thumbnail_policies fp
                   ON fp.targetType = 'FILE' AND fp.targetId = f.id
                WHERE COALESCE(f.folderId, f.preTrashFolderId) IS :folderId
           )
           SELECT f.id AS fileId,
                  f.name AS name,
                  f.mimeType AS mimeType,
                  f.category AS category,
                  f.sizeBytes AS fileSizeBytes,
                  COALESCE(f.folderId, f.preTrashFolderId) AS folderId,
                  t.sizeBytes AS thumbnailSizeBytes,
                  t.generatedAt AS generatedAt,
                  t.lastAccessAt AS lastAccessAt,
                  t.encrypted AS encrypted,
                  CASE WHEN f.trashedAt IS NULL THEN 0 ELSE 1 END AS isTrashed,
                  ep.directPolicy AS directPolicy,
                  ep.effectivePolicy AS effectivePolicy
             FROM scoped_files f
             JOIN effective_policies ep ON ep.fileId = f.id
             LEFT JOIN thumbnails t ON t.fileId = f.id
            WHERE (
                    :cacheFilter = 'ALL'
                    OR (:cacheFilter = 'GENERATED' AND t.fileId IS NOT NULL)
                    OR (:cacheFilter = 'MISSING' AND t.fileId IS NULL)
                  )
              AND (
                    :policyFilter = 'ALL'
                    OR (:policyFilter = 'INHERITED' AND ep.directPolicy = 'INHERIT')
                    OR (:policyFilter = 'EXPLICIT_AUTOMATIC' AND ep.directPolicy = 'AUTOMATIC')
                    OR (:policyFilter = 'EXPLICIT_MANUAL_ONLY' AND ep.directPolicy = 'MANUAL_ONLY')
                  )
              AND (
                    :effectivePolicyFilter = 'ALL'
                    OR (:effectivePolicyFilter = 'AUTOMATIC' AND ep.effectivePolicy = 'AUTOMATIC')
                    OR (:effectivePolicyFilter = 'MANUAL_ONLY' AND ep.effectivePolicy = 'MANUAL_ONLY')
                  )
              AND (:searchQuery = '' OR LOWER(f.name) LIKE '%' || LOWER(:searchQuery) || '%')
            ORDER BY
                CASE WHEN :sortOrder = 'GENERATED_FIRST'
                     THEN CASE WHEN t.fileId IS NOT NULL THEN 0 ELSE 1 END END ASC,
                CASE WHEN :sortOrder = 'MISSING_FIRST'
                     THEN CASE WHEN t.fileId IS NULL THEN 0 ELSE 1 END END ASC,
                CASE WHEN :sortOrder = 'THUMBNAIL_LARGEST' THEN t.sizeBytes END DESC,
                CASE WHEN :sortOrder = 'THUMBNAIL_SMALLEST' AND t.fileId IS NULL THEN 1 ELSE 0 END ASC,
                CASE WHEN :sortOrder = 'THUMBNAIL_SMALLEST' THEN t.sizeBytes END ASC,
                CASE WHEN :sortOrder = 'SOURCE_LARGEST' THEN f.sizeBytes END DESC,
                CASE WHEN :sortOrder = 'SOURCE_SMALLEST' THEN f.sizeBytes END ASC,
                CASE WHEN :sortOrder = 'GENERATED_NEWEST' THEN t.generatedAt END DESC,
                CASE WHEN :sortOrder = 'LAST_VIEWED_OLDEST' AND t.fileId IS NULL THEN 1 ELSE 0 END ASC,
                CASE WHEN :sortOrder = 'LAST_VIEWED_OLDEST' THEN t.lastAccessAt END ASC,
                CASE WHEN :sortOrder = 'NAME_DESC' THEN f.name END COLLATE NOCASE DESC,
                CASE WHEN :sortOrder = 'NAME_ASC' THEN f.name END COLLATE NOCASE ASC,
                CASE WHEN :sortOrder = 'THUMBNAIL_LARGEST' THEN t.fileId END,
                f.name COLLATE NOCASE ASC,
                f.id ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun filesInFolder(
        folderId: String?,
        folderEffectivePolicy: String,
        cacheFilter: String,
        policyFilter: String,
        effectivePolicyFilter: String,
        searchQuery: String,
        sortOrder: String,
        limit: Int,
        offset: Int
    ): List<ThumbnailFileRow>

    @Query(
        """WITH scoped_files AS (
               SELECT f.*
                 FROM files f
                WHERE COALESCE(f.folderId, f.preTrashFolderId) IS :folderId
                  AND (
                        f.trashedAt IS NULL
                        OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                      )
                  AND (
                        (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
                        OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                      )
           ),
           effective_policies(fileId, directPolicy, effectivePolicy) AS (
               SELECT f.id,
                      COALESCE(fp.policy, 'INHERIT'),
                      COALESCE(fp.policy, :folderEffectivePolicy)
                 FROM scoped_files f
                 LEFT JOIN thumbnail_policies fp
                   ON fp.targetType = 'FILE' AND fp.targetId = f.id
           )
           SELECT f.id
             FROM scoped_files f
             JOIN effective_policies ep ON ep.fileId = f.id
             LEFT JOIN thumbnails t ON t.fileId = f.id
            WHERE (
                    :cacheFilter = 'ALL'
                    OR (:cacheFilter = 'GENERATED' AND t.fileId IS NOT NULL)
                    OR (:cacheFilter = 'MISSING' AND t.fileId IS NULL)
                  )
              AND (
                    :policyFilter = 'ALL'
                    OR (:policyFilter = 'INHERITED' AND ep.directPolicy = 'INHERIT')
                    OR (:policyFilter = 'EXPLICIT_AUTOMATIC' AND ep.directPolicy = 'AUTOMATIC')
                    OR (:policyFilter = 'EXPLICIT_MANUAL_ONLY' AND ep.directPolicy = 'MANUAL_ONLY')
                  )
              AND (
                    :effectivePolicyFilter = 'ALL'
                    OR (:effectivePolicyFilter = 'AUTOMATIC' AND ep.effectivePolicy = 'AUTOMATIC')
                    OR (:effectivePolicyFilter = 'MANUAL_ONLY' AND ep.effectivePolicy = 'MANUAL_ONLY')
                  )
              AND (:searchQuery = '' OR LOWER(f.name) LIKE '%' || LOWER(:searchQuery) || '%')
            ORDER BY f.id ASC"""
    )
    suspend fun fileIdsMatching(
        folderId: String?,
        folderEffectivePolicy: String,
        cacheFilter: String,
        policyFilter: String,
        effectivePolicyFilter: String,
        searchQuery: String
    ): List<String>

    @Query(
        """SELECT f.id
             FROM files f
            WHERE f.id IN (:fileIds)
              AND f.trashedAt IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM thumbnails t WHERE t.fileId = f.id
                  )
              AND (
                    (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
                  )
            ORDER BY f.addedAt DESC, f.id ASC"""
    )
    suspend fun missingEligibleFileIds(fileIds: List<String>): List<String>

    @Query(
        """SELECT f.id AS fileId, f.addedAt AS addedAt
             FROM files f
            WHERE f.trashedAt IS NULL
              AND (
                    (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
                  )
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
    suspend fun allEligibleFileIdsPage(
        onlyMissing: Boolean,
        afterAddedAt: Long?,
        afterFileId: String?,
        limit: Int
    ): List<ThumbnailFileCursor>

    @Query(
        """SELECT f.id
             FROM files f
            WHERE COALESCE(f.folderId, f.preTrashFolderId) IS :folderId
              AND (
                    f.trashedAt IS NULL
                    OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                  )
              AND (
                    (f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive')
                    OR EXISTS (SELECT 1 FROM thumbnails cached WHERE cached.fileId = f.id)
                  )
            ORDER BY f.name COLLATE NOCASE ASC, f.id ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun allFileIdsInFolder(
        folderId: String?,
        limit: Int,
        offset: Int
    ): List<String>

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
             AND ((f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive'))
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
             AND ((f.category IN ('IMAGE', 'VIDEO') OR f.mimeType = 'application/vnd.android.package-archive'))
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

    @Query("SELECT fileId AS fileId, path AS path FROM thumbnails WHERE fileId IN (:fileIds)")
    suspend fun pathsForFiles(fileIds: List<String>): List<ThumbnailPathRef>

    @Query("DELETE FROM thumbnails WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    @Query("DELETE FROM thumbnails WHERE fileId IN (:fileIds)")
    suspend fun deleteAll(fileIds: List<String>)

    @Query("DELETE FROM thumbnails")
    suspend fun deleteAll()
}
