package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.transfer.BackupSessionCounts
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.data.local.dao.BackupDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.desktop.notifications.DesktopNotifications
import com.drdisagree.teledrive.desktop.resources.Res
import com.drdisagree.teledrive.desktop.resources.notification_backup_complete
import com.drdisagree.teledrive.desktop.resources.notification_backup_summary
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString

/**
 * Desktop mirror of Android's NotifyingBackupSessionTracker: when a backup
 * session settles, post the result if the user kept backup notifications on.
 */
class NotifyingDesktopBackupSessionTracker(
    transferDao: TransferDao,
    backupDao: BackupDao,
    private val settingsRepository: SettingsRepository,
    private val notifications: DesktopNotifications
) : CountingBackupSessionTracker(transferDao, backupDao) {

    override suspend fun onSessionSettled(counts: BackupSessionCounts) {
        if (!settingsRepository.preferences.first().backupNotifications) return
        notifications.notifyBackupResult(
            title = getString(Res.string.notification_backup_complete),
            message = getString(
                Res.string.notification_backup_summary,
                counts.completedFiles,
                counts.totalFiles
            )
        )
    }
}
