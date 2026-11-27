package com.armsx2.legend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * PS2 Legend Player API, read by the PS2 Legend catalog app.
 *
 * Protected by a signature-level permission (see the manifest), so only apps signed with the
 * same certificate can query it. The Player works the same with or without a companion: this
 * only exposes what it already records.
 *
 * Paths (authority `<applicationId>.legend`):
 *  - `sessions?since=<epoch ms>` — [SessionJournal] entries changed after `since`, oldest first.
 *    Upsert them by `id`; an entry keeps growing until `finished` is 1.
 *  - `info` — API version, app version and whether a BIOS is configured (for the companion's
 *    setup checklist).
 *  - `memcards` — per-game memory cards (serial, size, modified time).
 *  - `savestates?serial=` — that game's save states (file name, size, modified time).
 *  - files `memcard/<SERIAL>` and `savestate/<FILE>` (read = backup, write + commit = restore).
 */
class LegendApiProvider : ContentProvider() {

    companion object {
        const val API_VERSION = 3
        val SESSION_COLUMNS = arrayOf(
            "id", "serial", "title", "crc", "uri",
            "startedAt", "endedAt", "playedSec", "updatedAt", "finished",
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        return when (uri.lastPathSegment) {
            "sessions" -> {
                val since = uri.getQueryParameter("since")?.toLongOrNull() ?: 0L
                MatrixCursor(SESSION_COLUMNS).apply {
                    SessionJournal.entries(ctx, since).forEach { e ->
                        addRow(arrayOf<Any?>(
                            e.optString("id"),
                            e.optString("serial"),
                            e.optString("title"),
                            e.optString("crc"),
                            e.optString("uri"),
                            e.optLong("startedAt"),
                            e.optLong("endedAt"),
                            e.optLong("playedSec"),
                            e.optLong("updatedAt"),
                            if (e.optBoolean("finished")) 1 else 0,
                        ))
                    }
                }
            }
            "info" -> {
                val version = runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrNull().orEmpty()
                // Same prefs file the app writes ("ARMSX2", see RecentGamesContentProvider); "bios" is
                // the BIOS the user picked in setup.
                val biosConfigured = runCatching {
                    !ctx.getSharedPreferences("ARMSX2", android.content.Context.MODE_PRIVATE)
                        .getString("bios", null).isNullOrBlank()
                }.getOrDefault(false)
                MatrixCursor(arrayOf("apiVersion", "appVersion", "biosConfigured")).apply {
                    addRow(arrayOf<Any?>(API_VERSION, version, if (biosConfigured) 1 else 0))
                }
            }
            "memcards" -> MatrixCursor(arrayOf("serial", "sizeBytes", "modifiedAt")).apply {
                LegendMemcards.list(ctx).forEach { (serial, file) ->
                    addRow(arrayOf<Any?>(serial, file.length(), file.lastModified()))
                }
            }
            // savestates?serial=SLUS-20515 → that game's states, newest first.
            "savestates" -> MatrixCursor(arrayOf("fileName", "sizeBytes", "modifiedAt")).apply {
                val serial = uri.getQueryParameter("serial").orEmpty()
                if (LegendMemcards.isValidSerial(serial)) {
                    LegendSavestates.list(ctx, serial).forEach { f ->
                        addRow(arrayOf<Any?>(f.name, f.length(), f.lastModified()))
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Files the catalog backs up and restores:
     *  - `memcard/<SERIAL>`: that game's memory card.
     *  - `savestate/<FILE NAME>`: one save state (`<SERIAL> (<CRC>).NN.p2s` / `.autosave.p2s`).
     * "r" reads the file (cloud backup). "w" writes an incoming copy (cloud restore) that only
     * replaces the file after [call] `commitMemcard` / `commitSavestate` checks it. Restoring is
     * refused while a game is running: the console would write over the restored copy.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val target = targetFile(ctx, uri) ?: throw FileNotFoundException(uri.toString())
        return if (mode == "r") {
            if (!target.isFile) throw FileNotFoundException(uri.toString())
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            if (isGameRunning()) throw FileNotFoundException("game running")
            target.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                incomingFile(target),
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }
    }

    private fun targetFile(ctx: android.content.Context, uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return when (segments[0]) {
            "memcard" -> segments[1].takeIf(LegendMemcards::isValidSerial)?.let { LegendMemcards.cardFile(ctx, it) }
            "savestate" -> LegendSavestates.file(ctx, segments[1])
            else -> null
        }
    }

    /**
     * `commitMemcard` (arg = serial) / `commitSavestate` (arg = file name): checks the incoming copy
     * and puts it in place. Result: `ok` (replaced), `unchanged` (same as the current file, nothing
     * done) or `error`. The replaced file is kept as `<name>.before-restore`.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val result = Bundle()
        val target: File = when (method) {
            "commitMemcard" -> arg?.takeIf(LegendMemcards::isValidSerial)?.let { LegendMemcards.cardFile(ctx, it) }
            "commitSavestate" -> arg?.let { LegendSavestates.file(ctx, it) }
            else -> return null
        } ?: return result.apply { putString("error", "invalid_name") }
        if (isGameRunning()) return result.apply { putString("error", "game_running") }

        val incoming = incomingFile(target)
        val size = incoming.length()
        val valid = incoming.isFile && when (method) {
            // PS2 card images: 8 MB (+ECC = 8,650,752 bytes) and the larger 16–64 MB variants.
            "commitMemcard" -> size >= 1L shl 20 && size <= 128L shl 20 && (size % 512L == 0L || size % 528L == 0L)
            // Save states are zip archives.
            else -> size in 22L..(64L shl 20) && incoming.inputStream().use { s ->
                val head = ByteArray(4)
                s.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }
        if (!valid) {
            incoming.delete()
            return result.apply { putString("error", "invalid_file") }
        }
        if (sameContents(incoming, target)) {
            incoming.delete()
            return result.apply { putBoolean("unchanged", true) }
        }
        // Keep the file being replaced, so a bad restore can still be undone by hand.
        if (target.isFile) target.copyTo(File(target.parentFile, "${target.name}.before-restore"), overwrite = true)
        if (!incoming.renameTo(target)) {
            incoming.copyTo(target, overwrite = true)
            incoming.delete()
        }
        return result.apply { putBoolean("ok", true) }
    }

    private fun incomingFile(card: File) = File(card.parentFile, "${card.name}.incoming")

    private fun isGameRunning(): Boolean {
        val state = com.armsx2.runtime.MainActivityRuntime.eState.value
        return state == com.armsx2.EmuState.RUNNING || state == com.armsx2.EmuState.PAUSED
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
