package com.drdisagree.teledrive.core.files

import android.content.Context
import android.os.Environment
import java.io.File

class AndroidStoragePaths(
    private val context: Context
) : AppStoragePaths {

    override val filesDir: File
        get() = context.filesDir

    override val cacheDir: File
        get() = context.cacheDir

    override val externalCacheDir: File?
        get() = context.externalCacheDir

    override val externalStorageRoot: File?
        get() = Environment.getExternalStorageDirectory()

    override val dataDir: File
        get() = context.dataDir

    override val externalFilesDirs: List<File>
        get() = context.getExternalFilesDirs(null).filterNotNull()

    override val externalCacheDirs: List<File>
        get() = context.externalCacheDirs.filterNotNull()

    override val codeCacheDir: File
        get() = context.codeCacheDir

    override val autoRemoveManagedCopiesAfterUpload: Boolean = true

    override val appCodeFiles: List<File>
        get() = buildList {
            context.applicationInfo.sourceDir?.let { add(File(it)) }
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }
}
