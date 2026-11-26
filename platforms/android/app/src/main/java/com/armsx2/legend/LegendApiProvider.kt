package com.armsx2.legend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

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
 */
class LegendApiProvider : ContentProvider() {

    companion object {
        const val API_VERSION = 1
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
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
