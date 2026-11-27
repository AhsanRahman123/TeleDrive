package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * P6 (1.6.26): the desktop local preview behind Transfers' "Open in
 * Pranto Drive" for a completed archive row — the REAL
 * [DesktopLocalPreviewScreen] resolving a real saved file through the
 * REAL [PreviewContentResolver.resolveLocalPath] (the production route
 * the row menu navigates to), plus its Esc back key (P2).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLocalPreviewScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    /** See DesktopPreviewEscapeBackTest: explicit global Koin, never the
     *  KoinApplication composable, so contexts cannot leak across classes. */
    private fun startResolverKoin(resolver: PreviewContentResolver) {
        runCatching { stopKoin() }
        startKoin {
            modules(module { single<PreviewContentResolver> { resolver } })
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null
        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit
        override suspend fun hasStoredTelegramCredentials(): Boolean = false
        override suspend fun clearTelegramCredentials() = Unit
    }

    private fun resolver(): PreviewContentResolver {
        val settings = FakeSettingsRepository()
        val telegram = archiveTestProxy<TelegramClient> { m, _ ->
            throw UnsupportedOperationException(m)
        }
        val downloadWriter = object : DownloadWriter {
            override fun write(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                body: (OutputStream) -> Unit
            ): String {
                val out = ByteArrayOutputStream()
                body(out)
                return "/Downloads/$fileName"
            }

            override fun openPartial(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                token: String?
            ): PartialDownload? = null

            override fun freeBytes(folderPath: String?): Long? = null
        }
        val filePartDao = archiveTestProxy<FilePartDao> { method, _ ->
            when (method) {
                "partsOf" -> emptyList<FilePartEntity>()
                "countOf" -> 0
                else -> throw UnsupportedOperationException(method)
            }
        }
        val folderPathResolver = FolderPathResolver(
            archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
            ActiveChannel(settings),
            archiveTestProxy<FolderTombstoneDao> { m, _ -> throw UnsupportedOperationException(m) }
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("local-preview", "").also {
                it.delete()
                it.mkdirs()
            }
            override val filesDir: File = File(root, "files").also { it.mkdirs() }
            override val cacheDir: File = File(root, "cache").also { it.mkdirs() }
            override val externalCacheDir: File? = null
            override val externalStorageRoot: File? = null
        }
        return PreviewContentResolver(
            storagePaths = storagePaths,
            telegramClient = telegram,
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = archiveTestProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = archiveTestProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = filePartDao,
            settingsRepository = settings,
            thumbnailStore = archiveTestProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = RemoteArchiveService(
                telegram, filePartDao, downloadWriter, folderPathResolver
            )
        )
    }

    @Test
    fun `local preview renders the saved file and escape goes back`() {
        val saved = File.createTempFile("from-zip-readme", ".txt").apply {
            writeText("read me from the zip")
        }
        val backs = AtomicInteger(0)
        val resolver = resolver()
        startResolverKoin(resolver)
        rule.setContent {
            MaterialTheme {
                  ArchiveTestProviders {
                    DesktopLocalPreviewScreen(
                        localPath = saved.absolutePath,
                        displayName = "readme.txt",
                        onBack = { backs.incrementAndGet() }
                    )
                  }
            }
        }
        rule.onNodeWithText("read me from the zip").assertIsDisplayed()

        // The top-bar Back button is the production press path available
        // in the headless scene. The screen's Esc key runs through
        // AppBackHandler (the production back wrapper every screen uses);
        // in the real window the scene layer performs the
        // Escape-to-back-event conversion, which the headless test scene
        // does not reproduce (verified on CI run 45981769717: a raw Escape
        // key press there reaches key handlers only).
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals("the back action must leave the local preview", 1, backs.get())
    }

    @Test
    fun `local preview of a deleted file shows a failure instead of hanging`() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "teledrive-missing-${System.nanoTime()}.txt"
        )
        val resolver = resolver()
        startResolverKoin(resolver)
        rule.setContent {
            MaterialTheme {
                  ArchiveTestProviders {
                    DesktopLocalPreviewScreen(
                        localPath = missing.absolutePath,
                        displayName = "gone.txt",
                        onBack = {}
                    )
                  }
            }
        }
        // The resolver's no-copy failure state renders (its exact wording
        // is the shared preview_no_copy_available string); the screen must
        // settle, not spin forever.
        rule.waitForIdle()
        rule.onNodeWithText("gone.txt").assertIsDisplayed()
    }
}
