package com.drdisagree.teledrive.core.publish

import com.drdisagree.teledrive.core.common.AppResult

/**
 * Queues a fresh upload of a file's local copy through the normal
 * transfer path (PC-37).
 *
 * The publish drainer depends on this narrow seam instead of the
 * transfer repository itself for two reasons: the repair ladder only
 * ever needs "upload this file again", and the direct dependency
 * would close a construction cycle (transfer repository → transfer
 * scheduler → transfer executor → file repository → publish
 * scheduler → drainer). The production binding defers the repository
 * lookup until an enqueue is actually requested, so no cycle exists
 * at construction time either.
 */
fun interface ReuploadEnqueuer {

    /** Enqueues an upload for [fileId]; success carries the transfer id. */
    suspend fun enqueue(fileId: String): AppResult<String>
}
