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
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.preview.ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.normalizeArchiveEntryModifiedAt
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defect 6 (1.6.21): dateless ZIP entries displayed "Jan 18/19, 1970" in the desktop
 * viewer even though the row hides `modifiedAtMillis == 0`. These tests pin the
 * mechanism and the fix on BOTH listing paths:
 *
 * - remote (the shared [com.drdisagree.teledrive.core.archive.ZipArchiveReader] DOS
 *   conversion, which already returns 0 for an all-zero DOS pair), and
 * - local (zip4j's `header.lastModifiedTime`, whose conversion of a dateless entry
 *   does NOT return 0 — see the raw-value probe printed by the local test).
 *
 * The fix normalizes at ArchiveEntry construction: a ZIP entry cannot predate the
 * DOS epoch (1980-01-01), so any value below that floor becomes 0 (unknown), which
 * the row hides and the sort places last.
 */
class DesktopArchiveDateTest {

    private val datedDos = dosTime(12, 30, 0) to dosDate(2024, 6, 15)
    private val datedMillis =
        LocalDateTime.of(2024, 6, 15, 12, 30, 0).toEpochSecond(ZoneOffset.UTC) * 1000L

    private fun dosTime(hour: Int, minute: Int, second: Int): Int =
        (hour shl 11) or (minute shl 5) or (second / 2)

    private fun dosDate(year: Int, month: Int, day: Int): Int =
        ((year - 1980) shl 9) or (month shl 5) or day

    private fun datelessZip(): ByteArray = handBuiltZipWithDosTimes(
        listOf(
            Triple("nodate.txt", "a".toByteArray(), 0 to 0),
            Triple("dated.txt", "b".toByteArray(), datedDos)
        )
    )

    @Test
    fun `normalization floors sub-1980 values to unknown and keeps real dates`() {
        assertEquals(0L, normalizeArchiveEntryModifiedAt(0L))
        assertEquals(0L, normalizeArchiveEntryModifiedAt(-1L))
        // A Unix-seconds value read as milliseconds (the January 1970 garbage shape).
        assertEquals(0L, normalizeArchiveEntryModifiedAt(1_700_000_000L))
        assertEquals(0L, normalizeArchiveEntryModifiedAt(ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS - 1))
        assertEquals(
            ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS,
            normalizeArchiveEntryModifiedAt(ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS)
        )
        assertEquals(datedMillis, normalizeArchiveEntryModifiedAt(datedMillis))
    }

    @Test
    fun `remote listing path - dateless entry is unknown and dated entry is exact`() =
        runBlocking {
            val zipBytes = datelessZip()
            val service = RemoteArchiveService(
                archiveFakeTelegram(zipBytes),
                archiveTestProxy<FilePartDao> { method, _ ->
                    when (method) {
                        "partsOf" -> emptyList()
                        "countOf" -> 0
                        else -> throw UnsupportedOperationException(method)
                    }
                },
                object : DownloadWriter {
                    override fun write(
                        fileName: String,
                        mimeType: String,
                        folderPath: String?,
                        body: (OutputStream) -> Unit
                    ): String = "/Downloads/$fileName"

                    override fun openPartial(
                        fileName: String,
                        mimeType: String,
                        folderPath: String?,
                        token: String?
                    ): PartialDownload? = null

                    override fun freeBytes(folderPath: String?): Long? = null
                },
                FolderPathResolver(
                    archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
                    ActiveChannel(FakeSettings()),
                    archiveTestProxy<FolderTombstoneDao> { m, _ ->
                        throw UnsupportedOperationException(m)
                    }
                )
            )
            val entries = service.listEntries(zipFile(localPath = null, size = zipBytes.size))
            assertEquals(0L, entries.first { it.name == "nodate.txt" }.modifiedAtMillis)
            assertEquals(datedMillis, entries.first { it.name == "dated.txt" }.modifiedAtMillis)
        }

    @Test
    fun `local listing path - dateless entry is normalized to unknown`() = runBlocking {
        val zipBytes = datelessZip()
        val zipOnDisk = File.createTempFile("dateless", ".zip").also {
            it.writeBytes(zipBytes)
            it.deleteOnExit()
        }
        // Probe: what zip4j itself yields for the dateless entry (the defect's source
        // value), printed so the CI test output records the mechanism.
        val rawDateless = ZipFile(zipOnDisk.absolutePath).fileHeaders
            .first { it.fileName == "nodate.txt" }.lastModifiedTime
        println("ZIP4J-RAW dateless lastModifiedTime = $rawDateless")
        System.out.println("ZIP4J-RAW dateless lastModifiedTime = $rawDateless")

        val resolver = resolver()
        val content = resolver.resolve(zipFile(localPath = zipOnDisk.absolutePath, size = zipBytes.size))
            .first { it is PreviewContent.Archive } as PreviewContent.Archive
        val dateless = content.entries.first { it.name == "nodate.txt" }
        assertEquals(
            "dateless entry must be unknown (0), raw zip4j value was $rawDateless",
            0L,
            dateless.modifiedAtMillis
        )
        val dated = content.entries.first { it.name == "dated.txt" }
        assertTrue(
            "dated entry must keep a real post-1980 date, was ${dated.modifiedAtMillis}",
            dated.modifiedAtMillis >= ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS
        )
    }

    private class FakeSettings : SettingsRepository {
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
        val settings = FakeSettings()
        val root = File.createTempFile("archive-date", "").also {
            it.delete()
            it.mkdirs()
        }
        return PreviewContentResolver(
            storagePaths = object : AppStoragePaths {
                override val filesDir: File = File(root, "files").also { it.mkdirs() }
                override val cacheDir: File = File(root, "cache").also { it.mkdirs() }
                override val externalCacheDir: File? = null
                override val externalStorageRoot: File? = null
            },
            telegramClient = archiveFakeTelegram(ByteArray(0)),
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = archiveTestProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = archiveTestProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = archiveTestProxy<FilePartDao> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            settingsRepository = settings,
            thumbnailStore = archiveTestProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = RemoteArchiveService(
                archiveFakeTelegram(ByteArray(0)),
                archiveTestProxy<FilePartDao> { m, _ -> throw UnsupportedOperationException(m) },
                object : DownloadWriter {
                    override fun write(
                        fileName: String,
                        mimeType: String,
                        folderPath: String?,
                        body: (OutputStream) -> Unit
                    ): String = "/Downloads/$fileName"

                    override fun openPartial(
                        fileName: String,
                        mimeType: String,
                        folderPath: String?,
                        token: String?
                    ): PartialDownload? = null

                    override fun freeBytes(folderPath: String?): Long? = null
                },
                FolderPathResolver(
                    archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
                    ActiveChannel(settings),
                    archiveTestProxy<FolderTombstoneDao> { m, _ ->
                        throw UnsupportedOperationException(m)
                    }
                )
            )
        )
    }

    private fun zipFile(localPath: String?, size: Int): DriveFile = DriveFile(
        id = "zip-date-1",
        folderId = null,
        name = "dates.zip",
        sizeBytes = size.toLong(),
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = localPath,
        contentHash = null,
        chatId = 1L,
        messageId = 2L,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
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
}
