package com.drdisagree.teledrive.presentation.navigation

import org.jetbrains.compose.resources.StringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import com.drdisagree.teledrive.presentation.collection.CollectionType
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.collection_archived_title
import com.drdisagree.teledrive.resources.collection_favorites_title
import com.drdisagree.teledrive.resources.collection_hidden_title
import com.drdisagree.teledrive.resources.nav_files
import com.drdisagree.teledrive.resources.nav_gallery
import com.drdisagree.teledrive.resources.nav_home
import com.drdisagree.teledrive.resources.nav_offline
import com.drdisagree.teledrive.resources.nav_settings
import com.drdisagree.teledrive.resources.trash
import com.drdisagree.teledrive.resources.transfers

/**
 * The desktop rail exposes the main collections and transfer destinations directly.
 * The compact bottom bar intentionally keeps only the four primary destinations.
 */
enum class TopLevelDestination(
    val route: Route,
    val labelRes: StringResource,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val showInBottomBar: Boolean = false
) {
    HOME(
        Route.Home,
        Res.string.nav_home,
        Icons.Outlined.Home,
        Icons.Filled.Home,
        showInBottomBar = true
    ),
    FILES(
        Route.Files(),
        Res.string.nav_files,
        Icons.AutoMirrored.Outlined.InsertDriveFile,
        Icons.AutoMirrored.Filled.InsertDriveFile,
        showInBottomBar = true
    ),
    FAVORITES(
        Route.Collection(CollectionType.FAVORITES.name),
        Res.string.collection_favorites_title,
        Icons.Filled.Star,
        Icons.Filled.Star
    ),
    OFFLINE(
        Route.Collection(CollectionType.AVAILABLE_OFFLINE.name),
        Res.string.nav_offline,
        Icons.Filled.OfflinePin,
        Icons.Filled.OfflinePin
    ),
    ARCHIVED(
        Route.Collection(CollectionType.ARCHIVED.name),
        Res.string.collection_archived_title,
        Icons.Filled.Archive,
        Icons.Filled.Archive
    ),
    HIDDEN(
        Route.Collection(CollectionType.HIDDEN.name),
        Res.string.collection_hidden_title,
        Icons.Filled.VisibilityOff,
        Icons.Filled.VisibilityOff
    ),
    TRANSFERS(
        Route.Transfers,
        Res.string.transfers,
        Icons.Outlined.SwapVert,
        Icons.Outlined.SwapVert
    ),
    TRASH(
        Route.Trash,
        Res.string.trash,
        Icons.Filled.Delete,
        Icons.Filled.Delete
    ),
    GALLERY(
        Route.Gallery,
        Res.string.nav_gallery,
        Icons.Outlined.Photo,
        Icons.Filled.Photo,
        showInBottomBar = true
    ),
    SETTINGS(
        Route.Settings,
        Res.string.nav_settings,
        Icons.Outlined.Settings,
        Icons.Filled.Settings,
        showInBottomBar = true
    )
}
