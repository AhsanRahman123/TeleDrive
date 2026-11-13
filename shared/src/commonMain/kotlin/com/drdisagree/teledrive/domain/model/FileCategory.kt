package com.drdisagree.teledrive.domain.model

enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    OTHER;

    companion object {
        fun fromMimeType(mimeType: String): FileCategory = when {
            mimeType.startsWith("image/") -> IMAGE
            mimeType.startsWith("video/") -> VIDEO
            mimeType.startsWith("audio/") -> AUDIO
            mimeType == "application/pdf" ||
                    mimeType.startsWith("text/") ||
                    mimeType == "application/json" ||
                    mimeType == "application/jsonl" ||
                    mimeType == "application/xml" ||
                    mimeType == "application/yaml" ||
                    mimeType == "application/toml" ||
                    mimeType == "application/xhtml+xml" ||
                    mimeType == "application/rtf" ||
                    mimeType == "application/epub+zip" ||
                    mimeType == "application/x-mobipocket-ebook" ||
                    mimeType == "application/msword" ||
                    mimeType == "application/vnd.ms-excel" ||
                    mimeType == "application/vnd.ms-powerpoint" ||
                    mimeType.contains("document") ||
                    mimeType.contains("spreadsheet") ||
                    mimeType.contains("presentation") -> DOCUMENT

            mimeType == "application/zip" ||
                    mimeType == "application/x-7z-compressed" ||
                    mimeType == "application/vnd.rar" ||
                    mimeType == "application/x-tar" ||
                    mimeType == "application/x-compressed-tar" ||
                    mimeType == "application/gzip" ||
                    mimeType == "application/x-bzip" ||
                    mimeType == "application/x-bzip2" ||
                    mimeType == "application/x-xz" ||
                    mimeType == "application/zstd" ||
                    mimeType == "application/x-lzip" ||
                    mimeType == "application/x-lzma" ||
                    mimeType == "application/vnd.ms-cab-compressed" ||
                    mimeType == "application/x-arj-compressed" ||
                    mimeType == "application/x-cpio" ||
                    mimeType == "application/x-compress" ||
                    mimeType == "application/x-lz4" ||
                    mimeType == "application/brotli" ||
                    mimeType == "application/x-archive" -> ARCHIVE

            else -> OTHER
        }
    }
}
