package com.drdisagree.teledrive.presentation.di

import com.drdisagree.teledrive.presentation.AppViewModel
import com.drdisagree.teledrive.presentation.channels.ChannelsViewModel
import com.drdisagree.teledrive.presentation.collection.CollectionViewModel
import com.drdisagree.teledrive.presentation.files.FileClipboard
import com.drdisagree.teledrive.presentation.files.FilesViewModel
import com.drdisagree.teledrive.presentation.files.FilesWorkspaceViewModel
import com.drdisagree.teledrive.presentation.gallery.GalleryViewModel
import com.drdisagree.teledrive.presentation.home.HomeViewModel
import com.drdisagree.teledrive.presentation.onboarding.OnboardingViewModel
import com.drdisagree.teledrive.presentation.note.NoteEditorViewModel
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import com.drdisagree.teledrive.presentation.proxy.ProxyViewModel
import com.drdisagree.teledrive.presentation.search.SearchViewModel
import com.drdisagree.teledrive.presentation.settings.ExclusionsViewModel
import com.drdisagree.teledrive.presentation.settings.SettingsViewModel
import com.drdisagree.teledrive.presentation.transfers.TransfersStore
import com.drdisagree.teledrive.presentation.transfers.TransfersViewModel
import com.drdisagree.teledrive.presentation.trash.TrashViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val sharedUiModule = module {
    singleOf(::PreviewContentResolver)
    singleOf(::FileClipboard)
    // One transfers state holder for the whole app (round 6, PC-26):
    // the Transfers screen's ViewModel and the desktop widgets all bind
    // to this single instance. Explicit factory: the store's third
    // constructor parameter (its scope) has a default and must not be
    // resolved by Koin.
    single { TransfersStore(get(), get()) }
    viewModelOf(::AppViewModel)
    viewModelOf(::PreviewViewModel)
    viewModelOf(::ChannelsViewModel)
    viewModelOf(::CollectionViewModel)
    viewModelOf(::FilesViewModel)
    viewModelOf(::FilesWorkspaceViewModel)
    viewModelOf(::GalleryViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::OnboardingViewModel)
    viewModelOf(::NoteEditorViewModel)
    viewModelOf(::ProxyViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ExclusionsViewModel)
    // Explicit factory: TransfersViewModel has two constructors (the
    // store one used here, and a two-repository one for tests), which
    // viewModelOf cannot disambiguate. The screen's ViewModel wraps the
    // shared store single above.
    viewModel { TransfersViewModel(get()) }
    viewModelOf(::TrashViewModel)
}
