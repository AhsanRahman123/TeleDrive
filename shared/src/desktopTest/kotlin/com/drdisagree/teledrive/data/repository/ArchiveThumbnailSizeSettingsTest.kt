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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.26 item P7: the ZIP contents list's thumbnail size persists
 * through the real DataStore settings mechanism under the
 * `archive_thumbnail_size` key (the enum name), defaults to MEDIUM
 * (56 dp — the size the lists shipped with before the switcher existed),
 * and the steps are genuinely ordered Small 40 < Medium 56 < Big 80.
 *
 * PC 1.6.27 item N6 re-mapped the PC steps (this is the PC line's enum
 * copy; Android's steps are a separate copy and unchanged): Big's 80 dp
 * round slot still read as small in a wide row, so Big is now a wide
 * 152x112 dp preview tile and its decode cap rises to 512 px, while
 * Small / Medium keep their slots and the 256 px decode.
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
    fun `steps are small 40 medium 56 big 112 high and the default is medium`() {
        assertEquals(40, ArchiveThumbnailSize.SMALL.dp)
        assertEquals(56, ArchiveThumbnailSize.MEDIUM.dp)
        assertEquals(112, ArchiveThumbnailSize.BIG.dp)
        assertEquals(ArchiveThumbnailSize.MEDIUM, UserPreferences().archiveThumbnailSize)
    }

    @Test
    fun `big is a wide tile and only big raises the decode cap`() {
        // Small / Medium are square round slots; Big's slot is wider than
        // it is tall so the row's width is used by the thumbnail itself.
        assertEquals(ArchiveThumbnailSize.SMALL.dp, ArchiveThumbnailSize.SMALL.widthDp)
        assertEquals(ArchiveThumbnailSize.MEDIUM.dp, ArchiveThumbnailSize.MEDIUM.widthDp)
        assertEquals(152, ArchiveThumbnailSize.BIG.widthDp)
        assertTrue(ArchiveThumbnailSize.BIG.widthDp > ArchiveThumbnailSize.BIG.dp)
        // Decode caps: the established 256 px policy for Small / Medium,
        // 512 px for Big so the bigger tile is genuinely sharper.
        assertEquals(256, ArchiveThumbnailSize.SMALL.decodePx)
        assertEquals(256, ArchiveThumbnailSize.MEDIUM.decodePx)
        assertEquals(512, ArchiveThumbnailSize.BIG.decodePx)
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
