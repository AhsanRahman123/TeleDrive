package com.drdisagree.teledrive.core.update

/**
 * The platform whose releases an [UpdateChecker] instance may offer. The GitHub
 * repository is shared: desktop releases carry `v1.6.x` tags and MSI assets, while
 * Android releases carry an APK asset (and may use `android-vcNNN` tags). A checker
 * must only ever offer its own platform's releases — before this filter existed,
 * the phone (versionName 1.5.0) offered the PC release v1.6.23 with the desktop
 * changelog simply because 1.6.23 > 1.5.0 as dotted numbers.
 */
enum class UpdateChannel {
    /** Only Android releases count: an APK asset, or an `android-*` tag. */
    ANDROID,

    /** Historical behaviour: any published release, compared by dotted version. */
    GENERIC
}

/** The release facts the update decision needs, decoupled from the wire payload. */
data class ReleaseCandidate(
    val tag: String,
    val assetNames: List<String> = emptyList(),
    val body: String? = null,
    val pageUrl: String = "",
    val draft: Boolean = false,
    val preRelease: Boolean = false
)

private val ANDROID_TAG_CODE = Regex("""(?i)^android-vc(\d+)$""")

/**
 * Decides whether [candidate] is an update worth offering over the installed
 * [currentVersion] (and [currentVersionCode] when the caller knows it), or null
 * when it must be ignored entirely — no dialog.
 *
 * On the [UpdateChannel.ANDROID] channel a release qualifies only when it is an
 * Android release: it ships an `.apk` asset, or its tag matches the `android-*`
 * pattern (`android-vc180`). PC releases (`v1.6.x` tags with MSI-only assets)
 * never qualify. An `android-vcNNN` tag is compared by its version code against
 * [currentVersionCode]; any other qualifying tag keeps the dotted-number
 * comparison against [currentVersion]. The [UpdateChannel.GENERIC] channel keeps
 * the exact pre-filter behaviour, so desktop checks are unchanged.
 */
fun decideUpdate(
    candidate: ReleaseCandidate,
    currentVersion: String,
    currentVersionCode: Int? = null,
    channel: UpdateChannel = UpdateChannel.GENERIC
): AppRelease? {
    if (candidate.draft || candidate.preRelease) return null
    val tag = candidate.tag.trim()

    if (channel == UpdateChannel.ANDROID) {
        val tagCode = ANDROID_TAG_CODE.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
        val hasApkAsset = candidate.assetNames.any { it.endsWith(".apk", ignoreCase = true) }
        if (!hasApkAsset && tagCode == null) return null
        if (tagCode != null && currentVersionCode != null) {
            if (tagCode <= currentVersionCode) return null
            return AppRelease(
                version = tag.substringAfter('-'),
                notes = candidate.body.orEmpty().trim(),
                pageUrl = candidate.pageUrl
            )
        }
    }

    val latest = tag.removePrefix("v")
    if (!isNewerVersion(latest, currentVersion)) return null
    return AppRelease(
        version = latest,
        notes = candidate.body.orEmpty().trim(),
        pageUrl = candidate.pageUrl
    )
}

/** Compares dotted numbers so 1.10 counts as newer than 1.9. */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val left = candidate.numbers()
    val right = current.removePrefix("v").numbers()
    if (left.isEmpty()) return false
    for (index in 0 until maxOf(left.size, right.size)) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun String.numbers(): List<Int> = split('.', '-', '_')
    .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
