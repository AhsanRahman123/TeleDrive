package com.drdisagree.teledrive.presentation.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.files_import_preparing
import com.drdisagree.teledrive.resources.files_import_uploading
import com.drdisagree.teledrive.resources.files_import_uploading_copied
import com.drdisagree.teledrive.resources.files_import_uploading_duplicates
import com.drdisagree.teledrive.resources.files_import_uploading_failed
import com.drdisagree.teledrive.resources.files_import_uploading_restored
import com.drdisagree.teledrive.resources.files_queued_for_download
import com.drdisagree.teledrive.resources.files_queued_for_upload
import com.drdisagree.teledrive.resources.files_queued_partial
import com.drdisagree.teledrive.resources.message_download_queued
import com.drdisagree.teledrive.resources.message_upload_queued
import com.drdisagree.teledrive.resources.snackbar_view_transfers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Provided by AppNavHost so any screen can open Transfers from a snackbar action. */
val LocalOpenTransfers = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Replaces whatever is on screen: a queue would leave the newest result waiting behind stale text.
 * Transfer-related messages automatically get a View action when the app navigation is available.
 * Other messages may still expose one contextual action through [actionLabel].
 */
@Composable
fun CollectSnackbarMessages(
    messages: Flow<UiText>,
    hostState: SnackbarHostState,
    actionLabel: (UiText) -> String? = { null },
    onAction: (UiText) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val currentActionLabel = rememberUpdatedState(actionLabel)
    val currentOnAction = rememberUpdatedState(onAction)
    val currentOpenTransfers = rememberUpdatedState(LocalOpenTransfers.current)
    val viewTransfersLabel = stringResource(Res.string.snackbar_view_transfers)
    val currentViewTransfersLabel = rememberUpdatedState(viewTransfersLabel)

    LaunchedEffect(messages, hostState) {
        messages.collect { message ->
            val customLabel = currentActionLabel.value(message)
            val transferAction = message.isTransferMessage() && currentOpenTransfers.value != null
            val label = customLabel ?: if (transferAction) currentViewTransfersLabel.value else null
            hostState.currentSnackbarData?.dismiss()
            scope.launch {
                val result = hostState.showSnackbar(
                    message = message.load(),
                    actionLabel = label,
                    withDismissAction = label != null
                )
                if (result == SnackbarResult.ActionPerformed) {
                    when {
                        customLabel != null -> currentOnAction.value(message)
                        transferAction -> currentOpenTransfers.value?.invoke()
                    }
                }
            }
        }
    }
}

private fun UiText.isTransferMessage(): Boolean {
    if (this !is UiText.Resource) return false
    return resource == Res.string.files_import_preparing ||
            resource == Res.string.files_import_uploading ||
            resource == Res.string.files_import_uploading_copied ||
            resource == Res.string.files_import_uploading_duplicates ||
            resource == Res.string.files_import_uploading_failed ||
            resource == Res.string.files_import_uploading_restored ||
            resource == Res.string.files_queued_for_upload ||
            resource == Res.string.files_queued_for_download ||
            resource == Res.string.files_queued_partial ||
            resource == Res.string.message_upload_queued ||
            resource == Res.string.message_download_queued
}
