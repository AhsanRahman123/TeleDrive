package com.drdisagree.teledrive.domain.model

/**
 * PC-40 (1.6.18): one fair-share group's claim head — the group key and
 * the createdAt of its oldest QUEUED transfer in a lane. A null
 * [batchId] is the legacy group (rows written before the batchId
 * column existed).
 */
data class TransferGroupHead(
    val batchId: String?,
    val oldestCreatedAt: Long
)

/** PC-40: how many transfers of one group are RUNNING in a lane. */
data class TransferGroupRunning(
    val batchId: String?,
    val running: Int
)
