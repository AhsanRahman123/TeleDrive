package com.drdisagree.teledrive.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable thumbnail work queue. force=true represents an explicit user request and may bypass
 * the automatic/manual policy; upload jobs always use force=false.
 */
@Entity(
    tableName = "thumbnail_jobs",
    indices = [Index("queuedAt")]
)
data class ThumbnailJobEntity(
    @PrimaryKey val fileId: String,
    val queuedAt: Long,
    val attempts: Int = 0,
    val force: Boolean = false
)
