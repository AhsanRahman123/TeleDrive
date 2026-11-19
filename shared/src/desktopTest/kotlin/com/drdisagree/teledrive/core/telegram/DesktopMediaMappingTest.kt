package com.drdisagree.teledrive.core.telegram

import it.tdlight.jni.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 867b4593 media-type mapping (voice/video notes, animations, stickers) and the
 * remoteDocument name/MIME/size normalization ported from Android. TDLight's TdApi
 * classes have no-arg constructors with public fields, so messages are built directly.
 */
class DesktopMediaMappingTest {

    private fun file(remoteId: String, size: Long = 1_000L, expectedSize: Long = size): TdApi.File =
        TdApi.File().apply {
            this.size = size
            this.expectedSize = expectedSize
            remote = TdApi.RemoteFile().apply {
                id = remoteId
                uniqueId = "unique-$remoteId"
            }
        }

    private fun message(id: Long, content: TdApi.MessageContent): TdApi.Message =
        TdApi.Message().apply {
            this.id = id
            chatId = 99L
            date = 1_700_000_000
            this.content = content
        }

    @Test
    fun `voice note maps to a synthesized ogg name with its own mime type`() {
        val doc = message(
            42,
            TdApi.MessageVoiceNote().apply {
                voiceNote = TdApi.VoiceNote().apply {
                    mimeType = "audio/ogg"
                    voice = file("voice-remote")
                }
            }
        ).toRemoteDocument()

        assertEquals("voice_note_42.ogg", doc?.fileName)
        assertEquals("audio/ogg", doc?.mimeType)
        assertEquals("voice-remote", doc?.remoteFileId)
        assertEquals("unique-voice-remote", doc?.uniqueFileId)
        assertEquals(99L, doc?.chatId)
        assertEquals(42L, doc?.messageId)
        assertEquals("", doc?.caption)
    }

    @Test
    fun `video note maps to a synthesized mp4 name`() {
        val doc = message(
            7,
            TdApi.MessageVideoNote().apply {
                videoNote = TdApi.VideoNote().apply {
                    video = file("round-video")
                }
            }
        ).toRemoteDocument()

        assertEquals("video_note_7.mp4", doc?.fileName)
        assertEquals("video/mp4", doc?.mimeType)
        assertEquals("round-video", doc?.remoteFileId)
    }

    @Test
    fun `animation keeps its file name and caption`() {
        val doc = message(
            5,
            TdApi.MessageAnimation().apply {
                animation = TdApi.Animation().apply {
                    fileName = "funny.mp4"
                    mimeType = "video/mp4"
                    animation = file("anim-remote")
                }
                caption = TdApi.FormattedText("a caption", emptyArray())
            }
        ).toRemoteDocument()

        assertEquals("funny.mp4", doc?.fileName)
        assertEquals("video/mp4", doc?.mimeType)
        assertEquals("a caption", doc?.caption)
    }

    @Test
    fun `animation with an empty name gets a synthesized name and the expected size`() {
        val doc = message(
            9,
            TdApi.MessageAnimation().apply {
                animation = TdApi.Animation().apply {
                    fileName = ""
                    mimeType = "video/mp4"
                    animation = file("anim-noname", size = 0L, expectedSize = 4_321L)
                }
            }
        ).toRemoteDocument()

        assertEquals("telegram_file_9.mp4", doc?.fileName)
        assertEquals(4_321L, doc?.sizeBytes)
    }

    @Test
    fun `webp and tgs stickers map to their formats`() {
        val webp = message(
            11,
            TdApi.MessageSticker().apply {
                sticker = TdApi.Sticker().apply {
                    format = TdApi.StickerFormatWebp()
                    sticker = file("sticker-webp")
                }
            }
        ).toRemoteDocument()
        assertEquals("sticker_11.webp", webp?.fileName)
        assertEquals("image/webp", webp?.mimeType)

        val tgs = message(
            12,
            TdApi.MessageSticker().apply {
                sticker = TdApi.Sticker().apply {
                    format = TdApi.StickerFormatTgs()
                    sticker = file("sticker-tgs")
                }
            }
        ).toRemoteDocument()
        assertEquals("sticker_12.tgs", tgs?.fileName)
        assertEquals("application/x-tgsticker", tgs?.mimeType)

        val webm = message(
            13,
            TdApi.MessageSticker().apply {
                sticker = TdApi.Sticker().apply {
                    format = TdApi.StickerFormatWebm()
                    sticker = file("sticker-webm")
                }
            }
        ).toRemoteDocument()
        assertEquals("sticker_13.webm", webm?.fileName)
        assertEquals("video/webm", webm?.mimeType)
    }

    @Test
    fun `document name is stripped of path separators`() {
        val doc = message(
            21,
            TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    fileName = "C:\\Users\\test\\report.pdf"
                    mimeType = "application/pdf"
                    document = file("doc-remote")
                }
            }
        ).toRemoteDocument()

        assertEquals("report.pdf", doc?.fileName)
        assertEquals("application/pdf", doc?.mimeType)
    }

    @Test
    fun `document with a dot or empty name gets a synthesized name from its mime type`() {
        val dotted = message(
            22,
            TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    fileName = "."
                    mimeType = "application/pdf"
                    document = file("doc-dot")
                }
            }
        ).toRemoteDocument()
        assertEquals("telegram_file_22.pdf", dotted?.fileName)

        val generic = message(
            23,
            TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    fileName = "archive.bin"
                    mimeType = "application/octet-stream"
                    document = file("doc-bin")
                }
            }
        ).toRemoteDocument()
        // A generic declared type is resolved from the name instead of kept verbatim.
        assertEquals("archive.bin", generic?.fileName)
        assertEquals("application/octet-stream", generic?.mimeType)
    }

    @Test
    fun `photo picks the largest size`() {
        val doc = message(
            31,
            TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply {
                    sizes = arrayOf(
                        TdApi.PhotoSize().apply { photo = file("photo-small", size = 100L) },
                        TdApi.PhotoSize().apply { photo = file("photo-large", size = 900L) },
                        TdApi.PhotoSize().apply { photo = file("photo-mid", size = 400L) }
                    )
                }
            }
        ).toRemoteDocument()

        assertEquals("photo-large", doc?.remoteFileId)
        assertEquals("photo_31.jpg", doc?.fileName)
        assertEquals("image/jpeg", doc?.mimeType)
    }

    @Test
    fun `message without a usable remote id maps to nothing`() {
        val noRemote = message(
            41,
            TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    fileName = "x.pdf"
                    mimeType = "application/pdf"
                    document = TdApi.File()
                }
            }
        )
        assertNull(noRemote.toRemoteDocument())

        val blankRemote = message(
            42,
            TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    fileName = "x.pdf"
                    mimeType = "application/pdf"
                    document = file("  ".trim())
                }
            }
        )
        assertNull(blankRemote.toRemoteDocument())
    }

    @Test
    fun `mediaFile returns the attached file and nothing for text`() {
        val video = message(
            51,
            TdApi.MessageVideo().apply {
                video = TdApi.Video().apply {
                    fileName = "clip.mp4"
                    mimeType = "video/mp4"
                    video = file("video-remote")
                }
            }
        )
        assertEquals("video-remote", video.mediaFile()?.remote?.id)

        val text = message(52, TdApi.MessageText().apply {
            text = TdApi.FormattedText("hello", emptyArray())
        })
        assertNull(text.mediaFile())
        assertNull(text.toRemoteDocument())
    }
}
