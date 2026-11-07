package com.drdisagree.teledrive.core.transfer

/**
 * Mutable upload-import generation. TransferRepositoryImpl reads and mutates it only while holding
 * its enqueue mutex, so snapshots, inserts and Cancel all are serialized by one lock.
 */
internal class UploadBatchGeneration {
    private var value: Long = 0L

    fun current(): Long = value

    fun advance(): Long {
        value += 1L
        return value
    }

    fun isCurrent(snapshot: Long): Boolean = snapshot == value
}
