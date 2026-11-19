package com.drdisagree.teledrive.core.files

import java.io.File

interface AppStoragePaths {

    val filesDir: File

    val cacheDir: File

    val externalCacheDir: File?

    val externalStorageRoot: File?

    /** Complete private app data root, including files, databases, preferences and cache. */
    val dataDir: File
        get() = filesDir.parentFile ?: filesDir

    /** App-specific external files roots on all mounted volumes. */
    val externalFilesDirs: List<File>
        get() = emptyList()

    /** App-specific external cache roots on all mounted volumes. */
    val externalCacheDirs: List<File>
        get() = listOfNotNull(externalCacheDir)

    /** Runtime/code cache that Android can safely recreate. */
    val codeCacheDir: File?
        get() = null

    /**
     * Android opts into removing only Pranto Drive-managed local copies after a confirmed upload.
     * Desktop keeps its existing user-controlled delete-after-upload behaviour.
     */
    val autoRemoveManagedCopiesAfterUpload: Boolean
        get() = false

    /** Installed APK and split APK files; these are inventory-only, never deletable here. */
    val appCodeFiles: List<File>
        get() = emptyList()
}
