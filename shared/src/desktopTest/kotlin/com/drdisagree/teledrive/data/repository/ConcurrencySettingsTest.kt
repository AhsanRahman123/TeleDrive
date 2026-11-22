package com.drdisagree.teledrive.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.drdisagree.teledrive.core.transfer.TransferConcurrency
import com.drdisagree.teledrive.data.local.preferences.PreferenceKeys
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
 * PC-39: the per-direction concurrency settings — defaults are the
 * user's proven-safe 2/2, values persist through the real DataStore
 * settings mechanism, and an install that only ever wrote the legacy
 * shared key finds both directions seeded from it.
 */
class ConcurrencySettingsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File.createTempFile("teledrive-settings", ".preferences_pb")

    // One DataStore per test: the factory forbids a second live instance
    // for the same file, so the repository and the raw seeding share it.
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            producePath = { storeFile.absolutePath.toPath() }
        )
    }

    private fun repository() = SettingsRepositoryImpl(dataStore, unused())

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `defaults are the proven-safe two and two`() {
        val defaults = UserPreferences()
        assertEquals(2, defaults.uploadConcurrency)
        assertEquals(2, defaults.downloadConcurrency)
        assertEquals(2, TransferConcurrency.uploadLimit(defaults))
        assertEquals(2, TransferConcurrency.downloadLimit(defaults))
    }

    @Test
    fun `limits clamp to the one to six range`() {
        assertEquals(1, TransferConcurrency.uploadLimit(UserPreferences(uploadConcurrency = 0)))
        assertEquals(6, TransferConcurrency.uploadLimit(UserPreferences(uploadConcurrency = 99)))
        assertEquals(1, TransferConcurrency.downloadLimit(UserPreferences(downloadConcurrency = -3)))
        assertEquals(6, TransferConcurrency.downloadLimit(UserPreferences(downloadConcurrency = 7)))
        assertEquals(4, TransferConcurrency.uploadLimit(UserPreferences(uploadConcurrency = 4)))
        assertEquals(5, TransferConcurrency.downloadLimit(UserPreferences(downloadConcurrency = 5)))
    }

    @Test
    fun `chosen values persist through the real settings store`() = runBlocking {
        val repository = repository()

        repository.update { it.copy(uploadConcurrency = 5, downloadConcurrency = 3) }

        val readBack = repository.preferences.first()
        assertEquals(5, readBack.uploadConcurrency)
        assertEquals(3, readBack.downloadConcurrency)

        // And the values really landed under the new keys on disk.
        val raw = dataStore.data.first()
        assertEquals(5, raw[PreferenceKeys.UPLOAD_CONCURRENCY])
        assertEquals(3, raw[PreferenceKeys.DOWNLOAD_CONCURRENCY])
    }

    @Test
    fun `a fresh store reads the defaults`() = runBlocking {
        val prefs = repository().preferences.first()
        assertEquals(2, prefs.uploadConcurrency)
        assertEquals(2, prefs.downloadConcurrency)
    }

    @Test
    fun `the legacy shared value seeds both directions when the new keys were never written`() =
        runBlocking {
            dataStore.edit { it[PreferenceKeys.TRANSFER_CONCURRENCY] = 4 }

            val prefs = repository().preferences.first()
            assertEquals(4, prefs.uploadConcurrency)
            assertEquals(4, prefs.downloadConcurrency)
        }

    @Test
    fun `the new keys win over the legacy seed once written`() = runBlocking {
        dataStore.edit {
            it[PreferenceKeys.TRANSFER_CONCURRENCY] = 4
            it[PreferenceKeys.UPLOAD_CONCURRENCY] = 1
            it[PreferenceKeys.DOWNLOAD_CONCURRENCY] = 6
        }

        val prefs = repository().preferences.first()
        assertEquals(1, prefs.uploadConcurrency)
        assertEquals(6, prefs.downloadConcurrency)
    }
}
