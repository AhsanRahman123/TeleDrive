package com.drdisagree.teledrive.desktop.files

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.ImportSource
import com.drdisagree.teledrive.core.files.expandDirectory
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.ImportedFile
import java.io.File

/**
 * Desktop selections are copied into TeleDrive-owned storage. The user's original source is never
 * registered as a disposable local copy, so Free up space cannot delete the source they selected.
 */
class DesktopFileImporter(
    private val storagePaths: AppStoragePaths
) : FileImporter {

    override fun import(reference: String): ImportedFile? {
        val source = File(reference)
        if (!source.isFile || !source.canRead()) return null

        val targetDirectory = File(storagePaths.filesDir, LOCAL_COPY_DIR)
            .apply { if (!exists()) mkdirs() }
        if (!targetDirectory.isDirectory) return null

        val displayName = FileNameUtils.sanitize(source.name)
        val existingNames = targetDirectory.list()?.toSet().orEmpty()
        val target = File(
            targetDirectory,
            FileNameUtils.uniqueName(displayName) { it in existingNames }
        )

        return runCatching {
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (target.length() != source.length()) {
                error("Imported copy size does not match the selected source")
            }
            ImportedFile(
                path = target.absolutePath,
                name = displayName,
                sizeBytes = target.length()
            )
        }.getOrElse {
            target.delete()
            null
        }
    }

    override fun discard(imported: ImportedFile) {
        if (isManagedImport(imported.path)) File(imported.path).delete()
    }

    override fun expand(reference: String): List<ImportSource> =
        expandDirectory(reference) ?: listOf(ImportSource(reference, ""))

    // Managed local copies are permanent until the user invokes Free up space.
    override fun isStaged(path: String): Boolean =
        File(path).parentFile == File(storagePaths.filesDir, LEGACY_IMPORT_DIR)

    override fun sweepOrphans(referencedPaths: Set<String>) {
        val legacyImports = File(storagePaths.filesDir, LEGACY_IMPORT_DIR).listFiles() ?: return
        legacyImports.filter { it.isFile && it.absolutePath !in referencedPaths }
            .forEach { it.delete() }
    }

    private fun isManagedImport(path: String): Boolean {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val root = runCatching {
            File(storagePaths.filesDir, LOCAL_COPY_DIR).canonicalFile
        }.getOrNull() ?: return false
        return target.toPath().startsWith(root.toPath())
    }

    private companion object {
        const val LOCAL_COPY_DIR = "local-copies"
        const val LEGACY_IMPORT_DIR = "imports"
    }
}
