package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 item X1 at the production entry point: the REAL
 * [PreviewContentResolver] resolving real on-disk files — both through
 * [PreviewContentResolver.resolve] (the Files/Gallery route, with the
 * stored MIME/category a generic README actually carries) and through
 * [PreviewContentResolver.resolveLocalPath] (the Transfers-opened
 * route, which derives everything from the display name alone). Before
 * the fix every variant below resolved to PreviewContent.Unsupported.
 */
class DesktopReadmePreviewTest {

    private class FakeSettingsRepository : SettingsRepository {
        private val prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null
        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit
        override suspend fun hasStoredTelegramCredentials(): Boolean = false
        override suspend fun clearTelegramCredentials() = Unit
    }

    private fun resolver(): PreviewContentResolver {
        val settings = FakeSettingsRepository()
        val telegram = archiveFakeTelegram(ByteArray(0))
        val downloadWriter = object : DownloadWriter {
            override fun write(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                body: (OutputStream) -> Unit
            ): String {
                val out = ByteArrayOutputStream()
                body(out)
                return "/Downloads/$fileName"
            }

            override fun openPartial(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                token: String?
            ): PartialDownload? = null

            override fun freeBytes(folderPath: String?): Long? = null
        }
        val filePartDao = archiveTestProxy<FilePartDao> { method, _ ->
            when (method) {
                "partsOf" -> emptyList<FilePartEntity>()
                "countOf" -> 0
                else -> throw UnsupportedOperationException(method)
            }
        }
        val folderPathResolver = FolderPathResolver(
            archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
            ActiveChannel(settings),
            archiveTestProxy<FolderTombstoneDao> { m, _ -> throw UnsupportedOperationException(m) }
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("readme-preview", "").also {
                it.delete()
                it.mkdirs()
            }
            override val filesDir: File = File(root, "files").also { it.mkdirs() }
            override val cacheDir: File = File(root, "cache").also { it.mkdirs() }
            override val externalCacheDir: File? = null
            override val externalStorageRoot: File? = null
        }
        return PreviewContentResolver(
            storagePaths = storagePaths,
            telegramClient = telegram,
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = archiveTestProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = archiveTestProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = filePartDao,
            settingsRepository = settings,
            thumbnailStore = archiveTestProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = RemoteArchiveService(
                telegram, filePartDao, downloadWriter, folderPathResolver
            )
        )
    }

    private fun driveFile(name: String, localPath: String) = DriveFile(
        id = "readme-file",
        folderId = null,
        name = name,
        sizeBytes = 12,
        // The stored state a generic README actually carries: Telegram /
        // the sync manifest declared nothing meaningful, so the row kept
        // the generic MIME and the OTHER category.
        mimeType = "application/octet-stream",
        category = FileCategory.OTHER,
        localPath = localPath,
        contentHash = null,
        chatId = null,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0L,
        modifiedAt = 0L,
        addedAt = 0L
    )

    private fun tempFileNamed(name: String, text: String): File {
        val dir = File.createTempFile("readme-case", "").also {
            it.delete()
            it.mkdirs()
        }
        return File(dir, name).apply { writeText(text) }
    }

    @Test
    fun `resolve opens readme variants as plain text`() = runBlocking {
        val resolver = resolver()
        for (name in listOf("README", "README.md", "README.txt", "notes.txt", "guide.md", "LICENSE")) {
            val file = tempFileNamed(name, "# Hello\n\nbody text")
            val contents = resolver.resolve(driveFile(name, file.absolutePath)).toList()
            val last = contents.last()
            assertTrue(
                "$name must resolve to PlainText, was $last",
                last is PreviewContent.PlainText
            )
            assertTrue(
                (last as PreviewContent.PlainText).text.contains("Hello")
            )
        }
    }

    @Test
    fun `resolveLocalPath opens readme variants as plain text`() = runBlocking {
        val resolver = resolver()
        for (name in listOf("README", "README.md", "notes.txt", "LICENSE")) {
            val file = tempFileNamed(name, "local body")
            val contents = resolver.resolveLocalPath(file.absolutePath, name).toList()
            val last = contents.last()
            assertTrue(
                "$name via resolveLocalPath must resolve to PlainText, was $last",
                last is PreviewContent.PlainText
            )
        }
    }

    @Test
    fun `resolve still leaves an unknown binary unsupported`() = runBlocking {
        val resolver = resolver()
        val file = tempFileNamed("blob.bin", "not a text file")
        val contents = resolver.resolve(driveFile("blob.bin", file.absolutePath)).toList()
        assertEquals(
            true,
            contents.last() is PreviewContent.Unsupported
        )
    }
}
