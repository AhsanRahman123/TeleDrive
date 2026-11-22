package com.drdisagree.teledrive.core.common

import com.drdisagree.teledrive.presentation.transfers.AndroidTransferBubbleStore
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val commonModule = module {
    singleOf(::AppNotifications)
    singleOf(::AndroidTransferBubbleStore)
}
