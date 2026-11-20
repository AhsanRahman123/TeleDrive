package com.drdisagree.teledrive.core.files

/** References are platform URIs on Android and plain file paths on desktop. */
interface FileImporter {

    fun import(reference: String): ImportedFile?

    /** A folder yields one entry per file inside it; anything else yields the reference itself. */
    fun expand(reference: String): List<ImportSource>

    /**
     * Every directory inside a picked folder, as relative folder paths in
     * parent-before-child order — including empty directories, which
     * [expand] cannot represent (a folder with no files in it produces no
     * [ImportSource] at all). The import flow creates these in the library
     * alongside the files. Platforms that cannot enumerate a pick's
     * directory tree return an empty list.
     */
    fun expandFolders(reference: String): List<String> = emptyList()

    fun discard(imported: ImportedFile)

    fun isStaged(path: String): Boolean

    fun sweepOrphans(referencedPaths: Set<String>)
}
