package com.drdisagree.teledrive.domain.model

/**
 * The persisted thumbnail size for archive (ZIP) contents lists — the
 * Big / Medium / Small switcher in the ZIP viewer (PC 1.6.26 item P7;
 * PC 1.6.27 item N6 re-mapped the PC steps).
 *
 * Persisted in DataStore under the key `archive_thumbnail_size` (the enum
 * name) via [com.drdisagree.teledrive.domain.model.UserPreferences.archiveThumbnailSize];
 * MEDIUM (56 dp) is the size the lists shipped with before the switcher
 * existed, so it is the default and upgrades change nothing until the user
 * picks another step.
 *
 * PC 1.6.27 (N6): on PC, BIG at 80 dp still read as "a small circle in a
 * wide empty row" in the field, so the PC steps are now:
 *  - [dp] is the slot height (and the slot width for SMALL / MEDIUM, which
 *    keep the round slot);
 *  - BIG's slot is a wide preview tile: [widthDp] (152) × [dp] (112),
 *    rounded-rectangle instead of a circle, so the row's height and width
 *    are actually used by the thumbnail;
 *  - [decodePx] is the decode cap that follows the step: SMALL / MEDIUM
 *    keep the established 256 px policy, BIG decodes at 512 px so the
 *    bigger tile is genuinely sharper. 512 px is the bound: a 512×512
 *    ARGB bitmap is 1 MB transiently during decode, the on-disk JPEG
 *    stays in the same tens-of-KB class, and the store's 32 MB cache
 *    cap is unchanged.
 *
 * This enum is the PC line's copy; the Android line carries its own and
 * its steps are unchanged.
 */
enum class ArchiveThumbnailSize(
    val dp: Int,
    val widthDp: Int,
    val decodePx: Int
) {
    SMALL(40, 40, 256),
    MEDIUM(56, 56, 256),
    BIG(112, 152, 512)
}
