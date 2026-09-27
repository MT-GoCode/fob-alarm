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

    /** The open ring session, serialized. Survives process death and first-boot lock. */
    var sessionJson: String?
        get() = p.getString("session", null)
        set(v) = p.edit().putString("session", v).apply()

    var deviceId: String?
        get() = p.getString("deviceId", null)
        set(v) = p.edit().putString("deviceId", v).apply()

    var lastAliveMs: Long
        get() = p.getLong("lastAliveMs", 0)
        set(v) = p.edit().putLong("lastAliveMs", v).apply()

    /** A test ring was requested; survives the process hop to the alarm delivery. */
    var pendingTest: Boolean
        get() = p.getBoolean("pendingTest", false)
        set(v) = p.edit().putBoolean("pendingTest", v).apply()

    fun mirror(s: Settings, next: NextFire?, session: RingSession?) {
        p.edit()
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
    @Insert suspend fun insert(e: EventRow): Long
    @Insert fun insertBlocking(e: EventRow): Long
    @Query("SELECT * FROM events WHERE seq > :since ORDER BY seq ASC LIMIT :limit")
    fun since(since: Long, limit: Int): List<EventRow>
    @Query("SELECT * FROM events ORDER BY seq DESC LIMIT :n")
    fun recent(n: Int): List<EventRow>
    @Query("SELECT * FROM events ORDER BY seq ASC")
    fun all(): List<EventRow>
    @Query("DELETE FROM events WHERE atMs < :before")
    fun prune(before: Long)
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
            // An older build opening a newer schema throws at open -- on the alarm phone
            // that is a crash loop with no ring. History is expendable; the schedule is not.
            .fallbackToDestructiveMigrationOnDowngrade()
            .fallbackToDestructiveMigration()
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }
}

/** Persistent engine state that is NOT on the ring path. */
object Persist {
    const val SCHEMA_VERSION = 1

    fun save(dao: Dao_, st: EngineState) {
        dao.put(KvRow("settings", com.mtrinh.fobalarm.data.Wire.settingsToJson(st.settings)
            .put("passwordHash", st.settings.passwordHash ?: JSONObject.NULL)
            .put("passwordSalt", st.settings.passwordSalt ?: JSONObject.NULL)
            .put("recoveryHash", st.settings.recoveryHash ?: JSONObject.NULL)
            .put("recoverySalt", st.settings.recoverySalt ?: JSONObject.NULL)
            .toString()))
        dao.put(KvRow("latches", JSONArray().apply {
            st.latches.forEach { put(JSONObject().put("id", it.id.toString())
                .put("reason", it.reason.name).put("atMs", it.atMs)) }
        }.toString()))
        dao.put(KvRow("override", st.override?.let {
            JSONObject().put("bound", it.boundOccurrenceId.toString()).put("kind", it.kind.name)
                .put("fireAtMs", it.fireAtMs ?: JSONObject.NULL).toString()
        } ?: ""))
        dao.put(KvRow("nap", st.nap?.fireAtMs?.toString() ?: ""))
        dao.put(KvRow("lastOutcome", st.lastOutcome?.let {
            JSONObject().put("kind", it.kind.name).put("atMs", it.atMs)
                .put("occurrenceId", it.occurrenceId).put("ringId", it.ringId ?: JSONObject.NULL)
                .put("snoozeCount", it.snoozeCount).toString()
        } ?: ""))
        dao.put(KvRow("stateVersion", st.stateVersion.toString()))
        dao.put(KvRow("lastTimeZone", st.lastTimeZone ?: ""))
        dao.put(KvRow("lastAliveMs", st.lastAliveMs.toString()))
        dao.put(KvRow("schemaVersion", SCHEMA_VERSION.toString()))
    }

    fun load(dao: Dao_): EngineState {
        val settings = dao.get("settings")?.let {
            val o = JSONObject(it)
            com.mtrinh.fobalarm.data.Wire.settingsFrom(o, Settings()).copy(
                passwordHash = o.optString("passwordHash").takeIf { s -> s.isNotEmpty() && s != "null" },
                passwordSalt = o.optString("passwordSalt").takeIf { s -> s.isNotEmpty() && s != "null" },
                recoveryHash = o.optString("recoveryHash").takeIf { s -> s.isNotEmpty() && s != "null" },
                recoverySalt = o.optString("recoverySalt").takeIf { s -> s.isNotEmpty() && s != "null" },
            )
        } ?: Settings()
        val latches = dao.get("latches")?.let {
            val a = JSONArray(it)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Latch(OccurrenceId.parse(o.getString("id")), LatchReason.valueOf(o.getString("reason")), o.getLong("atMs"))
            }
        } ?: emptyList()
        val override = dao.get("override")?.takeIf { it.isNotEmpty() }?.let {
            val o = JSONObject(it)
            Override(OccurrenceId.parse(o.getString("bound")), OverrideKind.valueOf(o.getString("kind")),
                if (o.isNull("fireAtMs")) null else o.getLong("fireAtMs"))
        }
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
