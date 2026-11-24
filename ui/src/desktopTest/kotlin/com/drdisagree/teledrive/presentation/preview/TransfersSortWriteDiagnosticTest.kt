package com.drdisagree.teledrive.presentation.preview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.data.repository.SettingsRepositoryImpl
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.presentation.transfers.TransfersStore
import com.drdisagree.teledrive.presentation.transfers.TransfersViewModel
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TEMPORARY diagnostic for the merged branch's TransfersSortMemoryTest write-test
 * timeouts on Android CI: stages the exact write flow on the desktop suite (whose
 * XML results are uploaded) and prints each stage's outcome.
 */
class TransfersSortWriteDiagnosticTest {

    private fun newSettings(): Pair<SettingsRepository, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File.createTempFile("sort-diag", ".preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.createWithPath(
            scope = scope,
            produceFile = { file.absolutePath.toPath() }
        )
        return SettingsRepositoryImpl(store, IdentityCipher) to scope
    }

    private object IdentityCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeTransferRepository(): TransferRepository =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(TransferRepository::class.java)
        ) { _, method, _ ->
            when {
                method.name.startsWith("observeCompletedTotals") ->
                    flowOf(TransferWorkTotals(0, 0))
                method.name.startsWith("observeRemainingWork") ->
                    flowOf(TransferRemainingWork(0, 0, 0))
                method.returnType.name.contains("Flow") -> flowOf(emptyList<Any>())
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                method.returnType == String::class.java -> ""
                else -> null
            }
        } as TransferRepository

    @Test
    fun `diagnostic - viewModel setSortOrder write lands`() = runBlocking {
        val (settings, scope) = newSettings()
        try {
            val viewModel = TransfersViewModel(fakeTransferRepository(), settings)
            println("DIAG stage: viewModel constructed")
            viewModel.setSortOrder("NAME")
            println("DIAG stage: setSortOrder returned")
            repeat(10) { attempt ->
                val current = settings.preferences.first()
                println("DIAG poll $attempt: transfersSortOrder=${current.transfersSortOrder}")
                if (current.transfersSortOrder == "NAME") return@repeat
                delay(500)
            }
            val landed = withTimeoutOrNull(8_000) {
                settings.preferences.first { it.transfersSortOrder == "NAME" }
            }
            println("DIAG stage: predicate-first landed=${landed != null}")
            assertEquals("NAME", settings.preferences.first().transfersSortOrder)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `diagnostic - store setSortOrder write lands`() = runBlocking {
        val (settings, scope) = newSettings()
        try {
            val store = TransfersStore(fakeTransferRepository(), settings)
            println("DIAG stage: store constructed")
            store.setSortOrder("NAME")
            println("DIAG stage: store setSortOrder returned")
            val landed = withTimeoutOrNull(8_000) {
                settings.preferences.first { it.transfersSortOrder == "NAME" }
            }
            println("DIAG stage: store predicate-first landed=${landed != null}")
            assertEquals("NAME", settings.preferences.first().transfersSortOrder)
        } finally {
            scope.cancel()
        }
    }
}
