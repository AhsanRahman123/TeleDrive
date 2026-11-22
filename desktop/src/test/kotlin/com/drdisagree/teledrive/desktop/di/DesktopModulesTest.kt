package com.drdisagree.teledrive.desktop.di

import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.test.verify.verify

class DesktopModulesTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyConstructorDependencyResolves() {
        desktopModule.verify(extraTypes = listOf(SavedStateHandle::class))
    }

    /**
     * Runtime graph guard (1.6.20): actually STARTS Koin with the desktop module set and
     * constructs the preview graph, instead of only statically verifying it.
     *
     * The static verify() above did NOT catch the 1.6.19 field failure — opening a zip
     * crashed with "Could not create instance for '[Factory: PreviewViewModel]'" because
     * RemoteArchiveService was registered with singleOf while its constructor carries a
     * lambda parameter with a Kotlin default: Koin's constructor DSL resolves every
     * parameter from the container (defaults are never applied) and no
     * (DriveFile) -> MediaByteSource binding exists. This test constructs the real
     * objects, so a missing or unconstructable binding anywhere in the PreviewViewModel
     * chain fails CI. It was verified to fail on the pre-fix registration.
     */
    @Test
    fun desktopGraphConstructsPreviewGraphAtRuntime() {
        val koin = startKoin { modules(desktopModule) }.koin
        try {
            assertNotNull(koin.get<RemoteArchiveService>())
            assertNotNull(koin.get<PreviewContentResolver>())
            val handle = SavedStateHandle(mapOf("fileId" to "di-guard-file"))
            val viewModel = koin.get<PreviewViewModel> { parametersOf(handle) }
            assertNotNull(viewModel)
        } finally {
            stopKoin()
        }
    }
}
