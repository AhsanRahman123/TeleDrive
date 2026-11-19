package com.drdisagree.teledrive.desktop

import com.drdisagree.teledrive.core.common.SafeLog
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Two instances sharing the database and TDLib session deadlock; the lock releases with the
 * process, even after a crash.
 *
 * Failure policy: contention (another instance holding the lock) never throws —
 * [FileChannel.tryLock] returns null and the second instance is refused. An
 * *exception* means the data directory itself cannot be written (permissions,
 * disk, a transient IO glitch), in which case the app could not use its
 * database or TDLib session either. Failing open on an exception — the old
 * behaviour — admitted exactly the dual-instance state this lock exists to
 * prevent, so an exception is retried once (for a transient glitch) and then
 * fails CLOSED, with the cause logged loudly.
 */
class SingleInstanceLock(private val directory: File) {

    private var channel: FileChannel? = null

    fun acquire(): Boolean {
        repeat(ACQUIRE_ATTEMPTS) { attempt ->
            try {
                directory.mkdirs()
                val opened = FileChannel.open(
                    File(directory, LOCK_FILE).toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                )
                val lock = opened.tryLock()
                if (lock == null) {
                    opened.close()
                    return false
                }
                channel = opened
                return true
            } catch (e: Exception) {
                SafeLog.e(
                    TAG,
                    "Could not acquire the single-instance lock " +
                        "(attempt ${attempt + 1} of $ACQUIRE_ATTEMPTS); " +
                        "refusing to start a second instance on failure",
                    e
                )
                if (attempt + 1 < ACQUIRE_ATTEMPTS) {
                    runCatching { Thread.sleep(RETRY_DELAY_MS) }
                }
            }
        }
        return false
    }

    private companion object {
        const val TAG = "SingleInstanceLock"
        const val LOCK_FILE = "app.lock"
        const val ACQUIRE_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 250L
    }
}
