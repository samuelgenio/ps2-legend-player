package com.armsx2.legend

import android.content.Context
import android.os.SystemClock
import com.armsx2.GameInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * PS2 Legend: a journal of play sessions that companion apps (the PS2 Legend catalog) read
 * through [LegendApiProvider].
 *
 * One entry per game launch. Time is counted only while the VM is RUNNING — the same spans
 * [com.armsx2.PlayTime] counts — so pausing, the in-game menu and backgrounding the app do not add
 * time. A pause and a resume of the same game keep adding to the same entry; a new boot
 * ([onBoot]) or a different game closes it and opens another.
 *
 * Every span end rewrites the entry (with a fresh `updatedAt`), so a process that gets killed
 * mid-game still leaves the time played so far. Readers sync by `updatedAt` and upsert by `id`.
 *
 * Stored in its own SharedPreferences file so the provider can read it before (or without) the
 * main activity ever starting.
 */
object SessionJournal {
    const val PREFS_NAME = "legend_sessions"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 500
    private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    private var current: JSONObject? = null
    private var spanStartedAt: Long = 0L

    private fun appContext(): Context? = com.armsx2.runtime.MainActivityRuntime.instance?.applicationContext

    /** A new boot: whatever was open is finished, even if the same game boots again. */
    @Synchronized
    fun onBoot() {
        val ctx = appContext() ?: return
        spanEnd()
        current?.let { entry ->
            entry.put("finished", true)
            entry.put("updatedAt", System.currentTimeMillis())
            upsert(ctx, entry)
        }
        current = null
    }

    /** The VM started (or resumed) RUNNING [game]. No-op for an unidentified boot. */
    @Synchronized
    fun spanStart(game: GameInfo?) {
        val serial = game?.serial?.takeIf { it.isNotBlank() } ?: return
        val ctx = appContext() ?: return
        if (spanStartedAt != 0L) spanEnd()
        val open = current
        if (open == null || open.optString("serial") != serial || open.optBoolean("finished")) {
            open?.let {
                it.put("finished", true)
                it.put("updatedAt", System.currentTimeMillis())
                upsert(ctx, it)
            }
            val now = System.currentTimeMillis()
            current = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("serial", serial)
                .put("title", game.title)
                .put("uri", game.uri.toString())
                .put("crc", "")
                .put("startedAt", now)
                .put("endedAt", now)
                .put("playedSec", 0L)
                .put("updatedAt", now)
                .put("finished", false)
        }
        spanStartedAt = SystemClock.elapsedRealtime()
    }

    /** The VM left RUNNING: bank the span into the open entry. */
    @Synchronized
    fun spanEnd() {
        val started = spanStartedAt
        if (started == 0L) return
        spanStartedAt = 0L
        val entry = current ?: return
        val ctx = appContext() ?: return
        val elapsedSec = (SystemClock.elapsedRealtime() - started) / 1000L
        val now = System.currentTimeMillis()
        entry.put("playedSec", entry.optLong("playedSec") + elapsedSec.coerceAtLeast(0L))
        entry.put("endedAt", now)
        entry.put("updatedAt", now)
        if (entry.optString("crc").isEmpty()) {
            runCatching { kr.co.iefriends.pcsx2.NativeApp.getGameCRC() }.getOrNull()
                ?.takeIf { it.matches(Regex("[0-9A-Fa-f]{8}")) && it != "00000000" }
                ?.let { entry.put("crc", it.uppercase()) }
        }
        upsert(ctx, entry)
    }

    /** Entries changed after [sinceUpdatedAt] (epoch ms), oldest first. */
    fun entries(context: Context, sinceUpdatedAt: Long = 0L): List<JSONObject> {
        val all = load(context)
        val out = ArrayList<JSONObject>(all.length())
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue
            if (e.optLong("updatedAt") > sinceUpdatedAt) out += e
        }
        return out.sortedBy { it.optLong("updatedAt") }
    }

    private fun load(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
        return runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
    }

    @Synchronized
    private fun upsert(context: Context, entry: JSONObject) {
        val all = load(context)
        val id = entry.optString("id")
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val kept = ArrayList<JSONObject>(all.length() + 1)
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue
            if (e.optString("id") == id) continue
            if (e.optLong("updatedAt") < cutoff) continue
            kept += e
        }
        kept += JSONObject(entry.toString())
        val trimmed = kept.sortedBy { it.optLong("updatedAt") }.takeLast(MAX_ENTRIES)
        val out = JSONArray().apply { trimmed.forEach { put(it) } }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, out.toString()).apply()
    }
}
