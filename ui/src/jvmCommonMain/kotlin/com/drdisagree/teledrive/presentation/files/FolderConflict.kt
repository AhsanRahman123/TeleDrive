package com.drdisagree.teledrive.presentation.files

import com.drdisagree.teledrive.core.files.ImportSource

data class FolderConflict(
    val names: List<String>,
    val renamed: Map<String, String>,
    val sources: List<ImportSource>,
    /** The picks' directory trees (PC-22), renamed alongside [sources] on Keep Both. */
    val folders: List<String>,
    val target: String?,
    val uploadGeneration: Long,
    /** PC-40: the fair-share group every upload of this import joins. */
    val importBatchId: String
)
