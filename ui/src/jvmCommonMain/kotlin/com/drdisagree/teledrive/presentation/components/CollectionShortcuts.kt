package com.drdisagree.teledrive.presentation.components

class CollectionShortcuts(
    val onToggleSelect: ((Int) -> Unit)? = null,
    val onSelectAll: (() -> Unit)? = null,
    val onDelete: ((Int) -> Unit)? = null,
    val onRename: ((Int) -> Unit)? = null,
    val onCopy: ((Int) -> Unit)? = null,
    val onCut: ((Int) -> Unit)? = null,
    val onPaste: (() -> Unit)? = null,
    val onNewFolder: (() -> Unit)? = null,
    val onNewTab: (() -> Unit)? = null,
    val onExpand: ((Int) -> Unit)? = null,
    val onCollapse: ((Int) -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
    val onClear: (() -> Unit)? = null
)
