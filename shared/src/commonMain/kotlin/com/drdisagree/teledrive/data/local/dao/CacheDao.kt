package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.CacheEntryEntity
import com.drdisagree.teledrive.data.local.entity.CacheEntryType
import com.drdisagree.teledrive.domain.repository.CacheEntryUsage
import com.drdisagree.teledrive.domain.repository.CacheFolderUsage

@Dao
interface CacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CacheEntryEntity)

    @Query("SELECT * FROM cache_entries WHERE fileId = :fileId")
    suspend fun byFileId(fileId: String): List<CacheEntryEntity>

    @Query("UPDATE cache_entries SET lastAccessAt = :accessedAt WHERE path = :path")
    suspend fun touch(path: String, accessedAt: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cache_entries")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cache_entries WHERE type = :type")
    suspend fun sizeByType(type: CacheEntryType): Long

    @Query("SELECT * FROM cache_entries ORDER BY lastAccessAt ASC LIMIT :limit")
    suspend fun leastRecentlyUsed(limit: Int): List<CacheEntryEntity>

    @Query("SELECT * FROM cache_entries WHERE type = :type")
    suspend fun byType(type: CacheEntryType): List<CacheEntryEntity>

    @Query("DELETE FROM cache_entries WHERE path = :path")
    suspend fun delete(path: String)


    @Query("SELECT COUNT(*) FROM cache_entries")
    suspend fun entryCount(): Int

    @Query("SELECT * FROM cache_entries WHERE path = :path LIMIT 1")
    suspend fun entryByPath(path: String): CacheEntryEntity?

    @Query("SELECT * FROM cache_entries WHERE path IN (:paths)")
    suspend fun entriesByPaths(paths: List<String>): List<CacheEntryEntity>

    @Query(
        """WITH RECURSIVE folder_paths(entryPath, parentId, path, depth) AS (
               SELECT ce.path, d.parentId, d.name, 0
                 FROM cache_entries ce
                 JOIN files f ON f.id = ce.fileId
                 JOIN folders d ON d.id = COALESCE(f.folderId, f.preTrashFolderId)
               UNION ALL
               SELECT fp.entryPath, d.parentId, d.name || ' / ' || fp.path, fp.depth + 1
                 FROM folder_paths fp
                 JOIN folders d ON d.id = fp.parentId
           )
           SELECT ce.path AS path,
                  ce.fileId AS fileId,
                  CAST(ce.type AS TEXT) AS type,
                  ce.sizeBytes AS sizeBytes,
                  ce.encrypted AS encrypted,
                  ce.createdAt AS createdAt,
                  ce.lastAccessAt AS lastAccessAt,
                  COALESCE(f.name, ce.path) AS name,
                  COALESCE(f.mimeType, '') AS mimeType,
                  COALESCE(f.category, 'OTHER') AS category,
                  COALESCE(f.folderId, f.preTrashFolderId) AS folderId,
                  COALESCE(
                    (SELECT fp.path FROM folder_paths fp
                      WHERE fp.entryPath = ce.path
                      ORDER BY fp.depth DESC LIMIT 1),
                    'App cache'
                  ) AS folderPath
             FROM cache_entries ce
             LEFT JOIN files f ON f.id = ce.fileId
            WHERE (
                    (:folderId IS NULL AND
                        (ce.fileId IS NULL OR COALESCE(f.folderId, f.preTrashFolderId) IS NULL))
                    OR
                    (:folderId IS NOT NULL AND
                        COALESCE(f.folderId, f.preTrashFolderId) = :folderId)
                  )
              AND (:typeFilter = 'ALL' OR CAST(ce.type AS TEXT) = :typeFilter)
              AND (:searchQuery = '' OR
                   COALESCE(f.name, ce.path) LIKE '%' || :searchQuery || '%')
            ORDER BY
                CASE WHEN :sortOrder = 'LARGEST' THEN ce.sizeBytes END DESC,
                CASE WHEN :sortOrder = 'SMALLEST' THEN ce.sizeBytes END ASC,
                CASE WHEN :sortOrder = 'NEWEST' THEN ce.lastAccessAt END DESC,
                CASE WHEN :sortOrder = 'OLDEST' THEN ce.lastAccessAt END ASC,
                CASE WHEN :sortOrder = 'NAME_ASC' THEN COALESCE(f.name, ce.path) END COLLATE NOCASE ASC,
                ce.path ASC
            LIMIT :limit OFFSET :offset"""
    )
    suspend fun managerEntries(
        folderId: String?,
        typeFilter: String,
        searchQuery: String,
        sortOrder: String,
        limit: Int,
        offset: Int
    ): List<CacheEntryUsage>

    @Query(
        """WITH RECURSIVE folder_tree(rootId, id) AS (
               SELECT id, id FROM folders
                WHERE parentId IS :parentId AND trashedAt IS NULL
               UNION ALL
               SELECT folder_tree.rootId, child.id
                 FROM folders child
                 JOIN folder_tree ON child.parentId = folder_tree.id
                WHERE child.trashedAt IS NULL
           ),
           cache_usage AS (
               SELECT folder_tree.rootId AS id,
                      COALESCE(SUM(ce.sizeBytes), 0) AS totalBytes,
                      COUNT(ce.path) AS entryCount
                 FROM folder_tree
                 JOIN files f ON COALESCE(f.folderId, f.preTrashFolderId) = folder_tree.id
                 JOIN cache_entries ce ON ce.fileId = f.id
                GROUP BY folder_tree.rootId
           ),
           thumbnail_usage AS (
               SELECT folder_tree.rootId AS id,
                      COALESCE(SUM(t.sizeBytes), 0) AS totalBytes,
                      COUNT(t.fileId) AS entryCount
                 FROM folder_tree
                 JOIN files f ON COALESCE(f.folderId, f.preTrashFolderId) = folder_tree.id
                 JOIN thumbnails t ON t.fileId = f.id
                GROUP BY folder_tree.rootId
           ),
           folder_usage AS (
               SELECT roots.id AS id,
                      COALESCE(cache_usage.totalBytes, 0) +
                          COALESCE(thumbnail_usage.totalBytes, 0) AS totalBytes,
                      COALESCE(cache_usage.entryCount, 0) +
                          COALESCE(thumbnail_usage.entryCount, 0) AS entryCount
                 FROM (SELECT DISTINCT rootId AS id FROM folder_tree) roots
                 LEFT JOIN cache_usage ON cache_usage.id = roots.id
                 LEFT JOIN thumbnail_usage ON thumbnail_usage.id = roots.id
           )
           SELECT folder.id AS id,
                  folder.name AS name,
                  folder.parentId AS parentId,
                  COALESCE(folder_usage.totalBytes, 0) AS totalBytes,
                  COALESCE(folder_usage.entryCount, 0) AS entryCount
             FROM folders folder
             LEFT JOIN folder_usage ON folder_usage.id = folder.id
            WHERE folder.parentId IS :parentId
              AND folder.trashedAt IS NULL
              AND COALESCE(folder_usage.totalBytes, 0) > 0
            ORDER BY
                CASE WHEN :sortOrder = 'LARGEST' THEN folder_usage.totalBytes END DESC,
                CASE WHEN :sortOrder = 'SMALLEST' THEN folder_usage.totalBytes END ASC,
                folder.name COLLATE NOCASE ASC"""
    )
    suspend fun managerFolders(
        parentId: String?,
        sortOrder: String
    ): List<CacheFolderUsage>

    @Query(
        """SELECT ce.path
             FROM cache_entries ce
             LEFT JOIN files f ON f.id = ce.fileId
            WHERE (
                    (:folderId IS NULL AND
                        (ce.fileId IS NULL OR COALESCE(f.folderId, f.preTrashFolderId) IS NULL))
                    OR
                    (:folderId IS NOT NULL AND
                        COALESCE(f.folderId, f.preTrashFolderId) = :folderId)
                  )
              AND (:typeFilter = 'ALL' OR CAST(ce.type AS TEXT) = :typeFilter)
              AND (:searchQuery = '' OR
                   COALESCE(f.name, ce.path) LIKE '%' || :searchQuery || '%')
            ORDER BY ce.path ASC"""
    )
    suspend fun managerEntryPathsMatching(
        folderId: String?,
        typeFilter: String,
        searchQuery: String
    ): List<String>

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
                JOIN child_folders ON folders.parentId = child_folders.id
           )
           SELECT ce.path
             FROM cache_entries ce
             JOIN files f ON f.id = ce.fileId
            WHERE COALESCE(f.folderId, f.preTrashFolderId) IN (SELECT id FROM child_folders)
              AND (:typeFilter = 'ALL' OR CAST(ce.type AS TEXT) = :typeFilter)
              AND (:searchQuery = '' OR
                   COALESCE(f.name, ce.path) LIKE '%' || :searchQuery || '%')
            ORDER BY ce.path ASC"""
    )
    suspend fun managerEntryPathsInFolderTree(
        folderId: String,
        typeFilter: String = "ALL",
        searchQuery: String = ""
    ): List<String>

    @Query("DELETE FROM cache_entries WHERE path IN (:paths)")
    suspend fun deletePaths(paths: List<String>)

    @Query("DELETE FROM cache_entries")
    suspend fun deleteAll()
}
