package com.drdisagree.teledrive.domain.repository

data class CacheStats(
    val thumbnailBytes: Long,
    val previewBytes: Long,
    val streamBytes: Long,
    val tempBytes: Long,
    val tdlibBytes: Long
) {
    /** Cache managed by Storage > Clear cache; thumbnail previews are independent. */
    val nonThumbnailBytes: Long
        get() = previewBytes + streamBytes + tempBytes + tdlibBytes

    /** Combined footprint for diagnostics and overall storage accounting. */
    val totalBytes: Long
        get() = thumbnailBytes + nonThumbnailBytes
}
