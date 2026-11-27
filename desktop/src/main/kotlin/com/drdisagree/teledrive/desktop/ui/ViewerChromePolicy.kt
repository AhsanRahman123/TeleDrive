package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory

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
