package com.drdisagree.teledrive.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared GitHub repo publishes both platforms: PC releases carry v1.6.x tags
 * with MSI-only assets, Android releases carry an APK asset (or an android-vcNNN
 * tag). The phone (versionName 1.5.0, versionCode 179) must ignore PC releases
 * entirely, while genuine Android updates keep the old dialog behaviour.
 */
class UpdateDecisionTest {

    private fun candidate(
        tag: String,
        assets: List<String> = emptyList(),
        body: String? = "notes",
        draft: Boolean = false,
        preRelease: Boolean = false
    ) = ReleaseCandidate(
        tag = tag,
        assetNames = assets,
        body = body,
        pageUrl = "https://example.invalid/release",
        draft = draft,
        preRelease = preRelease
    )

    @Test
    fun `pc release with msi-only assets is ignored on android`() {
        // (a) Latest release is the PC build v1.6.23 — numerically newer than the
        // installed 1.5.0, but not an Android release, so no dialog.
        val decision = decideUpdate(
            candidate = candidate(
                tag = "v1.6.23",
                assets = listOf("PrantoDrive-1.6.23.msi")
            ),
            currentVersion = "1.5.0",
            currentVersionCode = 179,
            channel = UpdateChannel.ANDROID
        )
        assertNull(decision)
    }

    @Test
    fun `android-vc tag with apk asset is offered over vc179`() {
        // (b) Latest release is android-vc180 with an APK asset, installed vc179.
        val decision = decideUpdate(
            candidate = candidate(
                tag = "android-vc180",
                assets = listOf("PrantoDrive-vc180.apk")
            ),
            currentVersion = "1.5.0",
            currentVersionCode = 179,
            channel = UpdateChannel.ANDROID
        )
        assertNotNull(decision)
        assertEquals("vc180", decision!!.version)
        assertEquals("notes", decision.notes)
    }

    @Test
    fun `installed latest android release offers nothing`() {
        // (c) Same android-vc code as installed.
        assertNull(
            decideUpdate(
                candidate = candidate(
                    tag = "android-vc179",
                    assets = listOf("PrantoDrive-vc179.apk")
                ),
                currentVersion = "1.5.0",
                currentVersionCode = 179,
                channel = UpdateChannel.ANDROID
            )
        )
        // Same dotted version as installed, even with an APK asset.
        assertNull(
            decideUpdate(
                candidate = candidate(
                    tag = "v1.5.0",
                    assets = listOf("PrantoDrive-1.5.0.apk")
                ),
                currentVersion = "1.5.0",
                currentVersionCode = 179,
                channel = UpdateChannel.ANDROID
            )
        )
    }

    @Test
    fun `dotted android release with apk asset uses version comparison`() {
        val decision = decideUpdate(
            candidate = candidate(
                tag = "v1.5.1",
                assets = listOf("PrantoDrive-1.5.1.apk")
            ),
            currentVersion = "1.5.0",
            currentVersionCode = 179,
            channel = UpdateChannel.ANDROID
        )
        assertNotNull(decision)
        assertEquals("1.5.1", decision!!.version)
    }

    @Test
    fun `dotted release without apk asset is ignored on android`() {
        assertNull(
            decideUpdate(
                candidate = candidate(tag = "v1.5.1", assets = listOf("PrantoDrive-1.5.1.zip")),
                currentVersion = "1.5.0",
                currentVersionCode = 179,
                channel = UpdateChannel.ANDROID
            )
        )
    }

    @Test
    fun `drafts and prereleases are never offered`() {
        assertNull(
            decideUpdate(
                candidate = candidate(
                    tag = "android-vc180",
                    assets = listOf("app.apk"),
                    draft = true
                ),
                currentVersion = "1.5.0",
                currentVersionCode = 179,
                channel = UpdateChannel.ANDROID
            )
        )
        assertNull(
            decideUpdate(
                candidate = candidate(
                    tag = "android-vc180",
                    assets = listOf("app.apk"),
                    preRelease = true
                ),
                currentVersion = "1.5.0",
                currentVersionCode = 179,
                channel = UpdateChannel.ANDROID
            )
        )
    }

    @Test
    fun `generic channel keeps the historical behaviour for desktop`() {
        // Desktop checks pass no channel: a v1.6.x MSI release is still offered
        // over an older desktop version, exactly as before the Android filter.
        val decision = decideUpdate(
            candidate = candidate(
                tag = "v1.6.23",
                assets = listOf("PrantoDrive-1.6.23.msi")
            ),
            currentVersion = "1.6.22",
            channel = UpdateChannel.GENERIC
        )
        assertNotNull(decision)
        assertEquals("1.6.23", decision!!.version)
        assertNull(
            decideUpdate(
                candidate = candidate(tag = "v1.6.23", assets = listOf("PrantoDrive-1.6.23.msi")),
                currentVersion = "1.6.23",
                channel = UpdateChannel.GENERIC
            )
        )
    }
}
