package com.drdisagree.teledrive.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `common audio and video extensions are recognized independently of platform MIME tables`() {
        assertEquals("audio/mpeg", MimeTypes.fromFileName("song.mp3"))
        assertEquals("audio/opus", MimeTypes.fromFileName("voice.opus"))
        assertEquals("audio/flac", MimeTypes.fromFileName("lossless.flac"))
        assertEquals("video/mp4", MimeTypes.fromFileName("movie.mp4"))
        assertEquals("video/x-matroska", MimeTypes.fromFileName("movie.mkv"))
        assertEquals("video/quicktime", MimeTypes.fromFileName("clip.mov"))
        assertEquals("video/webm", MimeTypes.fromFileName("clip.webm"))
    }

    @Test
    fun `common image document and archive extensions are recognized`() {
        assertEquals("image/jpeg", MimeTypes.fromFileName("photo.JPG"))
        assertEquals("image/png", MimeTypes.fromFileName("image.png"))
        assertEquals("image/webp", MimeTypes.fromFileName("image.webp"))
        assertEquals("application/pdf", MimeTypes.fromFileName("report.pdf"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", MimeTypes.fromFileName("report.docx"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", MimeTypes.fromFileName("sheet.xlsx"))
        assertEquals("application/zip", MimeTypes.fromFileName("archive.zip"))
        assertEquals("application/x-7z-compressed", MimeTypes.fromFileName("archive.7z"))
        assertEquals("application/x-tar", MimeTypes.fromFileName("archive.tar"))
        assertEquals("application/x-compressed-tar", MimeTypes.fromFileName("archive.tar.gz"))
        assertEquals("application/x-compressed-tar", MimeTypes.fromFileName("archive.tar.bz2"))
        assertEquals("application/x-compressed-tar", MimeTypes.fromFileName("archive.txz"))
    }

    @Test
    fun `generic Telegram MIME falls back to the filename extension`() {
        assertEquals("audio/mpeg", MimeTypes.resolve("track.mp3", "application/octet-stream"))
        assertEquals("video/x-matroska", MimeTypes.resolve("movie.mkv", ""))
    }

    @Test
    fun `manifest MIME falls back to meaningful Telegram MIME before the extension`() {
        assertEquals(
            "audio/ogg",
            MimeTypes.resolve("voice.unknown", "application/octet-stream", "audio/ogg")
        )
    }

    @Test
    fun `specific declared MIME takes precedence over extension inference`() {
        assertEquals("application/pdf", MimeTypes.resolve("download.bin", "application/pdf"))
    }

    @Test
    fun `additional media and camera formats are recognized`() {
        assertEquals("video/mxf", MimeTypes.fromFileName("camera.mxf"))
        assertEquals("video/x-pn-realvideo", MimeTypes.fromFileName("camera.rmvb"))
        assertEquals("image/x-adobe-dng", MimeTypes.fromFileName("photo.dng"))
        assertEquals("image/x-nikon-nef", MimeTypes.fromFileName("photo.nef"))
        assertTrue(MimeTypes.isVideo(MimeTypes.fromFileName("camera.mxf")))
        assertTrue(MimeTypes.isImage(MimeTypes.fromFileName("photo.nef")))
    }

    @Test
    fun `source code formats are recognized as text previews`() {
        assertEquals("text/javascript", MimeTypes.fromFileName("app.mjs"))
        assertEquals("text/x-kotlin", MimeTypes.fromFileName("Main.kt"))
        assertEquals("text/x-powershell", MimeTypes.fromFileName("setup.ps1"))
        assertTrue(MimeTypes.isText(MimeTypes.fromFileName("app.mjs")))
        assertTrue(MimeTypes.isText(MimeTypes.fromFileName("settings.toml")))
    }

    @Test
    fun `all advertised archive MIME types are classified as archives`() {
        assertTrue(MimeTypes.isArchive(MimeTypes.fromFileName("backup.rar")))
        assertTrue(MimeTypes.isArchive(MimeTypes.fromFileName("backup.cpio")))
        assertTrue(MimeTypes.isArchive(MimeTypes.fromFileName("backup.lz4")))
        assertTrue(MimeTypes.isArchive(MimeTypes.fromFileName("backup.ar")))
    }

    @Test
    fun `recognized media MIME is classified for preview routing`() {
        assertTrue(MimeTypes.isAudio(MimeTypes.resolve("voice.opus", "application/octet-stream")))
        assertTrue(MimeTypes.isVideo(MimeTypes.resolve("clip.mp4", "application/octet-stream")))
        assertTrue(MimeTypes.isImage(MimeTypes.resolve("photo.png", "application/octet-stream")))
    }
}
