package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val sharedFilesModule = module {
    singleOf(::NoteStore)
    singleOf(::StorageInspector)
    singleOf(::RemoteArchiveService)
}
