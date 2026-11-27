package com.drdisagree.teledrive.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.drdisagree.teledrive.domain.model.ArchiveThumbnailSize
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PC 1.6.26 item P7 + R3 item P6: the ZIP contents list's thumbnail size
 * persists through the real DataStore settings mechanism under the
 * `archive_thumbnail_size` key (the enum name), defaults to MEDIUM
 * (56 dp — the size the lists shipped with before the switcher existed).
 * R3 P6 re-mapped the steps' PC geometry: Small 40x40 / Medium 56x56
 * unchanged, Big is now a wide 160x120 dp tile (was an 80 dp square) and
 * decodes at 512 px instead of 256 px. Persistence stores only the enum
 * name, so a "BIG" saved by an older build maps to the new tile with no
 * migration.
 */
class ArchiveThumbnailSizeSettingsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File.createTempFile("teledrive-settings", ".preferences_pb")

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            produceFile = { storeFile.absolutePath.toPath() }
        )
    }

    private fun repository() = SettingsRepositoryImpl(dataStore, unused())

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `steps are small 40 medium 56 and big is a wide 160 by 120 tile decoding at 512`() {
        assertEquals(40, ArchiveThumbnailSize.SMALL.dp)
        assertEquals(40, ArchiveThumbnailSize.SMALL.widthDp)
        assertEquals(256, ArchiveThumbnailSize.SMALL.decodePx)
        assertEquals(56, ArchiveThumbnailSize.MEDIUM.dp)
        assertEquals(56, ArchiveThumbnailSize.MEDIUM.widthDp)
        assertEquals(256, ArchiveThumbnailSize.MEDIUM.decodePx)
        // R3 P6: Big's slot height (dp) and width (widthDp), stated
        // explicitly — this is the px-value change behind the same
        // persisted enum name.
        assertEquals(120, ArchiveThumbnailSize.BIG.dp)
        assertEquals(160, ArchiveThumbnailSize.BIG.widthDp)
        assertEquals(512, ArchiveThumbnailSize.BIG.decodePx)
        assertEquals(ArchiveThumbnailSize.MEDIUM, UserPreferences().archiveThumbnailSize)
    }

    @Test
    fun `chosen size persists through the settings repository`() = runBlocking {
        val repository = repository()
        assertEquals(
            ArchiveThumbnailSize.MEDIUM,
            repository.preferences.first().archiveThumbnailSize
        )
        repository.update { it.copy(archiveThumbnailSize = ArchiveThumbnailSize.BIG) }
        assertEquals(
            ArchiveThumbnailSize.BIG,
            repository.preferences.first().archiveThumbnailSize
        )
        repository.update { it.copy(archiveThumbnailSize = ArchiveThumbnailSize.SMALL) }
        assertEquals(
            ArchiveThumbnailSize.SMALL,
            repository.preferences.first().archiveThumbnailSize
        )
    }
}
