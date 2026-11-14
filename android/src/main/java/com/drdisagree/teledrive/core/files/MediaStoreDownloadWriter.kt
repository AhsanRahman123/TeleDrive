package com.drdisagree.teledrive.core.files

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.OutputStream

/**
 * Downloads/TeleDrive survives uninstall and gets indexed; MediaStore owns the entry from Android
 * 10, older releases write directly.
 */
class MediaStoreDownloadWriter(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : DownloadWriter {

    /**
     * [folderPath] mirrors the drive folder, so downloading a folder recreates its tree; null when
     * the copy could not be written.
     */
    override fun write(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isOnPrimaryStorage(downloadBaseDirectory())) {
            writeThroughMediaStore(fileName, mimeType, folderPath, body)
        } else {
            writeDirectly(fileName, folderPath, body)
        }

    override fun openPartial(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        token: String?
    ): PartialDownload? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isOnPrimaryStorage(downloadBaseDirectory())) {
            MediaStorePartialDownload.open(
                context.contentResolver,
                fileName,
                mimeType,
                relativePath(folderPath),
                token
            )
        } else {
            FilePartialDownload.open(directoryFor(folderPath), fileName, token)
        }
    }.onFailure { SafeLog.w(TAG, "Could not open a partial download", it) }.getOrNull()

    override fun freeBytes(folderPath: String?): Long? = runCatching {
        StatFs(directoryFor(folderPath).absolutePath).availableBytes
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeThroughMediaStore(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(folderPath))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use(body) ?: error("No output stream")
        }.isSuccess

        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }

        return resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.toString()
    }

    private fun writeDirectly(
        fileName: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String? {
        val directory = directoryFor(folderPath)
        if (!directory.exists() && !directory.mkdirs()) return null

        val existingNames = directory.list()?.toSet().orEmpty()
        val target = File(directory, FileNameUtils.uniqueName(fileName) { it in existingNames })
        return runCatching {
            target.outputStream().buffered().use(body)
            target.absolutePath
        }.getOrElse {
            SafeLog.w(TAG, "Download write failed", it)
            target.delete()
            null
        }
    }

    /**
     * The selected directory is the parent of TeleDrive's own folder, matching the desktop
     * behaviour. With no custom value, downloads remain in Downloads/TeleDrive.
     */
    private fun downloadBaseDirectory(): File {
        val configured = runBlocking { settingsRepository.preferences.first() }.downloadDirectory
            ?.takeIf { it.isNotBlank() }
        return configured?.let(::File)
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    private fun directoryFor(folderPath: String?): File {
        val root = File(downloadBaseDirectory(), FOLDER_NAME)
        return folderPath
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?.fold(root) { parent, segment -> File(parent, FileNameUtils.sanitize(segment)) }
            ?: root
    }

    /**
     * MediaStore RELATIVE_PATH is relative to primary shared storage. The Android folder picker
     * starts there; folders on other mounted volumes use direct file I/O instead.
     */
    private fun relativePath(folderPath: String?): String {
        val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
        val basePath = downloadBaseDirectory().canonicalFile.path
        val rootPath = storageRoot.path.trimEnd(File.separatorChar)
        require(
            basePath == rootPath || basePath.startsWith(rootPath + File.separator)
        ) { "Selected download directory is outside primary shared storage" }

        val baseRelative = basePath.removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        val segments = buildList {
            if (baseRelative.isNotBlank()) add(baseRelative)
            add(FOLDER_NAME)
            folderPath?.split('/')?.filter { it.isNotBlank() }
                ?.mapTo(this) { FileNameUtils.sanitize(it) }
        }
        return segments.joinToString("/")
    }

    private fun isOnPrimaryStorage(directory: File): Boolean = runCatching {
        val rootPath = Environment.getExternalStorageDirectory().canonicalFile.path
            .trimEnd(File.separatorChar)
        val path = directory.canonicalFile.path
        path == rootPath || path.startsWith(rootPath + File.separator)
    }.getOrDefault(false)

    private companion object {
        const val TAG = "DownloadWriter"
        const val FOLDER_NAME = "TeleDrive"
    }
}
