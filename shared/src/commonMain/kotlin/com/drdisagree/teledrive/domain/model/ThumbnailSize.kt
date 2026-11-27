package com.drdisagree.teledrive.domain.model

/**
 * The ZIP-contents thumbnail size steps (vc184 P7), shared by both platforms:
 * the persisted choice lives in [UserPreferences] (DataStore, no Room
 * migration) and each platform's archive list renders its rows at [slotDp].
 *
 * Steps are Pranto's settled scope: Small 40dp / Medium 56dp / Big 80dp —
 * Medium is the vc183 size (56dp, which he called "feels small"), so the
 * default stays MEDIUM for continuity and Big is genuinely bigger.
 *
 * Decode stays bounded independently of the display slot: archive-entry
 * thumbnails are generated at the single fixed
 * `ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX` (256px) target for every
 * step, so choosing Big never raises bitmap decode size or invalidates the
 * existing thumbnail cache — the cached bitmap is simply shown larger.
 */
enum class ThumbnailSize(val slotDp: Int) {
    SMALL(40),
    MEDIUM(56),
    BIG(80)
}
