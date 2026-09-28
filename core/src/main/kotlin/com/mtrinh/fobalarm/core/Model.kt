package com.mtrinh.fobalarm.core

// ---------------------------------------------------------------------------
// Settings. Validation lives here and ONLY here, so the HTTP path cannot bypass
// a floor that the local path enforces. SPEC.md section 8.
// ---------------------------------------------------------------------------

enum class Role { ALARM, CONTROLLER }

data class Settings(
    val role: Role? = null,
    val defaultAlarmTime: String = "04:00",       // "HH:mm", local wall time
    val alarmVolumePercent: Int = 85,             // hard floor 50
    val ringtoneUri: String? = null,              // null => bundled asset
    val snoozeSeconds: Int = 30,                  // hard ceiling 600
    val snoozeThresholdDegrees: Int = 120,        // 60..720
    val maxRingMinutes: Int = 60,                 // floor 5
    val napMinutes: Int = 20,                     // last value remembered; 1..300
    val vibrate: Boolean = true,
    /** Both phones ship with the same link credentials, so the first connection needs no typing. */
    val ssid: String? = DEFAULT_SSID,
    val passphrase: String? = DEFAULT_PASSPHRASE,
    /** Null until a password is set. Until then nothing is locked. */
    val passwordHash: String? = null,
    val passwordSalt: String? = null,
    /** Set from the wire on the controller, which never sees the hash itself. */
    val hasPasswordRemote: Boolean = false,
) {
    val hasPassword: Boolean get() = passwordHash != null || hasPasswordRemote

    companion object {
        const val DEFAULT_SSID = "DIRECT-fa-alarm"
        const val DEFAULT_PASSPHRASE = "12345678"
        const val VOLUME_FLOOR = 50
        const val SNOOZE_CEILING_S = 600
        const val MAX_RING_FLOOR_M = 5

    }
}

sealed class Invalid(val reason: String) {
    class Field(val key: String, reason: String) : Invalid(reason)
}

object SettingsValidator {
    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** Clamp to the floors/ceilings. These are safety rails, not preferences. */
    fun normalize(s: Settings): Settings = s.copy(
        alarmVolumePercent = s.alarmVolumePercent.coerceIn(Settings.VOLUME_FLOOR, 100),
        snoozeSeconds = s.snoozeSeconds.coerceIn(5, Settings.SNOOZE_CEILING_S),
        snoozeThresholdDegrees = s.snoozeThresholdDegrees.coerceIn(60, 720),
        maxRingMinutes = s.maxRingMinutes.coerceIn(Settings.MAX_RING_FLOOR_M, 120),
        napMinutes = s.napMinutes.coerceIn(1, 720),
    )

    fun validate(s: Settings): List<Invalid> = buildList {
        if (!HHMM.matches(s.defaultAlarmTime)) add(Invalid.Field("defaultAlarmTime", "must be HH:mm"))
        s.passphrase?.let { if (it.length !in 8..63) add(Invalid.Field("passphrase", "8-63 characters")) }
        s.ssid?.let { if (!it.startsWith("DIRECT-")) add(Invalid.Field("ssid", "must begin DIRECT-xy")) }
    }
}

// ---------------------------------------------------------------------------
// Occurrences and latches. SPEC.md section 9.
// ---------------------------------------------------------------------------

enum class OccurrenceSource { SCHEDULED, TOMORROW_OVERRIDE, NAP }
enum class LatchReason { FIRED, SKIPPED, MISSED, SUPERSEDED }

/** Occurrence identity is (localDate, source) -- never the instant, which moves under DST. */
data class OccurrenceId(val localDate: String, val source: OccurrenceSource) {
    override fun toString() = "$localDate/$source"
    companion object {
        fun parse(s: String): OccurrenceId {
            val (d, src) = s.split("/")
            return OccurrenceId(d, OccurrenceSource.valueOf(src))
        }
    }
}

data class Latch(val id: OccurrenceId, val reason: LatchReason, val atMs: Long)

enum class OverrideKind { TIME, SKIP }

/**
 * The thing a user would call "tomorrow". Never stored as a word or a relative notion:
 * bound to a specific occurrence and resolved to an absolute instant at set time.
 */
data class Override(
    val boundOccurrenceId: OccurrenceId,
    val kind: OverrideKind,
    val fireAtMs: Long?,      // null when SKIP
)

data class Nap(val fireAtMs: Long)

// ---------------------------------------------------------------------------
// Ring session. SPEC.md section 3.
// ---------------------------------------------------------------------------

enum class RingPhase { RINGING, SNOOZED }
enum class Outcome { DISMISSED_LOCAL, DISMISSED_REMOTE, CAPPED, MISSED, SKIPPED, SUPERSEDED }

data class RingSession(
    val ringId: String,
    val occurrenceId: OccurrenceId,
    val startedAtMs: Long,
    val trigger: OccurrenceSource,
    val phase: RingPhase,
    val snoozeCount: Int,
    val snoozeUntilMs: Long?,
    val endsByMs: Long,
)

data class LastOutcome(
    val kind: Outcome,
    val atMs: Long,
    val occurrenceId: String,
    val ringId: String?,
    val snoozeCount: Int,
)

// ---------------------------------------------------------------------------
// Events. SPEC.md section 11.
// ---------------------------------------------------------------------------

enum class Actor { ALARM, CONTROLLER }

data class Event(
    val seq: Long,
    val atMs: Long,
    val type: String,
    val actor: Actor,
    val stateVersion: Long,
    val detail: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------------
// The snapshot -- the observability contract. SPEC.md section 2.
// Every timestamp renders as an age; an unknown renders as a problem, never as "OK".
// ---------------------------------------------------------------------------

enum class Mode { RINGING, INIT, WAITING }

data class Gates(
    val evaluatedAtMs: Long,
    val scheduleExists: Boolean,
    val exactAlarm: Boolean,
    val foregroundService: Boolean,
    val p2pSupported: Boolean,
    val gyroscopePresent: Boolean,
    val groupCredentialsSet: Boolean,
    val localNetworkPermission: Boolean,
    val notificationPolicyAccess: Boolean,
    val dndAllowsAlarms: Boolean,
    val volumeNotFixed: Boolean,
    val fullScreenIntent: Boolean,
    val notHibernating: Boolean,
    val thermalOk: Boolean,
    val audioPlayable: Boolean,
    val powerOk: Boolean,
    val vibrationEnabled: Boolean,
    val freeDiskOk: Boolean,
    val noBluetoothAudio: Boolean,
    /** Android may throttle a service that has run for days unless the app is exempt. */
    val batteryUnrestricted: Boolean = true,
) {
    /** Derived from the single GateInfo table -- never a second hand-kept list. */
    val allPass: Boolean get() = GateInfo.ALL.filter { it.blocking }.all { value(it.key) }

    fun failing(): List<String> = GateInfo.ALL.filter { !value(it.key) }.map { it.key }
}

data class ArmGate(val lastRunAtMs: Long, val result: String, val failingGates: List<String>)

data class NextFire(val atMs: Long, val source: OccurrenceSource, val label: String)
data class RingView(
    val ringId: String, val startedAtMs: Long, val trigger: OccurrenceSource,
    val phase: RingPhase, val snoozeCount: Int, val snoozeUntilMs: Long?,
    val endsByMs: Long, val rotationDeg: Double, val thresholdDeg: Int,
    val gyroBiasDps: Double, val gyroStale: Boolean, val rvStale: Boolean,
    val audible: String,
    /** Live orientation, so the instrument can draw the path actually traced. */
    val quaternion: DoubleArray? = null,
)
data class ClockView(
    val lastSyncAttemptMs: Long, val lastSyncOkMs: Long,
    val offsetAppliedMs: Long, val source: String, val staleByMs: Long,
)
data class ApView(
    val ssid: String?, val running: Boolean, val clientCount: Int,
    val lastStartedAtMs: Long, val lastError: String?,
)
data class DeviceView(
    val batteryPct: Int, val plugged: Boolean, val appVersion: String,
    val role: Role?, val deviceId: String, val lastSeenMs: Long = 0,
)
data class TomorrowView(val kind: String, val timeMs: Long?, val replacesMs: Long?)
data class NapView(val armed: Boolean, val atMs: Long?)

data class Snapshot(
    val stateVersion: Long,
    val serverTimeMs: Long,
    val bootedAtMs: Long,
    val mode: Mode,
    val gates: Gates,
    val armGate: ArmGate?,
    val nextFire: NextFire?,
    val ring: RingView?,
    val clock: ClockView,
    val ap: ApView,
    val self: DeviceView,
    val peer: DeviceView?,
    val lastOutcome: LastOutcome?,
    val appVersion: String,
    val settingsSchemaVersion: Int,
    val tomorrow: TomorrowView,
    val nap: NapView,
    val settings: Settings,
    val lastEvents: List<Event>,
    /** Blocking gates failing on the OTHER phone, reported by it. */
    val peerBlockers: List<String> = emptyList(),
    /** Last successful exchange between the two phones, either direction. */
    val lastHeartbeatMs: Long = 0,
    /** Non-zero while a test ring is live, so the UI can observe it ending. */
    val testUntilMs: Long = 0,
    /** Non-zero while a test ring is snoozed: when it rings again. */
    val testSnoozedUntilMs: Long = 0,
    /** Sentences that mean the alarm will NOT ring. Empty means it will. */
    val problems: List<String> = emptyList(),
    /** Sentences worth attention that do not stop the ring (link, power). */
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** mode is DERIVED, never stored. An open session outranks everything. */
        fun deriveMode(hasOpenSession: Boolean, gatesPass: Boolean): Mode = when {
            hasOpenSession -> Mode.RINGING
            !gatesPass -> Mode.INIT
            else -> Mode.WAITING
        }
    }
}

/** Everything needed to rebuild a phone. SPEC.md section 14. */
data class Backup(
    val schemaVersion: Int,
    val settings: Settings,
    val events: List<Event>,
    val exportedAtMs: Long,
)
