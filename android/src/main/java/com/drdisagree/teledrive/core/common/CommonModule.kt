package com.drdisagree.teledrive.core.common

import com.drdisagree.teledrive.core.archive.ArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.presentation.transfers.AndroidTransferBubbleStore
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val commonModule = module {
    singleOf(::AppNotifications)
    // Explicit factory: the store's CoroutineScope parameter has a default and is not
    // a Koin binding, so constructor DSL (singleOf) could not construct it.
    single { AndroidTransferBubbleStore(get()) }
    // Android reports in-ZIP downloads into Transfers history via the real tracker
    // (bound in transferEngineModule); the interface is what PreviewViewModel resolves.
    single<ArchiveDownloadReporter> { get<ArchiveDownloadTracker>() }
}
