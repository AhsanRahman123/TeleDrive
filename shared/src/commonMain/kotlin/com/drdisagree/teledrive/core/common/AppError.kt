package com.drdisagree.teledrive.core.common

/**
 * Telegram failures are mapped to these in the data layer, so the UI never sees raw TDLib errors.
 */
sealed interface AppError {

    data object NetworkUnavailable : AppError

    data class Timeout(val operation: String? = null) : AppError

    data object AuthenticationRequired : AppError

    data class AuthenticationFailed(val reason: String? = null) : AppError

    data class RateLimited(val retryAfterSeconds: Int) : AppError

    data class TelegramError(val code: Int, val message: String) : AppError

    /**
     * The row's stored channel message is not the file's own editable
     * message: Telegram answered a caption edit with 400
     * `MESSAGE_ID_INVALID` (the stored id resolves to nothing) or
     * `Message can't be edited` (it resolves to a message that is not
     * this file's editable post). Distinct from a generic permanent
     * rejection (PC-37): the publish drainer runs a repair ladder for
     * this outcome instead of retrying the same dead binding forever.
     * [telegramMessage] is Telegram's original error text.
     */
    data class MisboundRemote(val telegramMessage: String) : AppError

    /**
     * The file's Telegram copy is gone (or not editable) and no local
     * copy exists to re-upload: the row has been durably marked
     * remote-missing and taken out of the publish retry cycle. Surfaced
     * by [fileName] in the sync outcome so the loss is never silent.
     */
    data class RemoteMissing(val fileName: String) : AppError

    data object KeyUnreadable : AppError

    data class FileTooLarge(val sizeBytes: Long, val limitBytes: Long) : AppError

    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : AppError

    data class PermissionDenied(val permission: String? = null) : AppError

    data object NotFound : AppError

    data object FolderNameTaken : AppError

    data object FolderInsideItself : AppError

    data object BackupAlreadyRunning : AppError

    data object BackupFoldersUnreadable : AppError

    data object NoRemoteCopy : AppError

    data object NoLocalCopy : AppError

    data object LastDriveRemaining : AppError

    data object ChannelLimitReached : AppError

    data object InvalidApiCredentials : AppError

    data object InvalidPhoneNumber : AppError

    data object IncorrectCode : AppError

    data object IncorrectPassword : AppError

    data class UnsupportedOperation(val reason: String? = null) : AppError

    data class EncryptionFailure(val stage: String? = null) : AppError

    data class TransferFailed(val reason: String? = null) : AppError

    data class Unknown(val message: String? = null) : AppError
}
