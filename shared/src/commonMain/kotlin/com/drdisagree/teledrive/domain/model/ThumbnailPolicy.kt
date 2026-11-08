package com.drdisagree.teledrive.domain.model

/**
 * INHERIT is a UI-only choice. Persisted rows use AUTOMATIC or MANUAL_ONLY.
 *
 * Existing indexed files default to MANUAL_ONLY. Files completed by a new upload are explicitly
 * marked AUTOMATIC so only new uploads are prepared in the background by default.
 */
enum class ThumbnailPolicy {
    INHERIT,
    AUTOMATIC,
    MANUAL_ONLY
}
