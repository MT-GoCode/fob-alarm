package com.mtrinh.fobalarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Device-protected storage. The ring path reads ONLY this — never Room, never CE storage.
 * A reboot nobody unlocks must still be able to compute the next fire. SPEC.md section 3.4.
 */
object DeState {
    private const val FILE = "fobalarm_de"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        val de = ctx.createDeviceProtectedStorageContext()
        prefs = de.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var nextFireAtMs: Long
        get() = prefs.getLong("nextFireAtMs", 0L)
        set(v) = prefs.edit().putLong("nextFireAtMs", v).apply()

    /** Open ring session, or 0. Used to resume ringing after a kill. */
    var sessionStartedAtMs: Long
        get() = prefs.getLong("sessionStartedAtMs", 0L)
        set(v) = prefs.edit().putLong("sessionStartedAtMs", v).apply()

    var sessionEndsByMs: Long
        get() = prefs.getLong("sessionEndsByMs", 0L)
        set(v) = prefs.edit().putLong("sessionEndsByMs", v).apply()

    var alarmVolumePercent: Int
        get() = prefs.getInt("alarmVolumePercent", 100)
        set(v) = prefs.edit().putInt("alarmVolumePercent", v).apply()

    /** "HH:mm" */
    var defaultAlarmTime: String
        get() = prefs.getString("defaultAlarmTime", "04:00")!!
        set(v) = prefs.edit().putString("defaultAlarmTime", v).apply()

    val hasOpenSession: Boolean get() = sessionStartedAtMs > 0L

    fun openSession(now: Long, maxRingMinutes: Int) {
        prefs.edit()
            .putLong("sessionStartedAtMs", now)
            .putLong("sessionEndsByMs", now + maxRingMinutes * 60_000L)
            .apply()
    }

    fun closeSession() {
        prefs.edit().putLong("sessionStartedAtMs", 0L).putLong("sessionEndsByMs", 0L).apply()
    }
}

/**
 * Append-only event log in DE storage, served over HTTP because there is no logcat.
 * seq is monotonic per device so ordering is unambiguous regardless of the clock.
 */
object Log {
    private const val FILE = "fobalarm_log"
    private const val MAX = 2000
    private lateinit var prefs: SharedPreferences
    private val seq = AtomicLong(0)
    private val lines = ArrayDeque<JSONObject>()

    fun init(ctx: Context) {
        val de = ctx.createDeviceProtectedStorageContext()
        prefs = de.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        seq.set(prefs.getLong("seq", 0L))
        val stored = prefs.getString("lines", "[]")
        runCatching {
            val arr = JSONArray(stored)
            for (i in 0 until arr.length()) lines.addLast(arr.getJSONObject(i))
        }
    }

    @Synchronized
    fun e(event: String, vararg kv: Pair<String, Any?>) {
        if (!::prefs.isInitialized) return
        val o = JSONObject()
            .put("seq", seq.incrementAndGet())
            .put("wallMs", System.currentTimeMillis())
            .put("bootNanos", System.nanoTime())
            .put("event", event)
        kv.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        lines.addLast(o)
        while (lines.size > MAX) lines.removeFirst()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        prefs.edit().putString("lines", arr.toString()).putLong("seq", seq.get()).apply()
    }

    @Synchronized
    fun asJson(): String {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        return arr.toString(2)
    }

    @Synchronized
    fun recent(n: Int): List<JSONObject> = lines.takeLast(n)
}
