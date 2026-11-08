package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.ThumbnailJobEntity

@Dao
interface ThumbnailJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(job: ThumbnailJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(jobs: List<ThumbnailJobEntity>)

    @Query("SELECT * FROM thumbnail_jobs ORDER BY queuedAt ASC LIMIT :limit")
    suspend fun next(limit: Int): List<ThumbnailJobEntity>

    @Query("UPDATE thumbnail_jobs SET attempts = attempts + 1 WHERE fileId = :fileId")
    suspend fun recordAttempt(fileId: String)

    @Query("DELETE FROM thumbnail_jobs WHERE fileId = :fileId")
    suspend fun delete(fileId: String)

    @Query("DELETE FROM thumbnail_jobs WHERE fileId IN (:fileIds)")
    suspend fun delete(fileIds: List<String>)

    @Query("DELETE FROM thumbnail_jobs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM thumbnail_jobs")
    suspend fun count(): Int
}
