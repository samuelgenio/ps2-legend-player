package com.armsx2.legend

import android.content.Context
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import java.security.MessageDigest

/**
 * PS2 Legend: the save states the catalog backs up to the cloud. The core names them
 * `<SERIAL> (<CRC>).NN.p2s` for numbered slots and `<SERIAL> (<CRC>).autosave[.N].p2s` for the
 * automatic ones (see ui/saves/SaveCatalog.kt).
 */
object LegendSavestates {
    private val NAME = Regex("^([A-Z]{4}-\\d{5}) \\([0-9A-F]{8}\\)\\.(\\d{2}|autosave(\\.\\d+)?)\\.p2s$")

    fun dir(context: Context): File = File(MainActivityRuntime.assetCopyRoot(context), "sstates")

    /** Serial of a valid state file name, or null. Only plain names: no paths, no ".backup". */
    fun serialOf(fileName: String): String? = NAME.matchEntire(fileName)?.groupValues?.get(1)

    fun file(context: Context, fileName: String): File? =
        serialOf(fileName)?.let { File(dir(context), fileName) }

    /** States of one game, newest first. */
    fun list(context: Context, serial: String): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && serialOf(it.name) == serial }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
}

/** True when both files exist and have the same contents. */
internal fun sameContents(a: File, b: File): Boolean {
    if (!a.isFile || !b.isFile || a.length() != b.length()) return false
    fun digest(f: File): ByteArray = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest()
    }
    return digest(a).contentEquals(digest(b))
}
