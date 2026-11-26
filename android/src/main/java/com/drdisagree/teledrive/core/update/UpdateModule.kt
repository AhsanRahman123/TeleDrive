package com.drdisagree.teledrive.core.update

import com.drdisagree.teledrive.BuildConfig
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val updateModule = module {
    single {
        UpdateChecker(
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            channel = UpdateChannel.ANDROID
        )
    }
    workerOf(::UpdateCheckWorker)
}
