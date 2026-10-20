package com.mtrinh.fobalarm.core

// ---------------------------------------------------------------------------
// Settings. Validation lives here and ONLY here, so the HTTP path cannot bypass
// a floor that the local path enforces. SPEC.md section 8.
// ---------------------------------------------------------------------------

/**
 * SENTINEL is a presence beacon with a screen. It joins the alarm phone's group exactly as the
 * controller does, so being joined IS proof of being within Wi-Fi Direct range (~50-100 m). It
 * cannot dismiss, snooze, change settings or unlock. See PLAN.md.
 */
enum class Role { ALARM, CONTROLLER, SENTINEL }

data class Settings(
    val role: Role? = null,
    val defaultAlarmTime: String = "04:00",       // "HH:mm", local wall time
    val alarmVolumePercent: Int = 85,             // hard floor 50
    val ringtoneUri: String? = null,              // null => bundled asset
    val ringtoneName: String? = null,             // the chosen file's display name, for the screen only
    val snoozeSeconds: Int = 30,                  // hard ceiling 600
    /**
     * How long the snooze bar must be held before it snoozes. 0 is immediate, 10 s is
     * the ceiling. The bar sits in the bottom cutout of the box and is the ONLY thing
     * reachable without a key, so this is the whole defence against a half-asleep palm.
     */
    val snoozeHoldSeconds: Int = 3,               // 0..10
    /**
     * How long into a snooze before it may be extended. **Equal to [snoozeSeconds]
     * disables extending** -- you would have to wait out the whole snooze, by which
     * point it is over. Normalized down whenever it exceeds the snooze length.
     */
    val resnoozeAfterSeconds: Int = 5,            // 0..snoozeSeconds
    /**
     * Block the ring when the sentinel phone is away. **Defaults OFF**, password-gated.
     *
     * Known residual, accepted by the owner: a phone in airplane mode or rebooting across the
     * alarm is indistinguishable from one that left the house, and the ring is blocked.
     */
    val blockRingIfSentinelAway: Boolean = false,
    /**
     * The same, for the controller. Separate because the controller may BE the phone that leaves --
     * not everyone wall-mounts it. OR'd with [blockRingIfSentinelAway]: either condition, enabled
     * and met, blocks.
     *
     * Riskier than the sentinel one, and off by default for it. The owner-facing statement of why
     * lives on the row that arms it, which is the only place it can change a decision.
     */
    val blockRingIfControllerAway: Boolean = false,
    /**
     * How long a phone must be unreachable before it counts as away. 0 to 24 h.
     *
     * **The effective minimum is two minutes**, whatever is set here, because that is this app's
     * definition of "present" and the sentinel only reports every 30 s — a shorter floor would read
     * a phone sitting in the owner's pocket as having left. Genuinely immediate detection is not
     * available from a polling design, and pretending otherwise would be the dangerous kind of
     * configurable.
     */
    val awayAfterSeconds: Int = 600,             // 0..86400, effective floor 120
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
    fun normalize(s: Settings): Settings {
        // Against the CLAMPED snooze, not the raw one: lowering the snooze below the
        // extend delay has to pull the delay down with it, in the same pass.
        val snooze = s.snoozeSeconds.coerceIn(5, Settings.SNOOZE_CEILING_S)
        return s.copy(
        alarmVolumePercent = s.alarmVolumePercent.coerceIn(Settings.VOLUME_FLOOR, 100),
        snoozeSeconds = snooze,
        snoozeHoldSeconds = s.snoozeHoldSeconds.coerceIn(0, 10),
        resnoozeAfterSeconds = s.resnoozeAfterSeconds.coerceIn(0, snooze),
        awayAfterSeconds = s.awayAfterSeconds.coerceIn(0, 24 * 3600),
        maxRingMinutes = s.maxRingMinutes.coerceIn(Settings.MAX_RING_FLOOR_M, 120),
        napMinutes = s.napMinutes.coerceIn(1, 720),
        )
    }

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

/**
 * The thing a user would call "tomorrow". Never a word or a relative notion: bound to a specific
 * occurrence and, when it moves one, resolved to an absolute instant at set time.
 *
 * Sealed, with the instant carried by the only variant that has one. A single `kind` plus a
 * nullable `fireAtMs` made TIME-without-an-instant constructible, so four places in [Engine] each
 * had to remember `kind == TIME && fireAtMs != null`, and the fire path dereferenced it with `!!`
 * guarded only by a check three lines above. Now the type says it and the compiler checks it.
 *
 * The WIRE still carries a string tag ("NONE"/"SKIP"/"TIME"): that is a serialization boundary,
 * validated once where a request enters.
 */
sealed interface Override {
    val boundOccurrenceId: OccurrenceId

    /** Moved: this occurrence is replaced by one at [fireAtMs]. */
    data class Time(override val boundOccurrenceId: OccurrenceId, val fireAtMs: Long) : Override

    /** Silenced: this occurrence is spent without a sound. */
    data class Skip(override val boundOccurrenceId: OccurrenceId) : Override
}

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

data class Gates(
    val evaluatedAtMs: Long,
    val scheduleExists: Boolean,
    val exactAlarm: Boolean,
    val foregroundService: Boolean,
    val p2pSupported: Boolean,
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
    val endsByMs: Long,
    val audible: String,
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
    /**
     * This phone's address on the ordinary network, learned by the other phone while the P2P link
     * is up and used as the fallback host once it is not. Null when it has no LAN address.
     */
    val lanIp: String? = null,
)
/**
 * The one-off change in force on the next alarm, for the screen.
 *
 * [replacesMs] is the scheduled alarm the override replaces -- **or would replace, when
 * [kind] is NONE**. That second case is what lets the Move picker show where a typed
 * time will land before anything is committed, using the same anchor the engine uses.
 */
data class TomorrowView(val kind: String, val timeMs: Long?, val replacesMs: Long?)
data class NapView(val armed: Boolean, val atMs: Long?)

data class Snapshot(
    val stateVersion: Long,
    val serverTimeMs: Long,
    val bootedAtMs: Long,
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
    /** The sentinel phone, if one has ever reported in. Null means never set up. */
    val sentinel: DeviceView? = null,
    /** Non-null when the next ring would be blocked right now, with the reason. */
    val ringBlock: String? = null,
    /**
     * What the away gate makes of each blockable role right now, armed or not, so the rows that
     * arm those blocks can state their reach. Filled by the alarm phone only; it is the only
     * phone that judges presence.
     */
    val away: List<AwayView> = emptyList(),
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
)

/** Everything needed to rebuild a phone. SPEC.md section 14. */
data class Backup(
    val schemaVersion: Int,
    val settings: Settings,
    val events: List<Event>,
    val exportedAtMs: Long,
)
