package com.drdisagree.teledrive.presentation.preview

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
 * - local (zip4j's `header.lastModifiedTime` — see the raw-value probes printed by
 *   the local test for what zip4j yields per entry shape).
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

    private fun datelessZip(): ByteArray = localHandBuiltZip(
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
                localFakeTelegram(zipBytes),
                localProxy<FilePartDao> { method, _ ->
                    when (method) {
                        "partsOf" -> emptyList<com.drdisagree.teledrive.data.local.entity.FilePartEntity>()
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
                    localProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
                    ActiveChannel(FakeSettings()),
                    localProxy<FolderTombstoneDao> { m, _ ->
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
        val zipBytes = localHandBuiltZip(
            listOf(
                Triple("nodate.txt", "a".toByteArray(), 0 to 0),
                Triple("dated.txt", "b".toByteArray(), datedDos),
                Triple("extradated.txt", "c".toByteArray(), 0 to 0)
            ),
            extras = mapOf("extradated.txt" to extendedTimestampExtra(datedMillis / 1000))
        )
        val zipOnDisk = File.createTempFile("dateless", ".zip").also {
            it.writeBytes(zipBytes)
            it.deleteOnExit()
        }
        // Probes: what zip4j itself yields per entry shape, printed so the CI test
        // output records the mechanism behind the field defect.
        val headers = ZipFile(zipOnDisk.absolutePath).fileHeaders
        val rawDateless = headers.first { it.fileName == "nodate.txt" }.lastModifiedTime
        val rawDosDated = headers.first { it.fileName == "dated.txt" }.lastModifiedTime
        val rawExtraDated = headers.first { it.fileName == "extradated.txt" }.lastModifiedTime
        println("ZIP4J-RAW dateless lastModifiedTime = $rawDateless")
        println("ZIP4J-RAW dos-dated lastModifiedTime = $rawDosDated")
        println("ZIP4J-RAW extra-dated lastModifiedTime = $rawExtraDated")

        val resolver = resolver()
        val content = resolver.resolve(zipFile(localPath = zipOnDisk.absolutePath, size = zipBytes.size))
            .first { it is PreviewContent.Archive } as PreviewContent.Archive
        val dateless = content.entries.first { it.name == "nodate.txt" }
        assertEquals(
            "dateless entry must be unknown (0), raw zip4j value was $rawDateless",
            0L,
            dateless.modifiedAtMillis
        )
        // zip4j yields the packed DOS dword for a DOS-dated entry (proven by the probe:
        // it equals (dosDate shl 16) or dosTime exactly, i.e. January 1970 read as
        // millis — the field defect). The listing must decode it back to the real date.
        val packed = (datedDos.second shl 16) or datedDos.first
        assertEquals(
            "probe: zip4j raw for the DOS-dated entry is the packed DOS dword",
            packed.toLong(),
            rawDosDated
        )
        val dated = content.entries.first { it.name == "dated.txt" }
        assertEquals(datedMillis, dated.modifiedAtMillis)
        // For an entry whose DOS date is zero, zip4j yields 0 even when an
        // extended-timestamp extra field is present (probe), so the entry is unknown —
        // hidden, never a garbage date.
        assertEquals(0L, rawExtraDated)
        val extraDated = content.entries.first { it.name == "extradated.txt" }
        assertEquals(0L, extraDated.modifiedAtMillis)
    }

    @Test
    fun `zip4j interpretation - packed DOS dword decodes, real millis pass through`() {
        assertEquals(0L, archiveEntryModifiedAtFromZip4j(0L))
        assertEquals(0L, archiveEntryModifiedAtFromZip4j(-5L))
        val packed = ((datedDos.second shl 16) or datedDos.first).toLong()
        assertEquals(datedMillis, archiveEntryModifiedAtFromZip4j(packed))
        assertEquals(datedMillis, archiveEntryModifiedAtFromZip4j(datedMillis))
        assertEquals(
            ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS,
            archiveEntryModifiedAtFromZip4j(ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS)
        )
        // A packed pair with an out-of-range month is unknown, not a garbage date.
        val badMonth = ((dosDate(2024, 13, 1) shl 16) or dosTime(1, 2, 4)).toLong()
        assertEquals(0L, archiveEntryModifiedAtFromZip4j(badMonth))
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
            telegramClient = localFakeTelegram(ByteArray(0)),
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = localProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = localProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = localProxy<FilePartDao> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            settingsRepository = settings,
            thumbnailStore = localProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = RemoteArchiveService(
                localFakeTelegram(ByteArray(0)),
                localProxy<FilePartDao> { m, _ -> throw UnsupportedOperationException(m) },
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
                    localProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
                    ActiveChannel(settings),
                    localProxy<FolderTombstoneDao> { m, _ ->
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

/** Local proxy helper (the desktop module's test support is not visible here). */
private inline fun <reified T : Any> localProxy(
    crossinline handler: (method: String, args: Array<out Any?>?) -> Any?
): T {
    val invocation = java.lang.reflect.InvocationHandler { _, method, args ->
        handler(method.name, args)
    }
    return java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        invocation
    ) as T
}

private fun localFakeTelegram(bytes: ByteArray): com.drdisagree.teledrive.core.telegram.TelegramClient {
    val info = com.drdisagree.teledrive.core.telegram.TelegramFileInfo(
        fileId = 7,
        sizeBytes = bytes.size.toLong(),
        localPath = null,
        isDownloadingCompleted = true,
        isDownloadingActive = false,
        downloadOffset = 0,
        downloadedPrefixSize = bytes.size.toLong()
    )
    return localProxy { method, args ->
        when (method) {
            "resolveFile", "getFileInfo" -> info
            "readFilePart" -> {
                val offset = (args!![1] as Number).toLong()
                val count = (args[2] as Number).toLong()
                if (offset >= bytes.size) {
                    ByteArray(0)
                } else {
                    bytes.copyOfRange(
                        offset.toInt(),
                        minOf(offset + count, bytes.size.toLong()).toInt()
                    )
                }
            }
            "requestFileRange", "cancelFileDownload" -> Unit
            "fileUpdates" -> kotlinx.coroutines.flow.flowOf(info)
            else -> throw UnsupportedOperationException(method)
        }
    }
}

/** Extended-timestamp extra field (0x5455) carrying [unixSeconds] as the mtime. */
private fun extendedTimestampExtra(unixSeconds: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun w16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
    fun w32(v: Long) {
        for (i in 0 until 4) out.write((v shr (8 * i)).toInt() and 0xFF)
    }
    w16(0x5455)
    w16(5)
    out.write(1) // flags: modification time present
    w32(unixSeconds)
    return out.toByteArray()
}

/** Hand-built STORED zip with verbatim DOS date/time fields and optional extra fields. */
private fun localHandBuiltZip(
    entries: List<Triple<String, ByteArray, Pair<Int, Int>>>,
    extras: Map<String, ByteArray> = emptyMap()
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun w16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
    fun w32(v: Long) {
        for (i in 0 until 4) out.write((v shr (8 * i)).toInt() and 0xFF)
    }
    data class Rec(
        val name: ByteArray,
        val crc: Long,
        val size: Long,
        val offset: Long,
        val dosTime: Int,
        val dosDate: Int,
        val extra: ByteArray
    )
    val records = mutableListOf<Rec>()
    for ((name, data, dos) in entries) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val extra = extras[name] ?: ByteArray(0)
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        val offset = out.size().toLong()
        w32(0x04034b50L)
        w16(20)
        w16(0x0800)
        w16(0)
        w16(dos.first)
        w16(dos.second)
        w32(crc)
        w32(data.size.toLong())
        w32(data.size.toLong())
        w16(nameBytes.size)
        w16(extra.size)
        out.write(nameBytes)
        out.write(extra)
        out.write(data)
        records += Rec(nameBytes, crc, data.size.toLong(), offset, dos.first, dos.second, extra)
    }
    val cdStart = out.size().toLong()
    for (rec in records) {
        w32(0x02014b50L)
        w16(20)
        w16(20)
        w16(0x0800)
        w16(0)
        w16(rec.dosTime)
        w16(rec.dosDate)
        w32(rec.crc)
        w32(rec.size)
        w32(rec.size)
        w16(rec.name.size)
        w16(rec.extra.size)
        w16(0)
        w16(0)
        w16(0)
        w32(0L)
        w32(rec.offset)
        out.write(rec.name)
        out.write(rec.extra)
    }
    val cdSize = out.size().toLong() - cdStart
    w32(0x06054b50L)
    w16(0)
    w16(0)
    w16(records.size)
    w16(records.size)
    w32(cdSize)
    w32(cdStart)
    w16(0)
    return out.toByteArray()
}
