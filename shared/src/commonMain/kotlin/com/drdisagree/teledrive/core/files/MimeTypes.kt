package com.drdisagree.teledrive.core.files

object MimeTypes {

    const val GENERIC = "application/octet-stream"

    /**
     * A deterministic extension table for formats commonly stored in a Telegram drive.
     *
     * The platform MIME database is still used for extensions not listed here, but known media
     * formats must not become application/octet-stream merely because a particular Windows or
     * Android MIME database is incomplete.
     */
    private val knownExtensions = mapOf(
        // Audio
        "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "m4p" to "audio/mp4",
        "aac" to "audio/aac",
        "adts" to "audio/aac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "wave" to "audio/wav",
        "flac" to "audio/flac",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "aifc" to "audio/aiff",
        "wma" to "audio/x-ms-wma",
        "amr" to "audio/amr",
        "awb" to "audio/amr-wb",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "mka" to "audio/x-matroska",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
        "dts" to "audio/vnd.dts",
        "caf" to "audio/x-caf",

        // Video
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "qt" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "webm" to "video/webm",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "mpe" to "video/mpeg",
        "m1v" to "video/mpeg",
        "m2v" to "video/mpeg",
        "ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "3gp" to "video/3gpp",
        "3gpp" to "video/3gpp",
        "3g2" to "video/3gpp2",
        "wmv" to "video/x-ms-wmv",
        "asf" to "video/x-ms-asf",
        "flv" to "video/x-flv",
        "f4v" to "video/x-f4v",
        "ogv" to "video/ogg",
        "vob" to "video/dvd",
        "divx" to "video/divx",

        // Images
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jpe" to "image/jpeg",
        "jfif" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "dib" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "svgz" to "image/svg+xml",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "ico" to "image/vnd.microsoft.icon",
        "icns" to "image/icns",
        "jp2" to "image/jp2",
        "j2k" to "image/jp2",
        "jxl" to "image/jxl",
        "raw" to "image/x-canon-cr2",

        // Documents, text and structured data
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "text" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml",
        "xml" to "application/xml",
        "json" to "application/json",
        "jsonl" to "application/jsonl",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "rtf" to "application/rtf",
        "doc" to "application/msword",
        "dot" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "xls" to "application/vnd.ms-excel",
        "xlt" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "ppt" to "application/vnd.ms-powerpoint",
        "pps" to "application/vnd.ms-powerpoint",
        "pot" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ott" to "application/vnd.oasis.opendocument.text-template",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "ots" to "application/vnd.oasis.opendocument.spreadsheet-template",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "otp" to "application/vnd.oasis.opendocument.presentation-template",
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",

        // Archives and compressed files
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "bz" to "application/x-bzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "zst" to "application/zstd",
        "tzst" to "application/zstd",
        "lz" to "application/x-lzip",
        "lzma" to "application/x-lzma",
        "cab" to "application/vnd.ms-cab-compressed",
        "arj" to "application/x-arj-compressed",

        // Application packages
        "apk" to "application/vnd.android.package-archive",
        "aab" to "application/x-authorware-bin",
        "exe" to "application/vnd.microsoft.portable-executable",
        "msi" to "application/x-msi"
    )

    private val genericMimeTypes = setOf(
        "",
        GENERIC,
        "application/unknown",
        "application/x-unknown",
        "binary/octet-stream"
    )

    fun fromFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return GENERIC
        return knownExtensions[extension]
            ?: platformMimeTypeFromExtension(extension)
                ?.takeIf { it.isNotBlank() && it.lowercase() !in genericMimeTypes }
            ?: GENERIC
    }

    /**
     * Prefer a meaningful MIME type supplied by Telegram or a stored manifest. If it is absent
     * or generic, infer from the filename so a document uploaded as application/octet-stream
     * can still be recognized as audio, video, image, archive or a supported document.
     */
    fun resolve(fileName: String, declaredMimeType: String?): String {
        val declared = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return if (declared in genericMimeTypes) fromFileName(fileName) else declared
    }

    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")

    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    fun isAudio(mimeType: String): Boolean = mimeType.startsWith("audio/")

    fun isPdf(mimeType: String): Boolean = mimeType == "application/pdf"

    fun isMarkdown(mimeType: String): Boolean = mimeType == "text/markdown"

    fun isApk(mimeType: String, fileName: String? = null): Boolean =
        mimeType == "application/vnd.android.package-archive" ||
            fileName?.lowercase()?.endsWith(".apk") == true

    fun isText(mimeType: String): Boolean =
        mimeType.startsWith("text/") ||
            mimeType == "application/json" ||
            mimeType == "application/jsonl" ||
            mimeType == "application/xml" ||
            mimeType == "application/yaml" ||
            mimeType == "application/xhtml+xml" ||
            mimeType.endsWith("+xml")

    fun isArchive(mimeType: String): Boolean = mimeType in setOf(
        "application/zip",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-tar",
        "application/gzip",
        "application/x-bzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/zstd",
        "application/x-lzip",
        "application/x-lzma",
        "application/vnd.ms-cab-compressed",
        "application/x-arj-compressed"
    )
}
