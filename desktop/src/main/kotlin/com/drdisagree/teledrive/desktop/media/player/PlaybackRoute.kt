package com.drdisagree.teledrive.desktop.media.player

import java.io.File

/**
 * Which viewing route a desktop playback MRL belongs to (PC 1.6.27,
 * comparison round — N3 widened).
 *
 * Every PC video route — the normal Files/Gallery preview, the
 * local-file preview and the in-zip preview — converges on the same
 * [DesktopMediaPlayer], the same PlayerSession and the same
 * [composeVideoSurface], so the N3 negotiated-buffer-format diagnostics
 * in the surface cover all of them. What the shipped diagnostics could
 * NOT do is say WHICH route a given negotiation line belonged to: the
 * surface logs only the negotiated size. The viewer logs one start line
 * per MRL carrying this route, immediately before that start's
 * negotiation lines, so one field log capture attributes every
 * negotiation to its route and file.
 *
 * The classification is derived from the MRL alone, from how each route
 * hands media to the player:
 * - a streamed normal-route file is served by the loopback
 *   MediaStreamServer, so its MRL is an `http://127.0.0.1…` URL;
 * - an in-zip entry plays from the archive layer's temp file, whose
 *   name carries the `zip-entry-` prefix (remote archives,
 *   RemoteArchiveService) or the `local-entry-` prefix (local archives,
 *   LocalArchiveService);
 * - anything else is a plain local file path (a downloaded/local
 *   normal-route file, or a local-file preview row).
 *
 * Pure and side-effect free so host tests can pin the boundaries.
 */
internal enum class PlaybackRoute {
    STREAMED,
    IN_ZIP,
    LOCAL
}

internal fun playbackRouteForMrl(mrl: String): PlaybackRoute = when {
    mrl.startsWith("http://127.0.0.1") || mrl.startsWith("http://localhost") ->
        PlaybackRoute.STREAMED

    File(mrl).name.startsWith(REMOTE_ARCHIVE_TEMP_PREFIX) ||
        File(mrl).name.startsWith(LOCAL_ARCHIVE_TEMP_PREFIX) -> PlaybackRoute.IN_ZIP

    else -> PlaybackRoute.LOCAL
}

/** Temp-file prefixes used by the archive extraction services. */
private const val REMOTE_ARCHIVE_TEMP_PREFIX = "zip-entry-"
private const val LOCAL_ARCHIVE_TEMP_PREFIX = "local-entry-"
