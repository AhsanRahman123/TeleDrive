package com.drdisagree.teledrive.presentation.components

import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.domain.model.DriveFile

data class SelectionCapabilities(
    val canUpload: Boolean = false,
    val canDownload: Boolean = false,
    val canFreeUpSpace: Boolean = false,
    val anyNotAvailableOffline: Boolean = false,
    val anyUnfavorited: Boolean = false,
    val canEditNote: Boolean = false,
    val soleLocalPath: String? = null
) {
    companion object {
        fun of(files: List<DriveFile>): SelectionCapabilities = SelectionCapabilities(
            canUpload = files.any { !it.hasRemoteCopy },
            // A remote copy can be downloaded even when a local copy exists. This lets the
            // Storage menu offer Download and Free up space together instead of forcing
            // users to remove the local copy before they can fetch it again.
            canDownload = files.any { it.hasRemoteCopy },
            canFreeUpSpace = files.any { it.hasRemoteCopy && it.hasLocalCopy },
            anyNotAvailableOffline = files.any { !it.isAvailableOffline },
            anyUnfavorited = files.any { !it.isFavorite },
            canEditNote = files.singleOrNull()?.let { MimeTypes.isText(it.mimeType) } == true,
            soleLocalPath = files.singleOrNull()?.localPath
        )
    }
}
