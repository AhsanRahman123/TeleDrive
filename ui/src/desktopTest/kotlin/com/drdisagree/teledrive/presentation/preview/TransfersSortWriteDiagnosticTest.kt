package com.drdisagree.teledrive.presentation.preview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.data.repository.SettingsRepositoryImpl
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
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
 * TEMPORARY diagnostic: a byte-faithful replica of the Android
 * TransfersSortMemoryTest.sortOrderChangeIsWrittenToTheStore flow (setMain +
 * UnconfinedTestDispatcher, TemporaryFolder DataStore file, object fake), staged
 * with prints, so the desktop suite's uploaded XML shows where it sticks.
 */
class TransfersSortWriteDiagnosticTest {

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
    fun `replica - android write test flow`() = runBlocking {
        val settings = newSettingsRepository()
        val viewModel = TransfersViewModel(FakeTransferRepository, settings)
        println("DIAG replica: constructed")
        viewModel.setSortOrder("NAME")
        println("DIAG replica: setSortOrder returned")
        val stored = withTimeout(5_000) {
            settings.preferences.first { it.transfersSortOrder == "NAME" }
        }
        println("DIAG replica: landed")
        assertEquals("NAME", stored.transfersSortOrder)
    }

    private fun newStore() = PreferenceDataStoreFactory.createWithPath(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
    ) {
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
