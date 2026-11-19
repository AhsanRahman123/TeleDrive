package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** The Transfers screen's sort orders, by their stored value. */
val TRANSFERS_SORT_ORDERS: Set<String> = setOf(
    "STATUS",
    "PRIORITY",
    "LARGEST",
    "SMALLEST",
    "NEWEST",
    "OLDEST",
    "NAME"
)

/** The Transfers sort used when nothing has been stored yet. */
const val DEFAULT_TRANSFERS_SORT_ORDER = "PRIORITY"

/** A stored value that is missing or no longer a known order falls back to the default. */
fun sanitizeTransfersSortOrder(value: String?): String =
    value?.takeIf { it in TRANSFERS_SORT_ORDERS } ?: DEFAULT_TRANSFERS_SORT_ORDER

/**
 * Persists the Transfers sort choice across app restarts (PC-6).
 *
 * Files and Gallery sorts already persist through the shared settings
 * repository; the Transfers sort was a plain `remember` in the screen, so
 * it reset on every navigation and restart. Desktop provides an
 * implementation backed by its settings store. Other platforms do not
 * provide one (the local stays null) and keep their existing in-memory
 * behaviour — this hook exists so the desktop persistence does not change
 * Android.
 */
interface TransfersSortPreference {
    fun loadSortOrder(): String
    fun saveSortOrder(value: String)
}

val LocalTransfersSortPreference = staticCompositionLocalOf<TransfersSortPreference?> { null }
