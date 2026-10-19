# PLAN — Round 32: the due-instant race, and the gate becomes testable

The sentinel plan (rounds 28–31) shipped and is recorded in IMPROVEMENTS.md. This file is now the
plan for round 32. Three items, in descending order of how badly they can hurt the owner.

## 0. Measurement, from the live phone rather than inference

Round 31 closed with one stated unknown: *"`ap_start` frequency has not been counted."* Counted now,
from 2000 events over 7.31 days on the alarm phone (`:8766/v1/history`):

| event | count | per day |
|---|---|---|
| `ap_start` | 1 | **0.14** |
| `ap_error`, `ap_stale_group`, `p2p_lost` | 0 | 0 |
| `p2p_connection_changed` | 559 | 76.5 (the 30-min probe, 2 per cycle) |
| `missed` + `fire_now_after_clock_jump` | 2 | 0.27 |
| `db_unavailable` | 15 | 2.1 (CE locked before first unlock, expected) |

The group restarts about **once a week**. So `own_link_unstable` — which refuses to block while this
phone's own group has been up for less time than the peer's last sighting — costs at most one false
ring per week, and only if the owner is away in that exact window. The guard is cheap and stays.

`awayAfterSeconds` keeps its 10-minute default: `peer_gap` has only existed since 0.2.89 was
installed on 18 Oct and has logged nothing, so there is still no distribution to tune from, and
inventing one from a day of silence would be the same mistake round 31 called out.

## 1. The due-instant MISSED race — real, observed twice, fix first

Two mornings recorded this, 10-13 05:00:00 and 10-17 08:00:00, alarm time exactly those:

```
08:00:00  latch   {occurrence: 2026-10-17/SCHEDULED, reason: MISSED}
08:00:00  missed
08:00:00  fire_now_after_clock_jump {source: SCHEDULED}
08:00:00  ring_start
08:00:01  alarm_fired            <- the real broadcast, one second later
08:00:01  trigger_absorbed
```

I previously credited the `:00:20` hourly-tick offset with fixing this. That is wrong: the offset
shipped in **Round 19** (`15968cc`), long before both incidents. The cause is in `Engine.recompute`
step 2, and it is not about which caller runs:

```kotlin
if (at > now) continue                       // not in the past yet
if (st.session?.occurrenceId == id) continue // currently ringing for it
if (at < aliveSince) continue                // already handled, or predates us
```

At the due instant `at <= now`, and the session is not open yet because `ACTION_FIRE` arrives a beat
later, and `aliveSince` is the *previous* recompute so `at > aliveSince`. All three guards pass and
the occurrence is latched **MISSED** while its broadcast is still in flight. Any recompute landing in
that sub-second window loses the race. It happens every morning; it was observed to land twice.

**Why it is benign today and will not stay benign.** `fireNow` rings immediately, so the owner is
woken either way — that is why this never showed up as a complaint. But:

- History lies. `missed` is recorded for an alarm that rang on time, and `missed` is the one signal
  that must stay honest.
- It drives every raced morning down the `fireNow` path instead of the `ACTION_FIRE` path — and
  `fireNow` has no gate (defect 2 below).

I first wrote that the latch would corrupt `occurrenceBeingFired`. That is wrong and worth recording
as wrong: it reads `latch != null && latch.reason != LatchReason.MISSED`, so a MISSED latch is
deliberately allowed through. Checked rather than assumed.

## 1b. Second defect: the `fireNow` path opens a session with no gate

`Svc.apply` rings directly:

```kotlin
if (alsoFire && r.fireNow != null && state.session == null && state.settings.role == Role.ALARM) {
    log("fire_now_after_clock_jump", "source" to r.fireNow!!.name)
    urgent.execute { RingService.start(app) }      // <- no ringBlockDecision() anywhere
}
```

Round 28 recorded that I had verified "of seven `RingService.start`/`forceSession` call sites, only
two open new sessions", and the gate went to one of them. This is the other one, and it was missed.
So with a block armed and the owner genuinely away, an alarm that was missed during an outage rings
anyway. The direction is safe — it rings rather than stays silent — but the feature does not do what
the setting says, and on a raced morning (defect 1) that is *every* morning.

**Fix: one entry point, and the trigger is marked rather than re-derived.**

```kotlin
fun openSession(trigger: OccurrenceSource) {
    if (session != null) { RingService.start(app); return }        // already ringing; absorbed
    if (trigger != OccurrenceSource.NAP) ringBlockDecision()?.let { return suppressRing(it) }
    RingService.start(app)
}
```

`Receivers` passes the source the scheduler wrote when it armed (`de.nextFireSource` — the mark, set
at origin), `apply` passes `r.fireNow`. Nothing else may start a new session. The routing decision
itself goes to `:core` as a pure function so the ordering — session before nap before gate, the
exact ordering round 31 got wrong — is covered by tests.

**The fix, and why a constant is unavoidable here.** Telling "due 50 ms ago, broadcast in flight"
from "due 50 ms ago, broadcast lost" is not possible without waiting — `lastAliveMs` is a lower
bound on liveness, not a measure of it, so it cannot distinguish alive-and-idle from dead. The wait
is irreducible, so the only question is its length:

```kotlin
/**
 * How long after its instant an occurrence is still PENDING rather than missed.
 *
 * ACTION_FIRE is `setAlarmClock` -- Doze-exempt and documented exact -- and measured delivery on
 * the owner's phone was 1 s (`latch` 08:00:00 / `alarm_fired` 08:00:01, 17 Oct). This is 60x that.
 * The cost of widening it is bounded: an alarm whose broadcast is genuinely lost rings at the next
 * recompute, which the hourly tick guarantees within the hour either way, so a minute of grace adds
 * a minute to a recovery that is already up to an hour.
 */
const val FIRE_DELIVERY_GRACE_MS = 60_000L
```

Applied at both places that infer a miss from the clock: the backward walk (`if (at > now -
FIRE_DELIVERY_GRACE_MS) continue`) and the moved-override block (`ov.fireAtMs <= now -
FIRE_DELIVERY_GRACE_MS`).

**Regression tests, reproducing the two mornings exactly.** Recompute at the due instant with no
session open must latch nothing and set no `fireNow`; at `at + 61 s` it must latch MISSED and set
`fireNow`. Revert the one-line change and both go red — that is the gate each must pass.

## 2. The gate moves into `:core`, which is where it can be tested

`core/build.gradle.kts` states the architecture: *":core is pure Kotlin/JVM with ZERO Android
dependencies. That is the point: the entire state machine runs in unit tests on the Mac in
seconds."* The ring gate was built in `:service` instead, so:

- `service` is **3270 lines with zero tests**, and every alarm-silencing decision lives there.
- The whole suite is one file, `EngineTest.kt`, over `:core`.
- `ringBlockReason`, `awayReasonFor` and `roleAvailableTo` — the three functions that can keep the
  alarm silent — have never been executed by a test.

So move the decision, not the plumbing. `Svc.Presence` becomes `core.PeerReport` (the type moves
wholesale; there is no second copy), and `core/Gate.kt` gains pure functions taking `nowElapsed` and
`linkUpSinceElapsed` as parameters instead of reading `SystemClock` and `Group`:

```kotlin
object RingGate {
    const val PRESENT_WINDOW_MS = 120_000L
    fun awayFloorMs(s: Settings): Long
    fun armedRoles(s: Settings): List<Role>
    fun awayReason(role, p: PeerReport?, s, nowElapsed, linkUpSinceElapsed): Pair<String?, String>
    fun reason(s, nowElapsed, linkUpSinceElapsed, peer: (Role) -> PeerReport?): Pair<String?, String?>
    fun roleAvailable(holder: PeerReport?, deviceId: String, nowElapsed: Long): Boolean
}
```

`Svc` keeps one line per function, supplying `SystemClock.elapsedRealtime()` and
`Group.listenerUpSinceElapsed`. Dependencies still point one way: `service` → `core`.

This is a move, not a new abstraction — no behaviour changes, and nothing is added that did not
already exist. The payoff is that every fail-open branch becomes a test.

**Also pure, for the same reason:** the `ACTION_FIRE` routing (`session open` → `nap` → `gate`),
which is where round 31 shipped the "blocked nap logged `ring_blocked` then rang anyway" bug, and
the p2p→wifi path choice in `HttpStateClient.request`, which is where the 409 bug made a role
rejection render as a dead link. Both are three-line decisions wrapped in untestable I/O; both get
a pure function and tests.

### Tests to add, each with the defect it catches named

| test | mutant that must turn it red |
|---|---|
| never-seen peer rings | `null` presence returning "away" |
| unknown battery (`-1`) rings | `in 0..19` instead of `< 20` |
| peer that never sent `powerSave` rings | trusting a missing field |
| peer in power save rings | dropping the `powerSave` veto |
| peer with failing gates rings | dropping the `missingGates` veto |
| present 119 s with a 0 s setting rings | floor below `PRESENT_WINDOW_MS` |
| absent past the floor blocks | the whole feature being inert |
| own link down blocks nothing | dropping the `listenerUpSinceElapsed` guard |
| own link up *after* the peer's sighting blocks nothing | `>=` instead of `>` |
| both settings off never blocks | arming on the wrong boolean |
| either setting alone blocks on its own role | AND instead of OR |
| role slot free once the holder is silent 120 s | a reinstall locking the role out |
| nap is exempt from the gate | gate before nap in the routing |
| an open session skips the gate | a mid-ring recheck silencing a live alarm |
| 409 without a snapshot is `RoleTaken` | reading `snapshot` unconditionally |
| only `Transport` retries the other path | retrying a considered 403/409 |

## 3. Deferred, and why — not silently dropped

**The p2p→wifi path choice stays in `HttpStateClient`.** It was on this plan; I am not doing it.
The ordering half (`p2p` then `wifi`) is too thin to test without the test restating the code, and
the half that is worth testing — only `ClientError.Transport` retries the other path, so a 403 or a
409 is never re-asked — needs either `org.json` added as a test dependency or a test-only seam in
front of `attempt`. Neither is worth it against the two defects above, which can silence an alarm
outright. The retry rule is one `catch` clause with the reasoning written beside it. Recorded here
rather than quietly cut.

## 3b. Not in scope, deliberately

- **No new dependency.** Robolectric would let `:service` be tested in place, but moving the
  decision to `:core` gets the same coverage with nothing added.
- **No change to the away defaults.** Both blocks stay off until the owner turns one on.
- **No sentinel end-to-end verification.** It needs a third real phone; an emulator has no Wi-Fi
  Direct group, so `listenerUpSinceElapsed == 0` vetoes every block and the test would prove nothing.
  Stated, not glossed.

## 4. Done means

`./gradlew test` green with the new tests, each mutation-proved; 0.2.90 built, signed, downloaded
over HTTP with a matching hash; served from mac-work-2; adversarially reviewed against
`~/minh-agent-doctrines/code-principles.md`.
