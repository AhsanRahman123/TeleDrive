package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory

/**
 * PC 1.6.27 item P5: whether the desktop preview's header (top bar) is
 * visible, given each surface's own chrome state. This is the decision
 * [DesktopPreviewScreen] applies to its top bar's AnimatedVisibility,
 * extracted so the coupling is pinned by plain unit tests (the headless
 * test scene has no VLC, so a real player's hide cannot be driven
 * there — the same bound the 1.6.26 MF-1 tests document).
 *
 * The reference behaviour is the photo viewer and the Files-route
 * player, which already couple header + chrome: hiding the surface's
 * UI hides the header with it, and showing the UI brings it back.
 * Before this round the in-zip media preview was the exception: its
 * player reported its visibility nowhere, so an open in-zip media
 * preview fell through to the always-visible branch and the header
 * (and the in-zip overlay bar) stayed up over a hidden player UI.
 *
 * Priority order matters: a route media file's own player wins first;
 * an open in-zip media preview (the viewed file itself is the ZIP)
 * follows the in-zip player's chrome; a still image follows the image
 * chrome; everything else (zip contents list, documents, text) always
 * shows the header.
 */
fun previewHeaderVisible(
    mediaCategory: FileCategory?,
    archiveMediaPreviewOpen: Boolean,
    fileCategory: FileCategory?,
    playerChromeVisible: Boolean,
    archivePlayerChromeVisible: Boolean,
    imageChromeVisible: Boolean
): Boolean = when {
    mediaCategory != null -> playerChromeVisible
    archiveMediaPreviewOpen -> archivePlayerChromeVisible
    fileCategory == FileCategory.IMAGE -> imageChromeVisible
    else -> true
}

/**
 * R3 item P1: whether the PC viewer's Download action is offered for
 * [file] — the single condition BOTH viewer surfaces (the top-bar button
 * and the overflow menu) use, so they can never disagree again.
 *
 * The field failure: a video opened from a Gallery ALBUM showed only
 * trash / Info / ⋮ in the top bar, and the ⋮ menu had no Download either.
 * Every PC viewer route (Files, Gallery, Gallery album, Favorites /
 * Collection, Search, Transfers-opened-by-fileId) converges on the one
 * DesktopPreviewScreen with the same DriveFile, so the gap was not the
 * route — it was this condition: Download required
 * `hasRemoteCopy && !hasLocalCopy`, and the Gallery-album video already
 * had a local copy (his menu proved it: "Free up space" and "Show in
 * file manager" only render with a local copy present). A media file
 * that is playing from its local copy therefore had NO way to ask for
 * the established background download.
 *
 * The rule now: for media (image / video / audio) Download is offered
 * whenever a remote copy exists — tapping it routes through the same
 * PreviewViewModel.download → TransferRepository.enqueueDownload as
 * always (the round-2 A3 behaviour: the current view keeps playing, the
 * transfer runs in the background to completion). All other categories
 * keep the previous rule byte-for-byte: remote copy and no local copy
 * yet (a downloaded document gains "Free up space" instead, as before).
 */
fun viewerShowsDownloadAction(file: DriveFile): Boolean =
    if (file.category == FileCategory.IMAGE ||
        file.category == FileCategory.VIDEO ||
        file.category == FileCategory.AUDIO
    ) {
        file.hasRemoteCopy
    } else {
        file.hasRemoteCopy && !file.hasLocalCopy
    }
