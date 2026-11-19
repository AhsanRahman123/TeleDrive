package com.drdisagree.teledrive.domain.model

data class MediaAlbum(
    val folderId: String?,
    val name: String?,
    val itemCount: Int,
    val coverFileId: String?,
    val latestAt: Long,
    /**
     * Parent-folder path, set only when another album in the same list carries
     * the same display name, so same-named folders in different places can be
     * told apart. Null means the name is unique in the list — nothing to show.
     */
    val locationLabel: String? = null
)
