package com.drdisagree.teledrive.domain.model

/**
 * The persisted thumbnail size for archive (ZIP) contents lists — the
 * Big / Medium / Small switcher in the ZIP viewer (PC 1.6.26 item P7;
 * PC R3 item P6 re-mapped the Big step).
 *
 * Persisted in DataStore under the key `archive_thumbnail_size` (the enum
 * NAME) via [com.drdisagree.teledrive.domain.model.UserPreferences.archiveThumbnailSize];
 * MEDIUM (56 dp) is the size the lists shipped with before the switcher
 * existed, so it is the default and upgrades change nothing until the user
 * picks another step. Because persistence stores only the name, a value
 * saved by an older build ("BIG") still decodes to the same enum entry —
 * what changed in R3 is only the PC geometry that entry maps to, below.
 *
 * R3 P6 — the px values behind the enum, stated explicitly: SMALL and
 * MEDIUM are unchanged (40 / 56 dp square slots). BIG changed: it was an
 * 80 dp square (rendered as a circle in a wide, mostly-empty row, which
 * still read as small on PC) and is now a wide preview tile of
 * [widthDp] 160 × [dp] 120 dp with rounded corners, so the row's height
 * and width are actually used by the thumbnail. [dp] remains the slot
 * height for every step (and the width for SMALL / MEDIUM); [widthDp] is
 * the slot width.
 *
 * Decode size follows the step on PC ([decodePx]): SMALL / MEDIUM keep
 * the established bounded 256 px decode; BIG decodes at 512 px so the
 * bigger tile is genuinely sharper, not an upscale. The desktop
 * thumbnail store keys its cache per decode size, so a 256 px decode is
 * never served for the Big tile. (This enum's only consumer of the dp
 * values is the desktop archive list; nothing on the Android line reads
 * them.)
 */
enum class ArchiveThumbnailSize(
    val dp: Int,
    val widthDp: Int,
    val decodePx: Int
) {
    SMALL(40, 40, 256),
    MEDIUM(56, 56, 256),
    BIG(120, 160, 512)
}
