package com.drdisagree.teledrive.presentation.preview

const val PREVIEW_SLIDESHOW_INTERVAL_MS = 5_000L

/**
 * Returns the next page in a finite slideshow, or null when the current page is the last one.
 * A slideshow never wraps around automatically.
 */
fun nextSlideshowIndex(currentIndex: Int, fileCount: Int): Int? {
    if (fileCount <= 1 || currentIndex < 0 || currentIndex >= fileCount - 1) return null
    return currentIndex + 1
}
