package com.drdisagree.teledrive.presentation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.data.repository.SettingsRepositoryImpl
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.presentation.transfers.TransfersViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the Transfers sort memory: the chosen sort is written to the same
 * DataStore-backed preferences store that already persists the Files and
 * Gallery sorts, and a fresh screen/ViewModel restores it.
 */
class TransfersSortMemoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun defaultSortOrderIsPriorityWhenNothingStored() = runBlocking {
        val settings = newSettingsRepository()

        assertEquals("PRIORITY", settings.preferences.first().transfersSortOrder)

        val viewModel = TransfersViewModel(FakeTransferRepository, settings)
        assertEquals("PRIORITY", viewModel.sortOrder.value)
    }

    @Test
    fun sortOrderChangeIsWrittenToTheStore() = runBlocking {
        val settings = newSettingsRepository()
        val viewModel = TransfersViewModel(FakeTransferRepository, settings)

        viewModel.setSortOrder("NAME")

        val stored = withTimeout(5_000) {
            settings.preferences.first { it.transfersSortOrder == "NAME" }
        }
        assertEquals("NAME", stored.transfersSortOrder)
    }

    @Test
    fun freshViewModelRestoresTheStoredSortOrder() = runBlocking {
        val settings = newSettingsRepository()
        settings.update { it.copy(transfersSortOrder = "NEWEST") }

        // A new ViewModel over the same store stands in for re-entering the
        // Transfers screen (or relaunching the app): the sort comes back.
        val viewModel = TransfersViewModel(FakeTransferRepository, settings)
        val restored = withTimeout(5_000) {
            viewModel.sortOrder.first { it == "NEWEST" }
        }
        assertEquals("NEWEST", restored)
    }

    @Test
    fun storedSortOrderSurvivesAFreshRepositoryOverTheSameStore() = runBlocking {
        val store = newStore()
        val writer = SettingsRepositoryImpl(store, IdentityCipher)
        writer.update { it.copy(transfersSortOrder = "OLDEST") }

        // SettingsRepositoryImpl is stateless: a second instance can only see
        // the value if it round-tripped through the DataStore file.
        val reader = SettingsRepositoryImpl(store, IdentityCipher)
        assertEquals("OLDEST", reader.preferences.first().transfersSortOrder)

        val viewModel = TransfersViewModel(FakeTransferRepository, reader)
        val restored = withTimeout(5_000) {
            viewModel.sortOrder.first { it == "OLDEST" }
        }
        assertEquals("OLDEST", restored)
    }

    private fun newStore() = PreferenceDataStoreFactory.createWithPath(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
    ) {
        // The file must not exist yet: DataStore treats a pre-created empty
        // file as corrupt.
        File(folder.newFolder(), "settings.preferences_pb").toOkioPath()
    }

    private fun newSettingsRepository(): SettingsRepository =
        SettingsRepositoryImpl(newStore(), IdentityCipher)

    private object IdentityCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private object FakeTransferRepository : TransferRepository {
        override fun observeSection(
            section: TransferSection,
            limit: Int
        ): Flow<List<TransferTask>> = flowOf(emptyList())

        override fun observeSectionCount(section: TransferSection): Flow<Int> = flowOf(0)

        override fun observeActiveCount(): Flow<Int> = flowOf(0)

        override fun observeActiveForFile(fileId: String): Flow<TransferTask?> = flowOf(null)

        override fun observeActiveDownloads(): Flow<List<TransferTask>> = flowOf(emptyList())

        override fun observeCompletedTotals(incoming: Boolean): Flow<TransferWorkTotals> =
            flowOf(TransferWorkTotals(0, 0))

        override fun observeRemainingWork(incoming: Boolean): Flow<TransferRemainingWork> =
            flowOf(TransferRemainingWork(0, 0, 0))

        override suspend fun enqueueUpload(fileId: String, priority: Int): AppResult<String> =
            AppResult.Success(fileId)

        override suspend fun uploadQueueGeneration(): Long = 0L

        override suspend fun enqueueUploadForGeneration(
            fileId: String,
            generation: Long
        ): AppResult<Boolean> = AppResult.Success(true)

        override suspend fun enqueuePendingUploads(): AppResult<Int> = AppResult.Success(0)

        override suspend fun enqueueBackupBatch(
            fileIds: List<String>,
            sessionId: String
        ): AppResult<Int> = AppResult.Success(0)

        override suspend fun enqueueDownload(fileId: String, priority: Int): AppResult<String> =
            AppResult.Success(fileId)

        override suspend fun pause(id: String) = Unit

        override suspend fun resume(id: String) = Unit

        override suspend fun cancel(id: String) = Unit

        override suspend fun cancelForFiles(fileIds: List<String>) = Unit

        override suspend fun retry(id: String) = Unit

        override suspend fun setPriority(id: String, priority: Int) = Unit

        override suspend fun pauseAll() = Unit

        override suspend fun resumeAll() = Unit

        override suspend fun cancelAll() = Unit

        override suspend fun clearFinished() = Unit

        override suspend fun recoverOrphanedTransfers() = Unit
    }
}
