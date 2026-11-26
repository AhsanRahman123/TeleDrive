package com.drdisagree.teledrive.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import com.drdisagree.teledrive.desktop.media.player.ensureJnaUtf8StringEncoding
import com.sun.jna.Platform
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.key.Keyer
import coil3.request.Options
import coil3.request.crossfade
import androidx.compose.runtime.withFrameNanos
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.common.StartupTrace
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.desktop.startup.DeferredStartupAction
import com.drdisagree.teledrive.desktop.startup.readJournalMode
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.media.ThumbnailFetcherFactory
import com.drdisagree.teledrive.core.media.ThumbnailModel
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.media.thumbnailCacheKey
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.proxy.ProxyFailover
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.desktop.di.desktopModule
import com.drdisagree.teledrive.desktop.transfer.DesktopBackupResumeScheduler
import com.drdisagree.teledrive.desktop.resources.Res
import com.drdisagree.teledrive.desktop.resources.Res as DesktopRes
import com.drdisagree.teledrive.desktop.resources.app_already_running
import com.drdisagree.teledrive.desktop.resources.app_icon
import com.drdisagree.teledrive.desktop.ui.FullscreenController
import com.drdisagree.teledrive.desktop.ui.LocalFullscreenController
import com.drdisagree.teledrive.desktop.ui.ProvideDesktopPlatformActions
import com.drdisagree.teledrive.desktop.ui.TransferBubbleOverlay
import com.drdisagree.teledrive.desktop.window.WindowsCaptureBlocker
import com.drdisagree.teledrive.desktop.window.WindowsTitleBar
import com.drdisagree.teledrive.desktop.window.WindowsBorderlessFullscreenController
import com.drdisagree.teledrive.desktop.window.WindowsF11Shortcut
import com.drdisagree.teledrive.domain.model.AppTheme
import com.drdisagree.teledrive.core.logging.DebugLogController
import com.drdisagree.teledrive.data.repository.FolderOwnershipRepair
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.TeleDriveApp
import com.drdisagree.teledrive.presentation.theme.systemDarkTheme
import com.drdisagree.teledrive.resources.Res as SharedRes
import com.drdisagree.teledrive.resources.app_name
import java.awt.Dimension
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

fun main() {
    // Before anything touches JNA/vlcj: libvlc speaks UTF-8, but JNA's
    // default native-string decoding is the OS charset (windows-1252),
    // which mangles non-English audio tags in the player (1.6.24 note 4).
    ensureJnaUtf8StringEncoding()
    StartupTrace.markMainEntry()
    SafeLog.verbose = true
    startKoin {
        modules(desktopModule)
    }
    StartupTrace.mark("koin_ready")
    val storagePaths = getKoin().get<AppStoragePaths>()
    if (!SingleInstanceLock(storagePaths.filesDir).acquire()) {
        JOptionPane.showMessageDialog(
            null,
            runBlocking { getString(DesktopRes.string.app_already_running) },
            runBlocking { getString(SharedRes.string.app_name) },
            JOptionPane.INFORMATION_MESSAGE
        )
        exitProcess(0)
    }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    getKoin().get<DebugLogController>().start(backgroundScope, alwaysVerbose = true)
    // Round 11: time the first Room open and record the live journal mode
    // (D/Startup). The pragma read opens the database if nothing else has
    // yet; either way the mark bounds the open.
    backgroundScope.launch {
        runCatching {
            val mode = getKoin().get<TeleDriveDatabase>().readJournalMode()
            StartupTrace.mark("db_open_done")
            SafeLog.d("Startup", "journal_mode=$mode")
        }
    }
    // Round 11: the libVLC warm-up (PC-7) and the repository startup block
    // no longer run during process/window bring-up. Report 29 measured a
    // 3.5–7.2 s silent gap in which they competed with Compose/Skiko and
    // the TDLib client creation for the same CPU/IO. Both are now deferred
    // until after the window's first frames (see the LaunchedEffect in the
    // Window content below). Nothing here is required before the UI: the
    // warm-up only pre-loads what the player path otherwise loads lazily
    // on first use (PlayerSession reads VlcPlayback.factory directly),
    // and the startup block only reconciles persisted rows — it was
    // always fire-and-forget, never awaited by any screen.
    val vlcWarmup = DeferredStartupAction(backgroundScope) {
        StartupTrace.mark("vlc_warm_start")
        runCatching { com.drdisagree.teledrive.desktop.media.player.VlcPlayback.factory }
        StartupTrace.mark("vlc_warm_done")
    }
    val startupBlock = DeferredStartupAction(backgroundScope) {
        runCatching { getKoin().get<TransferRepository>().recoverOrphanedTransfers() }
        StartupTrace.mark("recover_done")
        runCatching { getKoin().get<FileRepository>().sweepImportOrphans() }
        StartupTrace.mark("sweep_done")
        runCatching { getKoin().get<FolderOwnershipRepair>().refileStrayFiles() }
        StartupTrace.mark("refile_done")
        getKoin().get<PublishScheduler>().kick()
        StartupTrace.mark("kick_done")
        runCatching { getKoin().get<TrashRepository>().repairTrashTree() }
        StartupTrace.mark("trash_repair_done")
    }
    // Maintenance arming, mirroring Android's TeleDriveApplication.onCreate:
    // without this startup call the desktop scheduler would only arm after
    // the user opened Settings. (Folder auto-backup itself was removed from
    // desktop on 2026-11-20; the scheduler now arms cleanup + update checks
    // only.) The resume scheduler's kick target is wired here because it
    // cannot be a constructor dependency (Koin cycle through the transfer
    // drainer).
    getKoin().get<DesktopBackupResumeScheduler>().kickAction = { allowMetered ->
        getKoin().get<TransferScheduler>().kick(allowMetered)
    }
    // Proxy auto-failover, mirroring TeleDriveApplication: rotate to the
    // next saved proxy when the connection stays CONNECTING too long.
    getKoin().get<ProxyFailover>().start(backgroundScope)
    backgroundScope.launch {
        runCatching {
            val prefs = getKoin().get<SettingsRepository>().preferences.first()
            getKoin().get<MaintenanceScheduler>().scheduleAll(
                // Desktop auto-backup is removed (2026-11-20): never arm it,
                // whatever an earlier watch-list left in the preferences.
                // The call itself stays — it also arms the cache/trash
                // cleanup loop and the daily update check.
                backupEnabled = false,
                backupIntervalHours = prefs.backupIntervalHours,
                wifiOnly = prefs.backupWifiOnly,
                chargingOnly = prefs.backupChargingOnly,
                instantBackup = prefs.instantBackupEnabled,
                updateChecks = prefs.updateCheckEnabled
            )
        }
    }
    application {
        val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        var previousWindowPlacement by remember { mutableStateOf(windowState.placement) }
        Window(
            onCloseRequest = ::exitApplication,
            title = stringResource(SharedRes.string.app_name),
            icon = painterResource(Res.drawable.app_icon),
            state = windowState
        ) {
            window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
            // Windows must use borderless windowed fullscreen. WindowPlacement.Fullscreen
            // delegates to Skiko's exclusive fullscreen path and blocks normal Alt+Tab switching.
            val windowsFullscreen = remember(window) {
                if (Platform.isWindows()) {
                    WindowsBorderlessFullscreenController(window, windowState)
                } else {
                    null
                }
            }
            val fullscreenController = remember(windowsFullscreen, windowState) {
                FullscreenController(
                    isFullscreen = {
                        windowsFullscreen?.isFullscreen
                            ?: (windowState.placement == WindowPlacement.Fullscreen)
                    },
                    toggle = {
                        if (windowsFullscreen != null) {
                            windowsFullscreen.toggle()
                        } else if (windowState.placement == WindowPlacement.Fullscreen) {
                            windowState.placement = previousWindowPlacement
                        } else {
                            previousWindowPlacement = windowState.placement
                            windowState.placement = WindowPlacement.Fullscreen
                        }
                    },
                    setFullscreen = { fullscreen ->
                        if (windowsFullscreen != null) {
                            windowsFullscreen.setFullscreen(fullscreen)
                        } else if (fullscreen) {
                            if (windowState.placement != WindowPlacement.Fullscreen) {
                                previousWindowPlacement = windowState.placement
                                windowState.placement = WindowPlacement.Fullscreen
                            }
                        } else if (windowState.placement == WindowPlacement.Fullscreen) {
                            windowState.placement = previousWindowPlacement
                        }
                    }
                )
            }
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(ThumbnailFetcherFactory(getKoin().get<ThumbnailStore>()))
                        add(Keyer<ThumbnailModel> { data, _: Options ->
                            thumbnailCacheKey(data.fileId, data.revision)
                        })
                    }
                    .crossfade(true)
                    .build()
            }
            val settingsRepository = remember { getKoin().get<SettingsRepository>() }
            val theme by remember {
                settingsRepository.preferences.map { it.theme }.distinctUntilChanged()
            }.collectAsState(initial = AppTheme.SYSTEM)
            val systemDark = systemDarkTheme()
            val darkTitleBar = when (theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> systemDark
            }
            LaunchedEffect(darkTitleBar) { WindowsTitleBar.setDark(window, darkTitleBar) }
            // Block screenshots (Settings): WDA_EXCLUDEFROMCAPTURE while on,
            // WDA_NONE restored when off. Default is off.
            val blockScreenCapture by remember {
                settingsRepository.preferences.map { it.blockScreenCapture }.distinctUntilChanged()
            }.collectAsState(initial = false)
            LaunchedEffect(blockScreenCapture) {
                WindowsCaptureBlocker.setBlocked(window, blockScreenCapture)
            }
            // Round 11: after the first frames are on screen, release the
            // deferred startup work (libVLC warm-up + repository startup
            // block) so it no longer competes with window bring-up.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                StartupTrace.markOnce("window_shown")
                withFrameNanos { }
                vlcWarmup.trigger()
                startupBlock.trigger()
            }
            // Windows uses an AWT-level dispatcher, so F11 is independent of whichever
            // Compose child currently owns focus. Other desktop platforms retain the
            // Compose window shortcut path.
            val windowsF11Shortcut = remember(window, fullscreenController) {
                if (Platform.isWindows()) {
                    WindowsF11Shortcut(window) { fullscreenController.toggle() }
                } else {
                    null
                }
            }
            DisposableEffect(windowsF11Shortcut) {
                windowsF11Shortcut?.install()
                onDispose { windowsF11Shortcut?.dispose() }
            }

            val windowShortcutFocus = remember { FocusRequester() }
            val windowInfo = LocalWindowInfo.current
            var fallbackF11Pressed by remember { mutableStateOf(false) }
            LaunchedEffect(windowsF11Shortcut) {
                if (windowsF11Shortcut == null) windowShortcutFocus.requestFocus()
            }
            LaunchedEffect(windowInfo.isWindowFocused) {
                if (!windowInfo.isWindowFocused) fallbackF11Pressed = false
            }
            val keyboardShortcutModifier = if (windowsF11Shortcut == null) {
                Modifier
                    .focusRequester(windowShortcutFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.F11 && event.type == KeyEventType.KeyUp) {
                            fallbackF11Pressed = false
                            true
                        } else if (
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.F11 &&
                            !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
                        ) {
                            if (!fallbackF11Pressed) {
                                fallbackF11Pressed = true
                                runCatching { fullscreenController.toggle() }
                                    .onFailure { SafeLog.w("DesktopWindow", "F11 toggle failed", it) }
                            }
                            true
                        } else false
                    }
                    .focusable()
            } else {
                Modifier
            }
            Box(
                modifier = Modifier.fillMaxSize().then(keyboardShortcutModifier)
            ) {
                CompositionLocalProvider(LocalFullscreenController provides fullscreenController) {
                    ProvideDesktopPlatformActions {
                        TeleDriveApp(
                            pendingShare = getKoin().get<PendingShare>(),
                            notificationDestination = null,
                            onDestinationHandled = {}
                        )
                    }
                }
                // Floating transfer bubble: an in-app overlay above the app
                // content. It renders nothing while its Settings switch is
                // off or the window is in fullscreen.
                TransferBubbleOverlay(fullscreenController = fullscreenController)
            }
        }
    }
}

private const val MIN_WINDOW_WIDTH = 480
private const val MIN_WINDOW_HEIGHT = 600
