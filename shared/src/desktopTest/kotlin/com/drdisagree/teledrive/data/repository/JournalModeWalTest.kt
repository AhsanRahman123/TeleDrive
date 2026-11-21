package com.drdisagree.teledrive.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 11 (report 29 §4): the desktop database builder configured no
 * journal mode at all, leaving the mode to the driver default. In
 * rollback-journal mode every startup/background write transaction
 * blocks Home's readers outright; WAL lets readers and the single
 * writer proceed concurrently. The production builder now pins WAL —
 * this test pins that a database opened the same way really reports
 * `wal` and keeps working.
 */
class JournalModeWalTest {

    @Test
    fun `database opened with WAL pinned reports wal and stores data`() = runBlocking {
        val dbFile = File.createTempFile("teledrive-wal", ".db")
        val database = Room.databaseBuilder<TeleDriveDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val mode = database.useReaderConnection { transactor ->
                transactor.usePrepared("PRAGMA journal_mode") { statement ->
                    if (statement.step()) statement.getText(0) else ""
                }
            }
            assertEquals("wal", mode)

            database.folderDao().upsert(
                FolderEntity(
                    id = "f1",
                    chatId = TEST_CHAT,
                    parentId = null,
                    name = "Folder",
                    createdAt = 1_000,
                    modifiedAt = 1_000,
                    changedAt = 1_000
                )
            )
            assertEquals("Folder", database.folderDao().byId("f1")?.name)
        } finally {
            database.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }
}
