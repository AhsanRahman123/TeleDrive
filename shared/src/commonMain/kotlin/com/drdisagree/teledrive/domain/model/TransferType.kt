package com.drdisagree.teledrive.domain.model

enum class TransferType {
    UPLOAD,
    DOWNLOAD,
    BACKUP,
    RESTORE,
    ARCHIVE_DOWNLOAD;

    val isIncoming: Boolean
        get() = this == DOWNLOAD || this == RESTORE

    /** Report-only rows: written by the archive viewer, never claimed or run by the transfer queue. */
    val isReportOnly: Boolean
        get() = this == ARCHIVE_DOWNLOAD
}
