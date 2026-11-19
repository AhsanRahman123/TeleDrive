package com.drdisagree.teledrive.data.repository

/**
 * Canonical keys for matching folder names across devices.
 *
 * The same logical folder can arrive with different raw spellings — trailing
 * spaces, doubled internal spaces, different case, or every letter spaced out
 * ("r o o t s") — and each spelling used to mint its own folder row, splitting
 * the children across twins. [of] is the conservative key (trim, collapse
 * whitespace runs, casefold); [compact] additionally ignores all whitespace so
 * spaced-out spellings converge too. Merging still only happens between
 * siblings under the same parent.
 */
internal object FolderNameKey {

    private val whitespaceRun = Regex("\\s+")

    fun of(name: String): String = name.trim().replace(whitespaceRun, " ").lowercase()

    fun compact(name: String): String = name.filterNot { it.isWhitespace() }.lowercase()

    fun matches(a: String, b: String): Boolean = of(a) == of(b) || compact(a) == compact(b)
}
