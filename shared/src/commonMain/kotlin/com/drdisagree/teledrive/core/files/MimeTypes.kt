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
        "mpga" to "audio/mpeg",
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
        "spx" to "audio/ogg",
        "weba" to "audio/webm",
        "ra" to "audio/vnd.rn-realaudio",

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
        "mp4v" to "video/mp4",
        "mxf" to "application/mxf",
        "ogm" to "video/ogg",
        "dv" to "video/dv",
        "rm" to "application/vnd.rn-realmedia",
        "rmvb" to "application/vnd.rn-realmedia-vbr",

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
        "tga" to "image/x-targa",
        "dds" to "image/vnd-ms.dds",
        "psd" to "image/vnd.adobe.photoshop",
        "xcf" to "image/x-xcf",
        "dng" to "image/x-adobe-dng",
        "cr2" to "image/x-canon-cr2",
        "cr3" to "image/x-canon-cr3",
        "nef" to "image/x-nikon-nef",
        "nrw" to "image/x-nikon-nrw",
        "arw" to "image/x-sony-arw",
        "srf" to "image/x-sony-srf",
        "sr2" to "image/x-sony-sr2",
        "raf" to "image/x-fuji-raf",
        "orf" to "image/x-olympus-orf",
        "rw2" to "image/x-panasonic-rw2",
        "pef" to "image/x-pentax-pef",
        "srw" to "image/x-samsung-srw",

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

        // Source code, configuration and scripts are safe to render as bounded plain text.
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "cjs" to "text/javascript",
        "css" to "text/css",
        "scss" to "text/x-scss",
        "sass" to "text/x-sass",
        "less" to "text/x-less",
        "kt" to "text/x-kotlin",
        "kts" to "text/x-kotlin",
        "java" to "text/x-java-source",
        "py" to "text/x-python",
        "pyw" to "text/x-python",
        "rb" to "text/x-ruby",
        "go" to "text/x-go",
        "rs" to "text/x-rust",
        "c" to "text/x-c",
        "h" to "text/x-c",
        "cc" to "text/x-c++",
        "cpp" to "text/x-c++",
        "cxx" to "text/x-c++",
        "hpp" to "text/x-c++",
        "hh" to "text/x-c++",
        "cs" to "text/x-csharp",
        "php" to "text/x-php",
        "swift" to "text/x-swift",
        "sh" to "text/x-shellscript",
        "bash" to "text/x-shellscript",
        "zsh" to "text/x-shellscript",
        "ps1" to "text/x-powershell",
        "psm1" to "text/x-powershell",
        "bat" to "text/x-bat",
        "cmd" to "text/x-bat",
        "sql" to "text/x-sql",
        "ini" to "text/plain",
        "toml" to "application/toml",
        "properties" to "text/plain",
        "gradle" to "text/plain",
        "xmls" to "application/xml",
        "diff" to "text/x-diff",
        "patch" to "text/x-diff",

        // Archives and compressed files
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "tgz" to "application/x-compressed-tar",
        "tbz" to "application/x-compressed-tar",
        "tbz2" to "application/x-compressed-tar",
        "txz" to "application/x-compressed-tar",
        "bz" to "application/x-bzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "zst" to "application/zstd",
        "tzst" to "application/zstd",
        "lz" to "application/x-lzip",
        "lzma" to "application/x-lzma",
        "cab" to "application/vnd.ms-cab-compressed",
        "arj" to "application/x-arj-compressed",
        "cpio" to "application/x-cpio",
        "z" to "application/x-compress",
        "lz4" to "application/x-lz4",
        "br" to "application/brotli",
        "ar" to "application/x-archive",

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
        val normalizedName = fileName.lowercase()
        val compoundArchiveMime = when {
            normalizedName.endsWith(".tar.gz") -> "application/x-compressed-tar"
            normalizedName.endsWith(".tar.bz2") -> "application/x-compressed-tar"
            normalizedName.endsWith(".tar.xz") -> "application/x-compressed-tar"
            else -> null
        }
        if (compoundArchiveMime != null) return compoundArchiveMime

        val extension = normalizedName.substringAfterLast('.', "")
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
    fun resolve(
        fileName: String,
        declaredMimeType: String?,
        fallbackMimeType: String? = null
    ): String {
        fun meaningful(value: String?): String? {
            val normalized = value
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()
            return normalized.takeUnless { it in genericMimeTypes }
        }

        return meaningful(declaredMimeType)
            ?: meaningful(fallbackMimeType)
            ?: fromFileName(fileName)
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
            mimeType == "application/javascript" ||
            mimeType == "application/toml" ||
            mimeType == "application/x-httpd-php" ||
            mimeType == "application/x-sh" ||
            mimeType == "application/x-python" ||
            mimeType == "application/x-powershell" ||
            mimeType == "application/xml" ||
            mimeType == "application/yaml" ||
            mimeType == "application/xhtml+xml" ||
            mimeType.endsWith("+xml")

    fun isArchive(mimeType: String): Boolean = mimeType in setOf(
        "application/zip",
        "application/x-7z-compressed",
        "application/vnd.rar",
        "application/x-tar",
        "application/x-compressed-tar",
        "application/gzip",
        "application/x-bzip",
        "application/x-bzip2",
        "application/x-xz",
        "application/zstd",
        "application/x-lzip",
        "application/x-lzma",
        "application/vnd.ms-cab-compressed",
        "application/x-arj-compressed",
        "application/x-cpio",
        "application/x-compress",
        "application/x-lz4",
        "application/brotli",
        "application/x-archive"
    )
}
