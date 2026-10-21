package com.mtrinh.fobalarm.ui

import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.data.AlarmHost
import com.mtrinh.fobalarm.data.LocalStateClient
import kotlinx.coroutines.CoroutineScope

/**
 * What a screen test needs to render a screen, and nothing else.
 *
 * The client is the REAL [LocalStateClient] over a fake [AlarmHost], not a second implementation
 * of `StateClient`: a parallel fake would be a 16-method copy of production code that could drift
 * from it, and the screens would then be exercised against something no phone ever runs.
 */
class FakeHost(var snap: Snapshot) : AlarmHost {
    override fun snapshot() = snap
    override fun dismiss(ringId: String, requestId: String, actor: Actor) = snap
    override fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?, actor: Actor) = snap
    override fun nap(minutes: Int, requestId: String, actor: Actor) = snap
    override fun napUntil(hhmm: String, requestId: String, actor: Actor) = snap
    override fun clearNap(requestId: String, actor: Actor) = snap
    override fun setOverride(kind: String, time: String?, requestId: String, actor: Actor) = snap
    override fun clearOverride(requestId: String, actor: Actor) = snap
    override fun history(sinceSeq: Long, limit: Int) = snap.lastEvents
    override fun export() = Backup(1, snap.settings, snap.lastEvents, NOW)
    override fun unlock(secret: String) = "token"
    override fun lock() {}
    override fun snooze() = snap
    override fun testRing(silent: Boolean, requestId: String) = snap
    override fun stopTest() = snap
    override fun checkClock() = snap
}

fun appState(snap: Snapshot, scope: CoroutineScope, isLocal: Boolean) =
    AppState(LocalStateClient(FakeHost(snap)), scope, isLocal = isLocal)

/** Every gate passing, so a test that is not about setup is not derailed by it. */
fun gatesAllPass() = Gates(
    evaluatedAtMs = 1, scheduleExists = true, exactAlarm = true, foregroundService = true,
    p2pSupported = true, groupCredentialsSet = true, localNetworkPermission = true,
    notificationPolicyAccess = true, dndAllowsAlarms = true, volumeNotFixed = true,
    fullScreenIntent = true, notHibernating = true, thermalOk = true, audioPlayable = true,
    powerOk = true, vibrationEnabled = true, freeDiskOk = true, noBluetoothAudio = true,
    batteryUnrestricted = true)

fun device(
    role: Role?, battery: Int = 80, plugged: Boolean = false, id: String = "dev-${role?.name}",
    lastSeenMs: Long = NOW, appVersion: String = "0.3",
) = DeviceView(battery, plugged, appVersion, role, id, lastSeenMs)

const val NOW = 1_800_000_000_000L

/**
 * A snapshot with every field defaulted to "nothing interesting is happening", so a test names
 * only what it is about and a reader can see the one thing that differs.
 *
 * [self] and [peer] follow the client's convention: `self` is the phone looking at the screen and
 * `peer` is the phone it polled, which for a controller or a sentinel is the alarm phone.
 */
fun snapshot(
    selfRole: Role,
    peer: DeviceView? = if (selfRole == Role.ALARM) device(Role.CONTROLLER) else device(Role.ALARM),
    sentinel: DeviceView? = null,
    settings: Settings = Settings(role = selfRole, ssid = "DIRECT-xy-fob", passphrase = "passphrase1"),
    nextFire: NextFire? = NextFire(NOW + 8 * 3600_000, OccurrenceSource.SCHEDULED, "scheduled"),
    ring: RingView? = null,
    nap: NapView = NapView(false, null),
    tomorrow: TomorrowView = TomorrowView("NONE", null, null),
    away: List<AwayView> = emptyList(),
    ringBlock: String? = null,
    problems: List<String> = emptyList(),
    warnings: List<String> = emptyList(),
    gates: Gates = gatesAllPass(),
    testUntilMs: Long = 0,
    events: List<Event> = emptyList(),
) = Snapshot(
    stateVersion = 1, serverTimeMs = NOW, bootedAtMs = NOW - 3600_000,
    gates = gates, armGate = null, nextFire = nextFire, ring = ring,
    clock = ClockView(NOW, NOW, 0, "network", 0),
    ap = ApView(settings.ssid, true, 1, NOW - 60_000, null),
    self = device(selfRole, plugged = selfRole == Role.ALARM), peer = peer,
    lastOutcome = null, appVersion = "0.3", settingsSchemaVersion = 1,
    tomorrow = tomorrow, nap = nap, settings = settings, lastEvents = events,
    sentinel = sentinel, ringBlock = ringBlock, away = away,
    lastHeartbeatMs = peer?.lastSeenMs ?: 0, testUntilMs = testUntilMs,
    problems = problems, warnings = warnings)
