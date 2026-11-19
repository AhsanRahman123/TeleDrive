package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.StorageExplorerDeleteResult
import com.drdisagree.teledrive.domain.repository.StorageExplorerEntry
import com.drdisagree.teledrive.domain.repository.StorageExplorerRepository
import com.drdisagree.teledrive.domain.repository.StorageExplorerRoot
import com.drdisagree.teledrive.domain.repository.StorageExplorerSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class StorageExplorerRepositoryImpl(
    private val storagePaths: AppStoragePaths,
    private val settingsRepository: SettingsRepository,
    private val cacheRepository: CacheRepository,
    private val telegramClient: TelegramClient
) : StorageExplorerRepository {

    private enum class RootKind {
        APP_DATA,
        EXTERNAL_FILES,
        EXTERNAL_CACHE,
        DOWNLOADS,
        APP_PACKAGE
    }

    private data class RootSpec(
        val id: String,
        val name: String,
        val file: File,
        val kind: RootKind,
        val isDirectory: Boolean
    )

    private data class DirectorySize(
        var bytes: Long = 0L,
        var fileCount: Int = 0
    )

    private data class ScanRootResult(
        val root: StorageExplorerRoot,
        val entries: List<StorageExplorerEntry>
    )

    override suspend fun scan(): StorageExplorerSnapshot {
        val preferences = settingsRepository.preferences.first()
        return withContext(Dispatchers.IO) {
            scanOnIo(preferences.downloadDirectory)
        }
    }

    private fun scanOnIo(downloadDirectory: String?): StorageExplorerSnapshot {
        val downloadRoot = downloadRoot(downloadDirectory)
        val cacheRoots = cacheDeleteRoots()
        val safeRoots = safeDeleteRoots(downloadRoot)
        val specs = buildRootSpecs(downloadRoot)
        val roots = mutableListOf<StorageExplorerRoot>()
        val entries = mutableListOf<StorageExplorerEntry>()
        val warnings = mutableListOf<String>()

        for (spec in specs) {
            val scanned = scanRoot(
                spec = spec,
                downloadRoot = downloadRoot,
                cacheRoots = cacheRoots,
                safeRoots = safeRoots,
                warnings = warnings
            )
            roots += scanned.root
            entries += scanned.entries
        }

        return StorageExplorerSnapshot(
            scannedAt = System.currentTimeMillis(),
            totalBytes = roots.sumOf { it.sizeBytes },
            fileCount = roots.sumOf { it.fileCount },
            directoryCount = roots.sumOf { it.directoryCount },
            roots = roots.sortedWith(
                compareByDescending<StorageExplorerRoot> { it.sizeBytes }
                    .thenBy { it.name.lowercase() }
            ),
            entries = entries,
            warnings = warnings.distinct().take(MAX_WARNINGS)
        )
    }

    /**
     * The private app data directory contains files/, cache/, databases/ and shared_prefs/.
     * External app-specific folders and public Pranto Drive downloads are added only when they do
     * not overlap a directory already being scanned. This prevents double counting.
     */
    private fun buildRootSpecs(downloadRoot: File?): List<RootSpec> {
        val candidates = buildList {
            add(
                RootSpec(
                    id = APP_DATA_ROOT_ID,
                    name = "App data",
                    file = storagePaths.dataDir,
                    kind = RootKind.APP_DATA,
                    isDirectory = true
                )
            )
            storagePaths.externalFilesDirs.forEachIndexed { index, directory ->
                val label = if (index == 0) {
                    "External app files"
                } else {
                    "External app files (" + (index + 1) + ")"
                }
                add(
                    RootSpec(
                        id = "EXTERNAL_FILES_" + index,
                        name = label,
                        file = directory,
                        kind = RootKind.EXTERNAL_FILES,
                        isDirectory = true
                    )
                )
            }
            storagePaths.externalCacheDirs.forEachIndexed { index, directory ->
                val label = if (index == 0) {
                    "External app cache"
                } else {
                    "External app cache (" + (index + 1) + ")"
                }
                add(
                    RootSpec(
                        id = "EXTERNAL_CACHE_" + index,
                        name = label,
                        file = directory,
                        kind = RootKind.EXTERNAL_CACHE,
                        isDirectory = true
                    )
                )
            }
            downloadRoot?.let { directory ->
                add(
                    RootSpec(
                        id = DOWNLOADS_ROOT_ID,
                        name = "Pranto Drive downloads",
                        file = directory,
                        kind = RootKind.DOWNLOADS,
                        isDirectory = true
                    )
                )
            }
            storagePaths.appCodeFiles.forEachIndexed { index, file ->
                val label = if (index == 0) {
                    "Installed app package"
                } else {
                    "Installed app split " + (index + 1)
                }
                add(
                    RootSpec(
                        id = "APP_PACKAGE_" + index,
                        name = label,
                        file = file,
                        kind = RootKind.APP_PACKAGE,
                        isDirectory = false
                    )
                )
            }
        }

        val directorySpecs = mutableListOf<RootSpec>()
        candidates.filter { it.isDirectory }
            .sortedBy { pathDepth(it.file) }
            .forEach { spec ->
                val candidate = canonical(spec.file)
                val alreadyCovered = directorySpecs.any { existing ->
                    isWithin(candidate, canonical(existing.file))
                }
                if (!alreadyCovered) directorySpecs += spec.copy(file = candidate)
            }

        val fileSpecs = candidates.filterNot { it.isDirectory }.mapNotNull { spec ->
            val candidate = canonical(spec.file)
            if (!candidate.isFile) return@mapNotNull null
            val alreadyCovered = directorySpecs.any { existing ->
                isWithin(candidate, canonical(existing.file))
            }
            if (alreadyCovered) null else spec.copy(file = candidate)
        }
        return directorySpecs + fileSpecs
    }

    private fun scanRoot(
        spec: RootSpec,
        downloadRoot: File?,
        cacheRoots: List<File>,
        safeRoots: List<File>,
        warnings: MutableList<String>
    ): ScanRootResult {
        val root = canonical(spec.file)
        val protection = rootProtectionReason(spec.kind)

        if (!root.exists()) {
            return ScanRootResult(
                root = StorageExplorerRoot(
                    id = spec.id,
                    name = spec.name,
                    path = root.path,
                    category = rootCategory(spec.kind),
                    exists = false,
                    isDirectory = spec.isDirectory,
                    sizeBytes = 0L,
                    fileCount = 0,
                    directoryCount = 0,
                    canDeleteContents = spec.isDirectory && isDeletable(root, safeRoots),
                    protectionReason = protection
                ),
                entries = emptyList()
            )
        }

        if (!spec.isDirectory || root.isFile) {
            val item = StorageExplorerEntry(
                path = root.path,
                rootId = spec.id,
                parentPath = root.parentFile?.path ?: root.path,
                name = root.name,
                category = CATEGORY_APP_PACKAGE,
                sizeBytes = runCatching { Files.size(root.toPath()) }.getOrDefault(root.length()).coerceAtLeast(0L),
                lastModifiedAt = root.lastModified(),
                isDirectory = false,
                canDelete = false,
                protectionReason = "Installed APK files are managed by Android."
            )
            return ScanRootResult(
                root = StorageExplorerRoot(
                    id = spec.id,
                    name = spec.name,
                    path = root.path,
                    category = CATEGORY_APP_PACKAGE,
                    exists = true,
                    isDirectory = false,
                    sizeBytes = item.sizeBytes,
                    fileCount = 1,
                    directoryCount = 0,
                    canDeleteContents = false,
                    protectionReason = item.protectionReason
                ),
                entries = listOf(item)
            )
        }

        val directoryFiles = mutableListOf<File>()
        val regularFiles = mutableListOf<File>()
        var unreadableCount = 0

        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        if (dir != root.toPath()) directoryFiles += dir.toFile()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        // The default file-tree walk does not follow symbolic links.
                        if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                            regularFiles += file.toFile()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        error: java.io.IOException
                    ): FileVisitResult {
                        unreadableCount++
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        } catch (error: Exception) {
            warnings += "Could not completely scan " + root.path + ": " +
                (error.message ?: "access error")
        }

        if (unreadableCount > 0) {
            warnings += unreadableCount.toString() +
                " unreadable path(s) under " + root.path + " were skipped."
        }

        val fileEntries = regularFiles.map { file ->
            val normalized = canonical(file)
            val category = categoryFor(normalized, spec.kind, downloadRoot, cacheRoots)
            val safe = isDeletable(normalized, safeRoots)
            StorageExplorerEntry(
                path = normalized.path,
                rootId = spec.id,
                parentPath = normalized.parentFile?.path ?: root.path,
                name = normalized.name,
                category = category,
                sizeBytes = runCatching { Files.size(normalized.toPath()) }
                    .getOrDefault(normalized.length()).coerceAtLeast(0L),
                lastModifiedAt = normalized.lastModified(),
                isDirectory = false,
                canDelete = safe,
                protectionReason = if (safe) null else protectionReason(category)
            )
        }

        // Aggregate parent sizes in memory from the file list; no repeated recursive walks.
        val directorySizes = mutableMapOf<String, DirectorySize>()
        for (entry in fileEntries) {
            var parent = File(entry.path).parentFile
            var guard = 0
            while (parent != null && isWithin(parent, root) && guard++ < MAX_DIRECTORY_DEPTH) {
                val key = parent.path
                val aggregate = directorySizes.getOrPut(key) { DirectorySize() }
                aggregate.bytes += entry.sizeBytes
                aggregate.fileCount++
                if (key == root.path) break
                parent = parent.parentFile
            }
        }

        val directoryEntries = directoryFiles.map { directory ->
            val normalized = canonical(directory)
            val category = categoryFor(normalized, spec.kind, downloadRoot, cacheRoots)
            val stats = directorySizes[normalized.path] ?: DirectorySize()
            val safe = isDeletable(normalized, safeRoots)
            StorageExplorerEntry(
                path = normalized.path,
                rootId = spec.id,
                parentPath = normalized.parentFile?.path ?: root.path,
                name = normalized.name,
                category = category,
                sizeBytes = stats.bytes,
                lastModifiedAt = normalized.lastModified(),
                isDirectory = true,
                fileCount = stats.fileCount,
                canDelete = safe,
                protectionReason = if (safe) null else protectionReason(category)
            )
        }

        val rootBytes = fileEntries.sumOf { it.sizeBytes }
        return ScanRootResult(
            root = StorageExplorerRoot(
                id = spec.id,
                name = spec.name,
                path = root.path,
                category = rootCategory(spec.kind),
                exists = true,
                isDirectory = true,
                sizeBytes = rootBytes,
                fileCount = fileEntries.size,
                directoryCount = directoryEntries.size,
                canDeleteContents = isDeletable(root, safeRoots),
                protectionReason = protection
            ),
            entries = directoryEntries + fileEntries
        )
    }

    override suspend fun delete(paths: List<String>): StorageExplorerDeleteResult {
        val preferences = settingsRepository.preferences.first()
        return withContext(Dispatchers.IO) {
            val downloadRoot = downloadRoot(preferences.downloadDirectory)
            val safeRoots = safeDeleteRoots(downloadRoot)
            val requested = paths.distinct().mapNotNull { path ->
                runCatching { canonical(File(path)) }.getOrNull()
            }.filter { candidate ->
                candidate.exists() && isDeletable(candidate, safeRoots)
            }

            val topLevel = requested.filterNot { candidate ->
                requested.any { parent ->
                    parent != candidate && parent.isDirectory && isWithin(candidate, parent)
                }
            }
            if (topLevel.isEmpty()) {
                return@withContext StorageExplorerDeleteResult(
                    skippedItems = paths.distinct().size
                )
            }

            val filesToDelete = linkedMapOf<String, File>()
            val directoriesToDelete = linkedMapOf<String, File>()
            for (candidate in topLevel) {
                if (candidate.isFile) {
                    filesToDelete[candidate.path] = candidate
                } else if (candidate.isDirectory) {
                    collectSafeContents(candidate, safeRoots, filesToDelete, directoriesToDelete)
                    directoriesToDelete[candidate.path] = candidate
                }
            }

            val sizesBeforeDelete = filesToDelete.mapValues { (_, file) ->
                runCatching { file.length() }.getOrDefault(0L)
            }
            val cachePaths = filesToDelete.values
                .filter { file -> isWithinAny(file, cacheDeleteRoots()) }
                .map { it.path }
            if (cachePaths.isNotEmpty()) {
                // This removes matching DB rows and thumbnail index entries as well as files.
                cacheRepository.removeCacheEntries(cachePaths)
            }

            var deletedFiles = 0
            var deletedBytes = 0L
            val failed = mutableListOf<String>()
            for (file in filesToDelete.values) {
                val size = sizesBeforeDelete[file.path] ?: 0L
                val removed = !file.exists() ||
                    runCatching { file.delete() }.getOrDefault(false) ||
                    !file.exists()
                if (removed) {
                    deletedFiles++
                    deletedBytes += size
                } else {
                    failed += file.path
                }
            }

            directoriesToDelete.values
                .sortedByDescending { pathDepth(it) }
                .forEach { directory ->
                    if (directory.exists() && !runCatching { directory.delete() }.getOrDefault(false)) {
                        failed += directory.path
                    }
                }

            if (cachePaths.isNotEmpty()) cacheRepository.refreshStats()
            StorageExplorerDeleteResult(
                deletedFiles = deletedFiles,
                deletedBytes = deletedBytes,
                skippedItems = (paths.distinct().size - topLevel.size).coerceAtLeast(0),
                failedPaths = failed.distinct()
            )
        }
    }

    private fun collectSafeContents(
        directory: File,
        safeRoots: List<File>,
        files: MutableMap<String, File>,
        directories: MutableMap<String, File>
    ) {
        runCatching {
            Files.walkFileTree(
                directory.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        val candidate = canonical(dir.toFile())
                        if (isDeletable(candidate, safeRoots)) {
                            directories[candidate.path] = candidate
                            return FileVisitResult.CONTINUE
                        }
                        return FileVisitResult.SKIP_SUBTREE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        if (attrs.isRegularFile && !attrs.isSymbolicLink) {
                            val candidate = canonical(file.toFile())
                            if (isDeletable(candidate, safeRoots)) {
                                files[candidate.path] = candidate
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }
    }

    override suspend fun clearTelegramDownloads(): Boolean {
        return try {
            telegramClient.clearDownloadedFiles()
            cacheRepository.refreshStats()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun categoryFor(
        file: File,
        kind: RootKind,
        downloadRoot: File?,
        cacheRoots: List<File>
    ): String {
        if (downloadRoot != null && isWithin(file, downloadRoot)) return CATEGORY_DOWNLOADS

        val tdlibRoot = File(storagePaths.filesDir, "tdlib")
        if (isWithin(file, File(tdlibRoot, "db"))) return CATEGORY_TELEGRAM_DATABASE
        if (isWithin(file, File(tdlibRoot, "files"))) return CATEGORY_TELEGRAM_DOWNLOADS
        if (isWithin(file, File(storagePaths.filesDir, "local-copies"))) return CATEGORY_LOCAL_COPIES
        if (isWithinAny(file, cacheRoots)) return CATEGORY_CACHE
        if (isWithin(file, File(storagePaths.dataDir, "databases"))) return CATEGORY_DATABASE
        if (isWithin(file, File(storagePaths.dataDir, "shared_prefs"))) return CATEGORY_SETTINGS

        return when (kind) {
            RootKind.APP_DATA -> CATEGORY_APP_DATA
            RootKind.EXTERNAL_FILES -> CATEGORY_EXTERNAL_FILES
            RootKind.EXTERNAL_CACHE -> CATEGORY_CACHE
            RootKind.DOWNLOADS -> CATEGORY_DOWNLOADS
            RootKind.APP_PACKAGE -> CATEGORY_APP_PACKAGE
        }
    }

    private fun protectionReason(category: String): String = when (category) {
        CATEGORY_TELEGRAM_DATABASE ->
            "Telegram's local database is protected. Deleting it can reset local session and message state."
        CATEGORY_TELEGRAM_DOWNLOADS ->
            "TDLib manages these downloaded files. Use the Telegram downloads cleanup action instead of deleting them directly."
        CATEGORY_LOCAL_COPIES ->
            "Use Free up space to verify the Telegram backup and preserve files marked Available Offline."
        CATEGORY_DATABASE ->
            "Pranto Drive's database is protected. Deleting it can break the app's local index."
        CATEGORY_SETTINGS ->
            "App preferences and credentials are protected."
        CATEGORY_APP_PACKAGE ->
            "Installed application files are managed by Android."
        CATEGORY_EXTERNAL_FILES ->
            "App-specific external data is protected from bulk cleanup."
        else ->
            "This app-managed data is protected from direct deletion."
    }

    private fun rootProtectionReason(kind: RootKind): String? = when (kind) {
        RootKind.APP_DATA ->
            "Shows all private app data. Only recognised cache folders can be deleted."
        RootKind.EXTERNAL_FILES ->
            "App-specific external files are listed but protected."
        RootKind.EXTERNAL_CACHE -> null
        RootKind.DOWNLOADS -> null
        RootKind.APP_PACKAGE ->
            "Installed package files are managed by Android."
    }

    private fun rootCategory(kind: RootKind): String = when (kind) {
        RootKind.APP_DATA -> CATEGORY_APP_DATA
        RootKind.EXTERNAL_FILES -> CATEGORY_EXTERNAL_FILES
        RootKind.EXTERNAL_CACHE -> CATEGORY_CACHE
        RootKind.DOWNLOADS -> CATEGORY_DOWNLOADS
        RootKind.APP_PACKAGE -> CATEGORY_APP_PACKAGE
    }

    private fun downloadRoot(downloadDirectory: String?): File? {
        val base = downloadDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: storagePaths.externalStorageRoot?.let { File(it, "Download") }
        return base?.let { File(it, "TeleDrive") }?.let(::canonical)
    }

    private fun safeDeleteRoots(downloadRoot: File?): List<File> = buildList {
        addAll(cacheDeleteRoots())
        downloadRoot?.let(::add)
    }.map(::canonical).distinctBy { it.path }

    private fun cacheDeleteRoots(): List<File> = buildList {
        add(storagePaths.cacheDir)
        storagePaths.codeCacheDir?.let(::add)
        addAll(storagePaths.externalCacheDirs)
    }.map(::canonical).distinctBy { it.path }

    private fun isDeletable(path: File, safeRoots: List<File>): Boolean =
        safeRoots.any { root -> isWithin(path, root) }

    private fun isWithinAny(path: File, roots: List<File>): Boolean =
        roots.any { root -> isWithin(path, root) }

    private fun isWithin(path: File, root: File): Boolean {
        val candidatePath = runCatching { canonical(path).toPath() }.getOrNull() ?: return false
        val rootPath = runCatching { canonical(root).toPath() }.getOrNull() ?: return false
        return candidatePath.startsWith(rootPath)
    }

    private fun canonical(file: File): File =
        runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun pathDepth(file: File): Int =
        runCatching { canonical(file).toPath().nameCount }.getOrDefault(0)

    private companion object {
        const val APP_DATA_ROOT_ID = "APP_DATA"
        const val DOWNLOADS_ROOT_ID = "DOWNLOADS"
        const val MAX_DIRECTORY_DEPTH = 128
        const val MAX_WARNINGS = 8

        const val CATEGORY_APP_DATA = "APP_DATA"
        const val CATEGORY_CACHE = "CACHE"
        const val CATEGORY_TELEGRAM_DATABASE = "TELEGRAM_DATABASE"
        const val CATEGORY_TELEGRAM_DOWNLOADS = "TELEGRAM_DOWNLOADS"
        const val CATEGORY_LOCAL_COPIES = "LOCAL_COPIES"
        const val CATEGORY_DOWNLOADS = "DOWNLOADS"
        const val CATEGORY_EXTERNAL_FILES = "EXTERNAL_FILES"
        const val CATEGORY_DATABASE = "DATABASE"
        const val CATEGORY_SETTINGS = "SETTINGS"
        const val CATEGORY_APP_PACKAGE = "APP_PACKAGE"
    }
}
