package com.drdisagree.teledrive.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {

    @Test
    fun `audio video and image formats map to their media categories`() {
        assertEquals(FileCategory.AUDIO, FileCategory.fromMimeType("audio/mpeg"))
        assertEquals(FileCategory.AUDIO, FileCategory.fromMimeType("audio/opus"))
        assertEquals(FileCategory.VIDEO, FileCategory.fromMimeType("video/mp4"))
        assertEquals(FileCategory.VIDEO, FileCategory.fromMimeType("video/x-matroska"))
        assertEquals(FileCategory.IMAGE, FileCategory.fromMimeType("image/webp"))
    }

    @Test
    fun `office structured text and ebook formats map to documents`() {
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromMimeType("application/msword"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromMimeType("application/vnd.ms-excel"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromMimeType("application/vnd.ms-powerpoint"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromMimeType("application/json"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromMimeType("application/epub+zip"))
    }

    @Test
    fun `common compressed formats map to archives`() {
        assertEquals(FileCategory.ARCHIVE, FileCategory.fromMimeType("application/x-7z-compressed"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.fromMimeType("application/x-bzip2"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.fromMimeType("application/x-xz"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.fromMimeType("application/zstd"))
    }
}
