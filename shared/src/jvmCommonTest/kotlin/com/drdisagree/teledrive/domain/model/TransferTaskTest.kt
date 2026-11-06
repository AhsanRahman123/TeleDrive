package com.drdisagree.teledrive.domain.model

import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.core.telegram.uploadOrReplaceDocument
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTaskTest {

    private fun task(
        size: Long,
        transferred: Long,
        speed: Long = 0,
        state: TransferState = TransferState.RUNNING,
        type: TransferType = TransferType.UPLOAD
    ) = TransferTask(
        id = "t",
        type = type,
        fileId = "f",
        displayName = "file",
        sizeBytes = size,
        transferredBytes = transferred,
        state = state,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = speed,
        createdAt = 0,
        updatedAt = 0,
        completedAt = null
    )

    @Test
    fun `progress is fraction of size`() {
        assertEquals(0.5f, task(100, 50).progress)
    }

    @Test
    fun `progress clamps and handles zero size`() {
        assertEquals(1f, task(100, 150).progress)
        assertEquals(0f, task(0, 0).progress)
    }

    @Test
    fun `eta computed from speed`() {
        assertEquals(50L, task(1000, 500, speed = 10).etaSeconds)
    }

    @Test
    fun `eta null without speed`() {
        assertNull(task(1000, 500, speed = 0).etaSeconds)
    }

    @Test
    fun `only queued downloads can be prioritized`() {
        assertTrue(task(100, 0, state = TransferState.QUEUED, type = TransferType.DOWNLOAD).canPrioritizeDownload)
        assertFalse(task(100, 0, state = TransferState.QUEUED, type = TransferType.UPLOAD).canPrioritizeDownload)
        assertFalse(task(100, 0, state = TransferState.RUNNING, type = TransferType.DOWNLOAD).canPrioritizeDownload)
        assertFalse(task(100, 0, state = TransferState.PAUSED, type = TransferType.DOWNLOAD).canPrioritizeDownload)
    }

    @Test
    fun `state helpers`() {
        assertTrue(TransferState.COMPLETED.isTerminal)
        assertTrue(TransferState.QUEUED.isActive)
        assertTrue(!TransferState.PAUSED.isActive && !TransferState.PAUSED.isTerminal)
    }
}


class TelegramClientReplacementTest {

    @Test
    fun `existing document is edited in place instead of sending a new message`() = runBlocking {
        val existingMessageId = 42L
        var editedMessageId: Long? = null
        var receivedThumbnail: String? = null
        var uploadCalled = false
        val edited = document(existingMessageId, "edited-caption")
        val client = object : TelegramClient by unused() {
            override suspend fun editDocument(
                chatId: Long,
                messageId: Long,
                localPath: String,
                caption: String,
                thumbnailPath: String?
            ): RemoteDocument {
                editedMessageId = messageId
                receivedThumbnail = thumbnailPath
                return edited
            }

            override fun uploadDocument(
                chatId: Long,
                localPath: String,
                fileName: String,
                mimeType: String,
                caption: String,
                thumbnailPath: String?
            ): Flow<TelegramUploadEvent> {
                uploadCalled = true
                return flowOf(TelegramUploadEvent.Completed(document(99L, caption)))
            }
        }

        val events = client.uploadOrReplaceDocument(
            chatId = 7L,
            existingMessageId = existingMessageId,
            localPath = "/tmp/edited.md",
            fileName = "edited.md",
            mimeType = "text/markdown",
            caption = "edited-caption",
            thumbnailPath = "/tmp/thumbnail.jpg"
        ).toList()

        assertEquals(existingMessageId, editedMessageId)
        assertEquals("/tmp/thumbnail.jpg", receivedThumbnail)
        assertEquals(false, uploadCalled)
        assertEquals(
            listOf(
                TelegramUploadEvent.Started(existingMessageId),
                TelegramUploadEvent.Completed(edited)
            ),
            events
        )
    }

    @Test
    fun `new document is uploaded when there is no existing remote message`() = runBlocking {
        var editCalled = false
        var uploadCalled = false
        val uploaded = document(99L, "new-caption")
        val client = object : TelegramClient by unused() {
            override suspend fun editDocument(
                chatId: Long,
                messageId: Long,
                localPath: String,
                caption: String,
                thumbnailPath: String?
            ): RemoteDocument {
                editCalled = true
                return uploaded
            }

            override fun uploadDocument(
                chatId: Long,
                localPath: String,
                fileName: String,
                mimeType: String,
                caption: String,
                thumbnailPath: String?
            ): Flow<TelegramUploadEvent> {
                uploadCalled = true
                return flowOf(
                    TelegramUploadEvent.Started(90L),
                    TelegramUploadEvent.Completed(uploaded)
                )
            }
        }

        val events = client.uploadOrReplaceDocument(
            chatId = 7L,
            existingMessageId = null,
            localPath = "/tmp/new.md",
            fileName = "new.md",
            mimeType = "text/markdown",
            caption = "new-caption"
        ).toList()

        assertEquals(false, editCalled)
        assertEquals(true, uploadCalled)
        assertEquals(
            listOf(
                TelegramUploadEvent.Started(90L),
                TelegramUploadEvent.Completed(uploaded)
            ),
            events
        )
    }

    private fun document(messageId: Long, caption: String) = RemoteDocument(
        chatId = 7L,
        messageId = messageId,
        remoteFileId = "remote-$messageId",
        uniqueFileId = "unique-$messageId",
        fileName = "note.md",
        mimeType = "text/markdown",
        sizeBytes = 12,
        caption = caption,
        dateSeconds = 100
    )
}
