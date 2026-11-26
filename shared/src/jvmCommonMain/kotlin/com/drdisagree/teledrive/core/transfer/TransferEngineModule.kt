package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val transferEngineModule = module {
    singleOf(::ApkIconUploader)
    singleOf(::PartUploader)
    singleOf(::PartDownloader)
    singleOf(::TransferExecutor)
    singleOf(::TransferQueueDrainer)
    singleOf(::ArchiveExtractionJobs)
    // Explicit primary-constructor binding: vc181 A2 added a secondary
    // 3-arg test-seam constructor to ArchiveDownloadTracker, which makes
    // singleOf(::ArchiveDownloadTracker) overload-ambiguous.
    single { ArchiveDownloadTracker(get(), get()) }
}
