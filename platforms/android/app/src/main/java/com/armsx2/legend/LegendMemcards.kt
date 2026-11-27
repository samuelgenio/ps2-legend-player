package com.armsx2.legend

import android.content.Context
import com.armsx2.MemoryCardBackup
import java.io.File

/**
 * PS2 Legend: per-game memory cards ("<SERIAL>.ps2" in the memcards folder), the unit the catalog
 * backs up to the cloud and restores.
 */
object LegendMemcards {
    private const val SHARED_CARD = "mcd001.ps2"
    private val SERIAL = Regex("^[A-Z]{4}-\\d{5}$")

    fun isValidSerial(serial: String): Boolean = SERIAL.matches(serial)

    fun cardFile(context: Context, serial: String): File =
        File(MemoryCardBackup.cardsDir(context), "$serial.ps2")

    /**
     * Before a game first boots on its own card, start that card as a copy of the shared one. The
     * card holds every game's saves at that point, which is harmless, and nothing made before the
     * switch disappears. Never overwrites an existing per-game card.
     */
    fun seedFromShared(context: Context, serial: String) {
        if (!isValidSerial(serial)) return
        val target = cardFile(context, serial)
        if (target.exists()) return
        val shared = File(MemoryCardBackup.cardsDir(context), SHARED_CARD)
        if (!shared.isFile || shared.length() == 0L) return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        shared.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) tmp.delete()
    }

    /** Per-game cards present: serial → file. */
    fun list(context: Context): List<Pair<String, File>> =
        MemoryCardBackup.cardsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ps2") }
            ?.mapNotNull { f -> f.name.removeSuffix(".ps2").takeIf(::isValidSerial)?.let { it to f } }
            .orEmpty()
}
