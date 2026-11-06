package com.drdisagree.teledrive.data.repository

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
