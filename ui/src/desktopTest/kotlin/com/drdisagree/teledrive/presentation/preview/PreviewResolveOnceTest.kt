package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 MF-2 (desktop port of the Android PreviewResolveOnceTest) —
 * resolution must run ONCE per file while transfer progress keeps
 * repainting the viewer.
 *
 * Before the fix, [PreviewViewModel.contentFor] flatMapLatest-ed over the
 * (file, transfer) pair, so every progress emission (about once a second
 * for the life of a download) restarted the uncached
 * PreviewContentResolver.resolve() — a full EPUB/DOCX extraction or archive
 * listing on every tick. These tests drive the production pipeline seam
 * ([resolvedPreviewContentFlow], which contentFor is built on) with a
 * counting resolver fake over a document file:
 *  - the Download-to-view gate case: several progress ticks produce the
 *    correct DownloadProgress values, with a single resolution;
 *  - the renderable case: progress ticks never hijack the content, also
 *    with a single resolution;
 *  - a genuine file change is the only thing that re-resolves.
 */
class PreviewResolveOnceTest {

    private fun documentFile(id: String = "doc-1") = DriveFile(
        id = id,
        folderId = null,
        name = "book.docx",
        sizeBytes = 5_000_000,
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        category = FileCategory.DOCUMENT,
        localPath = null,
        contentHash = null,
        chatId = 100L,
        messageId = 200L,
        remoteFileId = "remote-$id",
        remoteUniqueId = "unique-$id",
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0,
        modifiedAt = 0,
        addedAt = 0
    )

    private fun tick(transferred: Long, total: Long = 5_000_000) = TransferTask(
        id = "transfer-1",
        type = TransferType.DOWNLOAD,
        fileId = "doc-1",
        displayName = "book.docx",
        sizeBytes = total,
        transferredBytes = transferred,
        state = TransferState.RUNNING,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = 0,
        createdAt = 1,
        updatedAt = transferred,
        completedAt = null
    )

    private suspend fun awaitCondition(
        description: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(10)
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for '$description'")
        }
    }

    @Test
    fun `gate case - progress ticks repaint DownloadProgress while resolve runs once`() =
        runBlocking {
            val file = documentFile()
            val fileFlow = MutableStateFlow(file)
            val incoming = MutableStateFlow<TransferTask?>(null)
            val resolveCalls = AtomicInteger(0)
            val seen = mutableListOf<PreviewContent>()
            val job: Job = launch {
                resolvedPreviewContentFlow(
                    fileFlow = fileFlow,
                    incomingFlow = incoming,
                    resolve = {
                        resolveCalls.incrementAndGet()
                        flowOf(PreviewContent.RequiresDownload(it.sizeBytes))
                    }
                ).collect { seen += it }
            }
            try {
                awaitCondition("gate resolves") {
                    seen.lastOrNull() is PreviewContent.RequiresDownload
                }
                // Several progress ticks, as the rate meter publishes them.
                for (bytes in listOf(100L, 250L, 900L, 4_999_999L)) {
                    incoming.value = tick(bytes)
                    awaitCondition("gate shows progress $bytes") {
                        (seen.lastOrNull() as? PreviewContent.DownloadProgress)
                            ?.transferred == bytes
                    }
                    val progress = seen.last() as PreviewContent.DownloadProgress
                    assertEquals(bytes, progress.transferred)
                    assertEquals(5_000_000, progress.total)
                }
                delay(300)
                assertEquals(
                    "resolve() must run once for the file, not once per progress tick",
                    1,
                    resolveCalls.get()
                )
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `renderable case - progress ticks never hijack resolved content and resolve runs once`() =
        runBlocking {
            val file = documentFile()
            val fileFlow = MutableStateFlow(file)
            val incoming = MutableStateFlow<TransferTask?>(null)
            val resolveCalls = AtomicInteger(0)
            val seen = mutableListOf<PreviewContent>()
            val job: Job = launch {
                resolvedPreviewContentFlow(
                    fileFlow = fileFlow,
                    incomingFlow = incoming,
                    resolve = {
                        resolveCalls.incrementAndGet()
                        flowOf(PreviewContent.PlainText("extracted text", truncated = false))
                    }
                ).collect { seen += it }
            }
            try {
                awaitCondition("document resolves") {
                    seen.lastOrNull() is PreviewContent.PlainText
                }
                for (bytes in listOf(10L, 20L, 30L)) {
                    incoming.value = tick(bytes)
                }
                delay(300)
                assertTrue(
                    "renderable content must pass through unchanged, was ${seen.lastOrNull()}",
                    seen.lastOrNull() is PreviewContent.PlainText
                )
                assertTrue(
                    "no DownloadProgress may appear for renderable content: $seen",
                    seen.none { it is PreviewContent.DownloadProgress }
                )
                assertEquals(1, resolveCalls.get())
            } finally {
                job.cancel()
            }
        }

    @Test
    fun `a genuine file change re-resolves`() = runBlocking {
        val fileFlow = MutableStateFlow(documentFile())
        val incoming = MutableStateFlow<TransferTask?>(null)
        val resolveCalls = AtomicInteger(0)
        val seen = mutableListOf<PreviewContent>()
        val job: Job = launch {
            resolvedPreviewContentFlow(
                fileFlow = fileFlow,
                incomingFlow = incoming,
                resolve = {
                    resolveCalls.incrementAndGet()
                    flowOf(PreviewContent.RequiresDownload(it.sizeBytes))
                }
            ).collect { seen += it }
        }
        try {
            awaitCondition("first file resolves") { resolveCalls.get() == 1 }
            fileFlow.value = documentFile(id = "doc-2")
            awaitCondition("second file re-resolves") { resolveCalls.get() == 2 }
        } finally {
            job.cancel()
        }
    }
}
