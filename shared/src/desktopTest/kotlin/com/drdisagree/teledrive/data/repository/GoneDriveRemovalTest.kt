package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.NoteStore
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import java.io.File
import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.data.local.entity.StorageChannelEntity
import com.drdisagree.teledrive.domain.repository.ExclusionRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoneDriveRemovalTest {

    private val harness = SyncHarness()
    private val channels = harness.database.storageChannelDao()
    private var deleteCalled = false

    @After
    fun tearDown() = harness.close()

    @Test
    fun `storage channel deletion is disabled and leaves channel metadata intact`() = runBlocking {
        channels.upsert(StorageChannelEntity(chatId = 1, title = "TeleDrive", addedAt = 1, lastOpenedAt = 1))
        channels.upsert(StorageChannelEntity(chatId = 2, title = "TeleDrive Teste", addedAt = 1, lastOpenedAt = 0))

        val result = repository().deleteRemotely(2)

        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.RemoteDeletionDisabled, (result as AppResult.Failure).error)
        assertNotNull(channels.byId(2))
        assertFalse(deleteCalled)
    }

    private fun repository() = ChannelRepositoryImpl(
        channelDao = channels,
        telegramClient = object : TelegramClient by unused() {
            override suspend fun deleteStorageChannel(chatId: Long) {
                deleteCalled = true
            }
        },
        settingsRepository = harness.settings,
        syncRepository = unused<SyncRepository>(),
        channelOwnership = ChannelOwnership(
            harness.database.fileDao(),
            harness.database.folderDao(),
            harness.database.exclusionDao()
        ),
        exclusionRepository = unused<ExclusionRepository>()
    )
}


class NoteSaveRetentionTest {

    private val harness = SyncHarness()
    private val files = harness.database.fileDao()
    private val originalPath = File(
        File(harness.storagePaths.filesDir, "notes").apply { mkdirs() },
        "Original.md"
    ).apply { writeText("old note body") }

    @After
    fun tearDown() = harness.close()

    @Test
    fun `saving an existing note preserves its remote mapping until replacement succeeds`() =
        runBlocking {
            files.upsert(
                FileEntity(
                    id = "note-1",
                    folderId = null,
                    name = "Original.md",
                    sizeBytes = originalPath.length(),
                    mimeType = "text/markdown",
                    category = FileCategory.DOCUMENT,
                    localPath = originalPath.absolutePath,
                    contentHash = "old-hash",
                    chatId = TEST_CHAT,
                    messageId = 42L,
                    remoteFileId = "old-remote-id",
                    remoteUniqueId = "old-unique-id",
                    backupState = BackupState.BACKED_UP,
                    createdAt = 1,
                    modifiedAt = 1,
                    addedAt = 1
                )
            )

            val result = repository().saveNote(
                fileId = "note-1",
                folderId = null,
                title = "Edited note",
                body = "new note body"
            )

            assertTrue(result is AppResult.Success)
            val updated = files.byId("note-1")!!
            assertEquals(42L, updated.messageId)
            assertEquals("old-remote-id", updated.remoteFileId)
            assertEquals("old-unique-id", updated.remoteUniqueId)
            assertEquals(TEST_CHAT, updated.chatId)
            assertEquals(BackupState.NONE, updated.backupState)
            assertEquals("new note body", File(updated.localPath!!).readText())
            assertFalse(harness.telegram.deletedMessages.contains(42L))
        }

    private fun repository() = FileRepositoryImpl(
        fileDao = files,
        folderDao = harness.database.folderDao(),
        publishScheduler = harness.scheduler,
        localCopyDeleter = unused<LocalCopyDeleter>(),
        telegramClient = harness.telegram,
        manifestCodec = harness.codec,
        folderPathResolver = harness.folderPaths,
        activeChannel = harness.activeChannel,
        noteStore = NoteStore(harness.storagePaths),
        streamCrypto = StreamCrypto(),
        wrappedKeyRepository = unused<WrappedKeyRepository>(),
        settingsRepository = harness.settings,
        fileImporter = unused<FileImporter>()
    )
}
