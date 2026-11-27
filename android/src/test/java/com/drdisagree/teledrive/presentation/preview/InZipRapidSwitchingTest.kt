package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.presentation.common.UiText
import java.io.File
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * vc184 A4 — in-zip switching reliability, scripted against the REAL
 * [PreviewViewModel] production entry points (the same calls the overlay's
 * pager and the player's buttons make: [PreviewViewModel.onArchiveEntryTap],
 * [PreviewViewModel.onArchivePreviewPageSelected],
 * [PreviewViewModel.playCurrentArchiveVideo],
 * [PreviewViewModel.stepArchiveVideo]).
 *
 * The field failure (44 s recording in a 113-entry mixed ZIP): image pages
 * intermittently landed BLANK and one video page sat black — a switch
 * interrupted a load mid-flight and the settled page was never completed,
 * because the interrupted load was that page's only trigger. The contract
 * pinned here: after ANY sequence of switches, the page the user finally
 * settles on reaches a published, rendered state — its preview is the right
 * type for the entry, its extracted temp is on disk with the entry's exact
 * verified bytes (for text, its exact content), the published position
 * matches the settled page, and no false "corrupted" verdict appears.
 *
 * A5 (autoplay on settle) is pinned in the same script: a video page that is
 * merely PASSED during a swipe never starts extracting (asserted after
 * waiting past the settle delay), while a video page the user SETTLES on
 * extracts and reaches the published Video state — the state the player
 * auto-plays — with no Play tap anywhere in the script.
 */
class InZipRapidSwitchingTest {

    @get:Rule
    val folder = TemporaryFolder()

    // Kept in a field: the A5 autoplay settle delay runs on Dispatchers.Main,
    // whose test scheduler is virtual — the script advances it explicitly to
    // simulate time passing on a settled page.
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    /** Advances the Main test scheduler past [ms] of virtual time. */
    private fun advanceMainTime(ms: Long) {
        mainDispatcher.scheduler.advanceTimeBy(ms)
        mainDispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Name order == listing order, so pager positions map 1:1 to listing
    // indices: 0 image, 1 image, 2 video, 3 pdf, 4 text, 5 image.
    private val img1 = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
    private val img2 = byteArrayOf(0x66, 0x77, 0x18, 0x29, 0x3A, 0x4B)
    private val video = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)
    private val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
    private val notes = "hello zip world".toByteArray(Charsets.UTF_8)
    private val img3 = byteArrayOf(0x5C, 0x6D, 0x7E, 0x7F, 0x01, 0x02, 0x03)

    private fun zipEntries() = listOf(
        "a-img1.png" to img1,
        "b-img2.jpg" to img2,
        "c-video.mp4" to video,
        "d-doc.pdf" to pdf,
        "e-notes.txt" to notes,
        "f-img3.png" to img3
    )

    private fun PreviewUiState.settledOn(position: Int): Boolean =
        archivePreview != null &&
            archivePreviewPosition == position &&
            !archivePreviewLoading

    private fun assertTempBytes(preview: ArchiveEntryPreview, expected: ByteArray) {
        val path = when (preview) {
            is ArchiveEntryPreview.Image -> preview.path
            is ArchiveEntryPreview.Video -> preview.path
            is ArchiveEntryPreview.Pdf -> preview.path
            is ArchiveEntryPreview.Audio -> preview.path
            else -> error("preview $preview carries no temp file")
        }
        val temp = File(path)
        assertTrue("extracted temp for ${preview.name} must exist", temp.isFile)
        assertTrue(
            "extracted temp for ${preview.name} must hold the entry's exact bytes",
            temp.readBytes().contentEquals(expected)
        )
    }

    @Test
    fun `rapid switching across mixed types always lands the settled page rendered`() =
        runVmTest {
            val zipBytes = buildZipBytes(zipEntries())
            val zipFile = previewDriveFile(
                id = "zip-1",
                name = "mixed.zip",
                mimeType = "application/zip",
                category = FileCategory.ARCHIVE,
                sizeBytes = zipBytes.size.toLong()
            )
            val viewModel = buildPreviewViewModel(
                file = zipFile,
                rootDir = folder.newFolder(),
                telegramBytes = zipBytes
            )
            val plainMessages = mutableListOf<String>()
            val messageJob: Job = launch {
                viewModel.messages.collect {
                    if (it is UiText.Plain) plainMessages += it.value
                }
            }
            val contentJob: Job = launch { viewModel.contentFor(zipFile).collect {} }
            try {
                awaitPreviewCondition("archive listing is stored") {
                    viewModel.uiState.value.archiveListings["zip-1"]?.size == 6
                }

                // Open the first image from the list (the deliberate tap path).
                viewModel.onArchiveEntryTap(zipFile, 0)
                awaitPreviewCondition("first image published") {
                    val state = viewModel.uiState.value
                    state.settledOn(0) && state.archivePreview is ArchiveEntryPreview.Image
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, img1)

                // Rapid switching with NO settling between swipes, crossing
                // image -> image -> video -> pdf -> text -> image -> image:
                // every intermediate load is interrupted mid-flight; only the
                // final page (position 1) may — and must — land.
                viewModel.onArchivePreviewPageSelected(1)
                viewModel.onArchivePreviewPageSelected(2)
                viewModel.onArchivePreviewPageSelected(3)
                viewModel.onArchivePreviewPageSelected(4)
                viewModel.onArchivePreviewPageSelected(5)
                viewModel.onArchivePreviewPageSelected(1)
                awaitPreviewCondition("settled image page rendered after rapid switching") {
                    val state = viewModel.uiState.value
                    state.settledOn(1) &&
                        (state.archivePreview as? ArchiveEntryPreview.Image)?.entryIndex == 1
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, img2)

                // A5 pass-through: the burst above PASSED the video page
                // (position 2) without settling on it. Even after time runs
                // well past the autoplay settle delay, the video must never
                // have been extracted or published — the settled image page
                // is still what is shown.
                advanceMainTime(1_000)
                delay(100)
                run {
                    val state = viewModel.uiState.value
                    assertTrue(
                        "a video page merely passed must never extract: ${state.archivePreview}",
                        state.settledOn(1) &&
                            state.archivePreview is ArchiveEntryPreview.Image
                    )
                }

                // A5 pass-through, second form: settle briefly toward the
                // video and move on to the pdf BEFORE the settle delay
                // elapses — the pending autoplay is superseded by the pdf
                // load, and after the delay the pdf is still what is shown.
                viewModel.onArchivePreviewPageSelected(2)
                viewModel.onArchivePreviewPageSelected(3)
                awaitPreviewCondition("pdf page rendered after passing the video") {
                    val state = viewModel.uiState.value
                    state.settledOn(3) &&
                        (state.archivePreview as? ArchiveEntryPreview.Pdf)?.entryIndex == 3
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, pdf)
                advanceMainTime(1_000)
                delay(100)
                run {
                    val state = viewModel.uiState.value
                    assertTrue(
                        "passed video must not extract after the settle delay: ${state.archivePreview}",
                        state.settledOn(3) &&
                            state.archivePreview is ArchiveEntryPreview.Pdf
                    )
                }

                // A5 settle: landing on the video page and STAYING there
                // extracts it and starts playback with NO Play tap — the
                // published Video preview is what the player auto-plays
                // (playWhenReady), with the verified temp on disk.
                viewModel.onArchivePreviewPageSelected(2)
                // Staying on the page lets the settle delay elapse, which is
                // what starts the extraction on its own.
                advanceMainTime(600)
                awaitPreviewCondition("settled video autoplays without a Play tap") {
                    val state = viewModel.uiState.value
                    state.settledOn(2) &&
                        (state.archivePreview as? ArchiveEntryPreview.Video)?.entryIndex == 2
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, video)

                // The player's own Skip button crosses video -> pdf.
                viewModel.stepArchiveVideo(1)
                awaitPreviewCondition("pdf page rendered after player skip") {
                    val state = viewModel.uiState.value
                    state.settledOn(3) &&
                        (state.archivePreview as? ArchiveEntryPreview.Pdf)?.entryIndex == 3
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, pdf)

                // Interrupt the text page's load by settling back onto the
                // published pdf, then settle on the text page again: the
                // interrupted page must be retried and land with its content.
                viewModel.onArchivePreviewPageSelected(4)
                viewModel.onArchivePreviewPageSelected(3)
                viewModel.onArchivePreviewPageSelected(4)
                awaitPreviewCondition("interrupted text page repaired and rendered") {
                    val state = viewModel.uiState.value
                    state.settledOn(4) &&
                        (state.archivePreview as? ArchiveEntryPreview.Text)?.entryIndex == 4
                }
                val text = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Text
                assertTrue(text.text.contains("hello zip world"))

                // Back across the whole sequence to the first image: its temp
                // was deleted when the user left it, so this is a fresh
                // extraction, and it must land rendered like the first time.
                viewModel.onArchivePreviewPageSelected(5)
                awaitPreviewCondition("last image rendered") {
                    val state = viewModel.uiState.value
                    state.settledOn(5) &&
                        (state.archivePreview as? ArchiveEntryPreview.Image)?.entryIndex == 5
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, img3)
                viewModel.onArchivePreviewPageSelected(0)
                awaitPreviewCondition("first image re-rendered on return") {
                    val state = viewModel.uiState.value
                    state.settledOn(0) &&
                        (state.archivePreview as? ArchiveEntryPreview.Image)?.entryIndex == 0
                }
                assertTempBytes(viewModel.uiState.value.archivePreview!!, img1)

                assertFalse(
                    "no false corruption verdict may appear: $plainMessages",
                    plainMessages.any { it.contains("corrupt", ignoreCase = true) }
                )
                assertEquals(
                    "no extraction failure messages at all: $plainMessages",
                    emptyList<String>(),
                    plainMessages
                )
            } finally {
                messageJob.cancel()
                contentJob.cancel()
            }
        }
}
