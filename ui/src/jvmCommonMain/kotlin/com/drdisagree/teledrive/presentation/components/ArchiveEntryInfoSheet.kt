package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryInfo
import com.drdisagree.teledrive.presentation.preview.archiveEntryDisplayName
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_name
import com.drdisagree.teledrive.resources.info_compressed_size
import com.drdisagree.teledrive.resources.info_extension
import com.drdisagree.teledrive.resources.info_modified
import com.drdisagree.teledrive.resources.info_path
import com.drdisagree.teledrive.resources.info_size
import com.drdisagree.teledrive.resources.info_type
import com.drdisagree.teledrive.resources.preview_archive
import com.drdisagree.teledrive.resources.preview_archive_format_title
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

/**
 * Info sheet for an entry open in the in-zip viewer (vc182): the entry's own
 * listing metadata only — name, sizes, type, in-archive path and the containing
 * archive — reusing [FileInfoSheet]'s rows. An archive entry has no Drive-side
 * metadata, so nothing from FileInfoSheet's Drive sections is shown here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveEntryInfoSheet(
    info: ArchiveEntryInfo,
    onDismiss: () -> Unit
) {
    val entry = info.entry
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = archiveEntryDisplayName(entry),
                style = MaterialTheme.typography.titleLarge
            )
            if (info.archiveFormat.isNotBlank()) {
                Text(
                    text = stringResource(
                        Res.string.preview_archive_format_title,
                        info.archiveFormat
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))

            InfoRow(stringResource(Res.string.common_name), archiveEntryDisplayName(entry))
            InfoRow(stringResource(Res.string.info_size), Formatters.bytes(entry.sizeBytes))
            InfoRow(
                stringResource(Res.string.info_compressed_size),
                Formatters.bytes(entry.compressedBytes)
            )
            InfoRow(
                stringResource(Res.string.info_type),
                MimeTypes.fromFileName(entry.name)
            )
            entry.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let {
                InfoRow(stringResource(Res.string.info_extension), it.uppercase(Locale.ROOT))
            }
            if (entry.modifiedAtMillis > 0) {
                InfoRow(
                    stringResource(Res.string.info_modified),
                    Formatters.dateTime(entry.modifiedAtMillis)
                )
            }
            InfoRow(stringResource(Res.string.info_path), entry.name)
            InfoRow(stringResource(Res.string.preview_archive), info.archiveName)

            Spacer(Modifier.height(24.dp))
        }
    }
}
