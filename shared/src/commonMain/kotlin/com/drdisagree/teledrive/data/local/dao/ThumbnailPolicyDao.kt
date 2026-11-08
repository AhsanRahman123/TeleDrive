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
              FROM files JOIN folders ON folders.id = files.folderId
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
        "DELETE FROM thumbnail_policies WHERE targetType = 'FILE' AND targetId IN (:fileIds)"
    )
    suspend fun deleteFileOverrides(fileIds: List<String>)

    @Query(
        "DELETE FROM thumbnail_policies WHERE targetType = 'FOLDER' AND targetId IN (:folderIds)"
    )
    suspend fun deleteFolderOverrides(folderIds: List<String>)

    @Query("DELETE FROM thumbnail_policies")
    suspend fun deleteAll()
}
