package com.drdisagree.teledrive.core.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.drdisagree.teledrive.core.common.SafeLog
import java.io.File

/**
 * Every import is copied into app-owned local storage. A selected external file or SAF source is
 * never recorded as a disposable local copy. Free up space only removes this managed copy.
 */
class AndroidFileImporter(
    private val context: Context
) : FileImporter {

    override fun import(reference: String): ImportedFile? {
        val direct = runCatching { File(reference) }.getOrNull()?.takeIf { it.isFile }
        if (direct != null) return copyIntoManagedStorage(direct)
        return import(reference.toUri())
    }

    private fun import(uri: Uri): ImportedFile? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val direct = uri.path?.let(::File)?.takeIf { it.isFile }
            if (direct != null) return copyIntoManagedStorage(direct)
        }
        readablePath(uri)?.let { source ->
            return copyIntoManagedStorage(source)
        }

        val metadata = queryMetadata(uri) ?: run {
            SafeLog.w(TAG, "Import failed: the provider returned no metadata")
            return null
        }
        val displayName = FileNameUtils.sanitize(metadata.first)
        val target = newManagedTarget(displayName) ?: return null

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("The provider refused to open the selected document")
            if (metadata.second > 0 && target.length() != metadata.second) {
                error("Imported copy size does not match the provider metadata")
            }
            ImportedFile(target.absolutePath, displayName, target.length())
        }.getOrElse {
            SafeLog.w(TAG, "Import failed", it)
            target.delete()
            null
        }
    }

    private fun copyIntoManagedStorage(source: File): ImportedFile? {
        val displayName = FileNameUtils.sanitize(source.name)
        val target = newManagedTarget(displayName) ?: return null
        return runCatching {
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (target.length() != source.length()) {
                error("Imported copy size does not match the selected source")
            }
            ImportedFile(target.absolutePath, displayName, target.length())
        }.getOrElse {
            SafeLog.w(TAG, "Import failed", it)
            target.delete()
            null
        }
    }

    private fun newManagedTarget(displayName: String): File? {
        val directory = File(context.filesDir, LOCAL_COPY_DIR)
        if (!directory.exists() && !directory.mkdirs()) return null
        val existingNames = directory.list()?.toSet().orEmpty()
        val name = FileNameUtils.uniqueName(displayName) { it in existingNames }
        return File(directory, name)
    }

    override fun discard(imported: ImportedFile) {
        if (isManagedCopy(imported.path)) File(imported.path).delete()
    }

    override fun expand(reference: String): List<ImportSource> =
        expandDirectory(reference) ?: listOf(ImportSource(reference, ""))

    // Only old temporary imports are staged. New local-copies remain available until the user
    // explicitly removes them with Free up space.
    override fun isStaged(path: String): Boolean =
        File(path).parentFile == File(context.filesDir, LEGACY_IMPORT_DIR)

    override fun sweepOrphans(referencedPaths: Set<String>) {
        val legacyImports = File(context.filesDir, LEGACY_IMPORT_DIR).listFiles() ?: return
        legacyImports.filter { it.isFile && it.absolutePath !in referencedPaths }
            .forEach { orphan ->
                SafeLog.d(TAG, "Dropping a legacy staged import of ${orphan.length()} bytes")
                orphan.delete()
            }
    }

    private fun isManagedCopy(path: String): Boolean {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val root = runCatching {
            File(context.filesDir, LOCAL_COPY_DIR).canonicalFile
        }.getOrNull() ?: return false
        return target.toPath().startsWith(root.toPath())
    }

    private fun readablePath(uri: Uri): File? {
        val candidate = DocumentTreePaths.documentToFilePath(context, uri) ?: return null
        val file = File(candidate)
        return file.takeIf { it.isFile && it.canRead() && it.length() > 0 }
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long>? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            (name ?: uri.lastPathSegment ?: "file") to size
        }

    companion object {
        private const val TAG = "FileImporter"
        private const val LOCAL_COPY_DIR = "local-copies"
        private const val LEGACY_IMPORT_DIR = "imports"
    }
}
