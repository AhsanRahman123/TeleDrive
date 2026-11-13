package com.drdisagree.teledrive.presentation.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesSelectionModeTest {

    @Test
    fun `empty selection during drag keeps selection toolbar active`() {
        assertTrue(
            keepFilesSelectionMode(
                rangeSelectionActive = true,
                selectedFileCount = 0,
                selectedFolderCount = 0
            )
        )
    }

    @Test
    fun `selection mode ends after drag when no items remain selected`() {
        assertFalse(
            keepFilesSelectionMode(
                rangeSelectionActive = false,
                selectedFileCount = 0,
                selectedFolderCount = 0
            )
        )
    }

    @Test
    fun `selection remains active after drag when any item remains selected`() {
        assertTrue(
            keepFilesSelectionMode(
                rangeSelectionActive = false,
                selectedFileCount = 2,
                selectedFolderCount = 0
            )
        )
        assertTrue(
            keepFilesSelectionMode(
                rangeSelectionActive = false,
                selectedFileCount = 0,
                selectedFolderCount = 1
            )
        )
    }
}
