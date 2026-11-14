package com.drdisagree.teledrive.core.telegram

/**
 * Evicts both the persistent download-list entry and the underlying TDLib file cache.
 *
 * RemoveFileFromDownloads only targets the managed-download list. Older transfers started via
 * DownloadFile may have a completed cache entry without a managed-list row, so DeleteFile must
 * also be issued even when RemoveFileFromDownloads returns successfully.
 */
suspend fun evictManagedDownloadCache(
    removeFromDownloadList: suspend () -> Unit,
    deleteFromCache: suspend () -> Unit,
    cacheStillPresent: suspend () -> Boolean
) {
    try {
        removeFromDownloadList()
    } catch (_: TelegramException) {
        // The file may not have a managed-list row (legacy DownloadFile transfer).
        // Still issue DeleteFile below to evict the local cache.
    }

    deleteFromCache()

    if (cacheStillPresent()) {
        throw TelegramException(
            500,
            "TDLib still has a local file after managed-download cache eviction"
        )
    }
}
