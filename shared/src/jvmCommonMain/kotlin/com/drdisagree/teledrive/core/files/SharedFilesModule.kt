package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val sharedFilesModule = module {
    singleOf(::NoteStore)
    singleOf(::StorageInspector)
    // Explicit factory — NOT singleOf(::RemoteArchiveService). The service's fifth
    // constructor parameter is a byte-source factory lambda with a Kotlin default.
    // Koin's constructor DSL (singleOf) resolves EVERY constructor parameter from the
    // container and never applies Kotlin defaults, and no (DriveFile) -> MediaByteSource
    // binding exists — so singleOf made the service unconstructable at runtime and every
    // PreviewViewModel creation failed with "Could not create instance for
    // '[Factory: PreviewViewModel]'" on desktop (1.6.19 field report). Android's service
    // has no lambda parameter, which is why only the PC build broke. Calling the
    // constructor with its four container-resolved arguments lets the default factory
    // (primeFromStart = false) apply, exactly as the unit tests construct it.
    single { RemoteArchiveService(get(), get(), get(), get()) }
}
