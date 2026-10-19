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

**Fix: one entry point, and the trigger is routed rather than re-derived.**

```kotlin
fun openSession(trigger: OccurrenceSource): Boolean =
    when (val action = RingGate.fireAction(session != null, trigger, ::ringBlockDecision)) {
        FireAction.Absorb, FireAction.Ring -> { RingService.ring(app, trigger); true }
        is FireAction.Suppress -> { runCatching { suppressRing(action.reason) }; false }
    }
```

`Receivers` passes `Scheduler.sourceOf(intent)` and `apply` passes `r.fireNow`. Nothing else may
start a new session, and the return value tells the receiver whether the wake lock was handed off.
The routing decision itself goes to `:core` as a pure function so the ordering — session before nap
before gate, the exact ordering round 31 got wrong — is covered by tests.

**Where the source comes from was wrong in the first draft of this plan.** It said `de.nextFireSource`
was "the mark, set at origin". It is not: it is the DE mirror, rewritten by every `apply`. Round 32e
below replaces it with a source carried on the armed alarm itself; this paragraph is kept only
because the wrong claim was load-bearing in the first version of the fix.

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

## 5. What the adversarial review found, and what it leaves open

Reviewed against `code-principles.md` by a subagent given the diff and told to falsify this plan.
It found one **critical defect I had introduced**, which is now fixed and has a state-threaded test:

> A recompute inside the grace window skipped the occurrence without latching it, then advanced
> `lastAliveMs` past it. On every later recompute `if (at < aliveSince) continue` ran *before* the
> grace check, so the occurrence read as "already handled" forever: no latch, no `fireNow`, no ring,
> nothing in history. A reboot at 04:00:30 lost the alarm for the whole day.

Fixed two ways, because one alone is not enough: liveness is now held strictly below a pending
occurrence so a later recompute can still reach it, **and** a pending occurrence stays `nextFire` at
its own past instant, so it is re-armed and delivered at once rather than waiting for a recompute
that might be an hour away. That second half also answers the review's follow-up: for the default
on-the-hour alarm the `:00:20` tick falls *inside* the window, so "recovery within the hour" would
have been wrong.

The review also confirmed what I had argued about re-arming during the window: it cannot recall a
broadcast already sent, because `FLAG_UPDATE_CURRENT` keeps the same PendingIntent and nothing
cancels it. The hole was not there — it was that the engine assumed a broadcast was in flight
without knowing one had ever been armed, which is false after a reboot.

And it caught my `own_link_unstable` test claiming to kill a `>`/`>=` mutant it could not: both
operators rang for the inputs I used. There is now a boundary test and a mutant for it.

### CLOSED in round 32e — the trigger source now travels with the alarm

`Receivers` and `RingService` both read `Svc.de.nextFireSource`, which is **the DE mirror, rewritten
by every `apply`** — not a mark written when that alarm was armed. So a recompute between arming and
delivery can relabel the trigger. Concretely, a nap firing after a process death:

1. `Boot.ensure` → `recompute("init:de")`; step 4 drops the elapsed nap, so the mirror now says
   SCHEDULED.
2. `openSession(SCHEDULED)` consults the gate, so a nap can be blocked — which the nap exemption
   exists to prevent.
3. If the gate passes, `occurrenceBeingFired` returns null because today's alarm already rang, so
   the result is `trigger_stale` and **the nap never rings**.

Pre-existing, and not made worse by the earlier commits in this round: the old code read the same
mirror. I deferred it so it would not ride on top of three other changes to the fire path — and then
did it once those were reviewed clean, because the reason to defer was spent and the defect was
still closable.

**The fix.** `Scheduler.EXTRA_SOURCE` carries the occurrence source on the armed alarm itself:
`pi()` takes an optional source, `arm()` passes `next.source`, `armFireRetry` passes the source it
is retrying, and `Scheduler.sourceOf(intent)` is the single reader for both `Receivers` and
`RingService`. `RingService.start` carries it on the service intent too.

The non-obvious part, written beside the code: **extras are not part of `PendingIntent` equality**,
so `FLAG_UPDATE_CURRENT` is what makes a re-arm's source take effect — and is also why cancelling
with no source still matches an alarm armed with one. A broadcast already dispatched carries the
extras it was sent with, which is exactly why this works: the recompute inside the same `onReceive`
rewrites the mirror but cannot touch the Intent already in hand.

`sourceOf` falls back to the mirror only for an alarm armed by a build that carried no source.
I first wrote that this was "once per install"; review falsified that, and the precise statement is
narrower and sharper: `MY_PACKAGE_REPLACED` (indeed any `Boot.ensure`) re-arms 1001 with
`FLAG_UPDATE_CURRENT` and so updates the pending alarm's extras, which means the fallback needs the
old sourceless alarm to be *the first thing that wakes the new process*. In exactly that case the
init recompute has already rewritten the mirror, so the mirror is **actively wrong rather than merely
old** — a pending nap is relabelled. It happens once and it is logged as `fire_source_from_mirror`,
which is why a silent fallback here would have been unacceptable.

**Proof it mattered**, as a pure engine test: with the nap elapsed and dropped from state,
`onTrigger(NAP)` opens a session and rings, while `onTrigger(SCHEDULED)` — the relabelling — returns
`trigger_stale` with no session. `onTrigger` builds a NAP occurrence directly instead of consulting
`occurrenceBeingFired`, which is why the carried mark is sufficient even though the engine no longer
holds the nap.

### Second pass: the override path had only half the fix

The reviewer, asked to verify rather than trust, found the scheduled path sound and the moved-alarm
path still an hour late on a reboot — `pending` was set only in the backward walk, which now skips
moved occurrences, and `overrideFire` needs `fireAtMs > now`. The moved block now produces its own
pending candidate. It also found `Svc`'s latch merge kept Room's copy over memory's, contradicting
its own comment, which only became dangerous once a latch could be replaced. Both fixed, both with
a test and a mutant.

It confirmed by walking them: arming a past instant cannot loop, no reader of `nextFire` misbehaves
on a past instant, and MISSED is the only latch `occurrenceBeingFired` tolerates so replacing it is
safe.

### Third pass: clean, with two residuals left on purpose

> "I found nothing in Round 32c that can stop a ring, delay one, or cause a wrong block. The fix is
> sound."

It checked that `TOMORROW_OVERRIDE` is the right pending source (nothing anywhere distinguishes it
from SCHEDULED except `== NAP` tests, and `occurrenceBeingFired`'s override branch binds the moved
occurrence); that the relaxed outer condition writes no latch inside the window so step 3 cannot
clear the override; that `pending` and `overrideFire` are mutually exclusive by their own
conditions; that nothing else loads Room's latches (`allowBackup="false"`, no import path); and that
the liveness clamp can only re-expose occurrences inside the same minute.

Two residuals, both **pre-existing**, both requiring a process death inside a window of
milliseconds, and both failing toward **ringing** rather than silence. Recorded rather than fixed,
because fixing either means touching the fire path again for a case that cannot silence an alarm:

1. `forceSession` builds its occurrence from the trigger instead of from `occurrenceBeingFired`, so
   `(today, TOMORROW_OVERRIDE)` never equals a bound `(day, SCHEDULED)`. If `onTrigger` throws and
   `forceSession` takes over, the moved block can re-arm the past instant for up to 60 s while the
   alarm is already ringing. It needs an engine exception and it stops on its own.
2. `de.mirror` (SharedPreferences `apply()`) and the Room save (`io.execute`) are both async. If
   Room's write lands and the mirror's does not before the process dies, memory-first brings back
   MISSED over SKIPPED — the mirror image of the case fixed in 32c. Ordering cannot fix it;
   memory-first is still correct for the gap the comment describes, between boot and first unlock.

## 6. Round 32f — what the 32e review turned up

No new ring-loss path in 32e itself. Two older ones, both fixed:

1. **A test ring could silence a ringing nap.** The test branch was sniffed (`session == null &&
   de.pendingTest`), so a nap that began ringing in the milliseconds before the service started made
   the test fall through to the real trigger path, supersede the live NAP session, hit
   `trigger_stale`, and tear down. `ACTION_TEST` now marks it at origin; a test arriving while a
   session is open is refused. `de.pendingTest` is deleted.
2. **The delivery-window race was still open for naps.** Step 4 dropped a nap at `fireAtMs <= now`,
   so a recompute between the nap's instant and its dispatch dropped it and re-armed for tomorrow.
   Same race, same grace; `napFire` already keeps it armed.

Plus: `RingService.start(ctx, resume, source)` split into `ring`/`resume`/`startTest` (the boolean
made two combinations meaningless and one fabricate a mark — a failed test start armed a real FIRE
retry); `require(action != ACTION_FIRE || source != null)`; two dead accessors deleted.

### Still open, deliberately

- `forceSession` builds its occurrence from the trigger rather than from `occurrenceBeingFired`.
  Needs an engine exception to reach, self-limiting, fails toward ringing.
- `de.mirror` and the Room save are both async, so a process death between them can reload MISSED
  over SKIPPED. Ordering cannot fix it; it can only make the alarm ring.
- In `onTrigger`'s supersede branch, an `occId == null` result drops the nap session, so a stale real
  trigger can kill a live nap. Reachable only through the test-ring sequence now fixed above; worth
  revisiting if it is ever reachable another way.

## 7. Round 32h — the last two, both found by review

1. **The refused test was still a ring-stopper.** `testRing` sets `testUntilMs` before starting the
   service and the heartbeat tests it first, so a refusal that left the window set ran the test path
   over a live ring for 60 s and then tore it down. The refusal clears the window.
2. **The nap grace invented a fire storm.** An absorbed NAP trigger left `st.nap` set; once an
   elapsed nap was no longer dropped immediately, `napFire` stayed `nextFire` at a past instant and
   re-armed in a loop for the window, recreating the ring screen each round. A NAP trigger now
   consumes the nap once, at the top of `onTrigger` — the delivery consumes its own mark.

Count for this round: **eleven defects**, four of them mine, three of those being a fix that
re-introduced the defect it was fixing by another route. Every one of the three was in a guard that
returned early from a shared path, and every one read correct in isolation.

### Deferred to a later round: the nap/alarm reboot asymmetry

A scheduled alarm the phone reboots through rings, via `pending` staying armed and then `fireNow`.
A nap does not: if no recompute runs inside its 60 s window — a reboot or force-stop across the nap
instant — the boot recompute drops it silently, with no `missed` event and no `fireNow`. This loss
predates the delivery window and the window did not move it; it is an asymmetry rather than a
regression, and closing it means deciding whether a nap missed by an outage should ring late at all,
which is a product question and not a bug fix. Not mixed into this round.
