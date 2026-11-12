package com.drdisagree.teledrive.presentation.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileClipboardTest {

    @Test
    fun `cut payload remains available until a successful move clears it`() {
        val clipboard = FileClipboard()
        clipboard.cut(CutItems(fileIds = listOf("file-1"), folderIds = listOf("folder-1")))

        val pasted = requireNotNull(clipboard.peek())
        assertTrue(pasted.isCut)
        assertSame(pasted, clipboard.peek())

        assertTrue(clipboard.clearIfMatches(pasted))
        assertNull(clipboard.peek())
    }

    @Test
    fun `a newer cut of the same IDs survives an older move completion`() {
        val clipboard = FileClipboard()
        clipboard.cut(CutItems(fileIds = listOf("file-1"), folderIds = emptyList()))

        val earlierPaste = requireNotNull(clipboard.peek())
        clipboard.cut(CutItems(fileIds = listOf("file-1"), folderIds = emptyList()))

        assertFalse(clipboard.clearIfMatches(earlierPaste))
        assertTrue(requireNotNull(clipboard.peek()).isCut)
    }

    @Test
    fun `copy payload is not consumed by cut cleanup`() {
        val clipboard = FileClipboard()
        clipboard.copy(CutItems(fileIds = listOf("file-1"), folderIds = emptyList(), isCut = false))

        val copied = requireNotNull(clipboard.peek())
        assertFalse(clipboard.clearIfMatches(copied))
        assertSame(copied, clipboard.peek())
    }
}
