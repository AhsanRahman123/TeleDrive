package com.drdisagree.teledrive.domain.model

data class UserPreferences(
    val onboardingComplete: Boolean = false,
    val storageChatId: Long? = null,

    val autoBackupEnabled: Boolean = false,
    val instantBackupEnabled: Boolean = true,
    val backupFolders: Set<String> = emptySet(),
    val backupWifiOnly: Boolean = false,
    val downloadDirectory: String? = null,
    val backupChargingOnly: Boolean = false,
    val backupIntervalHours: Int = 24,
    val backupMaxFileSizeMb: Int = 0,

    val maxCacheSizeMb: Int = 1024,
    val trashAutoClearDays: Int = 30,

    val appLockEnabled: Boolean = false,
    val blockScreenCapture: Boolean = false,
    val autoLockTimeoutMinutes: Int = 5,
    val encryptFiles: Boolean = false,
    val deleteAfterUpload: Boolean = false,
    val encryptThumbnails: Boolean = false,
    val keyBackupCreated: Boolean = false,
    val folderOwnershipRepaired: Boolean = false,

    val theme: AppTheme = AppTheme.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentColor: AccentColor = AccentColor.BLUE,
    val accentLevel: AccentLevel = AccentLevel.MEDIUM,
    val displayRefreshRate: Int = 60,
    val viewMode: ViewMode = ViewMode.GRID,
    val gridSize: Int = 3,
    val galleryViewMode: ViewMode = ViewMode.GRID,
    val galleryGridSize: Int = 3,
    val albumGridSize: Int = 3,
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val showHiddenFiles: Boolean = true,
    val showArchivedFiles: Boolean = true,
    val showRecentFiles: Boolean = true,
    val linkPreviews: Boolean = true,
    val textPreviewScale: Float = 1f,
    val previewBackgroundMode: PreviewBackgroundMode = PreviewBackgroundMode.THEME,

    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val gallerySortField: FileSortField = FileSortField.DATE_MODIFIED,
    val gallerySortDirection: SortDirection = SortDirection.DESCENDING,
    /** The ZIP viewer's own remembered sort, mirroring the gallery's per-view pair. */
    val archiveSortField: FileSortField = FileSortField.NAME,
    val archiveSortDirection: SortDirection = SortDirection.ASCENDING,

    val backgroundPlayback: Boolean = false,
    /** Desktop only: true = OS-native picker, false = built-in picker (default). */
    val useNativeFilePicker: Boolean = false,
    val streamBeforeDownload: Boolean = true,
    val preferredAudioLanguage: String = "",
    val preferredSubtitleLanguage: String = "",

    val backupNotifications: Boolean = true,
    val failureNotifications: Boolean = true,

    val transferConcurrency: Int = 2,

    /**
     * PC-39 (1.6.17): per-direction simultaneous-transfer limits, each
     * 1..6, replacing the single shared [transferConcurrency] pool (which
     * capped uploads + downloads COMBINED). Defaults are the user's
     * proven-safe 2/2; he raises them himself, live, from Settings.
     * [transferConcurrency] is kept and still persisted for compatibility;
     * on first read it seeds both values when the new keys were never set.
     */
    val uploadConcurrency: Int = 2,
    val downloadConcurrency: Int = 2,
    val transferRetryCount: Int = 3,
    val allowMeteredTransfers: Boolean = true,
    val debugLogging: Boolean = false,

    val updateCheckEnabled: Boolean = true,
    val lastUpdateCheckAt: Long = 0L,
    /** Wall-clock time of the last completed full (non-incremental) sync; persisted so the catch-up throttle survives restarts (round 11). */
    val lastFullSyncAt: Long = 0L,
    val notifiedUpdateVersion: String = "",
    val skippedUpdateVersion: String = "",

    val proxyEnabled: Boolean = false,
    val activeProxyId: String = ""
)
