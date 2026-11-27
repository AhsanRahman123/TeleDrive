package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.presentation.platform.LocalPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P2 (1.6.26): on desktop, Escape in the two folder-picker dialogs runs
 * the dialog's own back ladder — up one level per press, dismiss only at
 * the root — instead of the framework's default, which routed Escape
 * straight to dismiss and closed the whole picker from any depth (the
 * PC-30 lesson). Exercised against the REAL dialogs with desktop
 * platform capabilities provided, the configuration the PC app runs.
 *
 * Key delivery follows the DesktopUploadPickerDialogTest pattern: key
 * input goes to the focused element of the dialog's scene, so the
 * confirm button is clicked once to place focus inside the dialog (the
 * fakes' onConfirm is a no-op record) and then serves as the stable
 * handle for every Escape.
 */
@OptIn(ExperimentalTestApi::class)
class FolderPickerEscapeBackTest {

    @get:Rule
    val rule = createComposeRule()

    /** Desktop capabilities: no touch selection bar, so desktop key handling is on. */
    private object DesktopCapabilities : PlatformCapabilities {
        override val supportsAutoBackup = false
        override val requiresPermissions = false
        override val supportsPullToRefresh = false
        override val supportsAppLock = false
        override val supportsScreenCaptureBlocking = true
        override val supportsDynamicColor = false
    }

    private fun folder(id: String, parentId: String?, name: String) = DriveFolder(
        id = id,
        parentId = parentId,
        name = name,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        trashedAt = null,
        createdAt = 0,
        modifiedAt = 0
    )

    private fun pressEscape(confirmLabel: String) {
        rule.onNodeWithText(confirmLabel).performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    @Test
    fun `folder picker escape steps up one level then dismisses at the root`() {
        val dismissed = AtomicBoolean(false)
        val alpha = folder("a", null, "Alpha")
        val beta = folder("b", "a", "Beta")
        rule.setContent {
            CompositionLocalProvider(LocalPlatformCapabilities provides DesktopCapabilities) {
                FolderPickerDialog(
                    title = "Pick a folder",
                    confirmLabel = "Select",
                    childrenOf = { parent ->
                        when (parent) {
                            null -> listOf(alpha)
                            "a" -> listOf(beta)
                            else -> emptyList()
                        }
                    },
                    nameOf = { id -> if (id == "a") "Alpha" else "Beta" },
                    parentOf = { id -> if (id == "b") "a" else null },
                    onConfirm = {},
                    onDismiss = { dismissed.set(true) }
                )
            }
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        // Focus inside the dialog scene (see class doc), then descend.
        rule.onNodeWithText("Select").performClick()
        rule.onNodeWithText("Alpha").performClick()
        rule.onNodeWithText("Beta").assertIsDisplayed()

        // One Escape: up exactly one level, dialog still open.
        pressEscape("Select")
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        assertFalse("one Escape inside a folder must not dismiss", dismissed.get())

        // Escape at the root: dismiss.
        pressEscape("Select")
        assertTrue("Escape at the root dismisses", dismissed.get())
    }

    @Test
    fun `filesystem picker escape steps up one level then dismisses at the root`() {
        val dismissed = AtomicBoolean(false)
        rule.setContent {
            CompositionLocalProvider(LocalPlatformCapabilities provides DesktopCapabilities) {
                FileSystemFolderPickerDialog(
                    rootPath = "/root",
                    listSubfolders = { path ->
                        when (path) {
                            "/root" -> FolderListResult.Success(
                                listOf(LocalFolderItem("Sub", "/root/Sub"))
                            )
                            else -> FolderListResult.Success(emptyList())
                        }
                    },
                    onConfirm = {},
                    onDismiss = { dismissed.set(true) }
                )
            }
        }
        rule.onNodeWithText("Sub").assertIsDisplayed()
        // Focus inside the dialog scene (see class doc), then descend.
        rule.onNodeWithText("Select this folder").performClick()
        rule.onNodeWithText("Sub").performClick()
        rule.waitForIdle()

        // One Escape: back up to the root listing, dialog still open.
        pressEscape("Select this folder")
        rule.onNodeWithText("Sub").assertIsDisplayed()
        assertFalse("one Escape inside a folder must not dismiss", dismissed.get())

        // Escape at the root: dismiss.
        pressEscape("Select this folder")
        assertTrue("Escape at the root dismisses", dismissed.get())
    }
}
