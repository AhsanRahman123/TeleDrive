package com.drdisagree.teledrive.data.local

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntegrityMigrationTest {

    @Test
    fun `version 17 part rows upgrade with the new hash column empty`() = runBlocking {
        val file = File.createTempFile("teledrive-v17", ".db")
        file.delete()
        createLegacyDatabase(
            file,
            version = 17,
            """INSERT INTO file_parts (fileId, partIndex, chatId, messageId, remoteFileId,
               remoteUniqueId, plainOffset, plainSize, storedSize, uploadedAt)
               VALUES ('f1', 0, 7, 42, 'remote-1', 'unique-1', 0, 512, 512, 1234)"""
        )

        val database = openMigrated(file)
        try {
            val part = database.filePartDao().partsOf("f1").single()
            assertEquals(0, part.partIndex)
            assertEquals(7L, part.chatId)
            assertEquals(42L, part.messageId)
            assertEquals("remote-1", part.remoteFileId)
            assertEquals("unique-1", part.remoteUniqueId)
            assertEquals(0L, part.plainOffset)
            assertEquals(512L, part.plainSize)
            assertNull(part.plainHash)
        } finally {
            database.close()
            file.delete()
        }
    }
}
