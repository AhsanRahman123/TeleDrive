package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 MF-2 — resolution must run ONCE per file while transfer progress
 * keeps repainting the viewer.
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

    private fun documentFile(id: String = "doc-1") = previewDriveFile(
        id = id,
        name = "book.docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        category = FileCategory.DOCUMENT,
        sizeBytes = 5_000_000
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
                awaitPreviewCondition("gate resolves") {
                    seen.lastOrNull() is PreviewContent.RequiresDownload
                }
                // Several progress ticks, as the rate meter publishes them.
                for (bytes in listOf(100L, 250L, 900L, 4_999_999L)) {
                    incoming.value = tick(bytes)
                    awaitPreviewCondition("gate shows progress $bytes") {
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
                awaitPreviewCondition("document resolves") {
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
            awaitPreviewCondition("first file resolves") { resolveCalls.get() == 1 }
            fileFlow.value = documentFile(id = "doc-2")
            awaitPreviewCondition("second file re-resolves") { resolveCalls.get() == 2 }
        } finally {
            job.cancel()
        }
    }
}
