package com.drdisagree.teledrive.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import org.koin.compose.viewmodel.koinViewModel
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.album_not_in_folder
import com.drdisagree.teledrive.presentation.channels.ChannelsScreen
import com.drdisagree.teledrive.presentation.common.LocalOpenTransfers
import com.drdisagree.teledrive.presentation.components.LocalAndroidLayout
import com.drdisagree.teledrive.presentation.collection.CollectionScreen
import com.drdisagree.teledrive.presentation.files.FilesScreen
import com.drdisagree.teledrive.presentation.files.FilesWorkspaceViewModel
import com.drdisagree.teledrive.presentation.gallery.GalleryScreen
import com.drdisagree.teledrive.presentation.home.HomeScreen
import com.drdisagree.teledrive.presentation.platform.LocalPlatformScreens
import com.drdisagree.teledrive.presentation.navigation.NavigationTransitions.isPeerSwitch
import com.drdisagree.teledrive.presentation.navigation.NavigationTransitions.movesForward
import com.drdisagree.teledrive.presentation.note.NoteEditorScreen
import com.drdisagree.teledrive.presentation.onboarding.OnboardingScreen
import com.drdisagree.teledrive.presentation.search.SearchScreen
import com.drdisagree.teledrive.presentation.proxy.ProxyScreen
import com.drdisagree.teledrive.presentation.settings.ExclusionsScreen
import com.drdisagree.teledrive.presentation.settings.CacheManagerScreen
import com.drdisagree.teledrive.presentation.settings.ThumbnailQueueScreen
import com.drdisagree.teledrive.presentation.settings.SettingsScreen
import com.drdisagree.teledrive.presentation.settings.SettingsSectionScreen
import com.drdisagree.teledrive.presentation.settings.SettingsSectionType
import com.drdisagree.teledrive.presentation.transfers.TransfersScreen
import com.drdisagree.teledrive.presentation.trash.TrashScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: Route,
    modifier: Modifier = Modifier,
    verticalTabMotion: Boolean = false
) {
    val androidLayout = LocalAndroidLayout.current
    CompositionLocalProvider(
        LocalOpenTransfers provides { navController.navigateOnce(Route.Transfers) }
    ) {
        NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // Android's file and gallery screens can contain many live thumbnails and controls.
        // Avoid keeping two full collection screens composited during a navigation animation.
        // Desktop retains the established shared-axis transitions.
        enterTransition = {
            if (androidLayout) EnterTransition.None
            else if (isPeerSwitch()) tabEnterFor(verticalTabMotion, movesForward())
            else NavigationTransitions.sharedAxisEnter(forward = true)
        },
        exitTransition = {
            if (androidLayout) ExitTransition.None
            else if (isPeerSwitch()) tabExitFor(verticalTabMotion, movesForward())
            else NavigationTransitions.sharedAxisExit(forward = true)
        },
        popEnterTransition = {
            if (androidLayout) EnterTransition.None
            else if (isPeerSwitch()) tabEnterFor(verticalTabMotion, movesForward())
            else NavigationTransitions.sharedAxisEnter(forward = false)
        },
        popExitTransition = {
            if (androidLayout) ExitTransition.None
            else if (isPeerSwitch()) tabExitFor(verticalTabMotion, movesForward())
            else NavigationTransitions.sharedAxisExit(forward = false)
        }
    ) {
        composable<Route.Onboarding> {
            OnboardingScreen(
                onOpenProxy = { navController.navigateOnce(Route.Proxy) },
                onFinished = {
                    navController.navigateOnce(Route.Home) {
                        popUpTo(Route.Onboarding) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.Home> {
            UnderBottomBar {
                HomeScreen(
                    onOpenFile = { id, sequence ->
                        navController.navigateOnce(sequence.routeFor(id))
                    },
                    onOpenFolder = { navController.navigateOnce(Route.Files(it)) },
                    onOpenTransfers = { navController.navigateOnce(Route.Transfers) },
                    onOpenTrash = { navController.navigateOnce(Route.Trash) },
                    onOpenChannels = { navController.navigateOnce(Route.Channels) },
                    onOpenCollection = { navController.navigateOnce(Route.Collection(it.name)) },
                    onOpenBackupSettings = {
                        navController.navigateOnce(
                            Route.SettingsSection(SettingsSectionType.BACKUP.name)
                        )
                    }
                )
            }
        }
        composable<Route.Files> { entry ->
            UnderBottomBar {
                val filesRoute = entry.toRoute<Route.Files>()
                val isRoot = filesRoute.folderId == null
                // The workspace tabs belong to the whole Files back stack, not to one
                // folder destination: their holder is scoped to the bottom-most Files
                // entry, so every folder destination above it shares the same tabs and
                // the tabs die with the stack — the lifetime they had when one screen
                // swapped folders in place. The entry below a live destination can
                // never be popped first, so this owner is stable for the destination.
                val tabsOwner = remember(navController) {
                    navController.currentBackStack.value.firstOrNull { backEntry ->
                        backEntry.destination.hasRoute(Route.Files::class)
                    } ?: entry
                }
                val workspaceViewModel: FilesWorkspaceViewModel =
                    koinViewModel(viewModelStoreOwner = tabsOwner)
                FilesScreen(
                    focusFileId = filesRoute.focusFileId,
                    workspaceViewModel = workspaceViewModel,
                    onOpenFolder = { navController.navigateOnce(Route.Files(it)) },
                    onOpenCrumb = { folderId ->
                        val route = Route.Files(folderId)
                        if (!navController.popBackStack(route, inclusive = false)) {
                            navController.navigateOnce(route)
                        }
                    },
                    onOpenFile = { id, sequence ->
                        navController.navigateOnce(sequence.routeFor(id))
                    },
                    onOpenSearch = { navController.navigateOnce(Route.Search) },
                    onOpenTransfers = { navController.navigateOnce(Route.Transfers) },
                    onViewThumbnailQueue = { navController.navigateOnce(Route.ThumbnailQueue) },
                    onNewNote = { folderId ->
                        navController.navigateOnce(Route.NoteEditor(folderId = folderId))
                    },
                    onEditNote = { fileId, title ->
                        navController.navigateOnce(
                            Route.NoteEditor(fileId = fileId, title = title)
                        )
                    },
                    onBack = if (isRoot) null else ({ navController.popBackStackOnce() })
                )
            }
        }
        composable<Route.Gallery> {
            UnderBottomBar {
                val unfiledAlbumName = stringResource(Res.string.album_not_in_folder)
                GalleryScreen(
                    onOpenFile = { id, sequence ->
                        navController.navigateOnce(sequence.routeFor(id))
                    },
                    onOpenAlbum = { album ->
                        navController.navigateOnce(
                            Route.GalleryAlbum(album.folderId, album.name ?: unfiledAlbumName)
                        )
                    },
                    onOpenTransfers = { navController.navigateOnce(Route.Transfers) },
                    onViewThumbnailQueue = { navController.navigateOnce(Route.ThumbnailQueue) }
                )
            }
        }
        composable<Route.GalleryAlbum> {
            GalleryScreen(
                onOpenFile = { id, sequence ->
                    navController.navigateOnce(sequence.routeFor(id))
                },
                onBack = { navController.popBackStackOnce() },
                onOpenTransfers = { navController.navigateOnce(Route.Transfers) },
                onViewThumbnailQueue = { navController.navigateOnce(Route.ThumbnailQueue) }
            )
        }
        composable<Route.Preview>(
            enterTransition = { NavigationTransitions.previewEnter() },
            exitTransition = { NavigationTransitions.fadeThroughExit() },
            popEnterTransition = { NavigationTransitions.fadeThroughEnter() },
            popExitTransition = { NavigationTransitions.previewExit() }
        ) {
            LocalPlatformScreens.current.Preview(
                onBack = { navController.popBackStackOnce() },
                onEditNote = { fileId, title ->
                    navController.navigateOnce(
                        Route.NoteEditor(fileId = fileId, title = title)
                    )
                }
            )
        }
        composable<Route.Transfers> {
            TransfersScreen(
                onBack = { navController.popBackStackOnce() },
                onOpenFile = { fileId ->
                    navController.navigateOnce(Route.Preview(fileId = fileId))
                },
                onOpenLocalFile = { localPath, displayName ->
                    navController.navigateOnce(
                        Route.LocalPreview(localPath = localPath, displayName = displayName)
                    )
                }
            )
        }
        composable<Route.LocalPreview> { entry ->
            val route = entry.toRoute<Route.LocalPreview>()
            LocalPlatformScreens.current.LocalPreview(
                localPath = route.localPath,
                displayName = route.displayName,
                onBack = { navController.popBackStackOnce() }
            )
        }
        composable<Route.Search> {
            SearchScreen(
                onBack = { navController.popBackStackOnce() },
                onOpenFile = { id, sequence ->
                    navController.navigateOnce(sequence.routeFor(id))
                },
                onOpenFolder = { folderId, fileId ->
                    navController.navigateOnce(Route.Files(folderId, fileId))
                }
            )
        }
        composable<Route.Collection> {
            CollectionScreen(
                onBack = { navController.popBackStackOnce() },
                onOpenFile = { id, sequence ->
                    navController.navigateOnce(sequence.routeFor(id))
                },
                onOpenFolder = { navController.navigateOnce(Route.Files(it)) }
            )
        }
        composable<Route.NoteEditor> {
            NoteEditorScreen(onBack = { navController.popBackStackOnce() })
        }
        composable<Route.Channels> {
            ChannelsScreen(onBack = { navController.popBackStackOnce() })
        }
        composable<Route.Trash> {
            TrashScreen(onBack = { navController.popBackStackOnce() })
        }
        composable<Route.Settings> {
            UnderBottomBar {
                SettingsScreen(
                    onBack = null,
                    onOpenSection = { section ->
                        navController.navigateOnce(Route.SettingsSection(section.name))
                    }
                )
            }
        }
        composable<Route.SettingsSection> { entry ->
            val route = entry.toRoute<Route.SettingsSection>()
            val section = SettingsSectionType.entries
                .firstOrNull { it.name == route.section }
                ?: SettingsSectionType.ACCOUNT
            SettingsSectionScreen(
                section = section,
                onOpenChannels = { navController.navigateOnce(Route.Channels) },
                onBack = { navController.popBackStackOnce() },
                onOpenExclusions = { navController.navigateOnce(Route.Exclusions) },
                onOpenProxy = { navController.navigateOnce(Route.Proxy) },
                onOpenCacheManager = { filter -> navController.navigateOnce(Route.CacheManager(filter)) },
                onLoggedOut = {
                    navController.navigateOnce(Route.Onboarding) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable<Route.ThumbnailQueue> {
            ThumbnailQueueScreen(onBack = { navController.popBackStackOnce() })
        }
        composable<Route.CacheManager> { entry ->
            val route = entry.toRoute<Route.CacheManager>()
            CacheManagerScreen(
                onBack = { navController.popBackStackOnce() },
                initialFilter = route.initialFilter
            )
        }
        composable<Route.Exclusions> {
            ExclusionsScreen(onBack = { navController.popBackStackOnce() })
        }
        composable<Route.Proxy> {
            ProxyScreen(onBack = { navController.popBackStackOnce() })
        }
        }
    }
}

private fun tabEnterFor(vertical: Boolean, forward: Boolean) =
    if (vertical) NavigationTransitions.tabEnterVertical(forward)
    else NavigationTransitions.tabEnter(forward)

private fun tabExitFor(vertical: Boolean, forward: Boolean) =
    if (vertical) NavigationTransitions.tabExitVertical(forward)
    else NavigationTransitions.tabExit(forward)

/** Read per screen, so an outgoing screen's padding stays steady while it animates away. */
@Composable
private fun UnderBottomBar(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBottomBarInset provides LocalBottomBarHeight.current,
        content = content
    )
}
