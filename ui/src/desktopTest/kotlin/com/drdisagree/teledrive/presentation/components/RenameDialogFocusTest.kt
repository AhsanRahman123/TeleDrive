package com.drdisagree.teledrive.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class RenameDialogFocusTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `name field receives focus when dialog opens`() {
        rule.setContent {
            MaterialTheme {
                RenameDialog(
                    title = "New folder",
                    initialValue = "",
                    confirmLabel = "Create",
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }
}
