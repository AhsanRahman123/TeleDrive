package com.drdisagree.teledrive.domain.model

/**
 * Aggregate work counters for one transfer direction (PC-39 ETA).
 * Room projection for the aggregate queries in
 * [com.drdisagree.teledrive.data.local.dao.TransferDao]; property names
 * match the SQL column aliases. All values are [Long] because that is
 * what SQLite's COUNT/SUM return.
 */
data class TransferWorkTotals(
    val files: Long,
    val bytes: Long
)

/** Remaining (queued + running) work for one direction, plus its running count. */
data class TransferRemainingWork(
    val files: Long,
    val bytes: Long,
    val running: Long
)
