package com.drdisagree.teledrive.data.remote.telegram

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.testing.unused
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestPartHashTest {

    @Test
    fun `part hash survives a caption encode and decode round trip`() {
        val manifest = RemoteFileManifest(
            version = RemoteFileManifest.PART_VERSION,
            fileId = "file-1",
            name = "archive.bin",
            folderPath = "/",
            mimeType = "application/octet-stream",
            sizeBytes = 512,
            createdAt = 1,
            modifiedAt = 2,
            partCount = 2,
            partIndex = 0,
            partOffset = 0,
            partSize = 256,
            partHash = "a".repeat(64)
        )
        val codec = ManifestCodec(StreamCrypto(), unused())

        val decoded = codec.decode(codec.encode(manifest, encrypt = false))

        assertEquals(manifest, decoded)
    }
}
