package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.domain.model.ThumbnailSize
import com.drdisagree.teledrive.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 P7: the ZIP thumbnail switcher's shared steps (domain model in
 * commonMain, persisted via UserPreferences/DataStore — the same setting the
 * PC side reads) and the bounded-decode invariant.
 */
class ThumbnailSizeTest {

    @Test
    fun `steps are small 40 medium 56 big 80`() {
        assertEquals(40, ThumbnailSize.SMALL.slotDp)
        assertEquals(56, ThumbnailSize.MEDIUM.slotDp)
        assertEquals(80, ThumbnailSize.BIG.slotDp)
    }

    @Test
    fun `big is genuinely bigger than the vc183 fixed size and steps increase`() {
        assertTrue(ThumbnailSize.BIG.slotDp > ThumbnailSize.MEDIUM.slotDp)
        assertTrue(ThumbnailSize.MEDIUM.slotDp > ThumbnailSize.SMALL.slotDp)
        // MEDIUM is the old fixed 56dp, so existing users see no change
        // until they pick a different step.
        assertEquals(56, ThumbnailSize.MEDIUM.slotDp)
    }

    @Test
    fun `default preference is medium`() {
        assertEquals(ThumbnailSize.MEDIUM, UserPreferences().archiveThumbnailSize)
    }

    @Test
    fun `every step round-trips through its persisted name`() {
        for (size in ThumbnailSize.entries) {
            assertEquals(size, ThumbnailSize.valueOf(size.name))
        }
    }

    @Test
    fun `decode target stays bounded and independent of the display step`() {
        // The archive thumbnail decode target is one fixed constant for all
        // steps: choosing Big enlarges the display slot only, never the
        // bitmap decode size, and the cache keys do not change with the step.
        assertEquals(256, ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX)
        assertTrue(ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX <= 512)
    }
}
