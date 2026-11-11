package com.drdisagree.teledrive.core.files

data class LocalCleanup(
    val deletedCount: Int,
    val consentRequest: DeleteConsentRequest? = null,
    val keptOffline: Int = 0,
    /** Local paths outside TeleDrive-owned folders are preserved as original user files. */
    val keptUnmanaged: Int = 0
)
