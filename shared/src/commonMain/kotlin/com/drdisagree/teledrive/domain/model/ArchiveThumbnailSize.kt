package com.drdisagree.teledrive.domain.model

/**
 * The persisted thumbnail size for archive (ZIP) contents lists — the
 * Big / Medium / Small switcher in the ZIP viewer (PC 1.6.26 item P7).
 *
 * Persisted in DataStore under the key `archive_thumbnail_size` (the enum
 * name) via [com.drdisagree.teledrive.domain.model.UserPreferences.archiveThumbnailSize];
 * MEDIUM (56 dp) is the size the lists shipped with before the switcher
 * existed, so it is the default and upgrades change nothing until the user
 * picks another step. Decode size does NOT follow this value: thumbnails
 * are still decoded once at the policy's bounded 256 px and only the
 * display slot changes, so BIG never triggers a larger bitmap decode.
 */
enum class ArchiveThumbnailSize(val dp: Int) {
    SMALL(40),
    MEDIUM(56),
    BIG(80)
}
