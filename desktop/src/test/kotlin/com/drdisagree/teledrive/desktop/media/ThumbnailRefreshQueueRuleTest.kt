package com.drdisagree.teledrive.desktop.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRefreshQueueRuleTest {

    @Test
    fun `automatic job skips an already cached thumbnail`() {
        assertTrue(
            shouldSkipThumbnailJob(
                cached = true,
                force = false,
                automaticPolicy = true
            )
        )
    }

    @Test
    fun `forced job replaces an already cached thumbnail`() {
        assertFalse(
            shouldSkipThumbnailJob(
                cached = true,
                force = true,
                automaticPolicy = true
            )
        )
    }

    @Test
    fun `automatic job respects manual only policy`() {
        assertTrue(
            shouldSkipThumbnailJob(
                cached = false,
                force = false,
                automaticPolicy = false
            )
        )
    }

    @Test
    fun `explicit forced job can bypass manual only policy`() {
        assertFalse(
            shouldSkipThumbnailJob(
                cached = false,
                force = true,
                automaticPolicy = false
            )
        )
    }
}
