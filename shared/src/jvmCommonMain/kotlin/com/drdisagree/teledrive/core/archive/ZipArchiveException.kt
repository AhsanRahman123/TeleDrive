package com.drdisagree.teledrive.core.archive

/**
 * Typed, user-presentable failures for remote zip browsing/extraction. Every message is plain
 * language that can be shown to the user as-is.
 */
sealed class ZipArchiveException(message: String) : Exception(message) {

    /** The bytes do not contain a zip End Of Central Directory record at all. */
    class NotAZip : ZipArchiveException("This file doesn't look like a valid zip archive.")

    /** The archive structure is damaged, truncated, or internally inconsistent. */
    class Corrupt(detail: String) :
        ZipArchiveException("This zip archive is damaged or incomplete ($detail).")

    /** The archive spans more than one disk / split-zip part, so it is not a single file. */
    class MultiDisk : ZipArchiveException(
        "This zip is split across multiple parts, so it can't be browsed as a single file. " +
            "Download all of its parts and open it locally instead."
    )

    /** The entry is password-protected inside the zip itself. */
    class EncryptedEntry(entryName: String) : ZipArchiveException(
        "\"$entryName\" is password-protected inside the zip, so it can't be extracted here. " +
            "Download the whole zip and open it locally instead."
    )

    /** The entry uses a compression method other than Stored (0) or Deflate (8). */
    class UnsupportedMethod(entryName: String, method: Int) : ZipArchiveException(
        "\"$entryName\" uses an unsupported compression method ($method). " +
            "Download the whole zip and open it locally instead."
    )

    /** The extracted bytes do not match the CRC-32 recorded in the archive. */
    class CrcMismatch(entryName: String) : ZipArchiveException(
        "\"$entryName\" failed its integrity check (CRC mismatch), so the copy in the zip " +
            "appears damaged. Download the whole zip and open it locally instead."
    )

    /** No entry with the requested name (and occurrence) exists in the archive. */
    class EntryNotFound(entryName: String) : ZipArchiveException(
        "\"$entryName\" was not found inside this zip."
    )
}

/** A gate refusal from [RemoteArchiveService]: the file cannot be browsed online at all. */
class RemoteArchiveRefusalException(message: String) : Exception(message)
