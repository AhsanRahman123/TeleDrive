package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * vc184 A3 — the viewer Download button must start a genuine BACKGROUND
 * transfer and never hijack the player. Drives the production entry points
 * ([PreviewViewModel.contentFor] and [PreviewViewModel.download]) on the
 * real view model: on vc183, tapping Download in the video viewer replaced
 * the playing video with a stuck "Preparing… 0 B" screen, because contentFor
 * swapped the resolved content for DownloadProgress whenever ANY incoming
 * transfer existed for the file. The only content a transfer may still
 * replace is the Download-to-view gate (RequiresDownload), whose whole
 * purpose is to follow the download it started.
 */
class ViewerBackgroundDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun videoFile() = previewDriveFile(
        id = "vid-1",
        name = "movie.mp4",
        mimeType = "video/mp4",
        category = FileCategory.VIDEO,
        sizeBytes = 5_000_000
    )

    @Test
    fun `download while a video plays starts a background transfer and playback content never changes`() =
        runVmTest {
            val file = videoFile()
            val transfers = PreviewFakeTransferRepository()
            val viewModel = buildPreviewViewModel(
                file = file,
                rootDir = folder.newFolder(),
                transferRepository = transfers
            )
            val seen = mutableListOf<PreviewContent>()
            val collectJob: Job = launch {
                viewModel.contentFor(file).collect { seen += it }
            }
            try {
                awaitPreviewCondition("streamed media resolves") {
                    seen.lastOrNull() is PreviewContent.StreamedMedia
                }

                // The vc183 field action: tap Download in the video viewer.
                viewModel.download(file)

                awaitPreviewCondition("the background transfer is enqueued") {
                    transfers.enqueuedFileIds == listOf("vid-1")
                }
                // The transfer becoming active re-runs the content pipeline.
                // Wait until it is active, then give the pipeline every
                // chance to (wrongly) react to it, and assert below that the
                // player content is untouched. (An equal re-resolved content
                // is conflated by the StateFlow, so a second StreamedMedia
                // emission is not something to wait for — staying on
                // StreamedMedia with no DownloadProgress is the property.)
                awaitPreviewCondition("the background transfer becomes active for the file") {
                    transfers.activeForFile.value != null
                }
                delay(500)
                assertTrue(
                    "viewer content must stay the streamed video, was ${seen.lastOrNull()}",
                    seen.lastOrNull() is PreviewContent.StreamedMedia
                )
                assertTrue(
                    "no DownloadProgress may appear once the video can play: $seen",
                    seen.dropWhile { it is PreviewContent.Loading }
                        .none { it is PreviewContent.DownloadProgress }
                )
            } finally {
                collectJob.cancel()
            }
        }

    @Test
    fun `the download-to-view gate still follows its transfer to completion`() = runVmTest {
        val file = videoFile()
        val transfers = PreviewFakeTransferRepository()
        // Streaming off: the resolver gates the video behind RequiresDownload,
        // which is the one case the transfer overlay exists for.
        val viewModel = buildPreviewViewModel(
            file = file,
            rootDir = folder.newFolder(),
            prefs = UserPreferences(streamBeforeDownload = false),
            transferRepository = transfers
        )
        val seen = mutableListOf<PreviewContent>()
        val collectJob: Job = launch {
            viewModel.contentFor(file).collect { seen += it }
        }
        try {
            awaitPreviewCondition("gate resolves") {
                seen.lastOrNull() is PreviewContent.RequiresDownload
            }

            viewModel.download(file)

            awaitPreviewCondition("gate shows the running transfer's progress") {
                seen.lastOrNull() is PreviewContent.DownloadProgress
            }
            val progress = seen.last() as PreviewContent.DownloadProgress
            assertEquals(123, progress.transferred)
            assertEquals(5_000_000, progress.total)
        } finally {
            collectJob.cancel()
        }
    }
}
