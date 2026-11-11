package com.drdisagree.teledrive.core.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ManagedDownloadCacheEvictionTest {

    @Test
    fun `explicit cache deletion always follows a successful queue removal`() = runBlocking {
        val calls = mutableListOf<String>()

        evictManagedDownloadCache(
            removeFromDownloadList = { calls += "remove-from-list" },
            deleteFromCache = { calls += "delete-cache" },
            cacheStillPresent = {
                calls += "verify-cache"
                false
            }
        )

        assertEquals(
            listOf("remove-from-list", "delete-cache", "verify-cache"),
            calls
        )
    }

    @Test
    fun `explicit cache deletion is still attempted for legacy transfers without a queue row`() =
        runBlocking {
            val calls = mutableListOf<String>()

            evictManagedDownloadCache(
                removeFromDownloadList = {
                    calls += "remove-from-list"
                    throw TelegramException(404, "not present in managed download list")
                },
                deleteFromCache = { calls += "delete-cache" },
                cacheStillPresent = {
                    calls += "verify-cache"
                    false
                }
            )

            assertEquals(
                listOf("remove-from-list", "delete-cache", "verify-cache"),
                calls
            )
        }

    @Test
    fun `eviction fails when TDLib still reports a local cached file`() = runBlocking {
        try {
            evictManagedDownloadCache(
                removeFromDownloadList = {},
                deleteFromCache = {},
                cacheStillPresent = { true }
            )
            fail("Expected cache eviction to fail while the local path remains")
        } catch (error: TelegramException) {
            assertEquals(500, error.code)
            assertEquals(
                "TDLib still has a local file after managed-download cache eviction",
                error.message
            )
        }
    }

    @Test
    fun `cancellation is not swallowed while removing a queue entry`() = runBlocking {
        val cancellation = CancellationException("test cancellation")
        try {
            evictManagedDownloadCache(
                removeFromDownloadList = { throw cancellation },
                deleteFromCache = { fail("Cache deletion must not run after cancellation") },
                cacheStillPresent = { false }
            )
            fail("Expected cancellation to propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }
}
