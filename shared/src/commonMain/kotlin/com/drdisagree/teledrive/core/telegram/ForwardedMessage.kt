package com.drdisagree.teledrive.core.telegram

/**
 * One slot of a forward answer. [messageId] is the new message's FINAL id and is always
 * known when the slot itself is non-null — even while [document] is still null, because
 * Telegram can answer a forward before the forwarded file's remote identity is filled
 * in. A null document is therefore not a failed forward: callers that need the identity
 * re-read the message by id (see `TelegramClient.getDocument`) instead of treating the
 * copy as not arrived, and clean-up deletes by [messageId] either way.
 *
 * The id is final because a just-sent message starts out under a temporary id that
 * Telegram retires the moment the send succeeds, re-issuing the message under its final
 * server id. Forwarding clients resolve that mapping (via the send-succeeded update)
 * before returning, so every id a caller keys on — the in-flight registry, the settle
 * re-read, the caption edit, the recorded copy — is the id the message actually lives
 * under. [temporaryMessageId] is the pre-resolution id when it differed: it is carried
 * for clean-up only, so a deletion can target whichever form the message currently
 * lives under, and it must never be used as a working id.
 */
data class ForwardedMessage(
    val messageId: Long,
    val document: RemoteDocument?,
    val temporaryMessageId: Long? = null
)
