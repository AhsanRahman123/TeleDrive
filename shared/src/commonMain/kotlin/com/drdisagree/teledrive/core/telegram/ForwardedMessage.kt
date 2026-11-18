package com.drdisagree.teledrive.core.telegram

/**
 * One slot of a forward answer. [messageId] is the new message's id and is always known
 * when the slot itself is non-null — even while [document] is still null, because
 * Telegram can answer a forward before the forwarded file's remote identity is filled
 * in. A null document is therefore not a failed forward: callers that need the identity
 * re-read the message by id (see `TelegramClient.getDocument`) instead of treating the
 * copy as not arrived, and clean-up deletes by [messageId] either way.
 */
data class ForwardedMessage(
    val messageId: Long,
    val document: RemoteDocument?
)
