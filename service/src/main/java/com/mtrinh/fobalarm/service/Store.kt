package com.mtrinh.fobalarm.service

import android.content.Context
import android.content.SharedPreferences
import androidx.room.*
import com.mtrinh.fobalarm.core.*
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// DE (device-protected) mirror. THE RING PATH READS ONLY THIS.
//
// Room lives in credential-encrypted storage, unreadable until someone unlocks the
// phone -- which, by design, nobody ever does. A 02:00 reboot would otherwise leave
// the direct-boot receiver unable to compute nextFire, and 04:00 would pass in
// silence. SPEC.md section 3.
// ---------------------------------------------------------------------------

class DeMirror(ctx: Context) {
    private val p: SharedPreferences = ctx.createDeviceProtectedStorageContext()
        .getSharedPreferences("fobalarm_de", Context.MODE_PRIVATE)

    var nextFireAtMs: Long
        get() = p.getLong("nextFireAtMs", 0)
        set(v) = p.edit().putLong("nextFireAtMs", v).apply()

    var nextFireSource: String
        get() = p.getString("nextFireSource", "SCHEDULED")!!
        set(v) = p.edit().putString("nextFireSource", v).apply()

    var defaultAlarmTime: String
        get() = p.getString("defaultAlarmTime", "04:00")!!
        set(v) = p.edit().putString("defaultAlarmTime", v).apply()

    var alarmVolumePercent: Int
        get() = p.getInt("alarmVolumePercent", 85)
        set(v) = p.edit().putInt("alarmVolumePercent", v).apply()

    var maxRingMinutes: Int
        get() = p.getInt("maxRingMinutes", 60)
        set(v) = p.edit().putInt("maxRingMinutes", v).apply()

    var snoozeSeconds: Int
        get() = p.getInt("snoozeSeconds", 30)
        set(v) = p.edit().putInt("snoozeSeconds", v).apply()

    var snoozeThresholdDegrees: Int
        get() = p.getInt("snoozeThresholdDegrees", 120)
        set(v) = p.edit().putInt("snoozeThresholdDegrees", v).apply()

    var ringtoneUri: String?
        get() = p.getString("ringtoneUri", null)
        set(v) = p.edit().putString("ringtoneUri", v).apply()

    var role: String?
        get() = p.getString("role", null)
        set(v) = p.edit().putString("role", v).apply()

    // The link credentials and the password MUST be readable before first unlock, or a
    // reboot leaves the phone unpairable and ungated until someone physically unlocks
    // it -- which, by design, nobody ever does.
    var ssid: String?
        get() = p.getString("ssid", null)
        set(v) = p.edit().putString("ssid", v).apply()

    var passphrase: String?
        get() = p.getString("passphrase", null)
        set(v) = p.edit().putString("passphrase", v).apply()

    var passwordHash: String?
        get() = p.getString("passwordHash", null)
        set(v) = p.edit().putString("passwordHash", v).apply()

    var passwordSalt: String?
        get() = p.getString("passwordSalt", null)
        set(v) = p.edit().putString("passwordSalt", v).apply()

    /** Distinguishes "never set" from "deliberately removed", so removal sticks. */

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()


    /** Without this, process death between the tap and the fire turns a SILENT test
     *  into a full-volume siren. */
    var testSilent: Boolean
        get() = p.getBoolean("testSilent", false)
        set(v) = p.edit().putBoolean("testSilent", v).apply()

    /** The open ring session, serialized. Survives process death and first-boot lock. */
    var sessionJson: String?
        get() = p.getString("session", null)
        set(v) = p.edit().putString("session", v).apply()

    /**
     * Latches, the override and the nap, serialized. Before this the mirror held only the
     * resolved next-fire time, and the first recompute after a boot re-derived it from the
     * default schedule -- so a moved, skipped or napped alarm was forgotten until Room
     * (credential-encrypted, unreadable before unlock) loaded. The ring path must know
     * what "tomorrow" means from device-protected storage alone.
     */
    var scheduleJson: String?
        get() = p.getString("schedule", null)
        set(v) = p.edit().putString("schedule", v).apply()

    var deviceId: String?
        get() = p.getString("deviceId", null)
        set(v) = p.edit().putString("deviceId", v).apply()

    var lastAliveMs: Long
        get() = p.getLong("lastAliveMs", 0)
        set(v) = p.edit().putLong("lastAliveMs", v).apply()

    /**
     * A test ring was requested; survives the process hop to the alarm delivery.
     * Stamped with an expiry so a flag that is never consumed cannot be picked up by a
     * real alarm hours later and turned into a 60-second no-op.
     */
    var pendingTestUntilMs: Long
        get() = p.getLong("pendingTestUntilMs", 0)
        set(v) = p.edit().putLong("pendingTestUntilMs", v).apply()

    val pendingTest: Boolean get() = System.currentTimeMillis() < pendingTestUntilMs

    fun mirror(s: Settings, next: NextFire?, session: RingSession?,
               latches: List<Latch>, override: Override?, nap: Nap?) {
        p.edit()
            .putString("schedule", Persist.scheduleToJson(latches, override, nap))
            .putString("role", s.role?.name)
            .putString("ssid", s.ssid)
            .putString("passphrase", s.passphrase)
            .putString("passwordHash", s.passwordHash)
            .putString("passwordSalt", s.passwordSalt)
            .putBoolean("vibrate", s.vibrate)
            .putLong("nextFireAtMs", next?.atMs ?: 0)
            .putString("nextFireSource", next?.source?.name ?: "SCHEDULED")
            .putString("defaultAlarmTime", s.defaultAlarmTime)
            .putInt("alarmVolumePercent", s.alarmVolumePercent)
            .putInt("maxRingMinutes", s.maxRingMinutes)
            .putInt("snoozeSeconds", s.snoozeSeconds)
            .putInt("snoozeThresholdDegrees", s.snoozeThresholdDegrees)
            .putString("ringtoneUri", s.ringtoneUri)
            .putString("session", session?.let { sessionToJson(it) })
            .apply()
    }

    fun session(): RingSession? = sessionJson?.let { sessionFromJson(it) }

    /** null only on the first run of a build that has this key: then Room is the source. */
    fun schedule(): Persist.Schedule? = scheduleJson?.let { runCatching { Persist.scheduleFrom(it) }.getOrNull() }

    companion object {
        fun sessionToJson(s: RingSession) = JSONObject()
            .put("ringId", s.ringId).put("occurrenceId", s.occurrenceId.toString())
            .put("startedAtMs", s.startedAtMs).put("trigger", s.trigger.name)
            .put("phase", s.phase.name).put("snoozeCount", s.snoozeCount)
            .put("snoozeUntilMs", s.snoozeUntilMs ?: JSONObject.NULL)
            .put("endsByMs", s.endsByMs).toString()

        fun sessionFromJson(t: String): RingSession? = runCatching {
            val o = JSONObject(t)
            RingSession(
                o.getString("ringId"), OccurrenceId.parse(o.getString("occurrenceId")),
                o.getLong("startedAtMs"), OccurrenceSource.valueOf(o.getString("trigger")),
                RingPhase.valueOf(o.getString("phase")), o.getInt("snoozeCount"),
                if (o.isNull("snoozeUntilMs")) null else o.getLong("snoozeUntilMs"),
                o.getLong("endsByMs"))
        }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Room: settings, latches, and the append-only history. Expendable by design --
// if it is corrupt the ring path still works off the DE mirror. SPEC.md section 11.
// ---------------------------------------------------------------------------

@Entity(tableName = "events")
data class EventRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val atMs: Long, val type: String, val actor: String,
    val stateVersion: Long, val detail: String,
)

@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val k: String, val v: String)

@Dao
interface Dao_ {
    @Transaction
    fun putAll(rows: List<KvRow>) = rows.forEach { put(it) }

    @Insert fun insertBlocking(e: EventRow): Long
    @Query("SELECT * FROM events WHERE seq > :since ORDER BY seq ASC LIMIT :limit")
    fun since(since: Long, limit: Int): List<EventRow>
    @Query("SELECT * FROM events ORDER BY seq ASC")
    fun all(): List<EventRow>
    @Query("DELETE FROM events WHERE atMs < :before")
    fun prune(before: Long)
    /** Earlier builds re-latched the same occurrences many times; keep the first record of each. */
    @Query("DELETE FROM events WHERE type IN ('missed','latch') AND seq NOT IN (SELECT MIN(seq) FROM events WHERE type IN ('missed','latch') GROUP BY type, detail)")
    fun dedupe()
    @Query("DELETE FROM events")
    fun clearEvents()

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: KvRow)
    @Query("SELECT v FROM kv WHERE k = :k") fun get(k: String): String?
}

@Database(entities = [EventRow::class, KvRow::class], version = 1, exportSchema = true)
abstract class Db : RoomDatabase() {
    abstract fun dao(): Dao_
    companion object {
        fun open(ctx: Context): Db = Room.databaseBuilder(ctx, Db::class.java, "fobalarm.db")
            // Downgrade only. An older build opening a NEWER schema throws at open,
            // which on the alarm phone is a crash loop with no ring, so that case must
            // degrade. But a forward migration must NEVER destroy: the kv table holds
            // the settings, the password hash, the role and the latches, and wiping it
            // would drop the user back at the role picker with no password and no
            // pairing. Every future schema bump ships a real Migration.
            .fallbackToDestructiveMigrationOnDowngrade()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }
}

/** Persistent engine state that is NOT on the ring path. */
object Persist {
    const val SCHEMA_VERSION = 1

    fun save(dao: Dao_, st: EngineState) = dao.putAll(buildList {
        add(KvRow("settings", com.mtrinh.fobalarm.data.Wire.settingsToJson(st.settings)
            .put("passwordHash", st.settings.passwordHash ?: JSONObject.NULL)
            .put("passwordSalt", st.settings.passwordSalt ?: JSONObject.NULL)
            .toString()))
        add(KvRow("latches", latchesToJson(st.latches).toString()))
        add(KvRow("override", st.override?.let { overrideToJson(it).toString() } ?: ""))
        add(KvRow("nap", st.nap?.fireAtMs?.toString() ?: ""))
        add(KvRow("lastOutcome", st.lastOutcome?.let {
            JSONObject().put("kind", it.kind.name).put("atMs", it.atMs)
                .put("occurrenceId", it.occurrenceId).put("ringId", it.ringId ?: JSONObject.NULL)
                .put("snoozeCount", it.snoozeCount).toString()
        } ?: ""))
        add(KvRow("stateVersion", st.stateVersion.toString()))
        add(KvRow("lastTimeZone", st.lastTimeZone ?: ""))
        add(KvRow("lastAliveMs", st.lastAliveMs.toString()))
        add(KvRow("schemaVersion", SCHEMA_VERSION.toString()))
        })

    data class Schedule(val latches: List<Latch>, val override: Override?, val nap: Nap?)

    fun latchesToJson(l: List<Latch>): JSONArray = JSONArray().apply {
        l.forEach { put(JSONObject().put("id", it.id.toString())
            .put("reason", it.reason.name).put("atMs", it.atMs)) }
    }
    fun latchesFrom(a: JSONArray): List<Latch> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Latch(OccurrenceId.parse(o.getString("id")), LatchReason.valueOf(o.getString("reason")), o.getLong("atMs"))
    }
    fun overrideToJson(o: Override): JSONObject = JSONObject()
        .put("bound", o.boundOccurrenceId.toString()).put("kind", o.kind.name)
        .put("fireAtMs", o.fireAtMs ?: JSONObject.NULL)
    fun overrideFrom(o: JSONObject) = Override(
        OccurrenceId.parse(o.getString("bound")), OverrideKind.valueOf(o.getString("kind")),
        if (o.isNull("fireAtMs")) null else o.getLong("fireAtMs"))

    fun scheduleToJson(latches: List<Latch>, override: Override?, nap: Nap?): String = JSONObject()
        .put("latches", latchesToJson(latches))
        .put("override", override?.let { overrideToJson(it) } ?: JSONObject.NULL)
        .put("nap", nap?.fireAtMs ?: JSONObject.NULL)
        .toString()
    fun scheduleFrom(s: String): Schedule {
        val o = JSONObject(s)
        return Schedule(
            latches = latchesFrom(o.getJSONArray("latches")),
            override = if (o.isNull("override")) null else overrideFrom(o.getJSONObject("override")),
            nap = if (o.isNull("nap")) null else Nap(o.getLong("nap")))
    }

    fun load(dao: Dao_): EngineState {
        val settings = dao.get("settings")?.let {
            val o = JSONObject(it)
            com.mtrinh.fobalarm.data.Wire.settingsFrom(o, Settings()).copy(
                passwordHash = o.optString("passwordHash").takeIf { s -> s.isNotEmpty() && s != "null" },
                passwordSalt = o.optString("passwordSalt").takeIf { s -> s.isNotEmpty() && s != "null" },
            )
        } ?: Settings()
        val latches = dao.get("latches")?.let { latchesFrom(JSONArray(it)) } ?: emptyList()
        val override = dao.get("override")?.takeIf { it.isNotEmpty() }?.let { overrideFrom(JSONObject(it)) }
        val nap = dao.get("nap")?.takeIf { it.isNotEmpty() }?.let { Nap(it.toLong()) }
        val lastOutcome = dao.get("lastOutcome")?.takeIf { it.isNotEmpty() }?.let {
            val o = JSONObject(it)
            LastOutcome(Outcome.valueOf(o.getString("kind")), o.getLong("atMs"),
                o.getString("occurrenceId"),
                o.optString("ringId").takeIf { s -> s.isNotEmpty() && s != "null" },
                o.optInt("snoozeCount"))
        }
        return EngineState(
            settings = settings, latches = latches, override = override, nap = nap,
            lastOutcome = lastOutcome,
            stateVersion = dao.get("stateVersion")?.toLongOrNull() ?: 0,
            lastTimeZone = dao.get("lastTimeZone")?.takeIf { it.isNotEmpty() },
            lastAliveMs = dao.get("lastAliveMs")?.toLongOrNull() ?: 0,
        )
    }
}
