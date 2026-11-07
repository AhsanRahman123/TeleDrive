package com.drdisagree.teledrive.domain.model

/** Counts a recursive folder copy, including empty folders and all copied file entries. */
data class FolderCopyResult(
    val copiedItems: Int,
    val totalItems: Int
)
