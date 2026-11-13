package com.drdisagree.teledrive.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.Hashing
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.Markdown
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.files.NoteStore
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramDocumentCopyRequest
import com.drdisagree.teledrive.core.telegram.TelegramDocumentCopyResult
import com.drdisagree.teledrive.data.local.FileQueryBuilder
import com.drdisagree.teledrive.data.local.OffsetRefreshPagingSource
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.mapper.toDomain
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.FolderCopyResult
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.LinkMetadata
import com.drdisagree.teledrive.domain.model.MediaAlbum
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.StorageSlice
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.FolderCycleCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.File
import java.net.URI
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FileRepositoryImpl(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val publishScheduler: PublishScheduler,
    private val localCopyDeleter: LocalCopyDeleter,
    private val telegramClient: TelegramClient,
    private val manifestCodec: ManifestCodec,
    private val folderPathResolver: FolderPathResolver,
    private val activeChannel: ActiveChannel,
    private val noteStore: NoteStore,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val settingsRepository: SettingsRepository,
    private val fileImporter: FileImporter,
    private val storagePaths: AppStoragePaths
) : FileRepository {

    override suspend fun resolveImportFolder(relativePath: String, parentId: String?): String? =
        if (relativePath.isBlank()) {
            parentId
        } else {
            folderPathResolver.resolveOrCreate(relativePath, startParentId = parentId)
        }

    override suspend fun sweepImportOrphans() {
        fileDao.reclaimableLocalCopies()
            .filter { fileImporter.isStaged(it.localPath) }
            .forEach { ref ->
                File(ref.localPath).delete()
                fileDao.setLocalPath(ref.id, null)
            }
        fileImporter.sweepOrphans(fileDao.allLocalPaths().toSet())
    }

    override fun pagedFiles(spec: FileQuerySpec): Flow<PagingData<DriveFile>> =
        activeChannel.observe().flatMapLatest { chatId ->
            Pager(
                config = PagingConfig(
                    pageSize = 60,
                    prefetchDistance = 120,
                    enablePlaceholders = false
                ),
                pagingSourceFactory = {
                    OffsetRefreshPagingSource(
                        fileDao.pagingSource(FileQueryBuilder.build(spec.copy(chatId = chatId)))
                    )
                }
            ).flow.map { paging -> paging.map { it.toDomain() } }
        }

    override fun observeFiles(spec: FileQuerySpec): Flow<List<DriveFile>> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeList(FileQueryBuilder.build(spec.copy(chatId = chatId)))
                .map { list -> list.map { it.toDomain() } }
        }

    override suspend fun fileIdsInTree(folderId: String): List<String> {
        val ids = mutableListOf<String>()
        var frontier = listOf(folderId)
        var guard = 0
        val chatId = activeChannel.id()
        while (frontier.isNotEmpty() && guard++ < MAX_FOLDER_DEPTH) {
            for (id in frontier) {
                ids += fileDao.filesInFolder(id, chatId).map { it.id }
            }
            frontier = frontier.flatMap { parent ->
                folderDao.childrenOf(parent, chatId).map { it.id }
            }
        }
        return ids
    }

    override suspend fun fileIds(spec: FileQuerySpec): List<String> =
        fileDao.idList(FileQueryBuilder.buildIds(spec.copy(chatId = activeChannel.id())))

    override fun observeFile(id: String): Flow<DriveFile?> =
        fileDao.observeById(id).map { it?.toDomain() }

    override suspend fun getFile(id: String): DriveFile? = fileDao.byId(id)?.toDomain()

    override suspend fun getFiles(ids: List<String>): List<DriveFile> =
        fileDao.byIds(ids).map { it.toDomain() }

    override fun observeRecent(limit: Int): Flow<List<DriveFile>> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeRecent(limit, chatId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeAlbums(
        showHidden: Boolean,
        showArchived: Boolean
    ): Flow<List<MediaAlbum>> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeAlbumsScoped(showHidden, showArchived, chatId).map { list ->
                list.map { it.toDomain() }
            }
        }

    override fun observeFolders(
        parentId: String?,
        showHidden: Boolean,
        showArchived: Boolean,
        sortField: FileSortField,
        sortDirection: SortDirection
    ): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeChildrenWithCountsScoped(parentId, chatId, showHidden, showArchived)
                .map { list -> list.map { it.toDomain() }.sortedBy(sortField, sortDirection) }
        }

    /**
     * Folders carry no size, type or backup state, so those fields fall back to
     * the name while still honouring the chosen direction.
     */
    private fun List<DriveFolder>.sortedBy(
        field: FileSortField,
        direction: SortDirection
    ): List<DriveFolder> {
        val ordered = when (field) {
            FileSortField.DATE_MODIFIED -> sortedBy { it.modifiedAt }
            FileSortField.DATE_ADDED -> sortedBy { it.createdAt }
            FileSortField.SIZE -> sortedBy { it.fileCount + it.folderCount }
            else -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        return if (direction == SortDirection.ASCENDING) ordered else ordered.reversed()
    }

    override fun searchFolders(
        nameQuery: String,
        showHidden: Boolean,
        showArchived: Boolean
    ): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeMatchingWithCountsScoped(
                pattern = "%${escapeLike(nameQuery)}%",
                chatId = chatId,
                showHidden = showHidden,
                showArchived = showArchived,
                limit = FOLDER_SEARCH_LIMIT
            ).map { list -> list.map { it.toDomain() } }
        }

    override fun observeStorageByCategory(): Flow<List<StorageSlice>> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeUsageByCategory(chatId).map { rows ->
                rows.map { StorageSlice(it.category, it.fileCount, it.totalBytes) }
            }
        }

    override fun observeFolder(id: String): Flow<DriveFolder?> =
        folderDao.observeById(id).map { it?.toDomain() }

    override suspend fun getFolder(id: String): DriveFolder? = folderDao.byId(id)?.toDomain()

    override fun observeFavoriteFolders(): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeFavorites(chatId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeAvailableOfflineFolders(): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeAvailableOffline(chatId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeAvailableOfflineMissingIds(): Flow<List<String>> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeAvailableOfflineMissingIds(chatId)
        }

    override fun observeArchivedFolders(): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeArchived(chatId).map { list -> list.map { it.toDomain() } }
        }

    override fun observeHiddenFolders(): Flow<List<DriveFolder>> =
        activeChannel.observe().flatMapLatest { chatId ->
            folderDao.observeHidden(chatId).map { list -> list.map { it.toDomain() } }
        }

    override suspend fun folderNamesIn(parentId: String?): List<String> =
        folderDao.namesIn(parentId, activeChannel.id())

    override suspend fun createFolder(parentId: String?, name: String): AppResult<DriveFolder> {
        val sanitized = FileNameUtils.sanitize(name)
        val siblings = folderDao.namesIn(parentId, activeChannel.id())
        if (siblings.any { it.equals(sanitized, ignoreCase = true) }) {
            return AppResult.Failure(AppError.FolderNameTaken)
        }
        val now = System.currentTimeMillis()
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            chatId = activeChannel.id(),
            parentId = parentId,
            name = sanitized,
            createdAt = now,
            modifiedAt = now,
            changedAt = now
        )
        folderDao.upsert(folder)
        markFolderStateDirty()
        return AppResult.Success(folder.toDomain())
    }

    override suspend fun renameFile(id: String, newName: String): AppResult<Unit> {
        val file = fileDao.byId(id) ?: return AppResult.Failure(AppError.NotFound)
        val sanitized = FileNameUtils.sanitize(newName)
        val siblingNames = fileDao.namesInFolderExcluding(file.folderId, id)
        val unique = FileNameUtils.uniqueName(sanitized) { candidate ->
            siblingNames.any { it.equals(candidate, ignoreCase = true) }
        }
        if (unique == file.name) return AppResult.Success(Unit)
        val now = System.currentTimeMillis()
        fileDao.rename(id, unique, now)

        file.localPath?.let { path ->
            val localFile = File(path)
            if (localFile.exists()) {
                val renamed = File(localFile.parentFile, unique)
                if (localFile.renameTo(renamed)) {
                    fileDao.setLocalPath(id, renamed.absolutePath)
                }
            }
        }
        markFilesDirty(listOf(id))
        return AppResult.Success(Unit)
    }

    override suspend fun renameFolder(id: String, newName: String): AppResult<Unit> {
        val folder = folderDao.byId(id) ?: return AppResult.Failure(AppError.NotFound)
        val sanitized = FileNameUtils.sanitize(newName)
        if (sanitized == folder.name) return AppResult.Success(Unit)
        val siblings = folderDao.namesInExcluding(
            folder.parentId,
            activeChannel.id(),
            id
        )
        if (siblings.any { it.equals(sanitized, ignoreCase = true) }) {
            return AppResult.Failure(AppError.FolderNameTaken)
        }
        folderDao.rename(id, sanitized, System.currentTimeMillis())
        markFolderStateDirty()
        markFolderContentsDirty(id)
        return AppResult.Success(Unit)
    }

    override suspend fun moveFiles(ids: List<String>, targetFolderId: String?): AppResult<Unit> {
        if (targetFolderId != null && folderDao.byId(targetFolderId) == null) {
            return AppResult.Failure(AppError.NotFound)
        }
        fileDao.move(ids, targetFolderId, System.currentTimeMillis())
        markFilesDirty(ids)
        return AppResult.Success(Unit)
    }

    override suspend fun copyFiles(
        ids: List<String>,
        targetFolderId: String?
    ): AppResult<Int> {
        if (ids.isEmpty()) return AppResult.Success(0)
        if (targetFolderId != null && folderDao.byId(targetFolderId) == null) {
            return AppResult.Failure(AppError.NotFound)
        }

        val existingNames = fileDao.namesInFolder(targetFolderId).toMutableList()
        val localCopies = mutableListOf<FileEntity>()
        val remoteCopies = mutableListOf<PendingRemoteCopy>()

        // Prepare records once, preserving the user's selection order and unique names.
        // A remote-backed source already has a durable Telegram copy; duplicating its local
        // cache is unnecessary and can block the UI on large files.
        for (source in fileDao.byIds(ids)) {
            val name = FileNameUtils.uniqueName(source.name) { candidate ->
                existingNames.any { it.equals(candidate, ignoreCase = true) }
            }
            existingNames += name

            val remoteFileId = source.remoteFileId
            val sourceChatId = source.chatId
            val isRemoteBacked = remoteFileId != null && sourceChatId != null
            val localPath = if (isRemoteBacked) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    duplicateLocalCopy(source.localPath, name)
                }
            }

            val now = System.currentTimeMillis()
            val copy = source.copy(
                id = UUID.randomUUID().toString(),
                folderId = targetFolderId,
                name = name,
                chatId = null,
                messageId = null,
                remoteFileId = null,
                remoteUniqueId = null,
                backupState = BackupState.NONE,
                localPath = localPath,
                trashedAt = null,
                preTrashFolderId = null,
                addedAt = now,
                modifiedAt = now
            )

            if (isRemoteBacked) {
                remoteCopies += PendingRemoteCopy(copy, remoteFileId!!, sourceChatId!!)
            } else {
                localCopies += copy
            }
        }

        // Local-only copies require no Telegram round trip. Persist them together.
        if (localCopies.isNotEmpty()) fileDao.upsertAll(localCopies)
        var copied = localCopies.size
        var lastError: AppError? = null

        // A document album carries up to ten independent document captions/manifests.
        // Batch by source chat because Telegram cannot mix channels in one album.
        for ((chatId, chatCopies) in remoteCopies.groupBy { it.sourceChatId }) {
            for (batch in chatCopies.chunked(10)) {
                if (batch.size == 1) {
                    val candidate = batch.single()
                    when (val result = copyRemote(candidate.copy, candidate.remoteFileId, chatId)) {
                        is AppResult.Success -> {
                            fileDao.upsert(result.value)
                            copied++
                        }
                        is AppResult.Failure -> lastError = result.error
                    }
                    continue
                }

                val outcomes = try {
                    telegramClient.copyDocuments(
                        chatId,
                        batch.map { candidate ->
                            remoteCopyRequest(candidate.copy, candidate.remoteFileId)
                        }
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val telegramError = error as? TelegramException
                        ?: TelegramException(500, error.message ?: "Telegram album copy failed")
                    List(batch.size) { TelegramDocumentCopyResult(error = telegramError) }
                }

                val successfulCopies = mutableListOf<FileEntity>()
                batch.forEachIndexed { index, candidate ->
                    val outcome = outcomes.getOrNull(index)
                    val document = outcome?.document
                    if (document != null) {
                        successfulCopies += withRemoteDocument(candidate.copy, document)
                    } else {
                        val error = outcome?.error
                            ?: TelegramException(500, "Telegram returned an incomplete album result")
                        lastError = if (error.isRateLimit) {
                            AppError.RateLimited(error.retryAfterSeconds ?: 0)
                        } else {
                            AppError.TelegramError(error.code, error.message)
                        }
                    }
                }

                // Persist successes even when another album item failed. A retry only needs
                // to create the copies that did not make it.
                if (successfulCopies.isNotEmpty()) {
                    fileDao.upsertAll(successfulCopies)
                    copied += successfulCopies.size
                }
            }
        }

        return if (copied == 0 && lastError != null) {
            AppResult.Failure(lastError)
        } else {
            AppResult.Success(copied)
        }
    }

    override suspend fun copyFolder(
        id: String,
        targetParentId: String?
    ): AppResult<FolderCopyResult> {
        val channelId = activeChannel.id()
        val root = folderDao.byId(id) ?: return AppResult.Failure(AppError.NotFound)
        if (root.trashedAt != null || (root.chatId != null && root.chatId != channelId)) {
            return AppResult.Failure(AppError.NotFound)
        }

        val targetParent = targetParentId?.let { folderDao.byId(it) }
        if (
            targetParentId != null &&
            (targetParent == null || targetParent.trashedAt != null ||
                (targetParent.chatId != null && targetParent.chatId != channelId))
        ) {
            return AppResult.Failure(AppError.NotFound)
        }
        if (FolderCycleCheck.createsCycle(id, targetParentId, ancestorsOf(targetParentId))) {
            return AppResult.Failure(AppError.FolderInsideItself)
        }

        // Read and validate the whole source tree before creating anything. This preserves empty
        // folders and avoids leaving a half-created tree if a cycle or excessive depth is found.
        val sourceFolders = mutableListOf<Pair<FolderEntity, Int>>()
        val pending = mutableListOf(root to 0)
        val visited = mutableSetOf<String>()
        var cursor = 0
        while (cursor < pending.size) {
            val (folder, depth) = pending[cursor++]
            if (!visited.add(folder.id)) {
                return AppResult.Failure(AppError.FolderInsideItself)
            }
            if (depth > MAX_FOLDER_DEPTH) {
                return AppResult.Failure(
                    AppError.UnsupportedOperation("Folder nesting exceeds the supported copy depth")
                )
            }
            sourceFolders += folder to depth
            val children = folderDao.childrenOf(folder.id, folder.chatId)
            if (children.isNotEmpty() && depth >= MAX_FOLDER_DEPTH) {
                return AppResult.Failure(
                    AppError.UnsupportedOperation("Folder nesting exceeds the supported copy depth")
                )
            }
            children.forEach { child -> pending += child to depth + 1 }
        }

        val copiedFolderIds = mutableMapOf<String, String>()
        val copiedFolders = mutableListOf<Pair<FolderEntity, String>>()
        for ((source, _) in sourceFolders) {
            val destinationParentId = if (source.id == id) {
                targetParentId
            } else {
                copiedFolderIds[source.parentId ?: return AppResult.Failure(AppError.NotFound)]
                    ?: return AppResult.Failure(AppError.NotFound)
            }
            val existingNames = folderDao.namesIn(destinationParentId, channelId)
            val copiedName = FileNameUtils.uniqueName(source.name) { candidate ->
                existingNames.any { it.equals(candidate, ignoreCase = true) }
            }
            val now = System.currentTimeMillis()
            val copy = source.copy(
                id = UUID.randomUUID().toString(),
                chatId = channelId,
                parentId = destinationParentId,
                name = copiedName,
                trashedAt = null,
                preTrashParentId = null,
                pendingPublish = true,
                modifiedAt = now,
                changedAt = now
            )
            folderDao.upsert(copy)
            copiedFolderIds[source.id] = copy.id
            copiedFolders += source to copy.id
        }

        var totalFiles = 0
        var copiedFiles = 0
        for ((source, destinationId) in copiedFolders) {
            val sourceFiles = fileDao.filesInFolder(source.id, channelId)
            totalFiles += sourceFiles.size
            if (sourceFiles.isEmpty()) continue
            when (val result = copyFiles(sourceFiles.map { it.id }, destinationId)) {
                is AppResult.Success -> copiedFiles += result.value
                is AppResult.Failure -> Unit
            }
        }

        // Publish the copied folder metadata and the file manifests with their new folder paths.
        markFolderStateDirty()
        copiedFolders.firstOrNull()?.second?.let { markFolderContentsDirty(it) }

        val copiedFolderCount = copiedFolders.size
        return AppResult.Success(
            FolderCopyResult(
                copiedItems = copiedFolderCount + copiedFiles,
                totalItems = copiedFolderCount + totalFiles
            )
        )
    }

    private data class PendingRemoteCopy(
        val copy: FileEntity,
        val remoteFileId: String,
        val sourceChatId: Long
    )

    private suspend fun remoteCopyRequest(
        copy: FileEntity,
        remoteFileId: String
    ): TelegramDocumentCopyRequest {
        val manifest = RemoteFileManifest(
            fileId = copy.id,
            name = copy.name,
            folderPath = folderPathResolver.pathOf(copy.folderId),
            folderId = copy.folderId,
            mimeType = copy.mimeType,
            sizeBytes = copy.sizeBytes,
            contentHash = copy.contentHash,
            hidden = copy.isHidden,
            archived = copy.isArchived,
            favorite = copy.isFavorite,
            encrypted = copy.isEncrypted,
            createdAt = copy.createdAt,
            modifiedAt = copy.modifiedAt,
            width = copy.width,
            height = copy.height,
            durationMs = copy.durationMs
        )
        return TelegramDocumentCopyRequest(
            remoteFileId = remoteFileId,
            fileName = if (copy.isEncrypted) "${copy.id}.tde" else copy.name,
            mimeType = if (copy.isEncrypted) "application/octet-stream" else copy.mimeType,
            caption = manifestCodec.encode(manifest, copy.isEncrypted)
        )
    }

    private fun withRemoteDocument(
        copy: FileEntity,
        document: RemoteDocument
    ): FileEntity = copy.copy(
        chatId = document.chatId,
        messageId = document.messageId,
        remoteFileId = document.remoteFileId,
        remoteUniqueId = document.uniqueFileId,
        backupState = BackupState.BACKED_UP
    )

    private suspend fun copyRemote(
        copy: FileEntity,
        remoteFileId: String,
        chatId: Long
    ): AppResult<FileEntity> = try {
        val request = remoteCopyRequest(copy, remoteFileId)
        val document = telegramClient.copyDocument(
            chatId = chatId,
            remoteFileId = request.remoteFileId,
            fileName = request.fileName,
            mimeType = request.mimeType,
            caption = request.caption
        )
        AppResult.Success(withRemoteDocument(copy, document))
    } catch (error: TelegramException) {
        AppResult.Failure(
            if (error.isRateLimit) AppError.RateLimited(error.retryAfterSeconds ?: 0)
            else AppError.TelegramError(error.code, error.message)
        )
    }

    private fun duplicateLocalCopy(sourcePath: String?, name: String): String? {
        val source = sourcePath?.let(::File)?.takeIf { it.exists() } ?: return null
        val target = File(source.parentFile, name)
        return runCatching { source.copyTo(target, overwrite = false).absolutePath }
            .getOrNull()
    }

    override suspend fun moveFolder(id: String, targetParentId: String?): AppResult<Unit> {
        if (FolderCycleCheck.createsCycle(id, targetParentId, ancestorsOf(targetParentId))) {
            return AppResult.Failure(
                AppError.FolderInsideItself
            )
        }
        folderDao.move(id, targetParentId, System.currentTimeMillis())
        markFolderStateDirty()
        markFolderContentsDirty(id)
        return AppResult.Success(Unit)
    }

    override fun observeReclaimableBytes(): Flow<Long> =
        activeChannel.observe().flatMapLatest { chatId ->
            fileDao.observeReclaimableBytes(chatId)
        }

    override suspend fun freeUpSpace(): AppResult<LocalCleanup> =
        deleteLocalCopy(fileDao.reclaimableFileIds(activeChannel.id()))

    private suspend fun ancestorsOf(folderId: String?): List<String> {
        val chain = mutableListOf<String>()
        var cursor = folderId
        var guard = 0
        while (cursor != null && guard++ < MAX_FOLDER_DEPTH) {
            chain += cursor
            cursor = folderDao.byId(cursor)?.parentId
        }
        return chain
    }

    /**
     * Marks rows by folder instead of walking files: a rename can cover thousands of captions
     * without blocking the caller.
     */
    private suspend fun markFolderContentsDirty(folderId: String) {
        var frontier = listOf(folderId)
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < MAX_FOLDER_DEPTH) {
            fileDao.markPendingPublishInFolders(frontier)
            frontier = frontier.flatMap { parent ->
                folderDao.childrenOf(parent, activeChannel.id()).map { it.id }
            }
        }
        publishScheduler.kick()
    }

    private suspend fun markFilesDirty(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQL_BATCH).forEach { fileDao.markPendingPublish(it) }
        publishScheduler.kick()
    }

    private suspend fun markFolderStateDirty() {
        folderDao.markPendingPublish()
        publishScheduler.kick()
    }

    override suspend fun setFilesAvailableOffline(ids: List<String>, available: Boolean) {
        fileDao.setAvailableOffline(ids, available)
    }

    override suspend fun setFolderAvailableOffline(id: String, available: Boolean) {
        folderDao.setAvailableOffline(id, available)
    }

    override suspend fun setFilesFavorite(ids: List<String>, favorite: Boolean) {
        fileDao.setFavorite(ids, favorite)
        markFilesDirty(ids)
    }

    override suspend fun setFilesHidden(ids: List<String>, hidden: Boolean) {
        fileDao.setHidden(ids, hidden)
        markFilesDirty(ids)
    }

    override suspend fun setFilesArchived(ids: List<String>, archived: Boolean) {
        fileDao.setArchived(ids, archived)
        markFilesDirty(ids)
    }

    override suspend fun setFolderFavorite(id: String, favorite: Boolean) {
        folderDao.setFavorite(id, favorite, System.currentTimeMillis())
        markFolderStateDirty()
    }

    override suspend fun setFolderHidden(id: String, hidden: Boolean) {
        folderDao.setHidden(id, hidden, System.currentTimeMillis())
        markFolderStateDirty()
    }

    override suspend fun setFolderArchived(id: String, archived: Boolean) {
        folderDao.setArchived(id, archived, System.currentTimeMillis())
        markFolderStateDirty()
    }

    override suspend fun importLocalFile(
        localPath: String,
        folderId: String?,
        displayName: String?
    ): AppResult<DriveFile> {
        val source = File(localPath)
        if (!source.exists() || !source.isFile) return AppResult.Failure(AppError.NotFound)
        fileDao.byLocalPath(localPath, activeChannel.id())?.takeIf { it.trashedAt == null }?.let {
            return AppResult.Success(it.toDomain())
        }
        reviveTrashedCopy(localPath) { folderId }?.let { return AppResult.Success(it) }

        val name = displayName ?: source.name
        val mime = MimeTypes.fromFileName(name)
        val now = System.currentTimeMillis()
        val entity = FileEntity(
            id = UUID.randomUUID().toString(),
            chatId = activeChannel.id(),
            folderId = folderId,
            name = name,
            sizeBytes = source.length(),
            mimeType = mime,
            category = FileCategory.fromMimeType(mime),
            localPath = source.absolutePath,
            contentHash = null,
            messageId = null,
            remoteFileId = null,
            remoteUniqueId = null,
            backupState = BackupState.NONE,
            createdAt = source.lastModified().takeIf { it > 0 } ?: now,
            modifiedAt = source.lastModified().takeIf { it > 0 } ?: now,
            addedAt = now
        )
        fileDao.upsert(entity)
        return AppResult.Success(entity.toDomain())
    }

    override fun observeFilesByIds(ids: List<String>): Flow<List<DriveFile>> =
        fileDao.observeByIds(ids).map { rows -> rows.map { it.toDomain() } }

    override suspend fun filesByIds(ids: List<String>): List<DriveFile> =
        fileDao.byIds(ids).map { it.toDomain() }

    override suspend fun reconcileLocalCopies(ids: List<String>) {
        fileDao.byIds(ids)
            .filter { entity -> entity.localPath?.let { !File(it).exists() } == true }
            .forEach { entity -> fileDao.setLocalPath(entity.id, null) }
    }

    override suspend fun saveNote(
        fileId: String?,
        folderId: String?,
        title: String,
        body: String
    ): AppResult<String> {
        val name = noteStore.fileName(title.ifBlank { fallbackTitle(body) })
        val existing = fileId?.let { fileDao.byId(it) }
        val file = noteStore.write(name, body, existing?.localPath)
        val now = System.currentTimeMillis()

        if (existing == null) {
            return when (val imported = importLocalFile(file.absolutePath, folderId, name)) {
                is AppResult.Success -> AppResult.Success(imported.value.id)
                is AppResult.Failure -> AppResult.Failure(imported.error)
            }
        }

        val chatId = existing.chatId
        val messageId = existing.messageId
        if (chatId != null && messageId != null) {
            runCatching { telegramClient.deleteMessages(chatId, listOf(messageId)) }
        }
        fileDao.upsert(
            existing.copy(
                name = name,
                localPath = file.absolutePath,
                sizeBytes = file.length(),
                contentHash = null,
                messageId = null,
                remoteFileId = null,
                remoteUniqueId = null,
                backupState = BackupState.NONE,
                modifiedAt = now
            )
        )
        return AppResult.Success(existing.id)
    }

    override suspend fun linkPreview(url: String): LinkMetadata? {
        if (!settingsRepository.preferences.first().linkPreviews) return null
        return telegramClient.linkPreview(url, withImage = true)
    }

    override suspend fun readNote(fileId: String): AppResult<String> {
        val entity = fileDao.byId(fileId) ?: return AppResult.Failure(AppError.NotFound)
        entity.localPath?.let(noteStore::read)?.let { return AppResult.Success(it) }
        val fetched = fetchNoteBody(entity) ?: return AppResult.Failure(AppError.NoRemoteCopy)
        return AppResult.Success(fetched)
    }

    private suspend fun fetchNoteBody(entity: FileEntity): String? {
        val remoteFileId = entity.remoteFileId ?: return null
        var downloaded: String? = null
        telegramClient.downloadDocument(remoteFileId).collect { event ->
            if (event is TelegramDownloadEvent.Completed) downloaded = event.localPath
        }
        val source = downloaded?.let(::File)?.takeIf { it.exists() } ?: return null

        val target = File(source.parentFile, "note-${entity.id}.tmp")
        val body = runCatching {
            if (entity.isEncrypted) {
                val key = wrappedKeyRepository.get(CryptoKeys.CONTENT) ?: return null
                source.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        streamCrypto.decryptStream(key, input, output)
                    }
                }
                target.readText()
            } else {
                source.readText()
            }
        }.getOrNull()
        target.delete()
        return body
    }

    private fun fallbackTitle(body: String): String {
        val trimmed = body.trim()
        val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val single = trimmed.lineSequence().count { it.isNotBlank() } == 1
        if (single && firstLine.startsWith("http", ignoreCase = true)) {
            runCatching { URI(firstLine).host }.getOrNull()
                ?.removePrefix("www.")
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return Markdown.plain(firstLine).take(NOTE_TITLE_LIMIT)
    }

    override suspend fun findDuplicate(localPath: String): DriveFile? {
        val source = File(localPath)
        if (!source.exists() || !source.isFile) return null

        // Rows without a remote copy do not count, so an interrupted upload can be retried.
        fileDao.byLocalPath(source.absolutePath, activeChannel.id())
            ?.takeIf { it.trashedAt == null && it.messageId != null }
            ?.let { return it.toDomain() }

        val candidates = fileDao.liveMatchesBySize(source.length(), activeChannel.id())
        if (candidates.isEmpty()) return null

        val localHash = Hashing.sha256(source) ?: return null
        return candidates.firstOrNull { it.contentHash == localHash }?.toDomain()
    }

    override suspend fun reviveTrashedCopy(
        localPath: String,
        folderId: suspend () -> String?
    ): DriveFile? {
        val source = File(localPath)
        if (!source.exists() || !source.isFile) return null

        val candidates = fileDao.trashedMatches(
            source.name,
            source.length(),
            activeChannel.id()
        )
        if (candidates.isEmpty()) return null

        val localHash = Hashing.sha256(source) ?: return null
        val match = candidates.firstOrNull { it.contentHash == localHash } ?: return null

        val now = System.currentTimeMillis()
        fileDao.restoreFromTrash(listOf(match.id))
        fileDao.move(listOf(match.id), folderId(), now)
        fileDao.setLocalPath(match.id, source.absolutePath)
        markFilesDirty(listOf(match.id))
        return fileDao.byId(match.id)?.toDomain()
    }

    override suspend fun deleteLocalCopy(ids: List<String>): AppResult<LocalCleanup> {
        val kept = fileDao.keptOfflineIds(ids).toSet()
        val candidates = fileDao.byIds(ids - kept).filter { entity ->
            entity.localPath != null &&
                    entity.messageId != null &&
                    entity.remoteFileId != null &&
                    entity.backupState == BackupState.BACKED_UP
        }
        if (candidates.isEmpty()) {
            return AppResult.Success(LocalCleanup(0, keptOffline = kept.size))
        }

        val managedRoots = managedLocalCopyRoots()
        // Keep track of whether TDLib's own cache is safe to remove separately from
        // the database-managed copy. Those two paths do not have to share a storage root.
        val safeCandidates = mutableListOf<Pair<FileEntity, Boolean>>()
        var keptUnmanaged = 0
        var unverifiedCount = 0
        var failedCount = 0

        // The database copy and TDLib cache can be stored in different locations.
        // The app copy is deletable only when its own path is managed. A separate
        // TDLib path outside managed roots must be preserved, not used to block deletion
        // of the safe app copy.
        for (entity in candidates) {
            val localPath = entity.localPath ?: continue
            val remoteFileId = entity.remoteFileId ?: continue
            if (!isWithinManagedRoots(localPath, managedRoots)) {
                keptUnmanaged++
                continue
            }
            val nativeFile = try {
                telegramClient.resolveFile(remoteFileId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (nativeFile == null) {
                unverifiedCount++
                continue
            }

            val nativePath = nativeFile.localPath
            val removeTdlibCache = nativePath.isNullOrBlank() ||
                    isWithinManagedRoots(nativePath, managedRoots)
            if (!removeTdlibCache) {
                keptUnmanaged++
            }
            safeCandidates += entity to removeTdlibCache
        }

        if (safeCandidates.isEmpty()) {
            return AppResult.Success(
                LocalCleanup(
                    0,
                    keptOffline = kept.size,
                    keptUnmanaged = keptUnmanaged,
                    unverifiedCount = unverifiedCount
                )
            )
        }

        val cleanup = localCopyDeleter.delete(safeCandidates.mapNotNull { it.first.localPath })
        if (cleanup.consentRequest != null) {
            return AppResult.Success(
                cleanup.copy(
                    keptOffline = kept.size,
                    keptUnmanaged = keptUnmanaged,
                    unverifiedCount = unverifiedCount
                )
            )
        }

        var cleared = 0
        for ((entity, removeTdlibCache) in safeCandidates) {
            val path = entity.localPath ?: continue
            if (localCopyDeleter.isGone(path)) {
                if (removeTdlibCache) {
                    val remoteFileId = entity.remoteFileId ?: continue
                    val cacheReleased = try {
                        telegramClient.removeManagedDownload(remoteFileId)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    if (!cacheReleased) {
                        failedCount++
                        continue
                    }
                }

                // The registered app copy is gone. Clear its database path even when a
                // separate external TDLib path was deliberately left untouched.
                fileDao.setLocalPath(entity.id, null)
                cleared++
            } else {
                failedCount++
            }
        }
        return AppResult.Success(
            LocalCleanup(
                cleared,
                keptOffline = kept.size,
                keptUnmanaged = keptUnmanaged,
                unverifiedCount = unverifiedCount,
                failedCount = failedCount
            )
        )
    }

    private suspend fun managedLocalCopyRoots(): List<File> {
        val preferences = settingsRepository.preferences.first()
        val defaultDownloads = File(System.getProperty("user.home").orEmpty(), "Downloads")
        val configuredDownloads = preferences.downloadDirectory?.let(::File) ?: defaultDownloads
        return buildList {
            add(File(storagePaths.filesDir, "local-copies"))
            add(File(storagePaths.filesDir, "imports")) // legacy staged import directory
            add(File(storagePaths.filesDir, "notes"))
            add(File(File(storagePaths.filesDir, "tdlib"), "files"))
            add(File(configuredDownloads, "TeleDrive"))
            storagePaths.externalStorageRoot?.let { add(File(it, "Download/TeleDrive")) }
        }
    }

    private fun isWithinManagedRoots(path: String, roots: List<File>): Boolean {
        val candidate = runCatching { File(path).canonicalFile.toPath() }.getOrNull()
            ?: return false
        return roots.any { root ->
            val normalizedRoot = runCatching { root.canonicalFile.toPath() }.getOrNull()
                ?: return@any false
            candidate.startsWith(normalizedRoot)
        }
    }

    companion object {
        private const val MAX_FOLDER_DEPTH = 64
        private const val SQL_BATCH = 500
        private const val FOLDER_SEARCH_LIMIT = 200
        private const val NOTE_TITLE_LIMIT = 60
    }

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
