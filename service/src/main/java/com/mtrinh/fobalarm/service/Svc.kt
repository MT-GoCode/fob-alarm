package com.mtrinh.fobalarm.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.data.AlarmHost
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The ALARM role's single owner of truth. Both LocalStateClient and the HTTP server
 * call these same methods, so the local path is not a privileged shortcut and a bug
 * appears identically on both phones. SPEC.md section 5.
 */
object Svc : AlarmHost {

    lateinit var app: Context; private set
    lateinit var de: DeMirror; private set
    private lateinit var db: Db
    private val io = Executors.newSingleThreadExecutor()
    /** Never share the ring path's queue with disk IO or a 1.5s group restart. */
    private val urgent = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile private var state = EngineState()
    @Volatile var lastNextFire: NextFire? = null; private set
    @Volatile var armGate: ArmGate? = null
    @Volatile var bootedAtMs: Long = 0
    @Volatile var appVersion: String = "?"
    @Volatile var variant: Variant = Variant.LIVE
    @Volatile var unlockToken: String? = null
    /** Last device report from the controller. Null until it has ever been heard from. */
    @Volatile var peerDevice: DeviceView? = null
    @Volatile private var unlockedAtMs: Long = 0
    private val seenRequests = HashMap<String, Long>()

    val ts = object : TimeSource {
        override fun nowMs() = System.currentTimeMillis()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }

    val settings: Settings get() = state.settings
    val session: RingSession? get() = state.session

    fun init(ctx: Context, version: String, variant: Variant) {
        if (this::app.isInitialized) return
        app = ctx.applicationContext
        appVersion = version
        this.variant = variant
        bootedAtMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        de = DeMirror(app)
        if (de.deviceId == null) de.deviceId = UUID.randomUUID().toString()

        // Seed from the DE mirror FIRST. Room is credential-encrypted, may be locked,
        // and reading it here would be disk IO on whatever thread woke the process --
        // including a broadcast receiver. The mirror is exactly the subset the ring path
        // needs, so the alarm is armed correctly before Room is even opened.
        state = state.copy(settings = state.settings.copy(
            defaultAlarmTime = de.defaultAlarmTime,
            alarmVolumePercent = de.alarmVolumePercent,
            maxRingMinutes = de.maxRingMinutes,
            snoozeSeconds = de.snoozeSeconds,
            snoozeThresholdDegrees = de.snoozeThresholdDegrees,
            ringtoneUri = de.ringtoneUri,
            role = de.role?.let { r -> runCatching { Role.valueOf(r) }.getOrNull() },
        ), lastAliveMs = de.lastAliveMs)
        de.session()?.let { state = state.copy(session = it) }
        recompute("init:de")

        // Then bring up Room off-thread and recompute again once latches, the override
        // and the nap are known. Room is expendable; the schedule is not.
        io.execute {
            var attempt = 0
            while (attempt < 5 && !dbReady) {
                attempt++
                runCatching { db = Db.open(app); Persist.load(db.dao()) }
                    .onFailure {
                        log("db_open_retry", "attempt" to attempt.toString(), "error" to it.toString())
                        Thread.sleep(500L * attempt)
                    }
            }
            runCatching {
                val loaded = Persist.load(db.dao())
                synchronized(lock) {
                    // Keep the live session and the newer liveness threshold.
                    state = loaded.copy(
                        session = state.session,
                        lastAliveMs = maxOf(loaded.lastAliveMs, state.lastAliveMs))
                }
                recompute("init:db")
            }.onFailure { log("db_open_failed", "error" to it.toString()) }
        }
    }

    val dbReady: Boolean get() = this::db.isInitialized

    // -----------------------------------------------------------------------
    // Logging -> Room, with the recent tail also kept in memory so a dead DB
    // never takes the log with it.
    // -----------------------------------------------------------------------

    private val memTail = ArrayDeque<Event>()
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)

    fun log(type: String, vararg kv: Pair<String, String>) {
        val e = Event(seqCounter.incrementAndGet(), System.currentTimeMillis(), type,
            Actor.ALARM, state.stateVersion, kv.toMap())
        synchronized(memTail) {
            memTail.addLast(e); while (memTail.size > 200) memTail.removeFirst()
        }
        if (!dbReady) return
        io.execute {
            runCatching {
                db.dao().insertBlocking(EventRow(0, e.atMs, e.type, e.actor.name, e.stateVersion,
                    JSONObject(e.detail as Map<*, *>).toString()))
            }
        }
    }

    fun logEvents(events: List<PendingEvent>) = events.forEach { log(it.type, *it.detail.toList().toTypedArray()) }

    fun recentEvents(n: Int): List<Event> = synchronized(memTail) { memTail.takeLast(n) }

    // -----------------------------------------------------------------------
    // The chokepoint wrapper: apply an engine result, persist, mirror, re-arm.
    // -----------------------------------------------------------------------

    private fun apply(r: RecomputeResult, alsoFire: Boolean = true): Snapshot = synchronized(lock) {
        state = r.state
        lastNextFire = r.nextFire
        logEvents(r.events)
        de.mirror(state.settings, r.nextFire, state.session)
        de.lastAliveMs = state.lastAliveMs
        if (dbReady) io.execute { runCatching { Persist.save(db.dao(), state) } }
        // ALARM role only. The controller must never compute or arm a schedule:
        // a second scheduler on the device with no clock sync would ring in the
        // wrong room and chirp about gates it cannot pass.
        if (state.settings.role == Role.ALARM) Scheduler.arm(app, r.nextFire)

        if (alsoFire && r.fireNow != null && state.session == null) {
            log("fire_now_after_clock_jump", "source" to r.fireNow!!.name)
            urgent.execute { RingService.start(app) }
        }
        if (r.chirpMissed) {
            log("missed_chirp")
            urgent.execute { Audio(app).chirp() }
        }
        snapshot()
    }

    fun recompute(reason: String): Snapshot =
        synchronized(lock) { apply(Engine.recompute(state, ts, reason)) }

    // -----------------------------------------------------------------------
    // Trigger entry points (called by the receiver / ring service)
    // -----------------------------------------------------------------------

    fun onTrigger(source: OccurrenceSource): Snapshot = synchronized(lock) {
        apply(Engine.onTrigger(state, ts, source, "r-" + UUID.randomUUID().toString().take(8)),
            alsoFire = false)
    }

    fun onSnooze(): Snapshot = synchronized(lock) { apply(Engine.snooze(state, ts), alsoFire = false) }

    fun endSession(outcome: Outcome): Snapshot =
        synchronized(lock) { apply(Engine.endSession(state, ts, outcome), alsoFire = false) }

    // -----------------------------------------------------------------------
    // AlarmHost -- the API surface shared by local and remote callers
    // -----------------------------------------------------------------------

    override fun snapshot(): Snapshot = synchronized(lock) { buildSnapshot() }

    private fun buildSnapshot(): Snapshot {
        val gates = GateEval.current(app, state.settings, lastNextFire != null)
        val s = state.session
        val ovFire = state.override?.fireAtMs
        val boundAt = state.override?.let {
            runCatching {
                Engine.scheduledInstant(java.time.LocalDate.parse(it.boundOccurrenceId.localDate),
                    state.settings.defaultAlarmTime, ts.zone())
            }.getOrNull()
        }
        return Snapshot(
            stateVersion = state.stateVersion,
            serverTimeMs = System.currentTimeMillis(),
            bootedAtMs = bootedAtMs,
            mode = Snapshot.deriveMode(s != null, gates.allPass),
            gates = gates,
            armGate = armGate,
            nextFire = lastNextFire,
            ring = s?.let {
                RingView(it.ringId, it.startedAtMs, it.trigger, it.phase, it.snoozeCount,
                    it.snoozeUntilMs, it.endsByMs,
                    RingService.rotationDeg, state.settings.snoozeThresholdDegrees,
                    RingService.gyroBiasDps, RingService.gyroStale, RingService.rvStale,
                    RingService.audible)
            },
            clock = ClockView(ClockObserver.lastAttemptMs, ClockObserver.lastOkMs,
                ClockObserver.offsetMs, ClockObserver.source,
                ClockObserver.staleByMs().coerceAtMost(Long.MAX_VALUE / 2)),
            ap = ApView(state.settings.ssid, Group.running, Group.clientCount,
                Group.lastStartedAtMs, Group.lastError),
            self = selfDevice(),
            peer = peerDevice,
            lastOutcome = state.lastOutcome,
            appVersion = appVersion,
            settingsSchemaVersion = Persist.SCHEMA_VERSION,
            tomorrow = TomorrowView(
                state.override?.kind?.name ?: "NONE", ovFire, boundAt),
            nap = NapView(state.nap != null, state.nap?.fireAtMs),
            settings = state.settings,
            lastEvents = recentEvents(10),
        )
    }

    @Volatile private var selfCache: DeviceView? = null
    @Volatile private var selfCacheAtMs = 0L

    fun selfDevice(): DeviceView {
        selfCache?.let { if (System.currentTimeMillis() - selfCacheAtMs < 10_000) return it }
        val bm = app.getSystemService(BatteryManager::class.java)
        val batt = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return DeviceView(
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            appVersion = appVersion, variant = variant,
            role = state.settings.role, deviceId = de.deviceId ?: "?",
            lastSeenMs = System.currentTimeMillis()).also {
            selfCache = it; selfCacheAtMs = System.currentTimeMillis()
        }
    }

    /** Idempotent by content: a replayed request returns the current snapshot, not a second action. */
    private fun seen(requestId: String): Boolean = synchronized(seenRequests) {
        val now = System.currentTimeMillis()
        seenRequests.entries.removeAll { now - it.value > 10 * 60_000 }
        if (seenRequests.containsKey(requestId)) true
        else { seenRequests[requestId] = now; false }
    }

    override fun dismiss(ringId: String, requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        // The ringId check and the end MUST be one transaction: a supersede landing
        // between them would let a dismiss aimed at the finished nap ring end the real
        // 04:00 session instead.
        val snap = synchronized(lock) {
            val s = state.session ?: throw StaleRingException(snapshot())
            if (s.ringId != ringId) {
                log("stale_dismiss_rejected", "got" to ringId, "have" to s.ringId)
                throw StaleRingException(snapshot())
            }
            apply(Engine.endSession(state, ts,
                if (actor == Actor.CONTROLLER) Outcome.DISMISSED_REMOTE else Outcome.DISMISSED_LOCAL),
                alsoFire = false)
        }
        runCatching { RingService.stop(app) }
        return snap
    }

    private fun requireUnlocked(patch: Settings, token: String?) {
        val changesGated = listOf(
            patch.ringtoneUri != state.settings.ringtoneUri,
            patch.snoozeSeconds != state.settings.snoozeSeconds,
            patch.defaultAlarmTime != state.settings.defaultAlarmTime,
            patch.armGateTime != state.settings.armGateTime,
            patch.maxRingMinutes != state.settings.maxRingMinutes,
            patch.alarmVolumePercent != state.settings.alarmVolumePercent,
            patch.ssid != state.settings.ssid,
            patch.passphrase != state.settings.passphrase,
            patch.role != state.settings.role,
        ).any { it }
        if (!changesGated) return
        if (Auth.gateOpen(state.settings)) return
        val fresh = unlockToken != null && token == unlockToken &&
                System.currentTimeMillis() - unlockedAtMs < 120_000
        if (!fresh) throw ForbiddenException()
    }

    override fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        if (ifVersion >= 0 && ifVersion != state.stateVersion) throw ConflictException(snapshot())
        requireUnlocked(patch, token)
        // Role change is rejected outright while a ring session is open.
        if (patch.role != state.settings.role && state.session != null) throw ConflictException(snapshot())
        val invalid = SettingsValidator.validate(patch)
        if (invalid.isNotEmpty()) throw IllegalArgumentException(invalid.joinToString { it.reason })
        val ssidChanged = patch.ssid != state.settings.ssid || patch.passphrase != state.settings.passphrase
        val snap = synchronized(lock) { apply(Engine.patchSettings(state, ts, patch, actor)) }
        if (ssidChanged) Executors.newSingleThreadExecutor().execute { Group.restart(app, state.settings) }
        return snap
    }

    override fun nap(minutes: Int, requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) { apply(Engine.setNap(state, ts, minutes)) }
    }

    override fun clearNap(requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) { apply(Engine.clearNap(state, ts)) }
    }

    override fun setOverride(kind: String, time: String?, shiftMinutes: Int?, requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) {
            when {
                kind == "SKIP" -> apply(Engine.setSkip(state, ts))
                kind == "NONE" -> apply(Engine.clearOverride(state, ts))
                shiftMinutes != null -> apply(Engine.shiftOverride(state, ts, shiftMinutes))
                time != null -> apply(Engine.setOverrideTime(state, ts, time))
                else -> snapshot()
            }
        }
    }

    override fun clearOverride(requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return apply(Engine.clearOverride(state, ts))
    }

    override fun history(sinceSeq: Long, limit: Int): List<Event> {
        if (!dbReady) return recentEvents(limit)
        return runCatching {
            db.dao().since(sinceSeq, limit).map {
                val d = JSONObject(it.detail)
                val m = mutableMapOf<String, String>()
                d.keys().forEach { k -> m[k] = d.optString(k) }
                Event(it.seq, it.atMs, it.type, Actor.valueOf(it.actor), it.stateVersion, m)
            }
        }.getOrDefault(recentEvents(limit))
    }

    override fun export(): Backup = Backup(
        schemaVersion = Persist.SCHEMA_VERSION,
        settings = state.settings,
        events = if (dbReady) runCatching {
            db.dao().all().map {
                val d = JSONObject(it.detail)
                val m = mutableMapOf<String, String>()
                d.keys().forEach { k -> m[k] = d.optString(k) }
                Event(it.seq, it.atMs, it.type, Actor.valueOf(it.actor), it.stateVersion, m)
            }
        }.getOrDefault(emptyList()) else emptyList(),
        exportedAtMs = System.currentTimeMillis())

    override fun import(backup: Backup, token: String?): Snapshot {
        if (!Auth.gateOpen(state.settings)) {
            val fresh = unlockToken != null && token == unlockToken &&
                    System.currentTimeMillis() - unlockedAtMs < 120_000
            if (!fresh) throw ForbiddenException()
        }
        val candidate = SettingsValidator.normalize(backup.settings)
        val invalid = SettingsValidator.validate(candidate)
        if (invalid.isNotEmpty()) throw IllegalArgumentException(invalid.joinToString { it.reason })
        synchronized(lock) { state = state.copy(settings = candidate) }
        if (dbReady) io.execute {
            runCatching {
                db.dao().clearEvents()
                backup.events.forEach {
                    db.dao().insertBlocking(EventRow(0, it.atMs, it.type, it.actor.name,
                        it.stateVersion, JSONObject(it.detail as Map<*, *>).toString()))
                }
            }
        }
        log("import", "events" to backup.events.size.toString())
        return recompute("import")
    }

    override fun unlock(secret: String): String {
        if (!Auth.accepts(state.settings, secret)) throw ForbiddenException()
        val t = UUID.randomUUID().toString()
        unlockToken = t
        unlockedAtMs = System.currentTimeMillis()
        return t
    }

    fun setPassword(password: String?): Pair<Snapshot, String?> {
        if (password == null) {
            state = state.copy(settings = state.settings.copy(
                passwordHash = null, passwordSalt = null, recoveryHash = null, recoverySalt = null))
            return recompute("password_cleared") to null
        }
        val ps = Auth.newSalt()
        val rs = Auth.newSalt()
        val code = Auth.newRecoveryCode()
        state = state.copy(settings = state.settings.copy(
            passwordHash = Auth.hash(password, ps), passwordSalt = ps,
            recoveryHash = Auth.hash(code, rs), recoverySalt = rs))
        log("password_set")
        return recompute("password_set") to code
    }

    fun setRole(role: Role): Snapshot {
        state = state.copy(settings = state.settings.copy(role = role))
        de.role = role.name
        log("role_changed", "to" to role.name)
        return recompute("role_changed")
    }

    /**
     * Test ring. Deliberately NOT an occurrence: no latch, no schedule change, no
     * override, no nap. It cannot consume tomorrow's alarm. SPEC.md section 8.
     */
    @Volatile var testSilent = false; private set
    /** Non-zero while a test ring is live. A test is NOT an engine session. */
    @Volatile var testUntilMs = 0L
    val testActive: Boolean get() = System.currentTimeMillis() < testUntilMs

    override fun testRing(silent: Boolean, requestId: String): Snapshot {
        if (seen(requestId)) return snapshot()
        testSilent = silent
        de.pendingTest = true
        log("test_ring", "silent" to silent.toString())
        // 10s so the phone can be locked and put down first -- testing from a
        // foregrounded app proves nothing about 04:00.
        Scheduler.armTestFire(app, 10)
        return snapshot()
    }

    fun pruneHistory() {
        if (!dbReady) return
        io.execute { runCatching { db.dao().prune(System.currentTimeMillis() - 90L * 86400_000) } }
    }
}

// Errors are ClientError (from :data) so the local and remote paths carry the SAME
// vocabulary. Anything else and the three-outcome table in section 7 is only
// implemented on one of the two paths.
typealias StaleRingException = com.mtrinh.fobalarm.data.ClientError.StaleRing
typealias ConflictException = com.mtrinh.fobalarm.data.ClientError.Conflict
typealias ForbiddenException = com.mtrinh.fobalarm.data.ClientError.Forbidden

/** All alarms are setAlarmClock: exempt from standby quotas and Doze. */
object Scheduler {
    const val ACTION_FIRE = "com.mtrinh.fobalarm.FIRE"
    const val ACTION_WATCHDOG = "com.mtrinh.fobalarm.WATCHDOG"
    const val ACTION_TICK = "com.mtrinh.fobalarm.TICK"
    const val ACTION_ARMGATE = "com.mtrinh.fobalarm.ARMGATE"

    fun pi(ctx: Context, action: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, rc, Intent(ctx, AlarmReceiver::class.java).setAction(action).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun set(ctx: Context, action: String, rc: Int, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, action, rc)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, p), p) }
            .onFailure { Svc.log("alarm_set_failed", "action" to action, "error" to it.toString()) }
    }

    fun arm(ctx: Context, next: NextFire?) {
        if (next != null) set(ctx, ACTION_FIRE, 1001, next.atMs)
        armHourlyTick(ctx)
        armGateAlarm(ctx)
    }

    fun armWatchdog(ctx: Context) = set(ctx, ACTION_WATCHDOG, 1002, System.currentTimeMillis() + 60_000)

    /** Separate request code from the scheduled fire, so a retry cannot clobber it. */
    fun armFireRetry(ctx: Context) = set(ctx, ACTION_FIRE, 1005, System.currentTimeMillis() + 5_000)

    /** Also its own request code: a test must never overwrite the real alarm. */
    fun armTestFire(ctx: Context, seconds: Int) =
        set(ctx, ACTION_FIRE, 1006, System.currentTimeMillis() + seconds * 1000L)
    fun cancelWatchdog(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_WATCHDOG, 1002))

    fun armHourlyTick(ctx: Context) = set(ctx, ACTION_TICK, 1003, System.currentTimeMillis() + 3600_000)

    fun armGateAlarm(ctx: Context) {
        val at = Engine.run {
            val zone = Svc.ts.zone()
            val now = Svc.ts.nowMs()
            var d = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            var t = scheduledInstant(d, Svc.settings.armGateTime, zone)
            if (t <= now) t = scheduledInstant(d.plusDays(1), Svc.settings.armGateTime, zone)
            t
        }
        set(ctx, ACTION_ARMGATE, 1004, at)
    }
}
