package com.drdisagree.teledrive.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "thumbnail_policies",
    primaryKeys = ["targetType", "targetId"],
    indices = [Index("targetId")]
)
data class ThumbnailPolicyEntity(
    val targetType: String,
    val targetId: String,
    val policy: String,
    val changedAt: Long
)
