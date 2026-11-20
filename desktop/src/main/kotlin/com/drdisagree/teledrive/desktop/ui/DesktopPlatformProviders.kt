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
import com.drdisagree.teledrive.presentation.platform.LocalInAppFolderPicker
import com.drdisagree.teledrive.presentation.platform.LocalInAppMultiFilePicker
import com.drdisagree.teledrive.presentation.platform.LocalNativeFolderPicker
import com.drdisagree.teledrive.presentation.platform.LocalNativeMultiFilePicker
import com.drdisagree.teledrive.presentation.platform.LocalMultiFilePicker
import com.drdisagree.teledrive.presentation.platform.LocalPermissionRequester
import com.drdisagree.teledrive.presentation.platform.LocalPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.LocalPlatformScreens
import com.drdisagree.teledrive.presentation.platform.LocalSystemScreens
import com.drdisagree.teledrive.presentation.platform.LocalTelegramLinkOpener
import com.drdisagree.teledrive.presentation.platform.LocalTransferBubblePreference
import com.drdisagree.teledrive.presentation.platform.LocalTransfersSortPreference
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
    var pendingFolderPick by remember {
        mutableStateOf<((PickResult) -> Unit)?>(null)
    }
    val urlOpener = remember {
        UrlOpener { url ->
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }
    }
    // Built-in picker variants: always the in-app Compose dialog, whatever
    // the Settings preference says. The create menu offers these explicitly.
    val inAppFilePicker = remember {
        FilePicker { onPicked -> pendingSinglePick = onPicked }
    }
    val inAppMultiFilePicker = remember {
        MultiFilePicker { onPicked -> pendingMultiPick = onPicked }
    }
    val inAppFolderPicker = remember {
        FolderPicker { onPicked -> pendingFolderPick = onPicked }
    }
    // Native Windows picker variants: always the OS dialogs. The create
    // menu offers these explicitly too.
    val nativeFolderPicker = remember {
        FolderPicker { onPicked ->
            thread {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    DesktopPickerStore.lastFolderDirectory()?.let { currentDirectory = it }
                }
                val result = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    // Remember the view the folder was picked from.
                    chooser.currentDirectory?.let { DesktopPickerStore.saveFolderDirectory(it) }
                    PickResult.Picked(chooser.selectedFile.absolutePath)
                } else {
                    PickResult.Canceled
                }
                onPicked(result)
            }
        }
    }
    val nativeFilePicker = remember {
        FilePicker { onPicked ->
            thread {
                val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
                DesktopPickerStore.lastFileDirectory()?.let { dialog.directory = it.absolutePath }
                dialog.isVisible = true
                val file = dialog.file
                val result = if (file == null) {
                    PickResult.Canceled
                } else {
                    dialog.directory?.let { DesktopPickerStore.saveFileDirectory(File(it)) }
                    PickResult.Picked(File(dialog.directory, file).absolutePath)
                }
                onPicked(result)
            }
        }
    }
    val nativeMultiFilePicker = remember {
        MultiFilePicker { onPicked ->
            thread {
                val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
                dialog.isMultipleMode = true
                DesktopPickerStore.lastFileDirectory()?.let { dialog.directory = it.absolutePath }
                dialog.isVisible = true
                val picked = dialog.files.orEmpty().map { it.absolutePath }
                if (picked.isNotEmpty()) {
                    dialog.directory?.let { DesktopPickerStore.saveFileDirectory(File(it)) }
                }
                onPicked(picked)
            }
        }
    }
    // The classic pickers follow the "Use Windows native file picker"
    // setting — it stays the default for every entry point that does not
    // choose a variant explicitly (the create menu does; Exclusions, the
    // download-location setting and other flows do not).
    val filePicker = if (useNativePicker) nativeFilePicker else inAppFilePicker
    val multiFilePicker = if (useNativePicker) nativeMultiFilePicker else inAppMultiFilePicker
    val folderPicker = if (useNativePicker) nativeFolderPicker else inAppFolderPicker
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
        LocalInAppMultiFilePicker provides inAppMultiFilePicker,
        LocalInAppFolderPicker provides inAppFolderPicker,
        LocalNativeMultiFilePicker provides nativeMultiFilePicker,
        LocalNativeFolderPicker provides nativeFolderPicker,
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
        LocalTransfersSortPreference provides DesktopTransfersSortPreference,
        LocalTransferBubblePreference provides DesktopTransferBubbleStore,
        content = content
    )

    pendingSinglePick?.let { onPicked ->
        DesktopUploadPickerDialog(
            mode = DesktopPickerMode.FILES,
            multiSelect = false,
            initialDirectory = pickerStartDirectory(folder = false),
            onDirectoryChanged = { DesktopPickerStore.saveFileDirectory(it) },
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
            mode = DesktopPickerMode.FILES,
            multiSelect = true,
            initialDirectory = pickerStartDirectory(folder = false),
            onDirectoryChanged = { DesktopPickerStore.saveFileDirectory(it) },
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
    pendingFolderPick?.let { onPicked ->
        DesktopUploadPickerDialog(
            mode = DesktopPickerMode.FOLDER,
            multiSelect = false,
            initialDirectory = pickerStartDirectory(folder = true),
            onDirectoryChanged = { DesktopPickerStore.saveFolderDirectory(it) },
            onPicked = { files ->
                pendingFolderPick = null
                val folder = files.firstOrNull()
                onPicked(
                    if (folder == null) PickResult.Canceled
                    else PickResult.Picked(folder.absolutePath)
                )
            },
            onCancel = {
                pendingFolderPick = null
                onPicked(PickResult.Canceled)
            }
        )
    }
}

/**
 * Where a picker opens: the position remembered from last time, falling
 * back to the user's home folder and then to the first filesystem root.
 */
internal fun pickerStartDirectory(folder: Boolean): File {
    val remembered = if (folder) {
        DesktopPickerStore.lastFolderDirectory()
    } else {
        DesktopPickerStore.lastFileDirectory()
    }
    return DesktopPickerStore.resolveStartDirectory(
        remembered = remembered,
        home = System.getProperty("user.home")?.let { File(it) },
        roots = fileSystemRoots()
    ) ?: File("/")
}
