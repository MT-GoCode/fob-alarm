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

    /**
     * Deliberate: the gate exists to slow down a half-asleep user, not to keep a secret
     * from an attacker, and a password the owner does not know would defeat its purpose.
     * Exposure is limited to the Wi-Fi Direct group, whose WPA2 passphrase the user sets.
     * Settings shows a warning while this is still in use.
     */
    const val DEFAULT_PASSWORD = "12345678"

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
    @Volatile private var unlockTokenValue: String? = null
    /** Self-expiring: a stale token must read as locked, not as enabled-but-failing. */
    val unlockToken: String?
        get() = unlockTokenValue?.takeIf { System.currentTimeMillis() - unlockedAtMs < 120_000 }
    /** Last device report from the controller. Null until it has ever been heard from. */
    @Volatile var peerDevice: DeviceView? = null
    @Volatile var peerBlockers: List<String> = emptyList()
    @Volatile private var unlockedAtMs: Long = 0
    private val seenRequests = HashMap<String, Long>()

    val ts = object : TimeSource {
        override fun nowMs() = System.currentTimeMillis()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }

    val settings: Settings get() = state.settings
    val session: RingSession? get() = state.session

    fun init(ctx: Context, version: String) {
        if (this::app.isInitialized) return
        app = ctx.applicationContext
        appVersion = version
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
            ssid = de.ssid,
            passphrase = de.passphrase,
            passwordHash = de.passwordHash,
            passwordSalt = de.passwordSalt,
            vibrate = de.vibrate,
        ), lastAliveMs = de.lastAliveMs)
        de.session()?.let { state = state.copy(session = it) }

        recompute("init:de")

        // Gated from the very first run only. Seeding happens off the fire path -- it is
        // 20,000 SHA-256 rounds and this method runs on whatever thread woke the process,
        // including AlarmReceiver at 04:00.
        if (!de.passwordSeeded) {
            io.execute {
                val salt = Auth.newSalt()
                synchronized(lock) {
                    state = state.copy(settings = state.settings.copy(
                        passwordHash = Auth.hash(DEFAULT_PASSWORD, salt), passwordSalt = salt))
                }
                log("default_password_set")
                recompute("password_seeded")     // mirrors the hash to DE
                de.passwordSeeded = true         // only then record that we seeded
            }
        }

        // Then bring up Room off-thread and recompute again once latches, the override
        // and the nap are known. Room is expendable; the schedule is not.
        io.execute { tryLoadRoom("init") }
    }

    @Volatile private var roomLoaded = false
    val dbReady: Boolean get() = roomLoaded

    /**
     * Opened lazily and only marked ready once a real read SUCCEEDS. Retried on
     * ACTION_USER_UNLOCKED, because before first unlock this always fails.
     */
    fun tryLoadRoom(reason: String) {
        if (roomLoaded) return
        runCatching {
            val d = Db.open(app)
            val loaded = Persist.load(d.dao())          // the read is the real test
            db = d
            roomLoaded = true
            synchronized(lock) {
                // MERGE, never replace. An empty or wiped kv table returns a default
                // EngineState whose role is null -- assigning it wholesale would throw
                // away the DE-seeded role and silently un-arm the alarm forever.
                val hasRoomSettings = loaded.settings.role != null || loaded.latches.isNotEmpty()
                state = state.copy(
                    settings = if (hasRoomSettings) loaded.settings.copy(
                        role = loaded.settings.role ?: state.settings.role,
                        ssid = loaded.settings.ssid ?: state.settings.ssid,
                        passphrase = loaded.settings.passphrase ?: state.settings.passphrase,
                        passwordHash = loaded.settings.passwordHash ?: state.settings.passwordHash,
                        passwordSalt = loaded.settings.passwordSalt ?: state.settings.passwordSalt,
                    ) else state.settings,
                    latches = loaded.latches,
                    override = loaded.override,
                    nap = loaded.nap,
                    lastOutcome = loaded.lastOutcome ?: state.lastOutcome,
                    stateVersion = maxOf(loaded.stateVersion, state.stateVersion),
                    lastAliveMs = maxOf(loaded.lastAliveMs, state.lastAliveMs),
                )
            }
            log("db_loaded", "reason" to reason)
            recompute("db_loaded")
        }.onFailure {
            log("db_unavailable", "reason" to reason, "error" to it.javaClass.simpleName)
        }
    }

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

        if (alsoFire && r.fireNow != null && state.session == null &&
            state.settings.role == Role.ALARM) {
            log("fire_now_after_clock_jump", "source" to r.fireNow!!.name)
            urgent.execute { RingService.start(app) }
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

    /**
     * Last resort when the engine refuses to open a session but an alarm is genuinely
     * due. A REAL session, so the ring screen, the dismiss button, the watchdog and the
     * DE mirror all work exactly as they normally do.
     */
    fun forceSession(source: OccurrenceSource): Snapshot = synchronized(lock) {
        val now = ts.nowMs()
        val forced = RingSession(
            ringId = "r-forced-" + UUID.randomUUID().toString().take(6),
            occurrenceId = OccurrenceId(Engine.localDateOf(now, ts.zone()), source),
            startedAtMs = now, trigger = source, phase = RingPhase.RINGING,
            snoozeCount = 0, snoozeUntilMs = null,
            endsByMs = now + state.settings.maxRingMinutes * 60_000L)
        state = state.copy(session = forced)
        de.mirror(state.settings, lastNextFire, forced)
        log("forced_session", "ringId" to forced.ringId)
        buildSnapshot()
    }

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
                    RingService.audible, RingService.quaternion)
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
            settings = state.settings.copy(
                usingDefaultPassword = Auth.isDefault(state.settings, DEFAULT_PASSWORD)),
            lastEvents = recentEvents(10),
            // Stale peer reports must not linger after the peer goes away.
            peerBlockers = if (peerDevice?.lastSeenMs?.let {
                    System.currentTimeMillis() - it < 120_000 } == true) peerBlockers else emptyList(),
            lastHeartbeatMs = peerDevice?.lastSeenMs ?: 0,
            testUntilMs = testUntilMs,
            problems = runCatching { Health.problems() }.getOrDefault(emptyList()),
        )
    }

    @Volatile private var selfCache: DeviceView? = null
    @Volatile private var selfCacheAtMs = 0L

    fun selfDevice(): DeviceView {
        val c = selfCache
        if (c != null) {
            // Refresh off-thread; never block the lock on a binder call.
            if (System.currentTimeMillis() - selfCacheAtMs > 10_000) io.execute { readSelf() }
            return c
        }
        return readSelf()
    }

    private fun readSelf(): DeviceView {
        val bm = app.getSystemService(BatteryManager::class.java)
        val batt = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return DeviceView(
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            appVersion = appVersion,
            role = state.settings.role, deviceId = de.deviceId ?: "?",
            lastSeenMs = System.currentTimeMillis()).also {
            selfCache = it; selfCacheAtMs = System.currentTimeMillis()
        }
    }

    /** True if this request already SUCCEEDED once. Replays return the current snapshot. */
    private fun seen(requestId: String): Boolean = synchronized(seenRequests) {
        val now = System.currentTimeMillis()
        seenRequests.entries.removeAll { now - it.value > 10 * 60_000 }
        seenRequests.containsKey(requestId)
    }

    /** Recorded only once the mutation has actually happened. */
    private fun commit(requestId: String) = synchronized(seenRequests) {
        seenRequests[requestId] = System.currentTimeMillis()
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
            commit(requestId)        // only once the dismiss is going to happen
            apply(Engine.endSession(state, ts,
                if (actor == Actor.CONTROLLER) Outcome.DISMISSED_REMOTE else Outcome.DISMISSED_LOCAL),
                alsoFire = false)
        }
        runCatching { RingService.stop(app) }
        return snap
    }

    private fun requireUnlocked(patch: Settings, token: String?) {
        // The gate protects the ALARM phone. On the controller this Svc only holds local
        // config; the real check happens when the controller POSTs to the alarm phone.
        if (state.settings.role == Role.CONTROLLER) return
        val changesGated = listOf(
            patch.ringtoneUri != state.settings.ringtoneUri,
            patch.snoozeSeconds != state.settings.snoozeSeconds,
            patch.defaultAlarmTime != state.settings.defaultAlarmTime,
            patch.vibrate != state.settings.vibrate,
            patch.maxRingMinutes != state.settings.maxRingMinutes,
            patch.alarmVolumePercent != state.settings.alarmVolumePercent,
            patch.ssid != state.settings.ssid,
            patch.passphrase != state.settings.passphrase,
            patch.role != state.settings.role,
        ).any { it }
        if (!changesGated) return
        if (Auth.gateOpen(state.settings)) return
        if (unlockToken == null || token != unlockToken) throw ForbiddenException()
    }

    override fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?, actor: Actor): Snapshot {
        // Check and apply in ONE transaction: separately, two patches with the same
        // ifVersion both passed the check and both applied, last writer winning silently.
        val ssidChanged: Boolean
        val snap = synchronized(lock) {
            if (seen(requestId)) return snapshot()
            if (ifVersion >= 0 && ifVersion != state.stateVersion) throw ConflictException(snapshot())
            requireUnlocked(patch, token)
            if (patch.role != state.settings.role && state.session != null) {
                throw ConflictException(snapshot())
            }
            val invalid = SettingsValidator.validate(patch)
            if (invalid.isNotEmpty()) throw IllegalArgumentException(invalid.joinToString { it.reason })
            ssidChanged = patch.ssid != state.settings.ssid ||
                    patch.passphrase != state.settings.passphrase
            commit(requestId)
            apply(Engine.patchSettings(state, ts, patch, actor))
        }
        // D19: reuse io rather than leaking a fresh executor per credential change.
        if (ssidChanged) io.execute { Group.restart(app, state.settings) }
        return snap
    }

    override fun nap(minutes: Int, requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) { commit(requestId); apply(Engine.setNap(state, ts, minutes)) }
    }

    override fun clearNap(requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) { commit(requestId); apply(Engine.clearNap(state, ts)) }
    }

    override fun setOverride(kind: String, time: String?, requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) {
            commit(requestId)
            when {
                kind == "SKIP" -> apply(Engine.setSkip(state, ts))
                kind == "NONE" -> apply(Engine.clearOverride(state, ts))
                time != null -> apply(Engine.setOverrideTime(state, ts, time))
                else -> snapshot()
            }
        }
    }

    override fun clearOverride(requestId: String, actor: Actor): Snapshot {
        if (seen(requestId)) return snapshot()
        return synchronized(lock) { commit(requestId); apply(Engine.clearOverride(state, ts)) }
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

    override fun unlock(secret: String): String {
        if (!Auth.accepts(state.settings, secret)) throw ForbiddenException()
        val t = UUID.randomUUID().toString()
        unlockTokenValue = t
        unlockedAtMs = System.currentTimeMillis()
        return t
    }

    /**
     * Changing or removing the password requires the CURRENT password (or the recovery
     * code). Otherwise the gate protects every setting except itself, and "Remove
     * password" is a two-tap bypass of the whole mechanism.
     */
    /** Changing or removing the password requires the current one. No recovery code. */
    fun setPassword(password: String?, current: String?): Snapshot {
        if (!Auth.gateOpen(state.settings) && !Auth.accepts(state.settings, current ?: "")) {
            log("password_change_rejected")
            throw ForbiddenException()
        }
        synchronized(lock) {
            state = if (password == null) {
                state.copy(settings = state.settings.copy(passwordHash = null, passwordSalt = null))
            } else {
                val salt = Auth.newSalt()
                state.copy(settings = state.settings.copy(
                    passwordHash = Auth.hash(password, salt), passwordSalt = salt))
            }
        }
        log(if (password == null) "password_removed" else "password_set")
        return recompute("password")
    }

    fun setRole(role: Role): Snapshot {
        synchronized(lock) { state = state.copy(settings = state.settings.copy(role = role)) }
        de.role = role.name
        log("role_changed", "to" to role.name)
        LinkService.start(app)
        return recompute("role_changed")
    }

    /**
     * Test ring. Deliberately NOT an occurrence: no latch, no schedule change, no
     * override, no nap. It cannot consume tomorrow's alarm. SPEC.md section 8.
     */
    /** Non-zero while a test ring is live. A test is NOT an engine session. */
    @Volatile var testUntilMs = 0L
    val testActive: Boolean get() = System.currentTimeMillis() < testUntilMs

    override fun testRing(silent: Boolean, requestId: String): Snapshot =
        testRing(silent, requestId, fromController = false)

    fun testRing(silent: Boolean, requestId: String, fromController: Boolean): Snapshot {
        if (seen(requestId)) return snapshot()
        // Never while a real alarm is live: the test branch would be skipped and the
        // flag left armed for the next genuine fire.
        if (state.session != null || testActive || RingService.serviceAlive) {
            throw IllegalStateException("already ringing")
        }
        commit(requestId)
        de.testSilent = silent
        // Short window: long enough to reach the fire, far too short to survive to 04:00.
        de.pendingTestUntilMs = System.currentTimeMillis() + 30_000
        log("test_ring", "silent" to silent.toString())
        // Local: start now. Remote: the app is backgrounded with no FGS allowlist, so
        // go through a test-specific alarm rather than a generic retry that could
        // become a real ring and latch an occurrence.
        if (fromController) Scheduler.armTestFire(app, 1)
        else urgent.execute { RingService.start(app) }
        return snapshot()
    }

    fun stopTest() {
        testUntilMs = 0L
        de.pendingTestUntilMs = 0L
        RingService.stop(app)
        log("test_ring_stopped")
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
    const val ACTION_DISMISS = "com.mtrinh.fobalarm.DISMISS"
    const val ACTION_TEST = "com.mtrinh.fobalarm.TEST"

    fun pi(ctx: Context, action: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, rc, Intent(ctx, AlarmReceiver::class.java).setAction(action).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** True only if the real scheduled fire was accepted by AlarmManager. */
    @Volatile var fireArmed = false

    private fun set(ctx: Context, action: String, rc: Int, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, action, rc)
        val ok = runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, p), p) }
            .onFailure { Svc.log("alarm_set_failed", "action" to action, "error" to it.toString()) }
            .isSuccess
        if (action == ACTION_FIRE && rc == 1001) fireArmed = ok
    }

    fun arm(ctx: Context, next: NextFire?) {
        if (next != null) set(ctx, ACTION_FIRE, 1001, next.atMs) else fireArmed = false
        armHourlyTick(ctx)
        armGateAlarm(ctx)
    }

    fun armWatchdog(ctx: Context) = set(ctx, ACTION_WATCHDOG, 1002, System.currentTimeMillis() + 60_000)

    /** Separate request code from the scheduled fire, so a retry cannot clobber it. */
    fun armFireRetry(ctx: Context) = set(ctx, ACTION_FIRE, 1005, System.currentTimeMillis() + 5_000)

    /** Its own ACTION and request code: a test can neither overwrite nor become a real alarm. */
    fun armTestFire(ctx: Context, seconds: Int) =
        set(ctx, ACTION_TEST, 1006, System.currentTimeMillis() + seconds * 1000L)
    fun cancelWatchdog(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_WATCHDOG, 1002))

    fun armHourlyTick(ctx: Context) = set(ctx, ACTION_TICK, 1003, System.currentTimeMillis() + 3600_000)

    /** Hourly health check. Not a chirp, not a schedule: it only refreshes status. */
    fun armGateAlarm(ctx: Context) =
        set(ctx, ACTION_ARMGATE, 1004, System.currentTimeMillis() + 3600_000)
}
