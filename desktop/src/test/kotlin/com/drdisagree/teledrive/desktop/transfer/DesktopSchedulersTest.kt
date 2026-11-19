package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.update.UpdateChecker
import com.drdisagree.teledrive.desktop.notifications.DesktopNotifications
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.model.TrashItem
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.CacheStats
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMaintenanceSchedulerTest {

    // Desktop auto-backup was removed (2026-11-20): the scheduler no longer
    // holds a BackupRepository at all, so no scheduleAll call — whatever the
    // stored preferences ask for — can start a scan. The tests below pin the
    // behaviour that remains: the cache/trash cleanup loop and its arming.

    @Test
    fun cleanupEnforcesTheCacheLimitAndClearsExpiredTrash() = runBlocking {
        val cache = FakeCacheRepository()
        val trash = FakeTrashRepository()
        val scheduler = maintenanceScheduler(
            settings = FakeSettingsRepository(
                UserPreferences(maxCacheSizeMb = 250, trashAutoClearDays = 14)
            ),
            cache = cache,
            trash = trash
        )
        scheduler.runCleanup()
        assertEquals(250L * 1024 * 1024, cache.lastLimitBytes)
        assertEquals(14, trash.lastClearedDays)
    }

    @Test
    fun cleanupSkipsTrashWhenAutoClearIsOff() = runBlocking {
        val trash = FakeTrashRepository()
        val scheduler = maintenanceScheduler(
            settings = FakeSettingsRepository(UserPreferences(trashAutoClearDays = 0)),
            trash = trash
        )
        scheduler.runCleanup()
        assertEquals(null, trash.lastClearedDays)
    }

    @Test
    fun schedulingRunsACleanupPassImmediatelyEvenWithBackupRequested() = runBlocking {
        val cache = FakeCacheRepository()
        val scheduler = maintenanceScheduler(cache = cache)
        scheduler.scheduleAll(
            backupEnabled = true,
            backupIntervalHours = 24,
            wifiOnly = false,
            chargingOnly = false,
            instantBackup = false,
            updateChecks = false
        )
        assertTrue(
            "cleanup runs at arm time; the backup request is ignored on desktop",
            awaitTrue { cache.enforceCalls.get() >= 1 }
        )
    }

    private fun maintenanceScheduler(
        settings: SettingsRepository = FakeSettingsRepository(),
        cache: CacheRepository = FakeCacheRepository(),
        trash: TrashRepository = FakeTrashRepository()
    ): DesktopMaintenanceScheduler {
        val scheduler = DesktopMaintenanceScheduler(
            settingsRepository = settings,
            updateChecker = UpdateChecker(currentVersion = "999.0.0"),
            notifications = DesktopNotifications(),
            cacheRepository = cache,
            trashRepository = trash,
            dispatchers = TestDispatchers
        )
        scheduler.updateIntervalMs = 60_000
        scheduler.cleanupIntervalMs = 60_000
        return scheduler
    }
}

class DesktopBackupResumeSchedulerTest {

    @Test
    fun resumeKicksTheTransferQueueAfterTheDelay() = runBlocking {
        val kicks = AtomicInteger(0)
        val scheduler = DesktopBackupResumeScheduler(TestDispatchers).apply {
            resumeDelayMs = 50
            kickAction = { kicks.incrementAndGet() }
        }
        scheduler.resumeWhen(chargingOnly = false, wifiOnly = false)
        assertTrue(awaitTrue { kicks.get() == 1 })
    }

    @Test
    fun aNewerResumeReplacesAPendingOne() = runBlocking {
        val kicks = AtomicInteger(0)
        val scheduler = DesktopBackupResumeScheduler(TestDispatchers).apply {
            resumeDelayMs = 150
            kickAction = { kicks.incrementAndGet() }
        }
        scheduler.resumeWhen(chargingOnly = false, wifiOnly = false)
        delay(50)
        scheduler.resumeWhen(chargingOnly = false, wifiOnly = false)
        assertTrue(awaitTrue { kicks.get() == 1 })
        delay(300)
        assertEquals("the replaced request must not kick twice", 1, kicks.get())
    }

    @Test
    fun resumeWithoutAWiredKickTargetIsANoOp() = runBlocking {
        val scheduler = DesktopBackupResumeScheduler(TestDispatchers).apply { resumeDelayMs = 10 }
        scheduler.resumeWhen(chargingOnly = false, wifiOnly = false)
        delay(100) // must simply do nothing, not throw
    }
}

private object TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

private suspend fun awaitTrue(condition: () -> Boolean): Boolean =
    withTimeoutOrNull(3_000) {
        while (!condition()) delay(10)
        true
    } ?: false

private class FakeSettingsRepository(
    initial: UserPreferences = UserPreferences()
) : SettingsRepository {
    override val preferences: Flow<UserPreferences> = flowOf(initial)

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) = Unit

    override suspend fun getTelegramCredentials(): TelegramCredentials? = null

    override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit

    override suspend fun hasStoredTelegramCredentials(): Boolean = false

    override suspend fun clearTelegramCredentials() = Unit
}

private class FakeCacheRepository : CacheRepository {
    val enforceCalls = AtomicInteger(0)

    @Volatile
    var lastLimitBytes: Long? = null

    override fun observeStats(): Flow<CacheStats> = flowOf(CacheStats(0, 0, 0, 0, 0))

    override suspend fun refreshStats() = Unit

    override suspend fun clearThumbnails() = Unit

    override suspend fun clearLinkThumbnails() = Unit

    override suspend fun clearTemp() = Unit

    override suspend fun clearAll() = Unit

    override suspend fun enforceLimit(maxBytes: Long) {
        enforceCalls.incrementAndGet()
        lastLimitBytes = maxBytes
    }
}

private class FakeTrashRepository : TrashRepository {
    @Volatile
    var lastClearedDays: Int? = null

    override fun observeTrash(): Flow<List<TrashItem>> = flowOf(emptyList())

    override suspend fun repairTrashTree() = Unit

    override suspend fun trashedChildCounts(folderIds: List<String>): Map<String, Int> = emptyMap()

    override suspend fun trashedChildren(folderId: String): List<TrashItem> = emptyList()

    override suspend fun moveFilesToTrash(ids: List<String>): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun moveFolderToTrash(id: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun restoreFiles(ids: List<String>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun restoreFolder(id: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteFilesPermanently(ids: List<String>): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun deleteFolderPermanently(id: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun emptyTrash(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun clearExpired(days: Int): AppResult<Int> {
        lastClearedDays = days
        return AppResult.Success(0)
    }
}
