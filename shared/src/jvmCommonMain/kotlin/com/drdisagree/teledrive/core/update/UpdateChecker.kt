package com.drdisagree.teledrive.core.update

import com.drdisagree.teledrive.core.common.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * An anonymous read of a public endpoint, so a check reveals no more than visiting the releases
 * page.
 *
 * The repository is shared between platforms, so [channel] decides which releases
 * may be offered at all (see [decideUpdate]): the Android checker passes
 * [UpdateChannel.ANDROID] with its version code, so PC releases (v1.6.x tags,
 * MSI-only assets) are ignored on the phone instead of popping a desktop
 * changelog. Callers that pass no channel keep the historical behaviour.
 */
class UpdateChecker(
    private val currentVersion: String,
    private val currentVersionCode: Int? = null,
    private val channel: UpdateChannel = UpdateChannel.GENERIC
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun newerRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val payload = runCatching { fetchLatest() }
            .onFailure { SafeLog.w(TAG, "Update check failed", it) }
            .getOrNull()
            ?: return@withContext null

        val release = runCatching {
            json.decodeFromString(GitHubRelease.serializer(), payload)
        }.getOrNull() ?: return@withContext null

        decideUpdate(
            candidate = ReleaseCandidate(
                tag = release.tag,
                assetNames = release.assets.map { it.name },
                body = release.body,
                pageUrl = release.pageUrl,
                draft = release.draft,
                preRelease = release.preRelease
            ),
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            channel = channel
        )
    }

    private fun fetchLatest(): String {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val RELEASES_URL =
            "https://api.github.com/repos/AhsanRahman123/TeleDrive/releases/latest"
        const val USER_AGENT = "TeleDrive"
        const val TIMEOUT_MS = 15_000
    }
}
