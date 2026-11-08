package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.ThumbnailPolicyEntity

@Dao
interface ThumbnailPolicyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: ThumbnailPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policies: List<ThumbnailPolicyEntity>)

    @Query(
        "SELECT policy FROM thumbnail_policies WHERE targetType = :targetType AND targetId = :targetId"
    )
    suspend fun policy(targetType: String, targetId: String): String?

    /**
     * File overrides win. Otherwise the closest ancestor folder override wins. With no explicit
     * rule the caller treats the file as MANUAL_ONLY.
     */
    @Query(
        """
        WITH RECURSIVE ancestors(id, parentId, depth) AS (
            SELECT folders.id, folders.parentId, 0
              FROM files JOIN folders ON folders.id = COALESCE(files.folderId, files.preTrashFolderId)
             WHERE files.id = :fileId
            UNION ALL
            SELECT parent.id, parent.parentId, ancestors.depth + 1
              FROM folders AS parent
              JOIN ancestors ON parent.id = ancestors.parentId
        ),
        candidates(policy, priority) AS (
            SELECT policy, -1
              FROM thumbnail_policies
             WHERE targetType = 'FILE' AND targetId = :fileId
            UNION ALL
            SELECT thumbnail_policies.policy, ancestors.depth
              FROM ancestors
              JOIN thumbnail_policies
                ON thumbnail_policies.targetType = 'FOLDER'
               AND thumbnail_policies.targetId = ancestors.id
        )
        SELECT policy FROM candidates ORDER BY priority ASC LIMIT 1
        """
    )
    suspend fun effectivePolicy(fileId: String): String?

    @Query(
        "DELETE FROM thumbnail_policies WHERE targetType = :targetType AND targetId = :targetId"
    )
    suspend fun delete(targetType: String, targetId: String)

    @Query(
        """WITH RECURSIVE parents(id, parentId, depth) AS (
               SELECT id, parentId, 0 FROM folders WHERE id = :folderId
               UNION ALL
               SELECT parent.id, parent.parentId, parents.depth + 1
                 FROM folders AS parent
                 JOIN parents ON parent.id = parents.parentId
           )
           SELECT policies.policy
             FROM parents
             JOIN thumbnail_policies AS policies
               ON policies.targetType = 'FOLDER'
              AND policies.targetId = parents.id
            WHERE parents.depth > 0
            ORDER BY parents.depth ASC
            LIMIT 1"""
    )
    suspend fun inheritedFolderPolicy(folderId: String): String?

    @Query(
        "DELETE FROM thumbnail_policies WHERE targetType = 'FILE' AND targetId IN (:fileIds)"
    )
    suspend fun deleteFileOverrides(fileIds: List<String>)

    @Query(
        "DELETE FROM thumbnail_policies WHERE targetType = 'FOLDER' AND targetId IN (:folderIds)"
    )
    suspend fun deleteFolderOverrides(folderIds: List<String>)

    @Query(
        """WITH RECURSIVE child_folders(id) AS (
               SELECT id FROM folders WHERE id = :folderId
               UNION ALL
               SELECT folders.id FROM folders
               JOIN child_folders ON folders.parentId = child_folders.id
           )
           DELETE FROM thumbnail_policies
            WHERE (targetType = 'FOLDER' AND targetId IN (SELECT id FROM child_folders))
               OR (targetType = 'FILE' AND targetId IN (
                    SELECT id FROM files WHERE COALESCE(folderId, preTrashFolderId) IN (SELECT id FROM child_folders)
               ))"""
    )
    suspend fun deleteOverridesInFolderTree(folderId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM thumbnail_policies WHERE targetType = 'QUEUE' AND targetId = 'GLOBAL' AND policy = 'PAUSED')"
    )
    suspend fun isQueuePaused(): Boolean

    @Query(
        "SELECT targetId FROM thumbnail_policies WHERE targetType = 'QUEUE_ITEM' AND policy = 'PAUSED'"
    )
    suspend fun pausedQueueItemIds(): List<String>

    @Query(
        "DELETE FROM thumbnail_policies WHERE targetType = 'QUEUE_ITEM' AND targetId IN (:fileIds)"
    )
    suspend fun clearQueueItemPauses(fileIds: List<String>)

    @Query("DELETE FROM thumbnail_policies WHERE targetType = 'QUEUE_ITEM'")
    suspend fun clearAllQueueItemPauses()

    @Query("DELETE FROM thumbnail_policies WHERE targetType = 'QUEUE' AND targetId = 'GLOBAL'")
    suspend fun clearGlobalQueuePause()

    @Query("DELETE FROM thumbnail_policies")
    suspend fun deleteAll()
}
