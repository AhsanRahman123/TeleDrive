package com.drdisagree.teledrive.data.repository

import java.util.Collections

/**
 * The message ids a copy operation has forwarded but not yet recorded or deleted.
 *
 * A forwarded copy is born caption-less, and sync files any caption-less document it
 * meets as a new stray file at the root — a duplicate of the source, pointing at the
 * copy's message. While an id is registered here, sync keeps its hands off that exact
 * message: the copy operation will either relabel it and record the copy, or delete
 * it. Registration is strictly scoped to the ids one copy operation forwarded and to
 * that operation's lifetime — ids are registered the moment a forward lands and
 * removed when the copy is recorded, the message is deleted, or the operation ends.
 */
object InFlightForwardedCopies {

    private data class Key(val chatId: Long, val messageId: Long)

    private val registered = Collections.synchronizedSet(mutableSetOf<Key>())

    fun register(chatId: Long, messageIds: Collection<Long>) {
        messageIds.forEach { registered += Key(chatId, it) }
    }

    fun unregister(chatId: Long, messageIds: Collection<Long>) {
        messageIds.forEach { registered -= Key(chatId, it) }
    }

    fun isRegistered(chatId: Long, messageId: Long): Boolean =
        Key(chatId, messageId) in registered
}
