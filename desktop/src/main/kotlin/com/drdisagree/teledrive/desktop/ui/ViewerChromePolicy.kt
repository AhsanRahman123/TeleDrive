package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory

/**
 * PC 1.6.27 N1: whether the desktop viewer's Download action is offered
 * for [file]. The top bar and the overflow menu share this one rule, so
 * the action can never be present in one and missing from the other.
 *
 * The rule before this round was `hasRemoteCopy && !hasLocalCopy` on
 * every surface. That made Download vanish for exactly the photo/video
 * files that already carry a local copy — the field case was a video
 * opened from a Gallery album, whose top bar and overflow menu both
 * showed no Download while the in-zip viewer's Download (which is not
 * gated on a local copy) worked. A tap still routes through the same
 * [com.drdisagree.teledrive.presentation.preview.PreviewViewModel.download]
 * enqueue as before: the A3 background transfer that leaves the current
 * view playing/viewing. The repository's enqueueDownload only requires
 * a remote copy, and saving a Downloads copy of a file that also has a
 * library-local copy is exactly what that enqueue does on every route.
 *
 * Photo/video therefore offer Download whenever a remote copy exists
 * (Android's viewer standard: Download present whenever a photo/video
 * is viewed). Every other category keeps the previous rule unchanged —
 * Download only while no local copy exists — so documents, archives
 * and other working surfaces are untouched.
 */
fun viewerShowsDownloadAction(file: DriveFile): Boolean {
    if (!file.hasRemoteCopy) return false
    if (!file.hasLocalCopy) return true
    return file.category == FileCategory.IMAGE || file.category == FileCategory.VIDEO
}

/**
 * PC 1.6.27 N5: whether the desktop preview's header (top bar) is
 * visible, given each surface's own chrome state. This is the decision
 * [DesktopPreviewScreen] applies to its top bar's AnimatedVisibility,
 * extracted so the coupling is pinned by plain unit tests (the headless
 * test scene has no VLC, so a real player's hide cannot be driven
 * there — the same bound the 1.6.26 MF-1 tests document).
 *
 * The reference behaviour is the photo viewer / Files-route player:
 * hiding the surface's UI hides the header with it, and showing the UI
 * brings it back. Before this round the in-zip media preview was the
 * exception: its player reported its visibility nowhere, so
 * [archiveMediaPreviewOpen] fell through to the always-visible branch
 * and the header stayed up over a hidden player UI.
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
