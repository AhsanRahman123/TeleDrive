package com.drdisagree.teledrive.presentation.platform

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.drdisagree.teledrive.BuildConfig
import com.drdisagree.teledrive.R
import com.drdisagree.teledrive.core.files.DocumentTreePaths
import com.drdisagree.teledrive.core.files.StandardBackupFolder
import com.drdisagree.teledrive.core.permissions.manifestPermission
import com.drdisagree.teledrive.core.permissions.openAllFilesAccess
import com.drdisagree.teledrive.core.permissions.openAppSettings
import com.drdisagree.teledrive.core.permissions.openBatteryOptimization
import com.drdisagree.teledrive.domain.model.AppTheme
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.applock.requireDeviceOwner
import com.drdisagree.teledrive.presentation.common.openLink
import com.drdisagree.teledrive.presentation.common.shareLocalFiles
import com.drdisagree.teledrive.presentation.platform.AndroidUploadPickerDialog
import com.drdisagree.teledrive.presentation.theme.TeleDriveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@Composable
fun ProvidePlatformActions(content: @Composable () -> Unit) {
    val capabilities = koinInject<PlatformCapabilities>()
    val settingsRepository = koinInject<SettingsRepository>()
    val context = LocalContext.current
    val activity = LocalActivity.current

    val prefs by settingsRepository.preferences.collectAsStateWithLifecycle(UserPreferences())
    val scope = rememberCoroutineScope()
    val darkTheme = when (prefs.theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }

    val urlOpener = remember(context) { UrlOpener { url -> openLink(context, url) } }
    val telegramLinkOpener = remember(context) { AndroidTelegramLinkOpener(context) }

    var activeFolderPickerCallback by remember { mutableStateOf<((PickResult) -> Unit)?>(null) }
    var activeUploadPickerCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    var storageAccessRefreshToken by remember { mutableIntStateOf(0) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { storageAccessRefreshToken++ }

    val folderCallback = remember { CallbackHolder<PickResult>() }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.let { DocumentTreePaths.treeToFilePath(context, it) }
        folderCallback.fire(
            when {
                uri == null -> PickResult.Canceled
                path == null -> PickResult.Unreadable
                else -> PickResult.Picked(path)
            }
        )
    }
    val folderPicker = remember {
        FolderPicker { onPicked ->
            activeFolderPickerCallback = onPicked
        }
    }

    val fileCallback = remember { CallbackHolder<PickResult>() }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val path = uri?.let { DocumentTreePaths.documentToFilePath(context, it) }
        fileCallback.fire(
            when {
                uri == null -> PickResult.Canceled
                path == null -> PickResult.Unreadable
                else -> PickResult.Picked(path)
            }
        )
    }
    val filePicker = remember {
        FilePicker { onPicked ->
            fileCallback.arm(onPicked)
            fileLauncher.launch(arrayOf("*/*"))
        }
    }

    val multiCallback = remember { CallbackHolder<List<String>>() }
    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> multiCallback.fire(uris.map { it.toString() }) }
    val multiFilePicker = remember {
        MultiFilePicker { onPicked ->
            activeUploadPickerCallback = onPicked
        }
    }

    val fileSharer = remember(context) {
        FileSharer { paths, mimeType, chooserTitle ->
            shareLocalFiles(context, paths, mimeType, chooserTitle)
        }
    }

    val consentCallback = remember { CallbackHolder<Boolean>() }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        consentCallback.fire(result.resultCode == Activity.RESULT_OK)
    }
    val deleteConsentLauncher = remember {
        DeleteConsentLauncher { request, onResult ->
            consentCallback.arm(onResult)
            consentLauncher.launch(IntentSenderRequest.Builder(request).build())
        }
    }

    val permissionCallback = remember { CallbackHolder<Unit>() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionCallback.fire(Unit) }
    val permissionRequester = remember {
        PermissionRequester { permissions, onDone ->
            val manifest = permissions.mapNotNull { it.manifestPermission }
            if (manifest.isEmpty()) {
                onDone()
            } else {
                permissionCallback.arm { onDone() }
                permissionLauncher.launch(manifest.toTypedArray())
            }
        }
    }

    val systemScreens = remember(context) {
        object : SystemScreens {
            override fun openAppSettings() = openAppSettings(context)
            override fun openAllFilesAccess() = openAllFilesAccess(context)
            override fun openBatteryOptimization() = openBatteryOptimization(context)
        }
    }

    val deviceOwnerGate = remember(activity) {
        DeviceOwnerGate { title, subtitle, onDenied, onConfirmed ->
            requireDeviceOwner(
                activity = activity as? FragmentActivity,
                title = title,
                subtitle = subtitle,
                onDenied = onDenied,
                onConfirmed = onConfirmed
            )
        }
    }

    val standardFolders = remember {
        StandardBackupFolder.entries.map { StandardFolderOption(it.labelRes, it.path) }
    }
    val appIcon: @Composable (Modifier) -> Unit = { modifier ->
        AsyncImage(
            model = R.mipmap.ic_launcher,
            contentDescription = null,
            modifier = modifier
        )
    }

    CompositionLocalProvider(
        LocalAppIcon provides appIcon,
        LocalStandardFolders provides standardFolders,
        LocalUrlOpener provides urlOpener,
        LocalTelegramLinkOpener provides telegramLinkOpener,
        LocalFolderPicker provides folderPicker,
        LocalDownloadLocationConfigurable provides true,
        LocalFilePicker provides filePicker,
        LocalMultiFilePicker provides multiFilePicker,
        LocalFileSharer provides fileSharer,
        LocalDeleteConsentLauncher provides deleteConsentLauncher,
        LocalPermissionRequester provides permissionRequester,
        LocalSystemScreens provides systemScreens,
        LocalDeviceOwnerGate provides deviceOwnerGate,
        LocalAppVersion provides BuildConfig.VERSION_NAME,
        LocalPlatformScreens provides AndroidPlatformScreens,
        LocalPlatformCapabilities provides capabilities
    ) {
        content()

        activeUploadPickerCallback?.let { callback ->
            TeleDriveTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.dynamicColor,
                accentColor = prefs.accentColor,
                accentLevel = prefs.accentLevel
            ) {
                AndroidUploadPickerDialog(
                    context = context,
                    folderMode = false,
                    showHiddenFiles = prefs.showHiddenFiles,
                    onShowHiddenFilesChange = { value ->
                        scope.launch { settingsRepository.update { it.copy(showHiddenFiles = value) } }
                    },
                    accessRefreshToken = storageAccessRefreshToken,
                    onRequestStorageAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            openAllFilesAccess(context)
                        } else {
                            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    onUseAndroidPicker = {
                        activeUploadPickerCallback = null
                        multiCallback.arm(callback)
                        multiLauncher.launch(arrayOf("*/*"))
                    },
                    onFilesSelected = { paths ->
                        activeUploadPickerCallback = null
                        callback(paths)
                    },
                    onFolderSelected = {},
                    onDismiss = {
                        activeUploadPickerCallback = null
                        callback(emptyList())
                    }
                )
            }
        }

        activeFolderPickerCallback?.let { callback ->
            TeleDriveTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.dynamicColor,
                accentColor = prefs.accentColor,
                accentLevel = prefs.accentLevel
            ) {
                AndroidUploadPickerDialog(
                    context = context,
                    folderMode = true,
                    showHiddenFiles = prefs.showHiddenFiles,
                    onShowHiddenFilesChange = { value ->
                        scope.launch { settingsRepository.update { it.copy(showHiddenFiles = value) } }
                    },
                    accessRefreshToken = storageAccessRefreshToken,
                    onRequestStorageAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            openAllFilesAccess(context)
                        } else {
                            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    },
                    onUseAndroidPicker = {
                        activeFolderPickerCallback = null
                        folderCallback.arm(callback)
                        folderLauncher.launch(null)
                    },
                    onFilesSelected = {},
                    onFolderSelected = { path ->
                        activeFolderPickerCallback = null
                        callback(PickResult.Picked(path))
                    },
                    onDismiss = {
                        activeFolderPickerCallback = null
                        callback(PickResult.Canceled)
                    }
                )
            }
        }
    }
}
