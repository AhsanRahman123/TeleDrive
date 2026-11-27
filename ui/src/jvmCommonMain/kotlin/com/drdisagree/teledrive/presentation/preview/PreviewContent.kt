package com.drdisagree.teledrive.presentation.preview

import org.jetbrains.compose.resources.StringResource
import com.drdisagree.teledrive.core.media.MediaPart
import com.drdisagree.teledrive.domain.model.TransferTask

sealed interface PreviewContent {

    data object Loading : PreviewContent

    data class DownloadProgress(val transferred: Long, val total: Long) : PreviewContent

    /** [model] is a file path or a ByteArray, both renderable by Coil. */
    data class Image(val model: Any) : PreviewContent

    data class LocalMedia(val path: String, val isAudio: Boolean) : PreviewContent

    data class StreamedMedia(
        val remoteFileId: String,
        val isAudio: Boolean,
        val parts: List<MediaPart> = emptyList(),
        val encrypted: Boolean = false,
        val chatId: Long? = null,
        val messageId: Long? = null
    ) : PreviewContent

    data class Pdf(val path: String) : PreviewContent

    data class PlainText(val text: String, val truncated: Boolean) : PreviewContent

    /**
     * An HTML document carried as a string (never a path/URL), so the safe viewer
     * can render it with no base URL, no JavaScript and no network access.
     * Desktop has no WebView: its screen renders this as readable text under the
     * same safe-mode caption instead.
     */
    data class Html(val text: String, val truncated: Boolean) : PreviewContent

    /** [remote] means the listing came from the online copy: entries can be downloaded one by one. */
    data class Archive(
        val entries: List<ArchiveEntry>,
        val format: String,
        val remote: Boolean = false
    ) : PreviewContent

    data class RequiresDownload(val sizeBytes: Long) : PreviewContent

    /** Optional local source lets desktop open formats without an in-app renderer externally. */
    data class Unsupported(
        val reasonRes: StringResource,
        val localPath: String? = null
    ) : PreviewContent

    /** [messageText], when set, is a specific plain-language failure that wins over [messageRes]. */
    data class Failed(
        val messageRes: StringResource,
        val messageText: String? = null
    ) : PreviewContent
}

/**
 * What the viewer shows for a file while a background transfer for that same
 * file is active (vc184 A3, ported from the Android fix — the desktop viewer
 * shares this content pipeline and had the identical hijack). The transfer
 * overlay exists for ONE case: the Download-to-view gate — the file cannot
 * render at all yet ([PreviewContent.RequiresDownload]), the user tapped
 * Download on that gate, and the viewer follows the transfer's progress
 * until the local copy lands and the resolved content replaces the gate.
 *
 * Every renderable content passes through UNCHANGED. Before vc184 the active
 * transfer replaced the resolved content unconditionally, so tapping
 * Download in the video viewer swapped the playing video for a stuck
 * "Preparing… 0 B" progress screen for the whole life of the transfer — the
 * download hijacked the player it was supposed to run behind. A background
 * download must never take over a viewer that can already show/play the
 * file. Pure, so the contract is host-testable.
 */
fun viewerContentDuringTransfer(
    resolved: PreviewContent,
    incoming: TransferTask?
): PreviewContent =
    if (incoming != null && resolved is PreviewContent.RequiresDownload) {
        PreviewContent.DownloadProgress(
            transferred = incoming.transferredBytes,
            total = incoming.sizeBytes
        )
    } else {
        resolved
    }
