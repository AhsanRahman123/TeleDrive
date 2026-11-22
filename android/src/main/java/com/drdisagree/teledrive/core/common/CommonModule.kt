package com.drdisagree.teledrive.core.common

import com.drdisagree.teledrive.presentation.transfers.AndroidTransferBubbleStore
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val commonModule = module {
    singleOf(::AppNotifications)
    // Explicit factory: the store's CoroutineScope parameter has a default and is not
    // a Koin binding, so constructor DSL (singleOf) could not construct it.
    single { AndroidTransferBubbleStore(get()) }
}
