package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.drdisagree.teledrive.desktop.BuildInfo
import com.drdisagree.teledrive.desktop.files.DesktopFileOpener
import com.drdisagree.teledrive.desktop.files.DesktopFileRevealer
import com.drdisagree.teledrive.presentation.platform.DeleteConsentLauncher
import com.drdisagree.teledrive.presentation.platform.DeviceOwnerGate
import com.drdisagree.teledrive.presentation.platform.FilePicker
import com.drdisagree.teledrive.presentation.platform.FileSharer
import com.drdisagree.teledrive.presentation.platform.FolderPicker
import com.drdisagree.teledrive.presentation.platform.LocalAppIcon
import com.drdisagree.teledrive.presentation.platform.LocalAppVersion
import com.drdisagree.teledrive.presentation.platform.LocalDeleteConsentLauncher
import com.drdisagree.teledrive.presentation.platform.LocalDeviceOwnerGate
import com.drdisagree.teledrive.presentation.platform.LocalDownloadLocationConfigurable
import com.drdisagree.teledrive.presentation.platform.LocalFileOpener
import com.drdisagree.teledrive.presentation.platform.LocalFilePicker
import com.drdisagree.teledrive.presentation.platform.LocalFileRevealer
import com.drdisagree.teledrive.presentation.platform.LocalFileSharer
import com.drdisagree.teledrive.presentation.platform.LocalFolderPicker
import com.drdisagree.teledrive.presentation.platform.LocalMultiFilePicker
import com.drdisagree.teledrive.presentation.platform.LocalPermissionRequester
import com.drdisagree.teledrive.presentation.platform.LocalPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.LocalPlatformScreens
import com.drdisagree.teledrive.presentation.platform.LocalSystemScreens
import com.drdisagree.teledrive.presentation.platform.LocalTelegramLinkOpener
import com.drdisagree.teledrive.presentation.platform.LocalUrlOpener
import com.drdisagree.teledrive.presentation.platform.MultiFilePicker
import com.drdisagree.teledrive.presentation.platform.PermissionRequester
import com.drdisagree.teledrive.presentation.platform.PickResult
import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.SystemScreens
import com.drdisagree.teledrive.presentation.platform.UrlOpener
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.ic_launcher
import org.jetbrains.compose.resources.painterResource
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import kotlin.concurrent.thread

@Composable
fun ProvideDesktopPlatformActions(content: @Composable () -> Unit) {
    val capabilities = koinInject<PlatformCapabilities>()
    val settingsRepository = koinInject<SettingsRepository>()
    val useNativePicker by remember {
        settingsRepository.preferences.map { it.useNativeFilePicker }
    }.collectAsState(initial = false)
    // Pending built-in picker requests; non-null shows the in-app dialog.
    var pendingSinglePick by remember {
        mutableStateOf<((PickResult) -> Unit)?>(null)
    }
    var pendingMultiPick by remember {
        mutableStateOf<((List<String>) -> Unit)?>(null)
    }
    val urlOpener = remember {
        UrlOpener { url ->
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }
    }
    val folderPicker = remember {
        FolderPicker { onPicked ->
            thread {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    PickResult.Picked(chooser.selectedFile.absolutePath)
                } else {
                    PickResult.Canceled
                }
                onPicked(result)
            }
        }
    }
    val filePicker = remember(useNativePicker) {
        FilePicker { onPicked ->
            if (!useNativePicker) {
                pendingSinglePick = onPicked
            } else {
                thread {
                    val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
                    dialog.isVisible = true
                    val file = dialog.file
                    val result = if (file == null) {
                        PickResult.Canceled
                    } else {
                        PickResult.Picked(File(dialog.directory, file).absolutePath)
                    }
                    onPicked(result)
                }
            }
        }
    }
    val multiFilePicker = remember(useNativePicker) {
        MultiFilePicker { onPicked ->
            if (!useNativePicker) {
                pendingMultiPick = onPicked
            } else {
                thread {
                    val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
                    dialog.isMultipleMode = true
                    dialog.isVisible = true
                    onPicked(dialog.files.orEmpty().map { it.absolutePath })
                }
            }
        }
    }
    val fileSharer = remember {
        FileSharer { paths, _, _ ->
            paths.firstOrNull()?.let { path ->
                runCatching {
                    Desktop.getDesktop().open(File(path).parentFile)
                }
            }
        }
    }
    val deleteConsentLauncher = remember {
        DeleteConsentLauncher { _, onResult -> onResult(true) }
    }
    val fileRevealer = remember { DesktopFileRevealer() }
    val fileOpener = remember { DesktopFileOpener() }
    val telegramLinkOpener = remember { DesktopTelegramLinkOpener() }
    val permissionRequester = remember {
        PermissionRequester { _, onDone -> onDone() }
    }
    val systemScreens = remember {
        object : SystemScreens {
            override fun openAppSettings() = Unit
            override fun openAllFilesAccess() = Unit
            override fun openBatteryOptimization() = Unit
        }
    }
    val deviceOwnerGate = remember {
        DeviceOwnerGate { _, _, _, onConfirmed -> onConfirmed() }
    }

    val appIcon: @Composable (Modifier) -> Unit = { modifier ->
        Image(
            painter = painterResource(Res.drawable.ic_launcher),
            contentDescription = null,
            modifier = modifier
        )
    }

    CompositionLocalProvider(
        LocalAppIcon provides appIcon,
        LocalUrlOpener provides urlOpener,
        LocalTelegramLinkOpener provides telegramLinkOpener,
        LocalFolderPicker provides folderPicker,
        LocalFilePicker provides filePicker,
        LocalMultiFilePicker provides multiFilePicker,
        LocalFileSharer provides fileSharer,
        LocalDeleteConsentLauncher provides deleteConsentLauncher,
        LocalPermissionRequester provides permissionRequester,
        LocalSystemScreens provides systemScreens,
        LocalDeviceOwnerGate provides deviceOwnerGate,
        LocalAppVersion provides BuildInfo.VERSION,
        LocalDownloadLocationConfigurable provides true,
        LocalPlatformScreens provides DesktopPlatformScreens,
        LocalFileRevealer provides fileRevealer,
        LocalFileOpener provides fileOpener,
        LocalPlatformCapabilities provides capabilities,
        content = content
    )

    pendingSinglePick?.let { onPicked ->
        DesktopUploadPickerDialog(
            multiSelect = false,
            onPicked = { files ->
                pendingSinglePick = null
                val file = files.firstOrNull()
                onPicked(
                    if (file == null) PickResult.Canceled
                    else PickResult.Picked(file.absolutePath)
                )
            },
            onCancel = {
                pendingSinglePick = null
                onPicked(PickResult.Canceled)
            }
        )
    }
    pendingMultiPick?.let { onPicked ->
        DesktopUploadPickerDialog(
            multiSelect = true,
            onPicked = { files ->
                pendingMultiPick = null
                onPicked(files.map { it.absolutePath })
            },
            onCancel = {
                pendingMultiPick = null
                onPicked(emptyList())
            }
        )
    }
}
