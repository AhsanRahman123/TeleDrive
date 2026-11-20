package com.drdisagree.teledrive.desktop.media.player

import com.drdisagree.teledrive.core.common.SafeLog
import com.sun.jna.NativeLibrary
import java.io.File
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

/**
 * Prefers the bundled libVLC (Windows, macOS, Linux AppImage), then the distro or system VLC;
 * without either, previews keep the external player.
 */
object VlcPlayback {

    val factory: MediaPlayerFactory? by lazy {
        runCatching { createFactory() }
            .onFailure { SafeLog.w(TAG, "Inline playback unavailable: ${it.message}") }
            .getOrNull()
    }

    val available: Boolean get() = factory != null

    /**
     * A dedicated LibVLC instance carrying a subtitle appearance's factory
     * arguments (PC-19). Appearance is instance configuration — it can
     * only be delivered by an instance built with it — so an appearance
     * swap builds one of these, proves a player from it live, and only
     * then retires the previous instance (see [PlayerSession]). Returns
     * null when instance creation fails; the caller then keeps the
     * current player untouched. The arguments must come from
     * [SubtitleAppearance.toVlcFactoryArguments]: an unverified instance
     * option makes libvlc_new fail outright (the report-16 `--no-libass`
     * lesson), which is exactly the failure this null surfaces safely.
     */
    fun createAppearanceFactory(appearanceArguments: List<String>): MediaPlayerFactory? =
        runCatching { createFactory(LIBVLC_ARGS + appearanceArguments) }
            .onFailure {
                SafeLog.w(TAG, "Appearance instance unavailable: ${it.message}")
            }
            .getOrNull()

    private fun createFactory(): MediaPlayerFactory = createFactory(LIBVLC_ARGS)

    private fun createFactory(arguments: List<String>): MediaPlayerFactory {
        val bundle = bundled()
        return if (bundle != null) {
            bundle.plugins?.let { setPluginPath(it) }
            NativeLibrary.addSearchPath("libvlc", bundle.libraries.absolutePath)
            NativeLibrary.addSearchPath("libvlccore", bundle.libraries.absolutePath)
            MediaPlayerFactory(null as NativeDiscovery?, arguments)
        } else {
            MediaPlayerFactory(NativeDiscovery(), arguments)
        }
    }

    private fun bundled(): VlcBundle? {
        appImageBundle()?.let { return it }
        val root = System.getProperty("compose.application.resources.dir")
            ?.let { File(it, "vlc") }
            ?: return null
        if (File(root, "libvlc.dll").exists()) return VlcBundle(root, null)

        val libraries = File(root, "lib")
        if (!File(libraries, "libvlc.dylib").exists()) return null
        return VlcBundle(libraries, File(root, "plugins").takeIf { it.isDirectory })
    }

    private fun appImageBundle(): VlcBundle? {
        val libraries = System.getenv("APPDIR")?.let { File(it, "usr/lib") } ?: return null
        if (!File(libraries, "libvlc.so.5").exists()) return null
        return VlcBundle(libraries, File(libraries, "vlc/plugins").takeIf { it.isDirectory })
    }

    private fun setPluginPath(plugins: File) {
        runCatching { PosixLibC.INSTANCE.setenv(PLUGIN_ENV_NAME, plugins.absolutePath, 1) }
            .onFailure { SafeLog.w(TAG, "Could not point libVLC at the bundled plugins") }
    }

    private val LIBVLC_ARGS = listOf("--no-video-title-show", "--quiet")
    private const val PLUGIN_ENV_NAME = "VLC_PLUGIN_PATH"
    private const val TAG = "VlcPlayback"
}
