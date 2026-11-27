package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.ThumbnailModel
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.presentation.components.FileTypePlaceholder

/** The category comes from the file name because a transfer can outlive its file record. */
@Composable
fun TransferThumbnail(
    transfer: TransferTask,
    modifier: Modifier = Modifier
) {
    val mimeType = remember(transfer.displayName) { MimeTypes.fromFileName(transfer.displayName) }
    val category = remember(mimeType) { FileCategory.fromMimeType(mimeType) }
    val canHaveThumbnail = transfer.fileId != null &&
            (category == FileCategory.IMAGE || category == FileCategory.VIDEO || MimeTypes.isApk(mimeType, transfer.displayName))
    var failed by remember(transfer.fileId) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val fileId = transfer.fileId
        if (canHaveThumbnail && fileId != null && !failed) {
            AsyncImage(
                model = ThumbnailModel(fileId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true }
            )
        } else {
            FileTypePlaceholder(
                name = transfer.displayName,
                mimeType = mimeType,
                category = category,
                iconSize = 16.dp,
                labelSize = 9.sp
            )
        }
    }
}
