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

private val LEGACY_SETTINGS_KEYS = listOf(
    "defaultAlarmTime", "alarmVolumePercent", "maxRingMinutes", "snoozeSeconds", "snoozeHoldSeconds",
    "resnoozeAfterSeconds", "ringtoneUri", "ringtoneName", "role", "ssid", "passphrase",
    "passwordHash", "passwordSalt", "vibrate")

class DeMirror(ctx: Context) {
    private val p: SharedPreferences = ctx.createDeviceProtectedStorageContext()
        .getSharedPreferences("fobalarm_de", Context.MODE_PRIVATE)

    /**
     * The whole of [Settings] as last mirrored by THIS version, or null if none has been written.
     *
     * Authoritative when present: it is written synchronously on every change, where Room is
     * written asynchronously and only after first unlock.
     */
    val settings: Settings?
        get() = p.getString("settings", null)
            ?.let { runCatching { com.mtrinh.fobalarm.data.Wire.settingsAtRestFrom(JSONObject(it)) }.getOrNull() }

    /**
     * The per-field keys a pre-0.3 build mirrored, for the one launch after an upgrade.
     *
     * A SEED, never authoritative: it carries only the subset the old mirror stored, so the away
     * settings and `napMinutes` come back as defaults. Room has the real values, so the caller
     * must let Room win -- treating this as authoritative silently turns the owner's away-blocking
     * off. Deletable once both phones have upgraded.
     */
    fun legacySettings(): Settings? {
        if (!p.contains("defaultAlarmTime")) return null
        return Settings(
            defaultAlarmTime = p.getString("defaultAlarmTime", "04:00")!!,
            alarmVolumePercent = p.getInt("alarmVolumePercent", 85),
            maxRingMinutes = p.getInt("maxRingMinutes", 60),
            snoozeSeconds = p.getInt("snoozeSeconds", 30),
            snoozeHoldSeconds = p.getInt("snoozeHoldSeconds", 3),
            resnoozeAfterSeconds = p.getInt("resnoozeAfterSeconds", 5),
            ringtoneUri = p.getString("ringtoneUri", null),
            ringtoneName = p.getString("ringtoneName", null),
            role = p.getString("role", null)?.let { r -> runCatching { Role.valueOf(r) }.getOrNull() },
            ssid = p.getString("ssid", null) ?: Settings.DEFAULT_SSID,
            passphrase = p.getString("passphrase", null) ?: Settings.DEFAULT_PASSPHRASE,
            passwordHash = p.getString("passwordHash", null),
            passwordSalt = p.getString("passwordSalt", null),
            vibrate = p.getBoolean("vibrate", true),
        )
    }

    /** Written by [mirror] only, and read only by `Scheduler.sourceOf`'s one migration case. */
    val nextFireSource: String get() = p.getString("nextFireSource", "SCHEDULED")!!

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
     * [s] is null until settings have a trustworthy source -- the blob existed at startup, or Room
     * has loaded. Writing a provisional copy makes it indistinguishable from a real one on the
     * next start, which is how an upgrade came to discard Room and reset to defaults. The schedule
     * and session are always mirrored: they are this process's own truth either way.
     */
    fun mirror(s: Settings?, next: NextFire?, session: RingSession?,
               latches: List<Latch>, override: Override?, nap: Nap?) {
        // The WHOLE of Settings, in the same encoding Room uses, under one key. A chosen subset
        // leaves whatever nobody remembered to add reading as a default before first unlock, and
        // makes this mirror and Room two partial copies of one fact that can disagree.
        p.edit().apply {
            if (s != null) {
                putString("settings", com.mtrinh.fobalarm.data.Wire.settingsAtRestToJson(s).toString())
                // The legacy keys are spent once a blob exists. Left behind, an unparseable blob
                // would fall back to them and resurrect the settings as they were on upgrade day.
                for (k in LEGACY_SETTINGS_KEYS) remove(k)
            }
            putString("schedule", Persist.scheduleToJson(latches, override, nap))
            putString("nextFireSource", next?.source?.name ?: "SCHEDULED")
            putString("session", session?.let { sessionToJson(it) })
        }.apply()
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
    /**
     * The NEWEST `limit` events after `since`, returned oldest-first.
     *
     * The inner ORDER BY seq DESC is the whole point. `ORDER BY seq ASC LIMIT n`
     * returns the n oldest rows in the table, which froze the History screen on
     * the first 200 events ever recorded and hid everything that happened since.
     */
    @Query("SELECT * FROM (SELECT * FROM events WHERE seq > :since ORDER BY seq DESC LIMIT :limit) ORDER BY seq ASC")
    fun since(since: Long, limit: Int): List<EventRow>
    @Query("SELECT * FROM events ORDER BY seq ASC")
    fun all(): List<EventRow>
    @Query("DELETE FROM events WHERE atMs < :before")
    fun prune(before: Long)
    /** Earlier builds re-latched the same occurrences many times; keep the first record of each. */
    @Query("DELETE FROM events WHERE type IN ('missed','latch') AND seq NOT IN (SELECT MIN(seq) FROM events WHERE type IN ('missed','latch') GROUP BY type, detail)")
    fun dedupe()
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
        add(KvRow("settings",
            com.mtrinh.fobalarm.data.Wire.settingsAtRestToJson(st.settings).toString()))
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

    /** Whether Room holds a settings row at all, as opposed to holding defaults. */
    fun hasSettings(dao: Dao_): Boolean = dao.get("settings") != null

    fun load(dao: Dao_): EngineState {
        // Null, not defaults: an absent row means Room has nothing to say about settings, and
        // replacing memory with defaults in that case loses the role and arms nothing.
        val settings = dao.get("settings")
            ?.let { com.mtrinh.fobalarm.data.Wire.settingsAtRestFrom(JSONObject(it)) }
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
            settings = settings ?: Settings(), latches = latches, override = override, nap = nap,
            lastOutcome = lastOutcome,
            stateVersion = dao.get("stateVersion")?.toLongOrNull() ?: 0,
            lastTimeZone = dao.get("lastTimeZone")?.takeIf { it.isNotEmpty() },
            lastAliveMs = dao.get("lastAliveMs")?.toLongOrNull() ?: 0,
        )
    }
}
