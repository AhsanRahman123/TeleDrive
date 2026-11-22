package com.drdisagree.teledrive.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.Dispatchers

internal class SyncHarness : AutoCloseable {
    private val dbFile = File.createTempFile("teledrive-sync", ".db")
    val database: TeleDriveDatabase = Room.databaseBuilder<TeleDriveDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    val settings = FakeSettings(UserPreferences(storageChatId = TEST_CHAT))
    val telegram = FakeTelegram()
    val scheduler = CountingScheduler()
    val codec = ManifestCodec(
        StreamCrypto(),
        object : WrappedKeyRepository by unused() {
            override fun exists(name: String): Boolean = false
        }
    )
    val activeChannel = ActiveChannel(settings)
    val folderPaths = FolderPathResolver(
        database.folderDao(),
        activeChannel,
        database.folderTombstoneDao()
    )
    val storagePaths = FakeStoragePaths()
    val folderMerger = FolderMerger(
        database.folderDao(),
        database.fileDao(),
        database.folderTombstoneDao(),
        database
    )
    val folderState = FolderStateSynchronizer(
        storagePaths,
        telegram,
        database.folderDao(),
        database.folderTombstoneDao(),
        settings,
        StreamCrypto(),
        unused<WrappedKeyRepository>(),
        folderMerger
    )
    val indexSnapshot = IndexSnapshot(storagePaths, telegram, folderState)
    val resolver = RemoteMessageResolver(telegram, codec)

    /**
     * PC-37: a recording [com.drdisagree.teledrive.core.publish.ReuploadEnqueuer]
     * for drainer tests — captures the file ids the repair ladder asks
     * to re-upload, and the row's messageId at the moment of the call
     * (so a test can prove the stale binding was detached BEFORE the
     * enqueue, never after).
     */
    val reuploads = mutableListOf<String>()
    val reuploadMessageIdsAtCall = mutableListOf<Long?>()
    val reuploadEnqueuer =
        com.drdisagree.teledrive.core.publish.ReuploadEnqueuer { fileId ->
            reuploads += fileId
            reuploadMessageIdsAtCall += database.fileDao().byId(fileId)?.messageId
            com.drdisagree.teledrive.core.common.AppResult.Success("transfer-$fileId")
        }

    fun newSync() = SyncRepositoryImpl(
        telegramClient = telegram,
        fileDao = database.fileDao(),
        manifestCodec = codec,
        pendingDeleteDao = database.pendingDeleteDao(),
        filePartDao = database.filePartDao(),
        folderDao = database.folderDao(),
        folderPathResolver = folderPaths,
        activeChannel = activeChannel,
        channelOwnership = ChannelOwnership(
            database.fileDao(),
            database.folderDao(),
            database.exclusionDao()
        ),
        folderStateSynchronizer = folderState,
        folderMerger = folderMerger,
        indexSnapshot = indexSnapshot,
        publishScheduler = scheduler,
        settingsRepository = settings,
        resolver = resolver,
        database = database
    )

    fun caption(manifest: RemoteFileManifest) = codec.encode(manifest, encrypt = false)

    override fun close() {
        database.close()
        dbFile.delete()
        telegram.cleanUp()
        storagePaths.cacheDir.deleteRecursively()
        storagePaths.filesDir.deleteRecursively()
    }
}
