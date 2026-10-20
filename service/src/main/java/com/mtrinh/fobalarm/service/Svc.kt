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
    @Volatile private var unlockTokenValue: String? = null
    /**
     * Unlocked until it is locked again, by the Lock button or by the process dying.
     * No timer. An unlock that lapses on a clock leaves the screen saying "Unlocked" while
     * the next save returns 403. Living only in memory is the one implicit re-lock that
     * remains: an app restart is locked.
     */
    val unlockToken: String? get() = unlockTokenValue
    /**
     * What a peer last told us about itself, per ROLE. One slot per role is how "one controller and
     * one sentinel" is enforced: a claim is refused with 409 while the holder is still present, and
     * the slot frees once it has been silent for `RingGate.PRESENT_WINDOW_MS`, so a reinstall --
     * which mints a new deviceId -- can always take its own role back.
     *
     * In memory ONLY, deliberately. Mirroring it would turn every reboot and every process death
     * into a suppression: a cold start at 04:00 would read "last seen 03:00, healthy, an hour
     * absent" and block, though the phone was dead for that hour. Un-mirrored, a restart reads
     * "never seen", which rings.
     */
    @Volatile var controllerPresence: PeerReport? = null
    @Volatile var sentinelPresence: PeerReport? = null

    /** Exhaustive: ALARM has no slot, and silently folding it into the controller's was wrong. */
    fun presenceOf(role: Role): PeerReport? = when (role) {
        Role.CONTROLLER -> controllerPresence
        Role.SENTINEL -> sentinelPresence
        Role.ALARM -> null
    }

    fun roleAvailableTo(role: Role, deviceId: String): Boolean =
        RingGate.roleAvailable(presenceOf(role), deviceId, SystemClock.elapsedRealtime())

    fun notePresence(role: Role, p: PeerReport) {
        val prev = presenceOf(role)
        // The gap is the measurement the absence floor has to be set from. Logged rather than
        // guessed, because "two minutes" was invented and nothing in this app recorded peer gaps.
        if (prev != null) {
            val gap = (p.lastSeenElapsed - prev.lastSeenElapsed) / 1000
            if (gap >= 60) log("peer_gap", "role" to role.name, "seconds" to gap.toString())
        }
        when (role) {
            Role.CONTROLLER -> controllerPresence = p
            Role.SENTINEL -> sentinelPresence = p
            Role.ALARM -> error("the alarm phone does not report presence to itself")
        }
    }

    /** Back-compat: the snapshot's single `peer` field has always meant the controller. */
    val peerDevice: DeviceView? get() = controllerPresence?.let { toDeviceView(it, Role.CONTROLLER) }
    val peerBlockers: List<String> get() = controllerPresence?.missingGates ?: emptyList()

    fun toDeviceView(p: PeerReport, role: Role) = DeviceView(
        batteryPct = p.batteryPct, plugged = p.plugged, appVersion = p.appVersion,
        role = role, deviceId = p.deviceId,
        // The screen wants a wall-clock instant; convert back at read time.
        lastSeenMs = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - p.lastSeenElapsed))

    /**
     * The live inputs the pure gate needs, gathered in ONE place. Read once per decision, never
     * cached: the ring decision and the rows that explain it must not be able to read a different
     * clock, link or peer slot from each other.
     *
     * **Pure** -- it is read by every snapshot, so it must not log. [ringBlockDecision] is the one
     * place that records an answer.
     */
    private fun views(): List<AwayView> = RingGate.views(
        settings, SystemClock.elapsedRealtime(), Group.listenerUpSinceElapsed, ::presenceOf)

    private fun ringVerdict(): RingVerdict = RingGate.fold(settings, views())

    /**
     * The gate, called once per fire. Logs the answer either way, so every morning's decision is
     * in history whether it blocked or not -- the whole feature is invisible otherwise.
     */
    fun ringBlockDecision(): String? = when (val v = ringVerdict()) {
        is RingVerdict.Blocked -> { log("ring_blocked", "reason" to v.reason); v.reason }
        is RingVerdict.Allowed -> { log("ring_block_skipped", "why" to v.why); null }
        RingVerdict.NotArmed -> null
    }

    /**
     * The ONLY place a new ring session opens. Both the armed broadcast and the `fireNow` path
     * come through here, so the gate cannot be bypassed by adding a caller -- which is exactly
     * how `fireNow` came to ring without consulting it at all.
     *
     * [trigger] is the mark written when the alarm was armed, routed through, never re-derived.
     *
     * Returns whether the ring service was started, which is the caller's signal that the wake
     * lock has been handed off and must not be released.
     */
    fun openSession(trigger: OccurrenceSource): Boolean =
        when (val action = RingGate.fireAction(session != null, trigger, ::ringBlockDecision)) {
            FireAction.Absorb, FireAction.Ring -> { RingService.ring(app, trigger); true }
            is FireAction.Suppress -> { runCatching { suppressRing(action.reason) }; false }
        }

    /**
     * Whether the settings in memory come from a source worth mirroring: the DE blob existed at
     * startup, or Room has since loaded. Until then they may be defaults or a partial legacy seed,
     * and writing them to the mirror would make them indistinguishable from real ones next start.
     */
    @Volatile private var settingsTrustworthy = false
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

        // Seed from the DE mirror FIRST. Room is credential-encrypted, may be locked, and
        // reading it here would be disk IO on whatever thread woke the process -- including a
        // broadcast receiver. This reads the two sources and nothing more: which one wins is
        // decided in :core, where every combination is a test.
        val chosen = SettingsSource.atStartup(de.settings, de.legacySettings(), state.settings)
        settingsTrustworthy = chosen.trustworthy
        state = state.copy(settings = chosen.settings, lastAliveMs = de.lastAliveMs)
        de.session()?.let { state = state.copy(session = it) }
        de.schedule()?.let {
            state = state.copy(latches = it.latches, override = it.override, nap = it.nap)
            scheduleSeededFromDe = true
        }

        recompute("init:de")

        io.execute { dropDefaultPassword() }

        // Then bring up Room off-thread and recompute again once latches, the override
        // and the nap are known. Room is expendable; the schedule is not.
        loadRoomAsync("init")
    }

    /** True once the mirror has supplied latches/override/nap. From then on it is written
     *  synchronously on every apply(), so it is never older than Room; Room's copy is
     *  only the source on the first run of a build that has the key. */
    @Volatile private var scheduleSeededFromDe = false
    @Volatile private var roomLoaded = false
    val dbReady: Boolean get() = roomLoaded

    /**
     * Opened lazily and only marked ready once a real read SUCCEEDS. Retried on
     * ACTION_USER_UNLOCKED, because before first unlock this always fails.
     */
    /**
     * Earlier builds shipped a default settings password. There is none now: nothing is
     * locked until a password is set. Runs on io (20,000 hash rounds), after the DE seed
     * and again after the Room merge, which would otherwise bring the old hash back.
     */
    private fun dropDefaultPassword() {
        // Not Settings.DEFAULT_PASSPHRASE. That is the Wi-Fi Direct passphrase and
        // happens to share this value; this is the retired settings password, and the
        // two must be free to change apart.
        if (!Auth.isDefault(state.settings, "12345678")) return
        synchronized(lock) {
            state = state.copy(settings = state.settings.copy(passwordHash = null, passwordSalt = null))
        }
        log("default_password_cleared")
        recompute("password_cleared")
    }

    /** Receivers run on the main thread, where Room refuses to open. Always go through io. */
    fun loadRoomAsync(reason: String) { if (!roomLoaded) io.execute { tryLoadRoom(reason) } }

    private val loadLock = Any()
    private fun tryLoadRoom(reason: String): Unit = synchronized(loadLock) {
        if (roomLoaded) return
        runCatching {
            val d = Db.open(app)
            val loaded = Persist.load(d.dao())          // the read is the real test
            val loadedSettings = if (Persist.hasSettings(d.dao())) loaded.settings else null
            db = d
            // Everything logged before Room was readable (boot, crash traces) is only in
            // memory. Flip the flag and take the backlog under the same lock log() uses,
            // so no event is written twice and none is lost.
            val backlog = synchronized(memTail) { roomLoaded = true; memTail.toList() }
            backlog.forEach { persist(it) }
            // A successful Room read means credential-encrypted storage is open, which is
            // the only moment a pre-0.2.80 ringtone can be lifted into DE storage.
            runCatching { Audio.migrateRingtone(app) }
            synchronized(lock) {
                val merged = SettingsSource.onDatabaseLoad(
                    state.settings, settingsTrustworthy, loadedSettings)
                // MERGE, never replace: an empty or wiped kv table returns a default EngineState
                // whose role is null, and assigning it wholesale un-arms the alarm forever.
                state = state.copy(
                    settings = merged.settings,
                    // Union, memory first: whatever happened between boot and unlock lives only
                    // in memory, and Room is older than that. `distinctBy` keeps the FIRST of a
                    // duplicated id, so memory must come FIRST: a latch can be replaced (a
                    // block supersedes a miss), and Room winning resurrects MISSED over SKIPPED
                    // and offers the occurrence up to ring again.
                    latches = (state.latches + loaded.latches).distinctBy { it.id },
                    override = if (scheduleSeededFromDe) state.override else loaded.override,
                    nap = if (scheduleSeededFromDe) state.nap else loaded.nap,
                    lastOutcome = listOfNotNull(loaded.lastOutcome, state.lastOutcome).maxByOrNull { it.atMs },
                    stateVersion = maxOf(loaded.stateVersion, state.stateVersion),
                    lastAliveMs = maxOf(loaded.lastAliveMs, state.lastAliveMs),
                )
                settingsTrustworthy = merged.trustworthy
            }
            log("db_loaded", "reason" to reason)
            recompute("db_loaded")
            dropDefaultPassword()
            pruneHistory()
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
        val ready = synchronized(memTail) {
            memTail.addLast(e); while (memTail.size > 200) memTail.removeFirst()
            dbReady
        }
        if (ready) persist(e)
    }

    private fun persist(e: Event) = io.execute {
        runCatching {
            db.dao().insertBlocking(EventRow(0, e.atMs, e.type, e.actor.name, e.stateVersion,
                JSONObject(e.detail as Map<*, *>).toString()))
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
        // Settings only once they come from a real source: a provisional copy is
        // indistinguishable from a real one next start, which is how an upgrade discarded Room.
        de.mirror(state.settings.takeIf { settingsTrustworthy },
            r.nextFire, state.session, state.latches, state.override, state.nap)
        de.lastAliveMs = state.lastAliveMs
        if (dbReady) io.execute { runCatching { Persist.save(db.dao(), state) } }
        // ALARM role only. The controller must never compute or arm a schedule:
        // a second scheduler on the device with no clock sync would ring in the
        // wrong room and chirp about gates it cannot pass.
        if (state.settings.role == Role.ALARM) Scheduler.arm(app, r.nextFire)

        val fireNow = r.fireNow
        if (alsoFire && fireNow != null && state.session == null &&
            state.settings.role == Role.ALARM) {
            log("fire_now_after_clock_jump", "source" to fireNow.name)
            // Through openSession, never straight to RingService: this is a second place a NEW
            // session opens, and the gate lives there.
            urgent.execute { openSession(fireNow) }
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
     * The owner was away, so this occurrence is spent without a sound. `alsoFire = false` matters:
     * the suppression latches SKIPPED, and letting `fireNow` run would re-enter the gate.
     */
    fun suppressRing(reason: String): Snapshot = synchronized(lock) {
        apply(Engine.suppressTrigger(state, ts, reason), alsoFire = false)
    }

    /**
     * The snooze bar on the ring screen. ALARM-LOCAL: there is no HTTP route and
     * HttpStateClient refuses without sending, because snoozing has to mean reaching the
     * box. RingService owns the act -- it stops the audio and handles a test ring the
     * same way -- so this only asks it.
     */
    override fun snooze(): Snapshot {
        RingService.snooze(app)
        return snapshot()
    }

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
        de.mirror(state.settings.takeIf { settingsTrustworthy },
            lastNextFire, forced, state.latches, state.override, state.nap)
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
        // Only the alarm phone judges presence, so only it has rows to show -- and an empty list
        // folds to NotArmed, which is the same null block reason the role check gave before.
        val awayNow = if (state.settings.role == Role.ALARM) views() else emptyList()
        val s = state.session
        val ov = state.override
        val ovFire = (ov as? Override.Time)?.fireAtMs
        // The scheduled alarm the override replaces -- or WOULD replace when there is
        // none yet, which is what lets the Move dialog show where a time will land.
        val boundAt = state.override?.let {
            runCatching {
                Engine.scheduledInstant(java.time.LocalDate.parse(it.boundOccurrenceId.localDate),
                    state.settings.defaultAlarmTime, ts.zone())
            }.getOrNull()
        } ?: runCatching { Engine.nextUnlatchedScheduled(state, ts).second }.getOrNull()
        return Snapshot(
            stateVersion = state.stateVersion,
            serverTimeMs = System.currentTimeMillis(),
            bootedAtMs = bootedAtMs,
            gates = gates,
            armGate = armGate,
            nextFire = lastNextFire,
            ring = s?.let {
                RingView(it.ringId, it.startedAtMs, it.trigger, it.phase, it.snoozeCount,
                    it.snoozeUntilMs, it.endsByMs,
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
            // The wire's string tag, produced exhaustively from the variant.
            tomorrow = TomorrowView(
                when (ov) {
                    null -> "NONE"
                    is Override.Time -> "TIME"
                    is Override.Skip -> "SKIP"
                }, ovFire, boundAt),
            nap = NapView(state.nap != null, state.nap?.fireAtMs),
            settings = state.settings,
            lastEvents = recentEvents(10),
            // Stale peer reports must not linger after the peer goes away.
            sentinel = sentinelPresence?.let { toDeviceView(it, Role.SENTINEL) },
            // One evaluation behind both, so the block reason and the rows that explain it cannot
            // be reading a different clock, link or peer slot from each other.
            ringBlock = (RingGate.fold(state.settings, awayNow) as? RingVerdict.Blocked)?.reason,
            away = awayNow,
            peerBlockers = if (peerDevice?.lastSeenMs?.let {
                    System.currentTimeMillis() - it < RingGate.PRESENT_WINDOW_MS } == true) peerBlockers else emptyList(),
            lastHeartbeatMs = peerDevice?.lastSeenMs ?: 0,
            testUntilMs = testUntilMs,
            testSnoozedUntilMs = testSnoozedUntilMs,
            // A throw here must never read as "nothing wrong": that turns the screen green on a
            // failure to look. Report the failure itself as the problem.
            problems = runCatching { Health.problems() }
                .getOrElse { listOf("health check failed: ${it.javaClass.simpleName}") },
            warnings = runCatching { Health.warnings() }
                .getOrElse { listOf("health check failed: ${it.javaClass.simpleName}") },
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
            lastSeenMs = System.currentTimeMillis(),
            lanIp = Power.lanIp()).also {
            selfCache = it; selfCacheAtMs = System.currentTimeMillis()
        }
    }

    /** True if this request already SUCCEEDED once. Replays return the current snapshot. */
    private fun seen(requestId: String): Boolean = synchronized(seenRequests) {
        val now = System.currentTimeMillis()
        seenRequests.entries.removeAll { now - it.value > 10 * 60_000 }
        seenRequests.containsKey(requestId)
    }

    /**
     * An idempotent mutation. The replay check, the mutation and the commit are ONE transaction:
     * checked outside the lock, two concurrent retries of the same requestId both passed and both
     * applied. The commit happens only after [body] returns, so a mutation that threw is retried
     * rather than swallowed.
     *
     * [body] must END in its result, never `return` it: this is inline, so a `return` inside it
     * leaves the enclosing function and skips the commit, silently disabling idempotency.
     */
    private inline fun idempotent(requestId: String, body: () -> Snapshot): Snapshot =
        synchronized(lock) {
            if (seen(requestId)) return snapshot()
            body().also { commit(requestId) }
        }

    /** Recorded only once the mutation has actually happened. */
    private fun commit(requestId: String) = synchronized(seenRequests) {
        seenRequests[requestId] = System.currentTimeMillis()
    }

    override fun dismiss(ringId: String, requestId: String, actor: Actor): Snapshot {
        // The ringId check and the end MUST be one transaction: a supersede landing
        // between them would let a dismiss aimed at the finished nap ring end the real
        // 04:00 session instead. The replay check is inside it for the same reason.
        val snap = synchronized(lock) {
            if (seen(requestId)) return snapshot()
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

    private fun requireUnlocked(patch: Settings, token: String?, actor: Actor) {
        // The gate protects the ALARM phone. On the controller this Svc only holds local
        // config; the real check happens when the controller POSTs to the alarm phone.
        if (state.settings.role == Role.CONTROLLER) return
        val changesGated = listOf(
            patch.ringtoneUri != state.settings.ringtoneUri,
            patch.ringtoneName != state.settings.ringtoneName,
            patch.snoozeSeconds != state.settings.snoozeSeconds,
            patch.snoozeHoldSeconds != state.settings.snoozeHoldSeconds,
            patch.resnoozeAfterSeconds != state.settings.resnoozeAfterSeconds,
            // The only two settings that can stop an alarm ringing. Locked, obviously.
            patch.blockRingIfSentinelAway != state.settings.blockRingIfSentinelAway,
            patch.blockRingIfControllerAway != state.settings.blockRingIfControllerAway,
            patch.awayAfterSeconds != state.settings.awayAfterSeconds,
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
        // The controller cannot hold the gate open, whatever it sends. Unlocking is a
        // thing you do standing at the box, so the password never crosses the link and
        // a token captured off the wire buys nothing.
        if (actor == Actor.CONTROLLER) throw ForbiddenException()
        if (unlockToken == null || token != unlockToken) throw ForbiddenException()
    }

    override fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?, actor: Actor): Snapshot {
        // Check and apply in ONE transaction: separately, two patches with the same
        // ifVersion both passed the check and both applied, last writer winning silently.
        val ssidChanged: Boolean
        val snap = synchronized(lock) {
            if (seen(requestId)) return snapshot()
            if (ifVersion >= 0 && ifVersion != state.stateVersion) throw ConflictException(snapshot())
            requireUnlocked(patch, token, actor)
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
        // Reuse `io` rather than leaking a fresh executor per credential change.
        if (ssidChanged && state.settings.role == Role.ALARM) io.execute { Group.restart(app, state.settings) }
        return snap
    }

    override fun nap(minutes: Int, requestId: String, actor: Actor): Snapshot =
        idempotent(requestId) { apply(Engine.setNap(state, ts, minutes)) }

    override fun napUntil(hhmm: String, requestId: String, actor: Actor): Snapshot =
        idempotent(requestId) { apply(Engine.setNapUntil(state, ts, hhmm)) }

    override fun clearNap(requestId: String, actor: Actor): Snapshot =
        idempotent(requestId) { apply(Engine.clearNap(state, ts)) }

    override fun setOverride(kind: String, time: String?, requestId: String, actor: Actor): Snapshot =
        idempotent(requestId) {
            // Exhaustive, and it throws rather than returning an unchanged snapshot: an
            // unrecognised kind must not be committed as a 200 that did nothing, which tells a
            // caller with a typo that its alarm moved.
            when (kind) {
                "SKIP" -> apply(Engine.setSkip(state, ts))
                "NONE" -> apply(Engine.clearOverride(state, ts))
                "TIME" -> apply(Engine.setOverrideTime(state, ts,
                    time ?: throw IllegalArgumentException("TIME override needs a time")))
                else -> throw IllegalArgumentException("unknown override kind: $kind")
            }
        }

    override fun clearOverride(requestId: String, actor: Actor): Snapshot =
        idempotent(requestId) { apply(Engine.clearOverride(state, ts)) }

    /**
     * Newest-last window of at most `limit` events with seq > `sinceSeq`.
     *
     * Before the database is open this falls back to the in-memory tail, whose
     * seq numbering restarts at 1 with the process and so is unrelated to the
     * database's. That is why callers that just want "what happened lately"
     * pass sinceSeq = 0 rather than paging.
     */
    override fun history(sinceSeq: Long, limit: Int): List<Event> {
        if (!dbReady) return recentEvents(limit).filter { it.seq > sinceSeq }
        // No fallback to the in-memory tail: its sequence numbers are not the database's, so a
        // client paging with `since` would silently receive the wrong events. A DB error is a 500.
        return db.dao().since(sinceSeq, limit).map(::eventOf)
    }

    private fun eventOf(r: EventRow): Event {
        val d = JSONObject(r.detail)
        return Event(r.seq, r.atMs, r.type, Actor.valueOf(r.actor), r.stateVersion,
            d.keys().asSequence().associateWith { d.optString(it) })
    }

    override fun export(): Backup = Backup(
        schemaVersion = Persist.SCHEMA_VERSION,
        settings = state.settings,
        // No fallback either: a backup that silently contains no events, reported as success, is
        // worse than a failed export.
        events = if (dbReady) db.dao().all().map(::eventOf) else emptyList(),
        exportedAtMs = System.currentTimeMillis())

    /**
     * ALARM-LOCAL ONLY. There is no HTTP route to this, deliberately: the password must
     * never cross the link. See `requireUnlocked` and SPEC section 8.
     */
    override fun unlock(secret: String): String {
        if (!Auth.accepts(state.settings, secret)) throw ForbiddenException()
        val t = UUID.randomUUID().toString()
        unlockTokenValue = t
        unlockedAtMs = System.currentTimeMillis()
        log("unlocked")
        return t
    }

    /** The Lock button. Idempotent, and the only way back other than a restart. */
    override fun lock() {
        if (unlockTokenValue != null) log("locked")
        unlockTokenValue = null
        unlockedAtMs = 0
    }

    /**
     * Changing or removing the password requires the CURRENT one. There is no recovery code.
     *
     * Without this the gate protects every setting except itself, and "Remove password" is a
     * two-tap bypass of the whole mechanism.
     */
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
        log("role_changed", "to" to role.name)
        // A controller's ring alert outlives the role that raised it, and its DISMISS button acts
        // without asking what this phone is now. Take the notification down with the role.
        runCatching { ControllerWatch.clear(app) }
        LinkService.start(app)
        return recompute("role_changed")
    }

    /**
     * Test ring. Deliberately NOT an occurrence: no latch, no schedule change, no
     * override, no nap. It cannot consume tomorrow's alarm. SPEC.md section 8.
     */
    /** Non-zero while a test ring is live. A test is NOT an engine session. */
    @Volatile var testUntilMs = 0L
    @Volatile var testSnoozedUntilMs = 0L
    val testActive: Boolean get() = System.currentTimeMillis() < testUntilMs

    override fun testRing(silent: Boolean, requestId: String): Snapshot =
        testRing(silent, requestId, fromController = false)

    fun testRing(silent: Boolean, requestId: String, fromController: Boolean): Snapshot =
        idempotent(requestId) {
            // Never while a real alarm is live: the test branch would be skipped and the flag
            // left armed for the next genuine fire. Under the lock, so the guard and the mutation
            // cannot interleave with a trigger or with a second test.
            if (state.session != null || testActive || RingService.serviceAlive) {
                throw BusyException("already ringing")
            }
            de.testSilent = silent
            log("test_ring", "silent" to silent.toString(),
                "from" to if (fromController) "controller" else "here")
            // Live from this instant, so the caller's snapshot already shows a test in progress
            // and both phones' screens flip to the ring at once. The link service keeps this
            // process at foreground importance, so the start is allowed either way.
            testUntilMs = System.currentTimeMillis() + 60_000
            urgent.execute { RingService.startTest(app) }
            snapshot()
        }

    override fun checkClock(): Snapshot {
        ClockObserver.poll()
        log("clock_check", "ok" to ClockObserver.healthy().toString(), "offsetMs" to ClockObserver.offsetMs.toString())
        return snapshot()
    }

    override fun stopTest(): Snapshot {
        testUntilMs = 0L; testSnoozedUntilMs = 0L
        RingService.stop(app)
        log("test_ring_stopped")
        return snapshot()
    }

    fun pruneHistory() {
        if (!dbReady) return
        io.execute { runCatching { db.dao().prune(System.currentTimeMillis() - 90L * 86400_000); db.dao().dedupe() } }
    }
}

// Errors are ClientError (from :data) so the local and remote paths carry the SAME
// vocabulary. Anything else and the three-outcome table in section 7 is only
// implemented on one of the two paths.
typealias StaleRingException = com.mtrinh.fobalarm.data.ClientError.StaleRing
typealias ConflictException = com.mtrinh.fobalarm.data.ClientError.Conflict
typealias ForbiddenException = com.mtrinh.fobalarm.data.ClientError.Forbidden
typealias BusyException = com.mtrinh.fobalarm.data.ClientError.Busy

/** All alarms are setAlarmClock: exempt from standby quotas and Doze. */
object Scheduler {
    const val ACTION_FIRE = "com.mtrinh.fobalarm.FIRE"
    const val ACTION_WATCHDOG = "com.mtrinh.fobalarm.WATCHDOG"
    const val ACTION_TICK = "com.mtrinh.fobalarm.TICK"
    const val ACTION_ARMGATE = "com.mtrinh.fobalarm.ARMGATE"
    const val ACTION_DISMISS = "com.mtrinh.fobalarm.DISMISS"

    /**
     * Which occurrence source this alarm was armed FOR, carried on the alarm itself.
     *
     * The DE mirror cannot answer this: it is rewritten by every `apply`, so a recompute between
     * arming and delivery relabels the trigger -- a nap firing after a process death read as
     * SCHEDULED, got gated, and was then dropped as `trigger_stale`. The mark has to travel with
     * the thing it describes.
     */
    const val EXTRA_SOURCE = "com.mtrinh.fobalarm.SOURCE"

    /**
     * Extras are NOT part of PendingIntent equality (action, data, type, class and categories are),
     * so two `pi()` calls differing only in [source] return the same PendingIntent --
     * `FLAG_UPDATE_CURRENT` is what makes a re-arm's source take effect, and is why cancelling with
     * no source still matches an alarm armed with one.
     */
    fun pi(ctx: Context, action: String, rc: Int, source: OccurrenceSource? = null): PendingIntent =
        PendingIntent.getBroadcast(ctx, rc,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).setPackage(ctx.packageName)
                .apply { source?.let { putExtra(EXTRA_SOURCE, it.name) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * The source carried on [intent].
     *
     * Falls back to the DE mirror ONLY for an alarm armed by a build that did not carry one, which
     * can happen exactly once per install -- for a `setAlarmClock` that was already pending when
     * the package was replaced. It is logged, because a standing fallback here would hide the very
     * relabelling this exists to stop.
     */
    fun sourceOf(intent: Intent?): OccurrenceSource {
        intent?.getStringExtra(EXTRA_SOURCE)
            ?.let { name -> runCatching { OccurrenceSource.valueOf(name) }.getOrNull() }
            ?.let { return it }
        val mirrored = Svc.de.nextFireSource
        Svc.log("fire_source_from_mirror", "mark" to mirrored)
        return runCatching { OccurrenceSource.valueOf(mirrored) }
            .getOrDefault(OccurrenceSource.SCHEDULED)
    }

    /** True only if the real scheduled fire was accepted by AlarmManager. */
    @Volatile var fireArmed = false

    private fun set(ctx: Context, action: String, rc: Int, atMs: Long,
                    source: OccurrenceSource? = null) {
        // A FIRE with no source is unrepresentable: the receiver would have nothing to route on
        // and would fall back to the mirror this exists to stop trusting. Blows up here, where the
        // caller is, rather than fabricating a mark at 04:00.
        require(action != ACTION_FIRE || source != null) { "ACTION_FIRE armed with no source" }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, action, rc, source)
        val ok = runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, p), p) }
            .onFailure { Svc.log("alarm_set_failed", "action" to action, "error" to it.toString()) }
            .isSuccess
        if (action == ACTION_FIRE && rc == 1001) fireArmed = ok
    }

    fun arm(ctx: Context, next: NextFire?) {
        if (next != null) set(ctx, ACTION_FIRE, 1001, next.atMs, next.source) else fireArmed = false
        armHourlyTick(ctx)
        armGateAlarm(ctx)
    }

    fun armWatchdog(ctx: Context) = set(ctx, ACTION_WATCHDOG, 1002, System.currentTimeMillis() + 60_000)

    /** Separate request code from the scheduled fire, so a retry cannot clobber it. */
    fun armFireRetry(ctx: Context, source: OccurrenceSource) =
        set(ctx, ACTION_FIRE, 1005, System.currentTimeMillis() + 5_000, source)
    fun cancelWatchdog(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_WATCHDOG, 1002))

    /**
     * The next top of the hour, plus [offsetMs].
     *
     * An absolute hour boundary, not "now + one hour": both hourly jobs are re-armed by every
     * state transaction, so a relative deadline drifts with whatever the user last did and
     * "hourly" stops meaning what the screen says.
     *
     * The LOCAL hour, via ZonedDateTime, not epoch-millis arithmetic: dividing by 3,600,000
     * gives the top of the UTC hour, which is :30 in India and :45 in Nepal. Truncating is also
     * what makes this safe to call from the wakeup it just served -- it always lands on the
     * following hour, never on the instant we are standing on.
     */
    private fun topOfNextHour(offsetMs: Long): Long =
        java.time.ZonedDateTime.now()
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .plusHours(1).toInstant().toEpochMilli() + offsetMs

    /**
     * **Twenty seconds past the hour, never on it.**
     *
     * On :00:00 this shares an instant with any alarm set to a whole hour -- 04:00 is the
     * default -- and both are `setAlarmClock`, so delivery order is a coin toss. Alarm times are
     * whole minutes, so any offset strictly inside the minute cannot collide with one. Twenty
     * seconds is ~20x the observed trigger delivery delay and leaves the health check its own
     * wakeup.
     *
     * **This offset is not what keeps `missed` honest.** It only stops the tick being one of the
     * callers that can race a due occurrence; ANY recompute landing between the due instant and
     * the session opening races it the same way. `Engine.FIRE_DELIVERY_GRACE_MS` is what settles
     * that. Not colliding on purpose is still worth doing.
     */
    fun armHourlyTick(ctx: Context) = set(ctx, ACTION_TICK, 1003, topOfNextHour(20_000))

    /**
     * Hourly health check. Not a chirp, not a schedule: it only refreshes status.
     * Twenty seconds after the tick so the two do not contend for the same wakeup.
     */
    fun armGateAlarm(ctx: Context) = set(ctx, ACTION_ARMGATE, 1004, topOfNextHour(40_000))
}
