package com.tickmode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

 data class TickSession(
    val id: String,
    val number: Int,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val intervalMs: Long,
    val vibrationEnabled: Boolean,
    val pattern: String,
    val soundEnabled: Boolean,
    val tone: String,
    val status: String,
    val nextSignalAt: Long,
    val remainingMs: Long,
    val signalCount: Int,
    val accumulatedActiveMs: Long,
    val activeSince: Long,
    val customToneUri: String = ""
) {
    fun elapsed(now: Long = System.currentTimeMillis()): Long =
        accumulatedActiveMs + if (status == "RUNNING" && activeSince > 0L) (now - activeSince).coerceAtLeast(0L) else 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("number", number); put("startedAt", startedAt); put("endedAt", endedAt)
        put("intervalMs", intervalMs); put("vibrationEnabled", vibrationEnabled); put("pattern", pattern)
        put("soundEnabled", soundEnabled); put("tone", tone); put("status", status)
        put("nextSignalAt", nextSignalAt); put("remainingMs", remainingMs); put("signalCount", signalCount)
        put("accumulatedActiveMs", accumulatedActiveMs); put("activeSince", activeSince); put("customToneUri", customToneUri)
    }

    companion object {
        fun fromJson(j: JSONObject) = TickSession(
            id = j.optString("id"), number = j.optInt("number"), startedAt = j.optLong("startedAt"),
            endedAt = j.optLong("endedAt"), intervalMs = j.optLong("intervalMs", 60_000L),
            vibrationEnabled = j.optBoolean("vibrationEnabled", true), pattern = HapticEngine.normalizePattern(j.optString("pattern", "Medium")),
            soundEnabled = j.optBoolean("soundEnabled", true), tone = j.optString("tone", "Beep"),
            status = j.optString("status", "RUNNING"), nextSignalAt = j.optLong("nextSignalAt"),
            remainingMs = j.optLong("remainingMs"), signalCount = j.optInt("signalCount"),
            accumulatedActiveMs = j.optLong("accumulatedActiveMs"), activeSince = j.optLong("activeSince"),
            customToneUri = j.optString("customToneUri", "")
        )
    }
}

object SessionStore {
    private const val PREFS = "tickmode_store"
    private const val CURRENT = "current_session"
    private const val HISTORY = "history"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): TickSession? = try {
        prefs(context).getString(CURRENT, null)?.let { TickSession.fromJson(JSONObject(it)) }
    } catch (_: Exception) { null }

    fun saveCurrent(context: Context, session: TickSession?) {
        prefs(context).edit().putString(CURRENT, session?.toJson()?.toString()).apply()
    }

    fun nextNumber(context: Context): Int = history(context).maxOfOrNull { it.number }?.plus(1) ?: 1

    fun history(context: Context): List<TickSession> = try {
        val array = JSONArray(prefs(context).getString(HISTORY, "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(TickSession::fromJson) }.sortedByDescending { it.startedAt }
    } catch (_: Exception) { emptyList() }

    fun finish(context: Context, session: TickSession, status: String) {
        val final = session.copy(
            status = status,
            endedAt = System.currentTimeMillis(),
            accumulatedActiveMs = session.elapsed(),
            activeSince = 0L,
            nextSignalAt = 0L
        )
        val existing = JSONArray(prefs(context).getString(HISTORY, "[]"))
        val updated = JSONArray().apply {
            put(final.toJson())
            for (i in 0 until existing.length()) {
                if (i < 99) existing.optJSONObject(i)?.let(::put)
            }
        }
        prefs(context).edit().putString(HISTORY, updated.toString()).remove(CURRENT).apply()
    }

    fun settingLong(context: Context, key: String, default: Long) = prefs(context).getLong(key, default)
    fun settingBoolean(context: Context, key: String, default: Boolean) = prefs(context).getBoolean(key, default)
    fun settingString(context: Context, key: String, default: String) = prefs(context).getString(key, default) ?: default
    fun setLong(context: Context, key: String, value: Long) = prefs(context).edit().putLong(key, value).apply()
    fun setBoolean(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()
    fun setString(context: Context, key: String, value: String) = prefs(context).edit().putString(key, value).apply()
}
