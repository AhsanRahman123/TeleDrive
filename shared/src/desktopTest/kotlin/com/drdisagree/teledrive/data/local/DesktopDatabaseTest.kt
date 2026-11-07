package com.drdisagree.teledrive.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.telegram.TelegramProxyType
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.entity.ProxyEntity
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDatabaseTest {

    private fun openDatabase(file: File): TeleDriveDatabase =
        Room.databaseBuilder<TeleDriveDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    @Test
    fun `proxy row survives a round trip`() = runBlocking {
        val file = File.createTempFile("teledrive", ".db")
        val database = openDatabase(file)
        try {
            val proxy = ProxyEntity(
                id = "p1",
                label = "Test",
                type = TelegramProxyType.SOCKS5,
                host = "127.0.0.1",
                port = 1080,
                addedAt = 42L
            )
            database.proxyDao().upsert(proxy)
            assertEquals(listOf(proxy), database.proxyDao().all())
        } finally {
            database.close()
            file.delete()
        }
    }

    @Test
    fun `gallery albums include empty folders but not folders with documents only`() = runBlocking {
        val file = File.createTempFile("teledrive", ".db")
        val database = openDatabase(file)
        try {
            database.folderDao().upsert(
                FolderEntity(
                    id = "empty-album",
                    chatId = 7L,
                    parentId = null,
                    name = "Empty album",
                    createdAt = 10L,
                    modifiedAt = 20L
                )
            )
            database.folderDao().upsert(
                FolderEntity(
                    id = "documents-only",
                    chatId = 7L,
                    parentId = null,
                    name = "Documents only",
                    createdAt = 10L,
                    modifiedAt = 30L
                )
            )
            database.fileDao().upsert(
                FileEntity(
                    id = "document",
                    folderId = "documents-only",
                    name = "notes.pdf",
                    sizeBytes = 100L,
                    mimeType = "application/pdf",
                    category = FileCategory.DOCUMENT,
                    localPath = null,
                    contentHash = null,
                    chatId = 7L,
                    messageId = null,
                    remoteFileId = null,
                    remoteUniqueId = null,
                    backupState = BackupState.NONE,
                    createdAt = 10L,
                    modifiedAt = 30L,
                    addedAt = 30L
                )
            )

            val albums = database.fileDao()
                .observeAlbumsScoped(showHidden = false, showArchived = false, chatId = 7L)
                .first()

            assertEquals(1, albums.size)
            assertEquals("empty-album", albums.single().folderId)
            assertEquals("Empty album", albums.single().name)
            assertEquals(0, albums.single().itemCount)
            assertNull(albums.single().coverFileId)
        } finally {
            database.close()
            file.delete()
        }
    }

    @Test
    fun `gallery albums group media and keep an empty album visible`() = runBlocking {
        val file = File.createTempFile("teledrive", ".db")
        val database = openDatabase(file)
        try {
            database.folderDao().upsert(
                FolderEntity(
                    id = "media-album",
                    chatId = 7L,
                    parentId = null,
                    name = "Media album",
                    createdAt = 10L,
                    modifiedAt = 40L
                )
            )
            database.folderDao().upsert(
                FolderEntity(
                    id = "empty-album",
                    chatId = 7L,
                    parentId = null,
                    name = "Empty album",
                    createdAt = 10L,
                    modifiedAt = 20L
                )
            )
            database.fileDao().upsert(
                FileEntity(
                    id = "photo",
                    folderId = "media-album",
                    name = "photo.jpg",
                    sizeBytes = 1200L,
                    mimeType = "image/jpeg",
                    category = FileCategory.IMAGE,
                    localPath = null,
                    contentHash = null,
                    chatId = 7L,
                    messageId = null,
                    remoteFileId = null,
                    remoteUniqueId = null,
                    backupState = BackupState.NONE,
                    width = 800,
                    height = 600,
                    createdAt = 10L,
                    modifiedAt = 40L,
                    addedAt = 40L
                )
            )

            val albums = database.fileDao()
                .observeAlbumsScoped(showHidden = false, showArchived = false, chatId = 7L)
                .first()

            assertEquals(2, albums.size)
            assertEquals("media-album", albums.first().folderId)
            assertEquals(1, albums.first().itemCount)
            assertEquals("photo", albums.first().coverFileId)
            assertEquals("empty-album", albums.last().folderId)
            assertEquals(0, albums.last().itemCount)
        } finally {
            database.close()
            file.delete()
        }
    }

    @Test
    fun `raw file query runs against an empty database`() = runBlocking {
        val file = File.createTempFile("teledrive", ".db")
        val database = openDatabase(file)
        try {
            val ids = database.fileDao().idList(FileQueryBuilder.buildIds(FileQuerySpec()))
            assertTrue(ids.isEmpty())
        } finally {
            database.close()
            file.delete()
        }
    }
}
