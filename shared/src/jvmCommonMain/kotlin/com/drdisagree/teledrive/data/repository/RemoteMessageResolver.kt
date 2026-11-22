package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.RemoteDocumentPage
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * The one place that answers "which channel message is this file's?"
 * (PC-37).
 *
 * Before this existed, the knowledge lived only inside
 * [SyncRepositoryImpl]'s reconcile: a page of scanned documents was
 * matched to rows by message id, then by the td1 caption's uuid, then
 * by Telegram unique id. The publish repair ladder needs the same
 * answers from the other direction — "which message carries THIS
 * row's uuid / unique id?" — so the matching rules are factored here
 * and shared: sync uses [matchRow] for its per-page choice, the
 * ladder uses [scanChannel] plus the same manifest decoding, and the
 * two can never drift into disagreeing about what identifies a file.
 */
class RemoteMessageResolver(
    private val telegramClient: TelegramClient,
    private val manifestCodec: ManifestCodec
) {

    /**
     * The decoded td1 manifest a document's caption carries, or null
     * for a caption-less document, a locked (encrypted-key) caption,
     * or a caption that is not a file manifest at all.
     */
    fun manifestOf(document: RemoteDocument): RemoteFileManifest? =
        if (manifestCodec.isLocked(document.caption)) {
            null
        } else {
            manifestCodec.decode(document.caption)
        }

    /**
     * One full pass over a channel's documents, indexed for repair
     * lookups. [ChannelIndex.complete] is false when the pass was cut
     * short (stopped, a fetch failed, or the page cap was reached):
     * positive hits from a partial pass are still trustworthy, but a
     * MISS is not — the caller must not conclude a file's message is
     * gone from an incomplete index.
     */
    suspend fun scanChannel(
        chatId: Long,
        isStopped: () -> Boolean = { false }
    ): ChannelIndex {
        val byUuid = mutableMapOf<String, RemoteDocument>()
        val byUniqueId = mutableMapOf<String, RemoteDocument>()
        var fromMessageId = 0L
        var pages = 0
        var complete = false
        while (pages++ < MAX_PAGES) {
            if (isStopped()) break
            val page = try {
                fetchPage(chatId, fromMessageId)
            } catch (e: TelegramException) {
                SafeLog.w(
                    TAG,
                    "Channel scan for repair stopped early in chat $chatId: ${e.message}"
                )
                break
            }
            if (page.documents.isEmpty() && page.nextFromMessageId == 0L) {
                complete = true
                break
            }
            for (document in page.documents) {
                manifestOf(document)?.let { manifest ->
                    byUuid.putIfAbsent(manifest.fileId, document)
                }
                byUniqueId.putIfAbsent(document.uniqueFileId, document)
            }
            if (page.nextFromMessageId == 0L) {
                complete = true
                break
            }
            fromMessageId = page.nextFromMessageId
        }
        SafeLog.d(
            TAG,
            "Channel scan for repair in chat $chatId: ${byUuid.size} manifests, " +
                "${byUniqueId.size} documents, complete=$complete"
        )
        return ChannelIndex(byUuid, byUniqueId, complete)
    }

    private suspend fun fetchPage(chatId: Long, fromMessageId: Long): RemoteDocumentPage {
        var retries = 0
        while (true) {
            try {
                return telegramClient.fetchDocuments(chatId, fromMessageId, PAGE_SIZE)
            } catch (e: TelegramException) {
                if (e.isRateLimit && retries++ < MAX_RATE_LIMIT_RETRIES) {
                    delay((((e.retryAfterSeconds ?: 5) + 1) * 1000L).milliseconds)
                } else {
                    throw e
                }
            }
        }
    }

    data class ChannelIndex(
        val byUuid: Map<String, RemoteDocument>,
        val byUniqueId: Map<String, RemoteDocument>,
        val complete: Boolean
    )

    companion object {
        private const val TAG = "RemoteMessageResolver"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 2000
        private const val MAX_RATE_LIMIT_RETRIES = 5

        /** A scanned document whose caption names a different row than its message-id binding. */
        data class BindingConflict(val captionRowId: String, val messageRowId: String)

        data class RowMatch(val row: FileEntity?, val conflict: BindingConflict?)

        /**
         * Which existing row a scanned document belongs to — sync's
         * identity priority, shared verbatim with reconcile:
         *
         * 1. a row bound to this exact message (the only identity a
         *    caption-less document can be matched by);
         * 2. the row the td1 caption's uuid names;
         * 3. a row with the same Telegram unique id, only when it is
         *    unbound or already bound to this very message (a row
         *    bound elsewhere is a duplicate upload of the same
         *    content, not this document's row).
         *
         * PC-37 hardening: when routes 1 and 2 both hit and name
         * DIFFERENT rows, the caption uuid wins and the disagreement
         * is returned as a [BindingConflict] for the caller to log
         * loudly. The pre-PC-37 silent route-1 priority is what let a
         * mis-binding entrench itself forever: every later sync saw
         * the wrong row claiming the message and left it alone.
         * Caption-less documents (route 2 cannot fire) keep the
         * original order unchanged.
         */
        fun matchRow(
            document: RemoteDocument,
            manifest: RemoteFileManifest?,
            byId: Map<String, FileEntity>,
            byUniqueId: Map<String, FileEntity>,
            byMessageId: Map<Long, FileEntity>
        ): RowMatch {
            val messageRow = byMessageId[document.messageId]
            val captionRow = manifest?.let { byId[it.fileId] }
            if (captionRow != null && messageRow != null && captionRow.id != messageRow.id) {
                return RowMatch(
                    row = captionRow,
                    conflict = BindingConflict(
                        captionRowId = captionRow.id,
                        messageRowId = messageRow.id
                    )
                )
            }
            val row = messageRow ?: captionRow ?: byUniqueId[document.uniqueFileId]
                ?.takeIf { candidate ->
                    candidate.messageId == null ||
                        candidate.messageId == document.messageId
                }
            return RowMatch(row, null)
        }
    }
}
