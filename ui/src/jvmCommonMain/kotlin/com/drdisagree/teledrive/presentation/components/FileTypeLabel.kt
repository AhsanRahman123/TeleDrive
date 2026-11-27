package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drdisagree.teledrive.domain.model.FileCategory

/**
 * The short type label a no-thumbnail placeholder shows so the icon SAYS what
 * the file is ("jpg", "pdf", "mp4", ...) instead of being a generic glyph.
 *
 * Preference order:
 * 1. The file name's extension — what the user recognises the file by.
 * 2. The mime subtype ("application/pdf" -> "pdf", "image/jpeg" -> "jpg").
 * 3. A kind word from the category ("image", "video", "audio", "doc",
 *    "archive"), or "file" when nothing else is known.
 */
fun fileTypeLabel(name: String?, mimeType: String?, category: FileCategory?): String {
    fileExtensionLabel(name)?.let { return it }
    mimeTypeLabel(mimeType)?.let { return it }
    return when (category) {
        FileCategory.IMAGE -> "image"
        FileCategory.VIDEO -> "video"
        FileCategory.AUDIO -> "audio"
        FileCategory.DOCUMENT -> "doc"
        FileCategory.ARCHIVE -> "archive"
        FileCategory.OTHER, null -> "file"
    }
}

/**
 * The lower-cased extension of [name] (path segments are ignored), or null
 * when the name has no usable extension: no dot, a leading dot only
 * (".hidden"), a trailing dot, or an extension too long / non-alphanumeric
 * to be a type label.
 */
internal fun fileExtensionLabel(name: String?): String? {
    val fileName = name
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.lastIndex) return null
    val extension = fileName.substring(dot + 1).lowercase()
    return extension.takeIf {
        it.length in 1..MAX_TYPE_LABEL_LENGTH && it.all { c -> c.isLetterOrDigit() }
    }
}

/**
 * A label derived from a mime type alone, or null when the subtype cannot be
 * shown as a short type word (vendor trees, parameters, generic binary).
 * Structured suffixes are dropped ("image/svg+xml" -> "svg") and the common
 * spellings the user expects are aliased ("jpeg" -> "jpg", mime "mpeg" audio
 * -> "mp3", "text/plain" -> "txt").
 */
internal fun mimeTypeLabel(mimeType: String?): String? {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if ('/' !in mime) return null
    val topLevel = mime.substringBefore('/')
    val subtype = mime.substringAfter('/')
        .substringBefore('+')
        .removePrefix("x-")
    val aliased = when {
        subtype == "jpeg" -> "jpg"
        subtype == "mpeg" && topLevel == "audio" -> "mp3"
        subtype == "plain" && topLevel == "text" -> "txt"
        else -> subtype
    }
    return aliased.takeIf {
        it.length in 1..MAX_TYPE_LABEL_LENGTH && it.all { c -> c.isLetterOrDigit() }
    }
}

private const val MAX_TYPE_LABEL_LENGTH = 8

/**
 * The shared no-thumbnail placeholder: the category icon with its type label
 * underneath. Used wherever a file renders without a thumbnail (Files list
 * and grid, Gallery, archive contents, transfers) so the placeholder always
 * names the type. Callers in very small slots pass [showLabel] = false.
 */
@Composable
fun FileTypePlaceholder(
    name: String?,
    mimeType: String?,
    category: FileCategory,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    labelSize: TextUnit = 11.sp,
    showLabel: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = iconFor(category),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint
        )
        if (showLabel) {
            Text(
                text = fileTypeLabel(name, mimeType, category),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = labelSize),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
