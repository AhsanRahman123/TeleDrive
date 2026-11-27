package com.drdisagree.teledrive.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.desktop.DesktopPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.LocalPlatformCapabilities
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.flowOf

/**
 * The platform locals the archive pane's shared components read (the app provides
 * them at its root via DesktopPlatformProviders). Tests composing the pane directly
 * must provide the capabilities local or composition fails.
 */
@Composable
fun ArchiveTestProviders(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPlatformCapabilities provides DesktopPlatformCapabilities(),
        content = content
    )
}

/** A dynamic proxy for an interface, throwing on any method the handler does not serve. */
inline fun <reified T : Any> archiveTestProxy(
    crossinline handler: (method: String, args: Array<out Any?>?) -> Any?
): T {
    val invocation = InvocationHandler { _, method, args -> handler(method.name, args) }
    return Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        invocation
    ) as T
}

/**
 * Minimal TelegramClient serving one in-memory file as already fully downloaded,
 * the same fake shape as `RemoteArchiveServiceTest`'s: ranged reads are answered
 * straight out of [bytes].
 */
fun archiveFakeTelegram(
    bytes: ByteArray,
    onReadPart: (() -> Unit)? = null
): TelegramClient {
    val info = TelegramFileInfo(
        fileId = 7,
        sizeBytes = bytes.size.toLong(),
        localPath = null,
        isDownloadingCompleted = true,
        isDownloadingActive = false,
        downloadOffset = 0,
        downloadedPrefixSize = bytes.size.toLong()
    )
    return archiveTestProxy { method, args ->
        when (method) {
            "resolveFile", "getFileInfo" -> info
            "readFilePart" -> {
                onReadPart?.invoke()
                val offset = (args!![1] as Number).toLong()
                val count = (args[2] as Number).toLong()
                if (offset >= bytes.size) {
                    ByteArray(0)
                } else {
                    bytes.copyOfRange(
                        offset.toInt(),
                        minOf(offset + count, bytes.size.toLong()).toInt()
                    )
                }
            }
            "requestFileRange", "cancelFileDownload" -> Unit
            "fileUpdates" -> flowOf(info)
            else -> throw UnsupportedOperationException(method)
        }
    }
}

/** A ZIP (deflated entries) holding [entries] as name -> bytes, built in memory. */
fun archiveZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * A hand-built ZIP of STORED entries whose central-directory DOS date/time fields are
 * written verbatim from [dosTimes] (name -> dosTime to dosDate), so tests can produce
 * entries no standard writer emits — in particular the all-zero "no timestamp" pair.
 */
fun handBuiltZipWithDosTimes(
    entries: List<Triple<String, ByteArray, Pair<Int, Int>>>
): ByteArray {
    val out = ByteArrayOutputStream()
    fun w16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
    fun w32(v: Long) {
        for (i in 0 until 4) out.write((v shr (8 * i)).toInt() and 0xFF)
    }
    data class Rec(
        val name: ByteArray,
        val crc: Long,
        val size: Long,
        val offset: Long,
        val dosTime: Int,
        val dosDate: Int
    )
    val records = mutableListOf<Rec>()
    for ((name, data, dos) in entries) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val offset = out.size().toLong()
        w32(0x04034b50L)
        w16(20)
        w16(0x0800)
        w16(0)
        w16(dos.first)
        w16(dos.second)
        w32(crc)
        w32(data.size.toLong())
        w32(data.size.toLong())
        w16(nameBytes.size)
        w16(0)
        out.write(nameBytes)
        out.write(data)
        records += Rec(nameBytes, crc, data.size.toLong(), offset, dos.first, dos.second)
    }
    val cdStart = out.size().toLong()
    for (rec in records) {
        w32(0x02014b50L)
        w16(20)
        w16(20)
        w16(0x0800)
        w16(0)
        w16(rec.dosTime)
        w16(rec.dosDate)
        w32(rec.crc)
        w32(rec.size)
        w32(rec.size)
        w16(rec.name.size)
        w16(0)
        w16(0)
        w16(0)
        w16(0)
        w32(0L)
        w32(rec.offset)
        out.write(rec.name)
    }
    val cdSize = out.size().toLong() - cdStart
    w32(0x06054b50L)
    w16(0)
    w16(0)
    w16(records.size)
    w16(records.size)
    w32(cdSize)
    w32(cdStart)
    w16(0)
    return out.toByteArray()
}
