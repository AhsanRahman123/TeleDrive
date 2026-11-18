package com.drdisagree.teledrive.presentation.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.drdisagree.teledrive.core.files.MimeTypes
import java.io.File

/**
 * Opens a local file in another app through the system chooser — the same
 * FileProvider content-URI + read-grant pattern the preview screen's
 * "Open with" action uses. The MIME type is resolved from the file name when
 * the declared type is missing or generic, and falls back to a wildcard type
 * when nothing is known about the file.
 */
fun openFileWithChooser(
    context: Context,
    path: String,
    displayName: String,
    mimeType: String?
): Boolean = runCatching {
    val uri = when {
        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
        else -> FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(path)
        )
    }
    val resolvedMimeType = MimeTypes.resolve(displayName, mimeType)
        .takeUnless { it == MimeTypes.GENERIC }
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, resolvedMimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, displayName, uri)
    }
    context.startActivity(Intent.createChooser(intent, displayName))
    true
}.getOrDefault(false)
