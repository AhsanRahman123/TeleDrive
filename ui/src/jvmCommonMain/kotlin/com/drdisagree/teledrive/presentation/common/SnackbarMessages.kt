package com.drdisagree.teledrive.presentation.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Replaces whatever is on screen: a queue would leave the newest result waiting behind stale text.
 * A message may optionally expose one contextual action in the snackbar.
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
    LaunchedEffect(messages, hostState) {
        messages.collect { message ->
            val text = message.load()
            val label = currentActionLabel.value(message)
            hostState.currentSnackbarData?.dismiss()
            scope.launch {
                val result = hostState.showSnackbar(
                    message = text,
                    actionLabel = label,
                    withDismissAction = label != null
                )
                if (result == SnackbarResult.ActionPerformed) currentOnAction.value(message)
            }
        }
    }
}
