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
 * PC 1.6.26 item P7: the ZIP contents list's thumbnail size persists
 * through the real DataStore settings mechanism under the
 * `archive_thumbnail_size` key (the enum name), defaults to MEDIUM
 * (56 dp — the size the lists shipped with before the switcher existed),
 * and the steps are genuinely ordered Small 40 < Medium 56 < Big 80.
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
    fun `steps are small 40 medium 56 big 80 and the default is medium`() {
        assertEquals(40, ArchiveThumbnailSize.SMALL.dp)
        assertEquals(56, ArchiveThumbnailSize.MEDIUM.dp)
        assertEquals(80, ArchiveThumbnailSize.BIG.dp)
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
