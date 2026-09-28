# Fob-Locked Alarm — Spec v3 (two Moto G Play, Android 16 / API 36)

Two phones, one APK, `role` flag.
- **ALARM** — locked in an acrylic box on the nightstand, mains powered. Hosts the network. **Single source of truth for all state.**
- **CONTROLLER** — bathroom wall, mains powered. Thin client. Caches, never authors.

## 0. Invariants (these are the whole product)

1. **The alarm rings.** Ringing depends on nothing but the alarm phone: no network, no controller, no clock sync, no peer.
2. **Local dismiss always works.** Unlock the box, press the button. This is the escape hatch that makes every other failure non-trapping.
3. **The alarm phone owns state.** Controller writes are *requests*; they apply only when the alarm phone acks with a new `stateVersion`.
4. **Nothing fails silently.** Every gate, every link, every sync has a visible timestamp and an age. "Unknown" is rendered as a problem, never as "fine".
5. **No nightly ritual.** Both devices live where they live. Arming is automatic.
6. **The present state is one glance away — on the first screen, with no navigation.** Neither phone keeps its
   screen lit while idle (see §6); the invariant is about *zero navigation*, not about a permanently lit panel.
   Not just failures — the *normal* state is legible at a glance,
   identically on both phones, with no navigation: next alarm (absolute + relative), link state + age, battery +
   charging for **both** devices, last clock sync, `lastOutcome` (dismissed / capped / missed, when, which device), AP state, active
   override or nap, snooze count. Anything with a timestamp renders as an **age**, and a field that is unknown
   renders as a problem — never as blank and never as "OK".
7. **Zero-touch.** In normal operation the only control you ever press is DISMISS. Nothing requires
   acknowledgement, nothing modal, no banner you must clear. Anything noteworthy goes to the log and is
   read from the controller when you're curious.

## 1. Transport — Wi-Fi Direct autonomous group (NOT local-only hotspot)

**LOHS is unusable and fails silently.** `startLocalOnlyHotspotWithConfiguration` is exactly the public API 36
method with exactly the permissions you'd expect — but a non-system app **cannot set the SSID or passphrase**, on
36.0 or 36.1. `SoftApConfiguration.Builder`'s public surface is `setChannels()` only in 36.0; and even on 36.1,
`WifiApConfigStore.generateLocalOnlyHotspotConfig` honours a custom config only when `isExclusive`, which requires
`PRIORITY_SYSTEM` (= `FLAG_SYSTEM`, a `/system` app). **A sideloaded Device Owner caps at `PRIORITY_FG_APP`.** The
call *succeeds*, `onStarted()` fires, the gate goes green — and the AP is `AndroidShare_<random>` with a random
passphrase regenerated every start, which the controller can never find. Worst failure shape in the whole document.

**Therefore: Wi-Fi Direct autonomous group owner, fixed credentials.** Plain public since **API 29**, no flags, no
privileged permission:

```kotlin
WifiP2pConfig.Builder()
    .setNetworkName("DIRECT-ax-alarm")   // MUST begin "DIRECT-xy"
    .setPassphrase(passphrase)           // 8–63 ASCII
    .setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ)   // 2.4 GHz: range + avoids DFS/no-channel
    .build()
manager.createGroup(channel, config, listener)      // ALARM = group owner
```

Permissions: `NEARBY_WIFI_DEVICES` (with `neverForLocation`) **plus `ACCESS_FINE_LOCATION`** — `createGroup` requires
it conditionally, so it comes back into the manifest. Declare it `maxSdkVersion="32"`-free here, and **never pass it
to `setPermissionGrantState`**: it is a *restricted* permission and at `targetSdk ≥ 35` that throws
`SecurityException` rather than no-opping, i.e. a crash in the provisioning path.

**Controller** joins with `WifiNetworkSpecifier` (exact SSID, WPA2, literal — never a pattern), binds the process to
that network, and reads the gateway from `LinkProperties`. The one-time approval genuinely persists across reboots
(stored per-package in `WifiConfigStore`, matched on SSID + security type only, ignoring BSSID) **provided** the SSID
stays byte-identical and the package name never changes. Two traps: the store is credential-encrypted, so **gate the
join loop on `UserManager.isUserUnlocked()`**; and there is a ~30 s / 3-scan cliff after which the request dies with
`onUnavailable` and does **not** resume scanning — so the 20 s poll must re-request, not wait.

### Both sides must already be a running foreground service

Undocumented, and it surfaces as a hardware-looking error. `WifiNetworkFactory.acceptRequest` rejects a specifier
request unless importance ≤ `IMPORTANCE_FOREGROUND_SERVICE` (125), replying `onUnavailable`. And once backgrounded
your app drops to `PRIORITY_BG`, at which point `HalDeviceManager.allowedToDelete` lets almost anything evict your
interface. So: **own the group and the `NetworkCallback` in one long-lived `START_STICKY` foreground service**,
confirmed up *before* either call. Treat `onStopped` / `onFailed` / `onUnavailable` as routine — backoff with jitter,
never fatal.

### Transport plumbing

Embedded HTTP server on **:8765**, JSON. **No application-layer signing.**

*(Earlier drafts HMAC'd every request keyed by the group passphrase. That was worse than useless: anyone who can
reach :8765 has already joined a WPA2 group **using that passphrase**, so they can sign — zero authentication added
over the link that carries it. Meanwhile signing over a `timestampMs` implies a freshness window evaluated across two
phones this very spec treats as unsynchronised by design, so a drifted controller clock would make **every dismiss
fail validation** — `No reply — retrying` forever, and you must fetch the key. Ceremony that can only break the
product. **WPA2 is the boundary; that is what it is for.**)*

**That argument covers the P2P link only, and concurrency (above) puts the phone on home WiFi too.** Unqualified,
`:8765` would expose `POST /v1/dismiss`, `/v1/nap` (both deliberately ungated) and the entire history to every
device on the home network — guests, IoT, a compromised TV. Therefore:

- **The control server binds to the P2P interface only.** Not `0.0.0.0`.
- **Logs get their own port, `:8766`, read-only, `GET` only**, bound on all interfaces so the Mac can reach it.
  Nothing on that port can change state.
- Anything that *does* mutate state and arrives off-P2P requires a bearer token. There is no path by which a device
  on home WiFi can dismiss an alarm.

- **Cleartext is blocked by default** since targetSdk 28, and because the gateway is dynamic there is no host to put
  in a `<domain-config>` (no CIDR support). Ship `network_security_config.xml` with
  `<base-config cleartextTrafficPermitted="true">`. The app makes no other network calls and every request is already
  authenticated, so the blast radius is nil.
- **Local Network Protection is triggered by `targetSdk`, not by an OTA — so the risk is entirely ours.** The
  official Android 16-vs-17 table: on Android 16 the gate is `targetSdk 36` and the permission is *temporarily*
  `NEARBY_WIFI_DEVICES` — which we already hold, so **LNP is already satisfied on API 36 and a declared
  `ACCESS_LOCAL_NETWORK` is inert today**. On Android 17 the gate is `targetSdk ≥ 37` and the permission becomes
  `ACCESS_LOCAL_NETWORK`. Enforcement is *"mandatory and enforced for apps targeting Android 17 or higher"* — an
  Android 17 OTA therefore **cannot** break this app. Only bumping `targetSdk` past 36 can.
  → Runbook rule: **do not raise `targetSdk` above 36 without re-running the Phase-3 transport spike.** The gate row
  checks `NEARBY_WIFI_DEVICES`, not `ACCESS_LOCAL_NETWORK`.
  Denied traffic does **not** hang: LNP surfaces concrete errno failures (`sendto failed: EPERM`, `ECONNABORTED`) —
  a loud, loggable error, so log the errno. *(Earlier drafts claimed a silent timeout and designed a defence around
  it; that failure class does not exist here.)* The `adb shell am compat enable RESTRICT_LOCAL_NETWORK` test an
  earlier draft prescribed **is not executable on these phones** — no ADB — and needs a second, unlocked device.
  Source: developer.android.com/privacy-and-security/local-network-permission

### Clock sync — V1 has no `setTime()`, and that is simpler

No Device Owner means no `DevicePolicyManager.setTime()`, so **the app never sets the clock.** Leave `AUTO_TIME` on
and let Android sync from the network (NITZ/cellular, or home WiFi when reachable) on its own schedule. The app only
**reads**: `SystemClock.currentNetworkTimeClock()` (API 33+) returns network time and throws when unavailable — which
is the **availability** signal — but not, as earlier drafts claimed, a staleness signal "with no work."

**Correction, from AOSP `SystemClock.java`:** the clock returns `timeDetectorService.latestNetworkTime()`
re-anchored to `elapsedRealtime()`. It exposes **no timestamp of the last successful sync** and no documented
staleness bound, so a 36 h "clock synced recently" gate is unimplementable as written — it would be hardcoded to
always-pass or always-fail. Also: `currentNetworkTimeClock()` itself never throws (it returns a `SimpleClock`
unconditionally); `DateTimeException` comes out of `millis()`/`instant()`. And `currentNetworkTimeMillis()` is
`@hide`.

So `lastSyncOkMs` is redefined as **app-observed**: on each hourly tick, call `currentNetworkTimeClock().millis()`
inside try/catch; on success record `now` and `offsetAppliedMs = networkMs - System.currentTimeMillis()`. The gate
asserts *observed available within 36 h* **and** *|offset| < 60 s*. The spec states plainly that the underlying
network time may itself be arbitrarily stale — this measures our observation of it, not its freshness.

**Deleted along with it:** the daily 13:00 tear-down-the-group-and-join-home-WiFi window, and the protocol
affordance telling the controller to expect a 30 s outage. A scheduled self-inflicted outage was never worth it for
a clock that drifts seconds per day.

**But the STA+P2P concurrency dependency is NOT deleted — it came back through the dev tooling.** §13's update
channel discovers the Mac on *home WiFi* while the alarm phone is hosting the P2P group, and the no-ADB replacement
for logcat is `curl phone:8765/v1/logs` over *home WiFi*. Both require simultaneous STA association and P2P group
ownership. So:

- `isStaApConcurrencySupported()` (and an actual on-device measurement) is a **gate row and a Phase-0 spike**. If
  concurrency is unavailable on the XT2615-1, the dev channel and log pull must fall back to a deliberate,
  password-gated "drop the group for 10 minutes" maintenance mode — never an automatic one.
- **The controller binds per-socket, not per-process.** `Network.bindSocket()` / `Network.openConnection()` on the
  P2P network for alarm traffic only. Process-wide `bindProcessToNetwork` also severs the controller from the Mac,
  breaking its own update channel and log pull. *(This was a real defect in earlier drafts.)*

**What to verify on-device instead:** that the phone still receives NITZ/network time while hosting the group. If it
doesn't, `staleBy` grows visibly and the arm gate complains at 22:00 — loud, not silent.

## 2. State snapshot (the observability contract)

```
{ stateVersion, serverTimeMs, bootedAtMs,
  // `mode` is DERIVED, never stored: open session → RINGING (outranks all);
  // else blocking gate failing → INIT; else WAITING.
  gates:   { evaluatedAtMs, scheduleExists, exactAlarm, foregroundService, p2pSupported, gyroscopePresent,
             groupCredentialsSet, localNetworkPermission, notificationPolicyAccess,
             dndAllowsAlarms, volumeNotFixed, fullScreenIntent, notHibernating, thermalOk,
             audioPlayable, powerOk, allPass },
  armGate: { lastRunAtMs, result, failingGates[] },
  nextFire:{ atMs, source: SCHEDULED|TOMORROW_OVERRIDE|NAP, label } | null,
  ring:    { ringId, startedAt, trigger, phase: RINGING|SNOOZED,
             snoozeCount, snoozeUntilMs, endsByMs, rotationDeg } | null,
  clock:   { lastSyncAttemptMs, lastSyncOkMs, offsetAppliedMs, source, staleByMs },
  ap:      { ssid, running, clientCount, lastStartedAtMs, lastError },
  self:    { batteryPct, plugged, appVersion, variant },   // filled by the CLIENT ADAPTER, locally
  peer:    { batteryPct, plugged, appVersion, variant, lastSeenMs } | null,
           // HttpStateClient sets self from local APIs and peer from the remote snapshot's self;
           // LocalStateClient mirrors it. No round trip to render a local fact — which otherwise
           // fails exactly when the link is down, i.e. when you care.
  lastOutcome: { kind: DISMISSED_LOCAL|DISMISSED_REMOTE|CAPPED|MISSED|SKIPPED|SUPERSEDED,
                 atMs, occurrenceId, ringId, snoozeCount } | null,
                 // ONE answer to "what happened last time an alarm was due?" — replaces
                 // lastSession + missed, which could contradict each other on screen.
  appVersion, settingsSchemaVersion,
  tomorrow:{ kind: NONE|TIME|SKIP, time },
  nap:     { armed, atMs } ,
  settings:{ …fully enumerated — the controller both renders and edits it… },
  lastEvents: [ last 10 ] }
```

The controller's idle screen is essentially a rendering of this. That answers "can the controller get the remote's timesync data" — yes, `clock.*`, every 20 s.

## 3. Ring session model

```
RingSession(ringId, startedAt, trigger, phase, snoozeCount,
            snoozeUntilMs, endsByMs, outcome)
```
Persisted on every phase change (a handful of writes per morning — flash wear is a non-issue).

- `endsByMs = startedAt + maxRingMinutes` — **snooze time counts against it.** The cap is on the session, not on airtime, so you can't snooze your way past it.
- Hitting the cap **stops audio, writes a `capped` event, sets `lastSession.outcome = CAPPED`, and returns to
  `WAITING`.** Nothing to clear, no chirp — the siren ran for a full hour, so either it woke you or nothing would.
  But `lastOutcome` is rendered persistently on both idle screens, so the screen can never show a dismissal from two
  days ago and imply you got up.
- Only **one session at a time, ever.**
- A trigger arriving while `SNOOZED` **cancels the snooze and rings now** (same session, `snoozeUntil` cleared).

**Trigger precedence** — `SCHEDULED` / `TOMORROW_OVERRIDE` **>** `NAP`. *(This is the "what if an alarm is off while another is on" case.)*

| open session | incoming | result |
|---|---|---|
| NAP | SCHEDULED | **nap session ends** (`outcome = SUPERSEDED`), **new session starts** with a fresh `ringId`, fresh rotation counter, fresh `endsBy`. Audio never actually stops — the handover is seamless. |
| SCHEDULED | NAP | nap is **dropped**, logged `nap_dropped`. The real alarm is already blaring; a nap on top is meaningless. Nap disarms. |
| any | same source | impossible (occurrence latch), but if seen: absorbed + logged |

A supersede mints a **new `ringId`**, so an in-flight controller dismiss aimed at the old one correctly `409`s. The controller re-polls at 2 Hz, sees the new session, and its button re-arms — visibly, not silently.

- Outcome is one of `DISMISSED_LOCAL`, `DISMISSED_REMOTE`, `CAPPED`, `SUPERSEDED`.

**Resurrection** — three independent layers, because this is invariant #1:
1. **`setAlarmClock()` for every alarm — scheduled fire, watchdog, and boot kick.** Never `setExact`: the
   `working_set` standby quota is **10 wakeups/hour** and a 60 s watchdog wants 60. `setAlarmClock` is exempt
   outright (`isExemptFromAppStandby`), which also makes it immune to the charging question below. Doze-exempt by
   design, and it carries the FGS-start allowlist reason.
2. Foreground service, type **`systemExempted`** (`specialUse` subtype `"alarm_clock_ringing"` as fallback). No
   timeout, and — unlike `mediaPlayback` — **not in the `BOOT_COMPLETED` blocklist.** `mediaPlayback` is also
   documented for actual media playback, which an alarm is not.

   **The manifest declares `android.permission.USE_EXACT_ALARM`, not `SCHEDULE_EXACT_ALARM`.** This matters more
   than it looks: V1 has no Device Owner, so `USE_EXACT_ALARM` is the *sole* basis for qualifying for
   `systemExempted`. `SCHEDULE_EXACT_ALARM` is denied-by-default at install for targetSdk 33+ and is
   user-revocable — declaring that one instead means `setAlarmClock` **and** the foreground service die together,
   the latter as a `ForegroundServiceTypeNotAllowedException` crash at 04:00. `USE_EXACT_ALARM` is install-time,
   not revocable, and is exactly what an alarm clock is meant to hold. The `specialUse` fallback additionally
   requires a `<property>` element inside the `<service>` tag — ship it.

   **Wake locks, which no earlier draft mentioned.** `AlarmManager` holds a wake lock only until `onReceive`
   returns and grants an FGS/BAL allowlist of roughly 10 s. So: the receiver takes a `PARTIAL_WAKE_LOCK` (60 s
   timeout) **before** calling `startForegroundService`, and calls it **synchronously** — any coroutine hop risks
   suspending before the service is up, or blowing the allowlist window on a cold start after an OTA, which throws
   `ForegroundServiceStartNotAllowedException` and the ring never begins. Wrap that start in try/catch with a
   `setAlarmClock(now + 5 s)` retry. The FGS then takes its own partial wake lock for the session and releases the
   receiver's. `MediaPlayer` does not hold the CPU awake by itself — set `setWakeMode(ctx, PARTIAL_WAKE_LOCK)` or
   playback can stall on suspend mid-session.
3. **Watchdog `setAlarmClock` every 60 s while a session is open**, plus a plain `Handler.postDelayed` self-heal
   loop inside the running FGS. Covers OOM kill, crash, and crash loop → next tick resurrects the session from disk
   and resumes ringing.

   **It does *not* cover force-stop — but force-stop is neither permanent nor silent.** Android 15+ does cancel
   every pending intent when an app enters the stopped state, so a force-stop kills the next fire and the watchdog
   until a human acts. Two corrections to earlier drafts, both from the Android 15 behaviour-change page:
   *"When the user's actions remove the app from the stopped state, the `ACTION_BOOT_COMPLETED` broadcast is
   delivered to the app, providing an opportunity to re-register any pending intents."* So **launching the app once
   fully re-arms it**, via the boot receiver already specced in (4) — no special path needed. And
   `ApplicationStartInfo.wasForceStopped()` reports it, so on every launch the app writes a **`force_stopped`
   event** and both idle screens render it until acknowledged. The window is still real and still unattended, so
   the controller's nag remains valuable — it is just no longer the *only* detector, and recovery is one tap
   rather than a reinstall.
4. **The ring path never touches Room.** This was a latent total-kill in every earlier draft: §3.4 promised
   rescheduling before first unlock, but Room lives in **credential-encrypted storage**, unreadable until someone
   unlocks the phone — which, per invariant #5, nobody ever does. A 02:00 reboot (OTA, brownout, panic) therefore
   meant the direct-boot receiver could not open the DB, could not compute `nextFire`, and 04:00 passed in silence.
   The same reasoning kills the ring path on DB corruption from an unclean shutdown, on a migration that throws
   mid-upgrade, and on `SQLiteFullException` during the per-phase-change write.

   So: mirror `{defaultAlarmTime, resolved override, nextFire.atMs, open RingSession, alarmVolumePercent,
   ringtone path}` into a small DataStore file under `createDeviceProtectedStorageContext()`, rewritten on every
   `recompute()`. **The receiver, the FGS, the ring Activity and the ringtone copy are all `directBootAware="true"`
   and read only the DE mirror.** Room holds history and the settings UI — expendable. Install a
   `DatabaseErrorHandler` that renames a corrupt file and rebuilds empty, wrap every session-persistence write in
   try/catch so a failed write can never propagate out of the ring path, and add a `freeBytes > 50 MB` row to the
   22:00 gate.

5. `LOCKED_BOOT_COMPLETED` (with `directBootAware="true"`, so rescheduling happens before first unlock) **and**
   `BOOT_COMPLETED` → **unconditionally rebuild the entire alarm set**; if an open session with `endsByMs > now`
   exists, resume ringing. Keep the receiver → immediate `setAlarmClock` → FGS hop as defence in depth; it's an
   explicitly documented exemption ("your app invokes an exact alarm to complete an action the user requests").

## 4. Audio

**Ringing is continuous** until snoozed or dismissed. `SNOOZED` and dismissed are **fully silent** — no hum, no chirp, no pulse. The only two durations in the whole audio model are `snoozeSeconds` and `maxRingMinutes`.


- User picks an mp3 → **copied into app-private storage at pick time.** No `READ_MEDIA_*` at ring time, no SAF grant to lose, no file that can vanish.
- Fallback chain: user copy → bundled asset → `RingtoneManager.TYPE_ALARM` default → `ToneGenerator`.
  *(Earlier drafts said the last link "cannot fail." It can: `ToneGenerator`'s constructor throws `RuntimeException`
  on AudioTrack init failure, and it plays on `STREAM_ALARM`, so the same zen mute bit that silences every link
  above it silences this one too. There is no terminal audio guarantee. Vibration and the screen are the last
  line — and per the vibration bullet below, even that is conditional.)*
- **The chain is re-entered mid-session, not evaluated once at ring.** `setLooping(true)`, `setOnErrorListener`,
  `setOnCompletionListener` — all three were missing, so "ringing is continuous" was an assertion with no
  mechanism, and `MEDIA_ERROR_SERVER_DIED` (a mediaserver restart, certain to happen over 3,650 sessions) left the
  app reporting `RINGING` in perfect silence.
- **Audibility heartbeat, every ~5 s, reusing the volume loop.** Assert `mp.isPlaying()`,
  `!audioManager.isStreamMute(STREAM_ALARM)`, `getStreamVolume(STREAM_ALARM) >= target`, and
  `mp.getRoutedDevice().type == TYPE_BUILTIN_SPEAKER`. Any failure → log it, drop one link down the fallback chain,
  and escalate to vibration + full-brightness screen. The four booleans publish as `ring.audible{…}` in the
  snapshot, because "the app thinks it is ringing" and "sound is coming out" must be separately observable.
- `AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)`. **Never request audio focus, and never react to focus
  loss** — `USAGE_ALARM` does not need focus to play, and the reflexive `AUDIOFOCUS_LOSS` idiom would stop the
  alarm for an incoming call or an emergency alert. Route with
  `setPreferredDevice(TYPE_BUILTIN_SPEAKER)`, which is a *preference and not a guarantee*: keep an
  `OnRoutingChangedListener` that re-asserts, add `bluetoothA2dpConnected` to the 22:00 gate, and pair no
  Bluetooth audio to the alarm phone at all (provisioning §12).
- **Volume is asserted, not inherited.** This is the explicit fix for how normal phone alarms behave:
  1. `setStreamVolume(STREAM_ALARM, …)` to `alarmVolumePercent` **at arm time (22:00)** — so it's already right hours before.
  2. Re-assert **immediately before `start()`** at ring time.
  3. **The detector is `isStreamMute(STREAM_ALARM)`, not an index comparison.** Under zen mute the stream *index
     is untouched* — `AudioService` sets a separate mute bit and `volumeAdjustmentAllowedByDnd` makes the write a
     bare `return`, while `getStreamVolume()` keeps returning the old, correct number. An index-comparing loop
     therefore reads back a match every 5 s, logs nothing, and reports healthy through a completely silent hour:
     blind to precisely the failure it was written to catch.
  4. **Read back, then write only on mismatch, every ~5 s** for the whole session — and **log every mismatch**. A
     blind write loop is useless anyway (see DND below) and a conditional one is a *detector* for whatever is
     lowering your volume.
  4. Independently, set the `MediaPlayer`'s own gain to 1.0 so there's no second attenuator hiding underneath.
  5. `alarmVolumePercent` is **floored at 50** in the UI *and* validated server-side — a `/v1/settings` patch below 50 is rejected `400`, so no client can silence it.
- Route to the built-in speaker explicitly; never to a connected BT or wired device.

**DND can mute `STREAM_ALARM` beyond your reach, and `setStreamVolume` then no-ops without throwing.**
`USAGE_ALARM` does *not* save you: "Total silence" (`ZEN_MODE_NO_INTERRUPTIONS`) mutes the alarm stream outright, and
normal priority DND mutes it whenever `PRIORITY_CATEGORY_ALARMS` is missing from the **consolidated** policy — which
is most-restrictive-wins across every active `AutomaticZenRule`, so Bedtime mode or any third-party rule can do it
without anyone touching the main toggle. When muted, `volumeAdjustmentAllowedByDnd()` makes `setStreamVolume` a bare
`return`: no exception, no log, `getStreamVolume()` unchanged. And apps targeting 35+ **cannot change DND policy**.

So: declare `ACCESS_NOTIFICATION_POLICY`, walk through `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` once at INIT, and
make these **hard gates** re-checked nightly: `PRIORITY_CATEGORY_ALARMS` present, `getCurrentInterruptionFilter() !=
INTERRUPTION_FILTER_NONE`, `!isVolumeFixed()`.

**Checking the *consolidated policy* at 22:00 is not sufficient, and is in fact evaluated at the one time of day
guaranteed to miss the named threat.** Bedtime mode is a *scheduled* `AutomaticZenRule`. At 22:00 it is inactive,
the consolidated policy is permissive, the gate passes — and it activates at 23:00, drops
`PRIORITY_CATEGORY_ALARMS`, and mutes `STREAM_ALARM` by 04:00. So the gate instead **enumerates
`NotificationManager.getAutomaticZenRules()` and fails if any *enabled* rule (active or not) either is
schedule-based with a window covering `nextFire.atMs`, or carries a `ZenPolicy` lacking
`PRIORITY_CATEGORY_ALARMS`.** The consolidated policy is re-checked at ring start as well, escalating through the
audibility heartbeat. *(Good news: `setStreamVolume(STREAM_ALARM, x, 0)` can never throw
`SecurityException` — that's a ring-stream phenomenon — is not rate-limited, and shows no UI.)*

**Vibration and the full-brightness screen are load-bearing, not decoration — but vibration is NOT unconditional,
and an earlier draft called it "the genuine last line" on a false premise.** `USAGE_ALARM` is exempt from *ringer
mode* muting, true. But `VibrationSettings.shouldIgnoreVibration` checks the **user setting first**: if
`Settings.System.VIBRATE_ON` is off, or `ALARM_VIBRATION_INTENSITY == VIBRATION_INTENSITY_OFF`, the vibration
returns `IGNORED_FOR_SETTINGS`. The bypass flags (`FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF`,
`FLAG_BYPASS_INTERRUPTION_POLICY`) are `@hide` and documented as ignored for non-privileged apps. So one stray tap
in Settings → Sound → Vibration, made once in 2027, silently removes the entire backstop for the zen-muted case.

Three consequences, all cheap:
- **Gate rows `vibrateOn` and `alarmVibrationIntensity`**, read via `Settings.System.getInt` (world-readable),
  checked at 22:00 *and* at ring start, in the same class as `dndAllowsAlarms`.
- **Re-issue the vibration on `ACTION_SCREEN_OFF`.** `shouldCancelVibrationOnScreenOff` cancels non-system
  vibrations unless the sleep reason is `TIMEOUT`/`INATTENTIVE` — and with the always-on screen, a **power-button
  press** is the only way the screen goes off, i.e. the one deliberate act most likely to happen mid-ring.
- **Wired charger only.** `config_ignoreVibrationsOnWirelessCharger` suppresses *all* vibration on a wireless
  charger with no usage allowlist. AOSP defaults it false, but it is OEM-overridable, and this phone is
  permanently charging. Also in provisioning §12.
- Vibrate + full-brightness screen alongside.
- Readability of the audio file is checked at the **nightly arm gate**, not at 04:00.

## 5. Architecture (one codebase, one renderer, two roles)

The rule: **the frontend never knows whether it is local or remote.** It talks to a repository; only the injected
implementation differs.

```
:core        pure Kotlin/JVM, zero Android deps
             Schedule, RingSession, Settings validation, occurrence latches,
             DST/clock-jump maths, Snapshot DTO, event log model
             → the entire state machine, fully unit-testable

:data        interface StateClient {
                 suspend fun snapshot(): Result<Snapshot>
                 suspend fun dismiss(ringId, requestId): Result<Snapshot>
                 suspend fun patchSettings(ifVersion, patch, requestId): Result<Snapshot>
                 suspend fun nap(minutes, requestId): Result<Snapshot>
                 suspend fun setOverride(kind, time, requestId): Result<Snapshot>
                 suspend fun history(since, limit): Result<List<Event>>
                 suspend fun export(): Result<Backup>            // §14 — everything needed to rebuild a phone
                 suspend fun import(backup, token): Result<Snapshot>
             }
             LocalStateClient  → in-process call into :core (ALARM role)
             HttpStateClient   → plain JSON over the bound network (CONTROLLER role)

:service     ALARM role only. Owns :core, the AP, the HTTP server, audio, alarm scheduling.
             Serves exactly the same Snapshot DTO that LocalStateClient returns.

:ui          Compose. Depends on StateClient + Snapshot and nothing else.
             One set of screens. One status row. One settings editor. One history list.
```

Consequences worth having:

- **The alarm phone's own UI is also a client.** It reads the same DTO through the same interface, so a bug in the
  snapshot or the renderer shows up identically on both phones instead of only remotely. The local path is not a
  privileged shortcut.
- **Role selects a client, not a screen.** Everything visual is shared. The only role-conditional UI is: AP
  credentials + audio file picker (alarm-only, because they're device-local), and the rotation instrument
  (alarm-only, because it's sensor-driven).
- `LocalStateClient` returning `Result` too means offline/error rendering is exercised on both phones.
- All validation lives in `:core`, so a settings floor is enforced once and cannot be bypassed by the HTTP path.

## 6. UI language

**Material 3, dark, fixed palette** (dynamic color off — the two phones must look identical and must not shift with wallpaper). Sleek but **no reinvented components**: stock M3 `Button`, `TimePicker`, `Slider`, `Snackbar`, `ListItem`, `TopAppBar`. Large type scale, high contrast, near-black surfaces so the always-on screen is unobtrusive at 3 AM.

The **only** custom-drawn thing in the app is the rotation instrument (§7 RINGING). Everything else is off the shelf.

**Neither phone keeps its screen on while idle.** `FLAG_KEEP_SCREEN_ON` applies **only during a ring session**
(alarm phone: full brightness; controller: full brightness while the remote-dismiss button is live). Otherwise both
phones use the normal system screen timeout: the screen comes on when you pick the phone up or unlock it, shows the
status screen immediately, and sleeps again. Idle state is dark on both devices.

Reason: ten years of a lit panel is ~87,600 hours against a typical 30–50 k hour LED backlight half-life — a
consumable that dims gradually and silently, and a continuous heat source next to the battery in a closed box.

**Two consequences that must not be lost:**
- **The hibernation gate is now strictly load-bearing.** An earlier draft's always-on Activity was probably keeping
  `lastTimeVisible` fresh and accidentally suppressing app hibernation. That accident is gone, so the INIT/22:00
  gate on `getUnusedAppRestrictionsStatus()` is the *only* thing standing between the zero-touch design and a
  hibernation-induced force-stop. It is not optional.
- **The ring Activity must turn the screen on by itself**, from locked and from fully asleep:
  `setShowWhenLocked(true)` + `setTurnScreenOn(true)` + the full-screen intent, plus the wake lock in §3. It can no
  longer inherit an already-awake screen.

## 7. Screens

### ALARM — RINGING
Time, large, centered. Below it: **PRESS TO DISMISS** (immediate, no confirm — it already costs a key and a walk).
Below that, the snooze instrument: a 3D orientation widget + `rotationDeg / threshold` live counter.
Header strip: `● Connected · 1s` / `○ Disconnected · 4m · AP: no clients`.

**The instrument.** A 2D Compose `Canvas` drawing a projected wireframe globe: lat/long grid, a marker at the
current orientation taken from the rotation vector, and a fading breadcrumb polyline of recent orientations — so
you literally watch the path you've traced. No 3D engine, no dependency, one file, ~200 lines. *(Filament /
SceneView would do real 3D and is not worth a heavyweight dependency and a second renderer for one widget.)*

**Legibility beats ornament at 4 AM**: the `deg / 120` numeral is the primary element, large and centered; the
globe is secondary and sits behind it. If you're barely conscious you read the number, not the sphere.

**Snooze gesture** — *path traveled, not displacement.*

**The phone has a real gyroscope** — ST LSM6DSV 6-axis IMU, confirmed at part-number level on the 2026 unit. (The
2023 and 2024 G Plays had none; verify yours is XT2615-1 with `adb shell dumpsys sensorservice`. Probe
`TYPE_GYROSCOPE` directly — `TYPE_ROTATION_VECTOR` exists on gyro-less devices too and proves nothing.)

**But `∫|ω| dt` is a rectifier and would self-snooze the alarm.** Taking the magnitude means bias and noise can never
cancel — they accumulate monotonically. LSM6DSV zero-rate level is ±1 dps typical; even with HAL calibration leaving
~0.3 dps residual per axis, a **perfectly motionless phone in a box reaches 120° in about four minutes.** Inside a
60-minute session that is a near-certain spurious snooze, repeatedly, with `maxSnoozes` unbounded. Silent, and
exactly the failure the product exists to prevent.

So, **primary estimator: sum the geodesic angle between consecutive `TYPE_GAME_ROTATION_VECTOR` quaternions**
(~50 Hz). A true path integral that does not accumulate gyro bias. The `∫|ω| dt` integral runs alongside as a
cross-check; **disagreement is a logged event.**

**It must be `GAME_ROTATION_VECTOR`, not `ROTATION_VECTOR`** — and an earlier draft got this wrong in a way that
reintroduced the exact bug this section exists to kill. `TYPE_ROTATION_VECTOR` is a 9-axis fusion including the
**magnetometer**. A phone sitting motionless in a box six inches from a charger transformer, a speaker magnet, or
the box's own magnetic latch produces continuous yaw corrections as the filter chases a disturbed field — and a
path integral **rectifies** those corrections exactly as it rectifies gyro bias: monotonic, never cancelling,
reaching 120° well inside a 60-minute session. The deadband does not save it, because the deadband is on gyro
`|ω|`, which reads ≈0 during a pure fusion correction, so the correction is never discarded. `GAME_ROTATION_VECTOR`
is gyro+accel with the magnetometer excluded; it has no absolute yaw reference, which is irrelevant here because
only *path length* is ever used. **Apply the deadband to the quaternion rate** (geodesic angle / Δt), not to the
gyro stream.

**Per-stream staleness.** Coupling two sensor streams means either can kill the gesture: a stalled gyro (HAL
glitch, batching under Doze) discards every sample and makes snooze permanently impossible; a stalled RV freezes
`rotationDeg`. Each stream gets a "no sample in > 2 s" check, published in the snapshot, with fallback to the
surviving estimator.

*(Note: "fuse rotation-vector for drift correction" — in the earlier draft — is a category error. Rotation vector
gives absolute orientation, which carries no information about path length.)*

Three guards regardless:
- **Deadband** — discard `|ω| < 15 °/s`. A deliberate box rotation runs 100–300 °/s; bias is under 1.
- **Zero-rate update** — while under the deadband and `|accel| ≈ 1 g` for > 1 s, average the gyro and subtract it as a
  live bias estimate.
- **Decay** — bleed the accumulator to 0 after ~3 s of stillness. A snooze should be **one continuous gesture**, not a
  sum over an hour. This also makes the gesture harder, which is the product intent.

Accumulate only while `phase == RINGING`; reset at each ring start and each snooze. Threshold flashes the widget,
then `phase = SNOOZED` for `snoozeSeconds`. Repeatable. `rotationDeg` and the live bias estimate are both in the
snapshot, so drift is visible in testing instead of mysterious.

### ALARM — SNOOZED
Distinct screen: countdown to re-ring, snooze count, and a still-live PRESS TO DISMISS.

### ALARM — WAITING
Next fire (absolute + relative), tomorrow-override / nap banners, link state, clock-sync age, gate summary, quick settings.

### ALARM — INIT
Gate checklist, one row per item, each with a **Fix** button that deep-links to the right settings page.

**An open ring session outranks everything. Gates never preempt audio.** A thermal, DND or battery gate regressing at
04:05 must not drop the screen into INIT and stop the siren — that would violate invariant #1. Gate regressions
during a session are logged and rendered in the health line; INIT is entered only from `WAITING`.

### CONTROLLER — RINGING
Same layout. Button reads **PRESS TO DISMISS REMOTE ALARM** → `Waiting…` → bottom-center toast **Dismissed!** on ack.
**Three distinct outcomes, never conflated** — collapsing them is how the UI ends up telling you to fetch the key
while the alarm phone is perfectly healthy:

| outcome | behaviour |
|---|---|
| transport failure | retry the same intent (idempotent by content); `No reply — retrying (3/∞)`; after ~10 s `Can't reach alarm phone — use the key` |
| `409` stale `ringId` | **never retried.** Re-render from the returned snapshot: `Alarm changed — press again` |
| `200` | `Dismissed!` toast |

The button is **disabled briefly across any `ringId` change**, so a reflexive second tap after a supersede can't kill
the real alarm that just took over.

### CONTROLLER — WAITING
Mirror of the snapshot: next fire, last dismissal (when/how/which device), last sync, AP state, recent history, all settings editable.

### CONTROLLER — INIT
Its own, thinner gates: `NEARBY_WIFI_DEVICES`, credentials entered, AP reachable once.

## 8. Settings

**Everything is editable from both phones** — reach is not the control. Instead, the settings that can silence
tomorrow are **password-gated**, with the password set *optionally* during INIT.

**Gated set** (these are the four cheap kills the design review found): `ringtone` — a near-silent mp3 defeats the
50 % stream floor completely, since the floor is on the stream, not the content · `snoozeSeconds` — 29 minutes turns
one rotation into a kill, and every log entry looks legal · `defaultAlarmTime` · `armGateTime` — moving the only
audible warning to when you're asleep · `maxRingMinutes` · `alarmVolumePercent` · `ssid` / `passphrase`.

**Ungated**, because they're what you actually need at 23:00 or from the bathroom: `dismiss`, `nap`, the next-alarm
override, brightness, and anything cosmetic.

Rules: enforced in `:core`, so the HTTP path can't bypass it. Verified against a hashed+salted value; a gated patch
without a valid token is `403`. The unlock lasts one editing session (~2 min idle), never persists. **It never gates
dismiss** — nothing on the 4 AM path can ever require a password.

**There is a one-time recovery code, because "recovery is a factory reset" was not an acceptable answer.** A
factory reset is *also* total data loss (§14) — so the spec's stated cure for a forgotten password destroyed the
settings, the pairing and the history, and after three years of the thing silently working, forgetting is the
likely case rather than the unlikely one. The password also gates `role`, the group credentials and the dev update
channel, i.e. everything you would need in order to fix anything.

So: at INIT the app generates a **recovery code**, displays it exactly once, and validates it in `:core` exactly as
it validates the password. Write it on the printed runbook stored with the spare key to the box. Store the password
in your password manager. If both are lost, the fallback is a factory reset plus a §14 restore — recoverable, but
a bad evening. Declining the password at INIT is fine and fully supported; it just means the
four kills stay two taps away.

Also: `snoozeSeconds` gets a hard ceiling of **600 s** regardless of password — a 29-minute snooze isn't a legitimate
setting for any password holder either.

### Table (SSID/passphrase are alarm-phone-only *and* gated)

| key | default |
|---|---|
| `role` | — |
| `defaultAlarmTime` | 04:00 |
| `tomorrow` | NONE \| TIME(hh:mm) \| SKIP |
| `alarmVolumePercent` | 85, **hard floor 50** |
| `ringtone` | bundled |
| `snoozeSeconds` | 30 |
| `snoozeThresholdDegrees` | 120 |
| `maxSnoozes` | *none* — bounded only by `maxRingMinutes` |
| `maxRingMinutes` | **60** — emergency stop; floor 5 |
| `armGateTime` | 22:00 |
| `napMinutes` | free entry, 1 min – 5 h (last value remembered) |
| `missedGraceMinutes` | 15 |
| `ssid` / `passphrase` | user-set, gate |

### Test ring

**Invokable from both phones**, because the point is to verify the thing you cannot
otherwise see: that the alarm phone wakes its own screen, appears over the lock screen,
and makes noise. A green permission tick is not the same as knowing.

Two buttons, because they answer different questions:

| button | does | answers |
|---|---|---|
| **Test ring** | full audio at the configured volume, vibration, screen | "will this actually wake me?" |
| **Test ring (silent)** | vibration + screen only, audio suppressed | "does the screen and the remote dismiss work?" — usable at 23:00 without waking the house |

Rules, all of them load-bearing:

- **Touches no latch, no schedule, no override, no nap.** It is not an occurrence. It
  cannot consume tomorrow's alarm, and `nextFire` is unchanged throughout.
- **Fires after 10 s**, so you can lock the phone and put it down first. That delay is
  the whole point: testing from an unlocked, foregrounded app proves nothing about
  4 AM.
- **Caps at 60 s** and stops by itself. It never needs dismissing to end, though dismiss
  works normally — which is also how you test the controller's dismiss button.
- Runs on a **dedicated `PendingIntent` request code**, so it can never overwrite the
  real scheduled fire.
- Logged as `test_ring{silent}` and `test_ring_end`. It is visible in history precisely
  so a test is never mistaken for a real alarm that fired.
- Lives in **Settings**, not on the setup screen. Setup is permissions and hardware.

Triggered from the controller it goes through the same `/v1/test` endpoint as everything
else, so a successful remote test also proves the link end to end.

### Next-alarm override (the thing you'd call "tomorrow")

**Never stored as a word or a date-relative notion.** Stored bound to an occurrence, resolved to an absolute instant at set time:

```
override = { boundOccurrenceId,        // identity of the occurrence it replaces
             kind: TIME | SKIP,
             fireAtMs }                // absolute epoch ms; null when SKIP
```

**Entry is a single absolute time, or SKIP.** (An earlier draft also offered
`+15m/+30m/+1h/+2h` shift buttons; the user removed them as a redundant second way to
do the same thing, and the engine transaction went with them.)

One stored shape, no new state:

- **Set a time** → `fireAtMs` = the first instant ≥ now matching that wall time.
- **Snooze tomorrow** → one tap per step (`+15m`, `+30m`, `+1h`, `+2h`), `fireAtMs` = the bound occurrence's instant
  plus the delta. Tapping again *accumulates* onto the existing override rather than re-basing on the default, so
  `+30m` twice is +1 h and the banner keeps naming the resolved instant.

`SHIFT` is an input method, not a state: it resolves to an absolute `fireAtMs` immediately and is then
indistinguishable from a time you typed. Nothing downstream — binding, clearing, latching, rendering — changes.

Resolution at the moment you set it:
1. Compute `N` = the next unlatched scheduled occurrence (from `defaultAlarmTime`, system zone).
2. `boundOccurrenceId = N.id`.
3. `fireAtMs` per the entry method above.

So at 01:00 with a 04:00 default, choosing 07:00 → fires **today 07:00**, and today's 04:00 is replaced, not added to. At 05:00 (today's already fired) the same choice binds to tomorrow's occurrence → **tomorrow 07:00**.

**It clears when its bound occurrence resolves** — fired, superseded, or latched-missed. Also on `defaultAlarmTime` change and on timezone change. It **never rolls over**: a power-off that eats the occurrence latches it missed and clears the override with it, so you can't wake up two days later to a forgotten 07:00.

**The UI never says "tomorrow," and it names *both* instants:**
`⏰ Sat 26 Sep 23:30 — replaces Sun 27 Sep 04:00`, with a one-tap Clear.
Naming only the new time leaves the confusion intact on the other side of the sentence: at 05:00 Saturday, choosing
23:30 fires Saturday night but binds to **Sunday's** 04:00, leaving a 28-hour gap that must be visible.

**Nap** — `now + minutes`, **1 min to 5 h**, freely entered. Stored as a bare `fireAtMs`. Touches no schedule, no latch, no override, no snap-back. Setting a nap while one is armed **replaces** it. Precedence against a real alarm per §3.

**Changing SSID/passphrase** — explicit confirm: *"This will disconnect the controller. You must re-enter the same values there."* Old AP is torn down, new one started, `stateVersion` bumped, event logged.

## 9. Time & scheduling edge cases

### The latch rule (one rule, one place)

Occurrence identity = `(localDate, source)`. **Every occurrence whose instant is in the past and has no latch
acquires one**, with a reason: `FIRED | SKIPPED | MISSED | SUPERSEDED`. That is the *only* thing that writes a latch
— nothing else, ever. This makes double-fire impossible and, critically, gives `SKIP` and overrides something real to
resolve against.

**Suppression of an occurrence replaced by an override is *derived* from the live override, never stored.** Clearing
an override therefore restores the original occurrence automatically; there is no stored suppression to leak.

**An override clears when its bound occurrence acquires *any* latch** (plus on `defaultAlarmTime` change and on
timezone change). No three-way condition.

Latches with `localDate > today` are **discarded** at every recompute — a forward clock excursion (dead RTC after a
multi-day outage, a bad `setTime()`) must not leave a latch that silently eats one alarm a year later.

### The recompute chokepoint

**Every state transaction ends by calling one `recompute()`** — which discards future-dated latches, latches the
past, resolves the override, picks `nextFire`, and re-arms `setAlarmClock`. There is no enumerated list of triggers,
because an enumerated list in a spec becomes a missing call site in code. Transactions include: any settings patch,
nap set/clear, override set/**clear**, fire, session end (including `CAPPED`), boot, clock sync, timezone change, and
an hourly tick as a backstop only.
- Clock jumps **backwards** past an already-fired occurrence → latch holds, no re-fire.
- Clock jumps **forward** past an unfired occurrence → **split the rule by where you are in the day, not by a
  fixed grace window.** If the jump lands inside a configured *still worth waking me* window (default: local wall
  time before 09:00), **ring**. Otherwise latch MISSED *and* fire the audible three-chirp alert immediately — a
  banner alone would be a silent failure, and on a phone sealed in a box it is invisible by construction.
  *(Earlier drafts latched silently outside a 15-minute grace, reasoning about a 3 p.m. siren. Wrong threat model:
  the realistic trigger is not mid-afternoon, it is a power outage, a dead RTC, or bad NITZ bringing the phone up
  with a wrong clock at 03:00 that NITZ then corrects to 04:20 — squarely in the silent branch, 20 minutes after
  you needed to be awake, in direct contradiction of invariant #4.)*
- DST: compute from local wall time in the system zone. Spring-forward into a non-existent time → fire at the next valid instant. Fall-back ambiguity → fire at the **first** occurrence.
- Timezone change → clears `tomorrow` and `nap` (both are wall-clock-relative and the intent no longer holds), logs it, banners it.

## 10. Nightly arm gate (`armGateTime`, default 22:00)

Checks: **`nextFire != null`**, link healthy, clock synced within 36 h, audio file playable,
**`isPlugged && batteryPct > 50`**, group running, thermal status < `SEVERE`, DND allows alarms, not hibernating,
all permission gates green.

**Never check `isCharging()` and never expect 100 %.** Motorola's Overcharge protection caps at 80 % once plugged in
for three days — permanent, for you — and at the plateau the battery may report *not charging* while still plugged.
Checking `isCharging` would fire three chirps at 22:00 **every night forever**, training you to ignore the one
audible signal the design depends on. Worst-shaped bug available. *(Leave Overcharge protection ON — it's the only
non-root defence against float-charge swelling, and `setAlarmClock` makes the standby-parole question moot.)*

**`nextFire == null` is also an INIT-blocking gate.** This is the highest-value line in the document: it converts the
entire class of "a bug, a bad migration, or a dropped setting ate the schedule" from silent-for-a-week into three
audible chirps at 22:00 while you're awake. `settingsSchemaVersion` is carried explicitly and every migration ships
with a fixture test alongside the DST fixtures.

**Pass → absolutely nothing happens.** No notification, no banner. Silence means healthy.

**Fail → it has to be audible**, because you've said you never look at either phone:
- three short chirps at `STREAM_ALARM` volume on the alarm phone (~2 s total, at 22:00 — you're awake),
- plus the controller's idle screen goes red with the specific failing gate named.

**It never disarms.** A failing gate that silenced the alarm would be the worst bug in the system; a two-second complaint at 22:00 is the correct shape.

**Charger failure does not wait for the 22:00 gate.** The gate is the only charger check in the design and runs
once a day, so a charger that dies at 23:00 gets no warning until it is far too late. The controller already
renders `peer.plugged`; it goes red the moment that field goes false, at any hour, at zero additional cost.

## 11. History

Append-only, on the alarm phone, `Room` table, 90-day retention, cursor-paged over `GET /v1/history`.
**Snooze is deliberately not remotable.** Snoozing requires picking up the box and rotating it 120° — a bathroom snooze button would let you buy 30 s and go back to bed, which defeats the mechanism. The controller can only dismiss.

Events: `boot`, `gate_fail`, `gate_pass`, `arm`, `ring_start`, `snooze`, `dismiss{local|remote}`, `capped`, `superseded`, `nap_dropped`, `missed`, `stale_dismiss_rejected`, `settings_change{who,diff}`, `sync{ok|fail}`, `ap_start|ap_stop|ap_error`, `tz_change`, `nap_set`, `override_set`, `override_cleared{reason}`, `latch{reason}`, `schema_migrated`.

Each carries `at`, `actor` (ALARM | CONTROLLER), and `stateVersion`. The controller can always answer "what happened last night, and who did it?"

---

## 12. Provisioning checklist (per phone, in this order)

Do this once per phone, identically, before installing anything. Deviating here is how you end up with a device you
cannot re-provision later.

1. **Do not sign in to a Google account.** Skip it in the setup wizard. Factory Reset Protection on a carrier-locked
   Moto running Android 16 can trap the setup wizard after a later reset, and you have no ADB to recover with.
   Nothing in this app needs Play Services, an account, or the Play Store.
2. **Do not insert a SIM / do not activate the eSIM** on the alarm phone. It only ever needs home WiFi. (The Cricket
   Device Unlock app re-downloads itself the moment a carrier-locked phone touches WiFi with service active; keeping
   service off keeps that surface smaller. It does *not* remove the Developer Options lock — that is accepted, see
   the Plan.)
3. **Join home WiFi.** Required for NTP, for `./ship`, and for pulling logs. Set it as a metered-off, auto-reconnect
   network.
4. **Settings → System → Date & time → set time automatically: ON**, and **set time zone automatically: ON**. V1 has
   no `setTime()`; Android's own NTP is the clock source and the app only reads it.
5. **Battery → unrestricted** for this app, and turn off any Motorola-specific power saver / "optimized charging"
   prompt that appears. (Overcharge protection capping at 80% is *expected* and the gate accounts for it — do not
   fight it.)
6. **Display:** screen timeout 30 s, adaptive brightness off, brightness ~40%. Do **not** enable always-on display
   on the alarm phone — years of a static clock is burn-in.
7. **Do not set a lockscreen PIN on the alarm phone.** It lives in a locked box; a PIN only adds a way to be locked
   out of your own dismiss button after a reboot. The physical key is the lock.
8. **Sound:** media/alarm volume to max at the OS level, Do Not Disturb off, no Bedtime/Digital Wellbeing schedule.
   The app asserts alarm volume anyway, but a clean baseline makes the DND detector's log readable.
9. **Install:** open Chrome → scan the QR from `./ship` → download the APK → grant *Install unknown apps* for
   Chrome when prompted → install.
10. **First launch:** pick the role. Grant, in order: notifications, exact alarms (`SCHEDULE_EXACT_ALARM`),
    nearby devices + fine location (Wi-Fi Direct group), battery-unrestricted, and on the alarm phone the audio file
    picker. The INIT gate screen deep-links each one; do not proceed past it until every row is green.
11. **Set the password** (alarm phone), and **write it into your password manager immediately.** There is no ADB and
    no recovery path — a forgotten password means a factory reset and redoing this list.
12. **Record the phone's identity** in the README: which `deviceId` tag (`alarm-7f3a`) is which physical phone, and
    where each one lives.

## 13. Dev update channel (same-WiFi self-update)

**Scan the QR exactly once per phone. After that the phones fetch builds themselves.**

```
Mac:    ./ship
          ├── ./gradlew --no-daemon :app:assembleRelease
          ├── writes manifest.json  { versionCode, versionName, sha256, url, builtAt }
          ├── python3 -m http.server 8000   (serves APK + manifest)
          └── dns-sd -R alarmdev _alarmdev._tcp local 8000     # built into macOS, zero deps

Phone:  NsdManager discovers _alarmdev._tcp  →  GET manifest.json
          →  versionCode newer?  →  download APK  →  verify SHA-256
          →  PackageInstaller session  →  system "Update?" dialog  →  one tap
```

**Manual IP is primary; mDNS is the convenience layer.** Earlier drafts had this the other way round. `NsdManager`
has a long bug history — resolutions that never call back while wedging a global busy flag so every later
`resolveService` queues silently, cross-resolution returning the wrong host's IP with the right port, TXT records
missing on resolve — and it is *also* exactly what Local Network Protection gates (resolving `.local` names needs
the permission), so discovery and permission risk are the same layer. None of that is proven on API 36
specifically; treat mDNS as **unproven-reliable rather than proven-broken**, and don't make it load-bearing.

- The debug screen's **manual Mac IP field is the primary mechanism**, and the install QR encodes the Mac's IP so
  the field is populated on day one without typing.
- mDNS (`_alarmdev._tcp`) is tried first as a convenience and updates the stored IP on success. All
  `resolveService` calls go through **one serialized queue with a hard timeout that recreates the listener** on
  expiry, so a wedged resolve cannot poison the channel.
- **macOS side is not dependency-free either.** macOS 26's per-app local-network privacy gates Bonjour, and the
  reported failure mode is that the prompt never appears and the operation simply fails. Runbook: System Settings →
  Privacy & Security → Local Network → enable for Terminal/iTerm. `./ship` **verifies its own advertisement with
  `dns-sd -B` before printing success**, rather than assuming. *(Not verified first-hand on macOS 26 — test it
  before relying on it.)*

**Zero-tap self-update is available in V1, and we deliberately decline it in `live`.** Earlier drafts claimed
silent update requires Device Owner. False on API 36: `SessionParams.setRequireUserAction` waives the dialog when
`USER_ACTION_NOT_REQUIRED` is set, the target is API 34+, the installer declares
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` — which AOSP defines at `protectionLevel="normal"`, granted at install, no
signature and no DO — and the installer is **updating itself**, a standalone qualifying branch that does not care
that Chrome was the original installer of record.

That changes a security argument, so it is now an explicit choice rather than a platform limitation:
**`dev` may use `USER_ACTION_NOT_REQUIRED`; `live` sets `USER_ACTION_REQUIRED` always.** §13's sabotage model rests
on installing-over being a deliberate, visible act, and a silent self-updater on the alarm phone would dissolve it.

*(Related, and worth one line so a future reader doesn't lose an evening: `setRequestUpdateOwnership` can only be
set on **initial** installation — setting it on update is a documented no-op. Because the first install comes from
Chrome, this app can never become its own update owner.)*

**Why this is safe enough, and it's not the checksum:** Android refuses any update not signed with the **same key**. Someone on your WiFi can serve you a hostile APK and the platform will simply reject it — they'd need your keystore. The SHA-256 check is belt-and-braces against a corrupt download. *(Which is one more reason to back up the keystore and never rotate it.)*

**Generate it with `-validity 10950`** (30 years). `keytool`'s default is **90 days**, and the ecosystem baseline
requires validity past 2033-10-22 — both inside this project's horizon. Record the expiry date in the README. Store
the keystore and its password in the password manager **and** on offline media kept with the spare key to the box.
Lose that file and there is no route forward but uninstall/reinstall, which erases the database — at which point
your only recovery is the export in §14.

### Two variants, because the update channel is also a sabotage vector

The easy install path is exactly how 6-AM-you would wreck this. So:

| variant | update channel | debug screen | used for |
|---|---|---|---|
| **`dev`** | yes | yes | Phases 0–6, iteration |
| **`live`** | **compiled out** | no | the build that actually guards your mornings |

Same `applicationId`, so they can't coexist — installing one replaces the other, and *that is a visible event* (below).

**Dev mode is a password-gated toggle that auto-expires after 60 minutes**, and the phone does not listen for
`_alarmdev._tcp` unless it's on. So the ambient state of a phone is *not reachable*: at 06:00 you would need the
password just to open the channel, before you could serve it anything. During early phases no password is set yet, so
it costs nothing.

### Sabotage is made expensive and permanently visible, not impossible

That's the same philosophy as the box: the physical distance enforces, the software refuses to lie.

- **Every install-over is recorded.** `ACTION_MY_PACKAGE_REPLACED` writes
  `package_replaced{fromVersion, toVersion, variant, atMs}` to history, and **both idle screens permanently display
  the running build**: `live 1.4.2 · installed 12 Sep`. Sideload a neutered build at 06:00 and the evidence is on the
  screen every day after.
- **A reinstall preserves the Room DB, the history, and the password.** So installing over the app does *not* clear
  the gated settings or erase the record.
- **Wiping the evidence requires an uninstall**, which also destroys the pairing, the presets, and the history — an
  unmistakable act with a visible aftermath, not a quiet edit. *(Honest caveat: rollback forces you to perform that
  same uninstall yourself — see below — so "an uninstall happened" is evidence of sabotage **or** of a bad build.)*

### Rollback — there isn't a clean one, so plan for the ugly one

A release-signed, non-debuggable APK **cannot be downgraded in place.** `INSTALL_REQUEST_DOWNGRADE` is honoured only
for a debuggable app or a debuggable build; `INSTALL_ALLOW_DOWNGRADE` is `@hide`/system, and `SessionParams` exposes
no downgrade API at all. A lower `versionCode` fails `INSTALL_FAILED_VERSION_DOWNGRADE` (-25).

So **rolling back a bad `live` build at 3 AM = uninstall + reinstall + re-pair + restore**, which destroys the Room
DB, the history, the password, the pairing and every occurrence latch. Consequences:

- `./ship` keeps the **N-1 signed APK** on the Mac and in the milestone archive, always. Never only the newest.
- The §14 export is what makes this survivable; pulling it is step 1 of any rollback.
- **Room must be built with `fallbackToDestructiveMigrationOnDowngrade()`.** An older build opening a newer schema
  throws `IllegalStateException` at open — on the alarm phone that is a crash loop with no ring. Downgrade fixtures
  join the migration test set.
- To sabotage at all you need the Mac, the keystore, a build, and several minutes. At 06:00 that is already a wall,
  and by minute two you're awake — which was the entire point.

### Why this doesn't get fragile

- **The phones are independent.** Each discovers the Mac and pulls its own APK. Neither needs the other to update,
  and per invariant #1 the alarm never needs the controller to *ring*.
- **Three tiers of fallback, each strictly simpler than the last:** mDNS discovery → manual IP on the debug screen →
  QR + Chrome. The bottom tier has no moving parts.
- **A stuck channel is visible.** `manifest.json` is plain HTTP and unsigned. A hostile APK is rejected for
  signature (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so installation is covered — but anyone on the LAN can serve a
  manifest pinning `versionCode` to the current value and **suppress updates indefinitely**, which is
  indistinguishable from "up to date." So the debug screen renders `lastUpdateCheckAtMs` and the last-seen remote
  `versionCode`, as ages, per invariant #6.
- **`./ship` is stateless and idempotent.** It tracks nothing about which phone has which build; re-run it freely.
- **Version skew is handled, not prevented.** `appVersion` is in the snapshot from both sides; a mismatch renders as
  a banner rather than breaking anything. The protocol is versioned (`/v1/`) and unknown JSON fields are ignored, so a
  newer controller talks to an older alarm.
- **Rebuildable years from now — not byte-reproducible.** Byte-identical APKs are aspirational with current
  AGP+R8: baseline-profile generation emits nondeterministically ordered `baseline.prof`/`baseline.profm`, R8's
  ServiceLoader rewriting is nondeterministic, dex output has varied with CPU core count, and there is an open R8
  nondeterminism regression. The spec claims **rebuildable**: pinned toolchain in `libs.versions.toml`, committed
  Gradle wrapper, one build command, keystore backed up, a signed release APK committed for every milestone, and a
  README that is a runbook. If byte-identity is ever wanted, disable baseline-profile generation explicitly and pin
  R8 separately from AGP — but don't claim it until it's measured.

**Two correctness requirements, both easy to miss:**
- **Refuse to update while a ring session is open.** `PackageInstaller` kills the process and takes the foreground service with it.
- **On `ACTION_MY_PACKAGE_REPLACED`: start the foreground service FIRST, then rebuild the alarm set, then write
  the event.** Three corrections to earlier drafts, all verified in AOSP:
  - *Pending alarms are not lost on update.* `AlarmManagerService` short-circuits `ACTION_PACKAGE_REMOVED` when
    `EXTRA_REPLACING` is set — *"This package is being updated; don't kill its alarms."* The old claim that every
    update silently disarms the alarm was wrong.
  - *The real hazard is the exact-alarm re-check.* On the `PACKAGE_ADDED + EXTRA_REPLACING` path the same service
    runs `CHECK_EXACT_ALARM_PERMISSION_ON_UPDATE` and calls `removeExactAlarmsOnPermissionRevoked` if the **new**
    APK lacks `USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM`. Every `setAlarmClock` is then wiped. A runtime gate notices
    far too late, so **`./ship` pre-flight-checks the built manifest for `USE_EXACT_ALARM` and refuses to serve a
    build without it.**
  - *The FGS is the thing that actually dies.* `PackageInstaller` kills the process, taking the Wi-Fi Direct group,
    the HTTP server and the audio path with it. `MY_PACKAGE_REPLACED` is delivered with a temporary background-FGS
    allowlist (`REASON_PACKAGE_REPLACED`, default ~10 s) — that window is the only chance to restart it from the
    background, so `startForegroundService` happens synchronously and first.
- **`MY_PACKAGE_REPLACED` is sent with `flags = 0` — no `FLAG_INCLUDE_STOPPED_PACKAGES`.** A force-stopped app that
  you then update receives **nothing**, and installing over it does not clear the stopped flag. So: after any
  force-stop the app must be **launched by hand**. A reinstall is not a substitute, and §13's "every install-over is
  recorded" does not hold in that one case.

This same hosted-APK-plus-checksum shape is what V2's Device Owner QR provisioning consumes, so none of it is throwaway.

## Repo

**One repo, everything in it** — both roles, shared modules, tooling, spec:

```
alarm-clock/            (github, private)
  core/ data/ service/ ui/ app/
  tools/ship            build + serve + advertise + QR
  gradle/libs.versions.toml
  README.md             THE RUNBOOK — see below
  SPEC.md
```

### Telling the two phones apart in the logs

Every log line carries, on both phones:

```
{ deviceId,      // UUID generated on first run, persisted in Room. NOT Settings.Secure.ANDROID_ID:
                //   that changes on factory reset or signing-key change — and factory reset is the spec's own
                //   password-recovery path (§8), so the log tag would change exactly when you most need to read
                //   logs. ANDROID_ID is seeded in once and kept only as a cross-check. It is also per-user.
  role,          // role AT THE TIME the line was written, because role is switchable
  variant,       // dev | live
  appVersion,
  seq,           // per-device monotonic counter — ordering WITHIN a device, immune to clock changes
  wallMs,        // epoch
  bootNanos }    // elapsedRealtimeNanos, for intra-device durations across clock jumps
```

`role` is per-line rather than per-device on purpose: lines written before a role swap must still read correctly.

**Pulling both at once.** Each phone advertises `_alarmlog._tcp` with `role` and a short device tag in the TXT
record. `tools/logs` enumerates with `dns-sd -B`, pulls both, and streams one merged view with a coloured
`[ALARM]` / `[CTRL]` prefix. A short memorable tag derived from `deviceId` (e.g. `alarm-7f3a`) goes in every prefix,
so the identity is unambiguous even if roles get swapped mid-project.

**The merge has to not lie about ordering.** Two phones with unsynchronised clocks, interleaved by raw wall time,
produces a plausible-looking and wrong causal order — e.g. a dismiss appearing before the ring it answered. Minimal
fix, reusing machinery that already exists: **the alarm phone is the time reference** (it's the source of truth), and
the controller already computes an offset against `serverTimeMs` on every poll for its countdown rendering. So each
controller line carries its raw `wallMs` *plus* the last-known offset, and the merge tool translates controller
timestamps into alarm time. Within a device the tool orders by `seq` and **never reorders**, whatever the clocks say.
`tools/logs` prints a header — `clock skew: CTRL +1.8s vs ALARM` — so you know how far to trust cross-device
interleaving at a glance.

**One README, and it is a runbook, not a description.** For a five-year single-maintainer project this is the
durability mechanism, so it holds only things future-you will need at an unfamiliar moment: the build command;
install and update; how to pull logs (`curl phone:8765/v1/logs`); what every INIT gate means and how to fix it; where
the keystore backup lives; how to recover from a forgotten password (factory reset — stated plainly); the
provisioning checklist; and the deliberate non-goals. Design rationale stays in `SPEC.md`.

## 14. Succession — the ten-year concerns

The alarm phone is the sole source of truth (invariant #3). Over a decade it *will* fail, and none of the following
was addressed anywhere in earlier drafts.

### Backup and restore

`GET /v1/export` returns one signed JSON blob: settings, group SSID + passphrase, password salt+hash, recovery-code
hash, `settingsSchemaVersion`, and the full history. `POST /v1/import` restores it behind the password gate.

- **The controller persists every export to its own disk**, piggybacking the 20 s poll it already makes.
- `tools/backup` pulls it to the Mac and commits it, so the repo is a third copy.
- `android:allowBackup="false"` and explicit `dataExtractionRules` are declared. The default is `true`, which would
  mean that if you ever did sign in to Google, the Room DB, the password hash and the group passphrase would
  silently sync to Drive — wrong in both directions given §12 step 1.
- The controller's `WifiNetworkSpecifier` approval matches on **SSID + security type, ignoring BSSID**, so a
  replacement alarm phone restored with the same credentials re-pairs with **no user action** — but only if those
  credentials survived, which is the entire point of the export.

### Physical maintenance — the parts software cannot see

- **The box must be vented, and the phone must not touch bedding.** A 5200 mAh cell float-charging at 24/7 in a
  sealed enclosure has swelling as its dominant failure mode, and swelling is invisible to every gate: `batteryPct`
  reads 80, `isPlugged` true, `thermalOk` passes, right up until the pouch lifts the display. There is no software
  fix — `BATTERY_PROPERTY_STATE_OF_HEALTH` needs the signature-level `BATTERY_STATS` permission, grantable only via
  ADB, which this project does not have.
- **Annual physical inspection**, dated in the runbook: open the box, check for display lift, a back-cover gap, or
  the phone rocking on a flat surface.
- **Replace the phone or its battery at year 4**, on schedule, not at failure.
- **Proxy signal that needs no permission:** log `BATTERY_PROPERTY_CHARGE_COUNTER` at each 80 % plateau. A falling
  counter at the same reported percentage is capacity fade. Add battery temperature to the snapshot as a trend.
- **The display is a consumable.** The panel is IPS LCD, so this is not OLED burn-in — it is LED **backlight**
  half-life, typically 30–50 k hours against the 87,600 hours in ten years. It dims gradually and silently, and you
  will read it as "the box is dark" long before you call it a fault. It is also a continuous heat source next to
  the battery. **[OPEN — see the question below: keep `FLAG_KEEP_SCREEN_ON` or duty-cycle it.]**
- **Buy a third XT2615-1 now, cold, as the succession unit.** ~$60 against both the swelling risk and the
  gyroscope problem below.

### Replacement hardware may not have a gyroscope

The 2023 and 2024 Moto G Plays shipped **without one**, and `TYPE_ROTATION_VECTOR` exists on gyro-less devices, so
its presence proves nothing. Install this APK on a 2029 Moto G Play and the snooze estimator runs on an accel+mag
fusion where every guard in §7 — the deadband, the zero-rate update — is defined in terms of a `|ω|` that does not
exist. **`gyroscopePresent` (probing `TYPE_GYROSCOPE` explicitly) is a blocking INIT gate and a 22:00 gate row**,
so an unsuitable replacement phone fails loudly at provisioning instead of silently at 04:00.

### Toolchain rot — pinning records what you need, not whether it still exists

Everything below is cheap and must be done now, not in 2033.

- **Mirror the toolchain as GitHub Release assets with recorded SHA-256**: the Gradle distribution zip,
  `cmdline-tools`, `platform-36`, `build-tools;36.0.0`, and a JDK 17 tarball. Point `distributionUrl` at the
  mirror. The wrapper otherwise fetches from `services.gradle.org` and Google's SDK servers at build time; any of
  those 404ing in 2033 ends the build.
- **`./gradlew --write-verification-metadata sha256`, plus a committed offline Maven repo snapshot**, so
  `--offline` builds.
- **Drop the `python3` dependency.** macOS has deprecated bundled scripting runtimes since Catalina and removed
  bundled Python in 12.3; `/usr/bin/python3` is a Command Line Tools shim, not a guarantee. `./ship` uses the JDK's
  own `jwebserver`, making the vendored JDK the only host dependency.
- **`qrencode` is optional** — print the URL if it is absent. It is a Homebrew dep for a cosmetic feature.
- **Commit a signed release APK for every milestone**, so "install a known-good build" never requires a build.

### Dated decision points

| When | What |
|---|---|
| Before v1.0 | Register the limited-distribution developer account; `applicationId` and signing key become immutable |
| Year 4 | Replace the alarm phone's battery or the phone |
| 2028-12-31 | Security support for this SKU ends |
| Annually | Physical inspection; verify the build still reproduces from the mirrors |

### The README is the only durable artifact, and it must be printed

It currently omits every item in this section. Add: device-death recovery, the export/restore procedure, keystore
location and expiry, the developer-verification account and its registered key, the battery and panel replacement
schedule, the 2028 EOL date, the offline-rebuild procedure, and the hibernation gate. Then **print it and put the
paper copy in the box with the spare key and the recovery code** — it otherwise lives in a private GitHub repo
reachable only from a working Mac with working credentials, which are exactly the things that may be missing in the
scenario where you need it.

# Plan (no ADB, no Device Owner — V1)

## Developer verification — handled by registration, not by developer mode

**This is not a blocker, and an earlier draft had the escape hatch backwards.**

Google's developer verification requires apps installed on **certified** Android devices to come from a registered
developer (package name + evidence of the signing key). Timeline: regional deadline 2026-09-30 (Brazil, Indonesia,
Singapore, Thailand), **global 2027+**. Nothing is enforced here today.

The relevant tier is a **limited distribution account**: free, no government ID, up to **20 devices**. Authorization
is per-*device*, via a QR from the Android Developer Console — scan it with the phone, the user confirms the consent
prompt, and **normal APK installation works on that phone from then on, without ADB and without developer mode.**
The "advanced flow" (developer mode → coercion check → reboot → 24 h wait → biometric) exists for installing apps
from developers who never registered. It does not apply to our own registered app.

So the §13 install/update path is unaffected in both eras. Actions:

1. **Register a limited distribution account before shipping v1.0**, with the final `applicationId` and the final
   signing key — both become immutable at registration, which is the only reason this has to happen early.
2. **Scan the console QR once per phone** to authorize them. Record both device registrations in the README, since
   a replacement phone in 2029 needs the same one-time authorization.
3. Nothing here requires Developer Options, which remain carrier-locked and are not needed. What their absence
   genuinely costs is unchanged and already designed around: **logcat** (replaced by `/v1/logs`) and
   **`dpm set-device-owner`** for V2.

Sources: developer.android.com/developer-verification/guides/limited-distribution ·
support.google.com/android-developer-console/answer/17131204

## What no-ADB actually costs, and what it doesn't

**Doesn't block anything in V1.** Install, update, test, and diagnose all work over WiFi.

| Lost | Replacement |
|---|---|
| `adb install` | QR → LAN URL → Chrome → APK. Faster than a cable once scripted. |
| `logcat` | **The app is its own instrument.** Both phones serve `GET /v1/logs` on :8765; you `curl` them from the Mac over home WiFi. Works even when the P2P link is the thing that's broken. |
| Crash traces | Uncaught-exception handler persists the stack to disk and surfaces it on the INIT screen at next launch. |
| `dpm set-device-owner` | Deferred to V2. See below for what that costs. |
| `DevicePolicyManager.setTime()` | **Not needed — and this is simpler.** Leave `AUTO_TIME` on and let Android run NTP itself whenever the phone is on home WiFi. The app only *reads* the clock: `SystemClock.currentNetworkTimeClock()` (API 33+) gives network time and throws when unavailable, which is exactly the `clock.lastSyncOkMs` / `staleBy` signal the snapshot needs. |
| Auto-granting permissions | The INIT gate screen already walks you through each one by hand, once. |

**What deferring Device Owner genuinely costs, stated plainly:**
- **The anti-distraction property is gone in V1.** No lock task, no kiosk, no uninstalled browser. This is a normal Android phone with Chrome on it, in a locked box. Mitigate by signing into nothing and disabling Chrome in Settings — but do not pretend it's solved. It was a primary reason for this architecture, and it returns in V2.
- **Force-stop remains an open hole** (§3). The controller's fallback nag is the only detector.
- **App hibernation is the single most likely total kill in V1, and the zero-touch design actively causes it.**
  Earlier drafts called it "can reset permissions after months of no interaction," which badly understates it.
  Hibernation's timer is driven *only by user interaction*: running a foreground service, holding alarms, and
  receiving broadcasts explicitly **do not** reset it. Invariants #5 and #7 say you will never touch the alarm
  phone. After a few months the system hibernates the app, which the docs describe as *similar to force-stopping*
  — and on Android 15+ the stopped state cancels every `PendingIntent`. The alarm dies, the 22:00 chirp cannot
  warn you because the chirp is itself a cancelled alarm, and the INIT gate cannot render because the app is
  stopped. The §3 table's "force-stop: Low probability, requires deliberate action" does not apply — this is a
  force-stop nobody performs.
  **Fix: hard INIT gate on `PackageManagerCompat.getUnusedAppRestrictionsStatus() == DISABLED`**, driving the user
  through `IntentCompat.createManageUnusedAppRestrictionsIntent()` (must be `startActivityForResult`), re-checked
  at every 22:00 gate, plus the `wasForceStopped()` event from §3. It also goes in the provisioning checklist and
  the runbook. *(The always-on Activity in §6 probably keeps `lastTimeVisible` fresh and may be masking this —
  that is an accident, not a mechanism, and it breaks on any reboot-without-unlock, crash, or screen-off.)*
- **`ACCESS_LOCAL_NETWORK` and `POST_NOTIFICATIONS` are runtime permissions**, so they are reset by hibernation and
  revocable by hand. Request `ACCESS_LOCAL_NETWORK` at runtime on both roles rather than treating it as a manifest
  line, and assert both with `checkSelfPermission` at every 22:00 run, not only at INIT. Without
  `POST_NOTIFICATIONS` the FGS notification — and therefore the full-screen intent — is suppressed.
  *(Unresolved conflict between reviewers: one found LNP surfaces `EPERM`/`ECONNABORTED`, the other that denied
  inbound TCP silently times out. Log both shapes and treat a hang as diagnostic, not as proof of health, until
  measured on-device in the Phase-3 spike.)*
- **The full-screen ring UI is specced nowhere.** The gate row exists; the mechanism does not. Needs
  `USE_FULL_SCREEN_INTENT` (auto-granted only for alarm/calling-category apps on 14+ — verify with
  `NotificationManager.canUseFullScreenIntent()`), plus `setShowWhenLocked(true)` and `setTurnScreenOn(true)` on
  the ring Activity.
- **Invariant #1 has no architectural enforcement.** §5 puts `:core`, the Wi-Fi Direct group, the HTTP server and
  audio in one `START_STICKY` process, so an ANR or deadlock in an HTTP handler or a `NetworkCallback` stops the
  audio thread too. One binding rule: **audio and session-state run on dedicated threads, and no ring-path call may
  block on network I/O or on a lock held by network code.**
- **A power outage that drains the battery leaves the phone off, and it does not auto-boot when mains returns** —
  it shows the charging animation. Nothing in the app can detect this. The controller's liveness nag (now
  reachable, per the fixed OR condition) is the only cover.

## Install / update loop

```
./ship          # assembleRelease → serve on :8000 → print a QR in the terminal
```

The script: `./gradlew --no-daemon :app:assembleRelease`, then `python3 -m http.server 8000` in the APK output dir, then a QR of `http://<mac-lan-ip>:8000/app-release.apk` rendered as terminal blocks. **Scan from either phone, tap the download, tap install.** Both phones can pull the same build at once. First time per phone: allow "Install unknown apps" for Chrome.

Needs `brew install qrencode` (not currently installed) — `qrencode -t ANSIUTF8 "$URL"`.

Milestones additionally go to a GitHub Release. **The repo is private, so release asset URLs are not browser-fetchable without auth** — publish milestone APKs either from a separate public `alarm-clock-releases` repo or by having `./ship` serve the archived APK from the Mac. (Earlier drafts claimed a public repo; the repo is private and that is the version that holds.), which gives durable artifacts and a stable QR that survives your Mac's IP changing. Same key forever: **back up the keystore**, since a signing-key change forces uninstall/reinstall.

## Role selection

**One APK on both phones.** Role is chosen on first launch — a two-button screen, ALARM or CONTROLLER — not baked into two build variants, because the architecture's whole value is that everything above `StateClient` is shared.

**Role is a password-gated setting** (§8), and it is the most dangerous one in the app: flipping the alarm phone to CONTROLLER is a one-tap total silencer, and every log entry would look legal. So on top of the password:
- rejected outright while a ring session is open,
- requires a typed confirmation, not a toggle,
- clears the pairing and forces re-pair, and
- writes a `role_changed` event.

## Phases

Each ends in something you can sleep next to. **Phase 1 alone is already a better alarm clock than what you have.**

| # | Phase | Gate to pass |
|---|---|---|
| 0 | Skeleton + **instrumentation** + **dev update channel** (§13): 4 modules, pinned `libs.versions.toml`, `./ship`, role picker, INIT gates, event log, crash handler, `/v1/logs`, debug screen | `./ship` → QR → installs on both. Second `./ship` → both phones offer the update **without a QR**. `curl phone:8765/v1/logs` works. A deliberate crash shows its stack next launch |
| 1 | **Offline alarm.** Schedule, ring, local dismiss, resurrection, fallback audio chain, latches, DST/clock-jump, asserted volume, DND gates | `am kill` equivalent (force-close from Recents) mid-ring → **resumes**. Reboot mid-ring → resumes. Sleep on it 7 nights |
| 2 | Snooze: quaternion path integral + deadband + zero-rate bias + decay, instrument widget, session `endsBy` | **Ring untouched for a full 60 min — zero self-snoozes.** Then snooze 20× ; cap fires at 60 min |
| 3 | **Transport spike.** Wi-Fi Direct group w/ fixed creds; controller joins; `/v1/state` renders. Run with `RESTRICT_LOCAL_NETWORK` compat on | Survives independent reboots of each side; 24 h heartbeat log, zero unexplained gaps |
| 4 | Remote dismiss: `ringId`, idempotency, the three distinct outcomes, toast | Kill WiFi mid-dismiss → retry succeeds, no double-dismiss. Replay yesterday's dismiss → 409, logged |
| 5 | Settings sync + password gate, nap, override, history, `ifVersion` | Edit the same field from both sides at once → one wins, both converge, both show it |
| 6 | Arm gate, clock-staleness reporting, observability polish | Unplug the controller at 21:59 → you're told at 22:00 |
| — | **V2 (not now):** Device Owner via QR provisioning, lock task, kiosk, uninstall the browser | Requires a phone with no carrier DPC and Developer Options available |

## Adversarial review — what actually kills this

| Failure | Why it's plausible | Mitigation | How you'd notice |
|---|---|---|---|
| **Your own bug in the fire/latch/DST path** — silent, no ring | By far #1. Zero field-hardening vs. a commercial clock's millions of device-nights | Occurrence latches, pure scheduling functions with unit tests over DST/leap/jump fixtures, **arm gate**, **controller fallback nag** (reduced form — see §11; it explicitly does *not* run its own scheduler) | Arm gate at 22:00; controller's independent nag at 04:01 |
| ~~LOHS with fixed credentials~~ — **confirmed impossible**, and it reports success | Verified against AOSP API signature files and service source | Wi-Fi Direct autonomous group is now primary (§1). **Ringing never depended on it**, which is why this cost a Phase-3 rewrite and nothing else | Already found, before you wrote a line |
| Process death mid-ring, no resume | Real; OOM happens | Persisted session + 60 s `setAlarmClock` watchdog + boot resume | Phase 1 gate tests this with `am kill` |
| **Force-stop** → every pending intent cancelled, silent forever | Low (requires deliberate action) but total | Accepted OS hole. V2 lock task removes the UI; controller nag is the only detector | Controller nag |
| **Snooze self-triggers from gyro bias** | Was near-certain: 120° of drift in ~4 min stationary | Quaternion path integral + 15 °/s deadband + zero-rate bias + 3 s decay | Phase 2 gate: 60 min untouched, zero snoozes |
| **DND mutes `STREAM_ALARM`**; volume writes no-op silently | Default is permissive, but any zen rule can do it | Nightly gate on consolidated policy + filter + `isVolumeFixed`; conditional write with logged mismatch; vibration load-bearing | 22:00 chirps |
| **Android 17 Local Network Protection** → inbound TCP times out | Unknown — this SKU may never get 17 (security EOL 2028-12-31); free to cover either way | Declare `ACCESS_LOCAL_NETWORK`; gate row; test now via compat flag | Phase 3, today |
| Stale/replayed dismiss kills a future alarm | Retries, queued requests, clock weirdness | `ringId` required on every dismiss; 409 + log otherwise | `stale_dismiss_rejected` in history |
| Settings used as a self-sabotage vector from bed | Volume→0, maxRing→1 | `alarmVolumePercent` floored at 50 client- *and* server-side; `maxRingMinutes` floored at 5. And the controller is on a wall you must walk to, so this is weaker than it looks anyway | `settings_change` in history, with actor |
| Trapped by an unstoppable ring | Dead link at 04:00 | Local dismiss + `maxRingMinutes` cap + key at a known distance | By design |
| Toolchain rot: can't rebuild in 2028 | Certain, if unpinned | Pin AGP/Kotlin/SDK versions + a committed note saying why; keep a signed APK in the repo | Only bites when you try to rebuild |
| `ifVersion` thrash between two editors | Low — one human | 409 + fresh snapshot + re-render | Phase 5 gate |
| Prepaid firmware carrier entitlement check blocks tethering | Documented on prepaid Motos | Irrelevant — LOHS is not tethering and doesn't touch that toggle | Phase 3 spike |

### The controller's fallback nag (reduced form — no second scheduler)

The controller does **not** compute its own schedule. A second implementation of the fire/latch/DST path, on the one
device with no clock sync and a stale settings cache, would double the surface of the #1 risk in order to cover it —
and its likeliest outcome is an unstoppable-from-bed nag on a night the alarm was deliberately silent.

Instead the controller nags iff **`(1 AND 2)` OR `(2 AND 3)`**:
1. the alarm phone's own last-published `nextFire.atMs` is in the past by > 90 s — *silent failure*
2. last-known `override != SKIP` — *the night was not deliberately silent*
3. no successful contact for > 20 min — *liveness*

**These were previously ANDed together, which made the headline case unreachable:** if the alarm phone is "alive
and polling fine but silently didn't ring," condition 3 is false by construction, so the conjunction could never
fire. Silent-failure (1) and liveness (3) are two independent alarms that happen to share the (2) guard; they must
never be conjoined. Nothing depends on the controller's clock either way.

**All relative rendering on the controller** — countdowns, contact age, everything — is extrapolated from
`serverTimeMs` plus the local `elapsedRealtime` delta. Absolute times come from the alarm phone, so deltas must too,
or a skewed controller clock makes the countdown lie while looking authoritative.
