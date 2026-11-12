package com.drdisagree.teledrive.core.files

data class LocalCleanup(
    val deletedCount: Int,
    val consentRequest: DeleteConsentRequest? = null,
    val keptOffline: Int = 0,
    /** Local paths outside TeleDrive-owned folders are preserved as original user files. */
    val keptUnmanaged: Int = 0,
    /** Files whose actual TDLib path could not be queried and therefore were not touched. */
    val unverifiedCount: Int = 0,
    /** Files whose local or TDLib cache cleanup did not complete. */
    val failedCount: Int = 0
)
