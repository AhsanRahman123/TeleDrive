package com.drdisagree.teledrive.desktop.startup

import androidx.room.useReaderConnection
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase

/**
 * Round 11: reads the live `PRAGMA journal_mode` of the opened database.
 * Opening the database is a side effect of the first connection, so the
 * caller doubles as the "DB open done" timing point for D/Startup.
 */
suspend fun TeleDriveDatabase.readJournalMode(): String =
    useReaderConnection { transactor ->
        transactor.usePrepared("PRAGMA journal_mode") { statement ->
            if (statement.step()) statement.getText(0) else "unknown"
        }
    }
