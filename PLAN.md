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

### Deferred to a later round: A KNOWN RING LOSS — a nap the phone reboots through

Stated as what it is. I first wrote this up as "a product question", and review rejected that
framing, correctly: **the engine has already answered the question.** `fireNow`'s rule is "an alarm
that should have gone off and did not, rings now", and the timezone comment says dropping a nap "is
the one outcome that loses an alarm outright". A nap rebooted through is an alarm that should have
gone off and did not.

A scheduled alarm the phone reboots through rings, via `pending` staying armed and then `fireNow`. A
nap does not: if no recompute runs inside its 60 s delivery window — a reboot or force-stop across
the nap instant — the boot recompute drops it, and **silently**: no `missed` event, no `fireNow`,
nothing in history. That silence is the no-fallbacks violation, independent of what the right
behaviour turns out to be: the failure is indistinguishable from success.

The forward-clock-jump case (`TIME_SET` racing AlarmManager's overdue delivery) belongs to the same
item.

**The silent half is closed.** Step 4 now emits `nap_expired_undelivered{fireAtMs, lateBySeconds}`
when it drops a nap whose broadcast never came. A delivered nap is consumed by its own trigger and
never reaches that branch, so the event has no false positives: if it ever appears in history, a nap
was genuinely lost, and by how much. The failure is no longer indistinguishable from success.

**What is left is genuinely Minh's call, not an open defect:** should a nap missed by an outage ring
late at all? The engine's own rule for alarms says yes ("an alarm that should have gone off and did
not, rings now"), but a nap is minutes-scale and a nap resurrected hours later by a boot is arguably
worse than none. I am not deciding that silently in either direction. Meanwhile it needs a reboot or
force-stop across a *daytime* nap, and the 04:00 morning path is unaffected.

### Also still open: nothing else can silence the 04:00 path

The reviewer's closing summary, recorded verbatim because it is the clearest statement of where this
round leaves the product: *"The 04:00 scheduled path is clean across every pass. What remains is a
sub-60 s expired-session ordering edge and an older reboot-through-nap loss."* The ordering edge was
then fixed in 32j; the nap loss is the item above.

## 8. 0.3 — what is deliberately NOT done, and why

Shipped as `0.3.108`. Four adversarial reviews against `code-principles.md`, about 30 defects, net
production code down. These items were raised by the reviews and consciously left, so that the next
round starts from a list rather than a rediscovery.

### Type-level work: two done, two declined on the evidence

The `:core` review asked for four changes that make illegal states unrepresentable. The test I
applied to each: **does a live defect hang off it, or only a shape problem?**

**Done — both had a defect attached.**

- **`Snapshot.mode` deleted.** A third copy of facts the snapshot already carried; two of its three
  states had no readers; and `Wire` decoded an unknown value as WAITING, so a version mismatch
  would have hidden the ring screen. Its own docstring claimed it was "DERIVED, never stored" while
  it was stored and transmitted. `NagTest`'s fixture passed `WAITING` while building a RINGING
  snapshot and nothing noticed, which is what a decorative field looks like.
- **`Override` sealed into `Time(bound, fireAtMs)` / `Skip(bound)`.** The old `kind` plus nullable
  `fireAtMs` made TIME-without-an-instant constructible, so four sites in `Engine` each had to
  remember `kind == TIME && fireAtMs != null`, and `Engine.kt:293` dereferenced it with `!!` guarded
  only by a `takeIf` three lines above. One forgotten conjunct away from a null-pointer exception in
  `recompute`, on the path that decides when the alarm fires. The compiler now enforces it, and it
  immediately rejected a test that had baked in the same assumption. The WIRE keeps its string tag:
  that is a serialization boundary, validated once where a request enters.

**Declined — guards that work, and a wide blast radius for no behaviour change.**

- **`RingPhase` → sealed with `Snoozed(untilMs)`.** The correlation costs one `?: return false` in
  `canExtendSnooze`, one `takeIf` in the UI, and a same-line null check in `RingService` (now a
  local rather than a `!!`). Sealing it ripples through Model, Engine, Store, Svc, RingService and
  the UI at once, to delete one guard and one `takeIf`.
- **`passwordHash` + `passwordSalt` → one `Credential`.** The half-set state is handled inside
  `verifies`, and `setPassword` is the single writer that always writes both or neither. No
  dereference, no default.

Doing those two for symmetry would apply the principle's letter against its purpose. *Make illegal
states unrepresentable* exists to stop defects; on a path where several defects were introduced
while fixing others this round, a no-behaviour-change refactor with a wide blast radius is a cost
with no attached benefit. Disagree on this evidence rather than rediscovering the question.

**Also done, with no refactor:** every `!!` in `:core` and `:service` replaced by a local the
compiler verifies. One of them (`Group.kt`, where `stale` implied `g != null`) was the same
correlated pattern as `Override`'s, contained to four lines.

### The original framing, kept for the record

- **`Override(kind, fireAtMs: Long?)` → sealed `Time(bound, fireAtMs)` / `Skip(bound)`.** TIME
  without an instant is constructible, so `kind == TIME && fireAtMs != null` is re-checked at four
  sites in `Engine` alone.
- **`RingPhase` → sealed, with `Snoozed(untilMs)`.** `snoozeUntilMs` is meaningful only when the
  phase is SNOOZED, which is why `canExtendSnooze` needs a `?: return false`.
- **`passwordHash` + `passwordSalt` → one `Credential`.** They must be both set or both null, and
  `Auth.verifies` guards the half-set case that the type permits.
- **Delete `Snapshot.mode`.** It repeats `ring != null` and `gates.allPass`; its only reader is one
  comparison, and `Wire` decodes an unknown mode as WAITING, so a version mismatch would hide the
  ring screen rather than fail.

Why not now: this round's own record is that touching the fire path costs defects — six of round
32's fourteen were mine, and three of 0.3's were too, each introduced while fixing something else.
These four change the shape of the state the fire path reads, simultaneously, with no behaviour
change to show for it. They belong in a round where that is the only thing happening, with the
suite green before and after and nothing else in the diff.

`Snapshot.mode` is the cheapest of the four and the best first move.

### Smaller minimality items from the same review

`Engine`'s `repeat(8)` override probe needs at most two steps; `fireNow: OccurrenceSource?` is only
ever SCHEDULED and could be a Boolean; `Outcome.MISSED` and `Outcome.SKIPPED` are never constructed;
`NapView.armed` always equals `atMs != null`; `Nag.Reason.NONE` exists only to return `""` twice;
`GateInfo.forRole(alarmRole: Boolean)` should take a `Role`; `Wire` detects JSON null by comparing
against the string `"null"` in six places and with `isNull` in six others.

### Not fixable locally

- **The sentinel block end to end.** An emulator has no Wi-Fi Direct group, so
  `listenerUpSinceElapsed == 0` vetoes every block and a test there proves nothing. It needs the
  third phone joined.
- **`:service` has no unit tests** and is 3,200 lines. The pure decisions have been moved out as
  they were found — `RingGate`, `FireAction`, now `SettingsSource` — which is the pattern that
  works: each move takes the part that can be got wrong somewhere it can be tested. What remains is
  Android plumbing that would need Robolectric to reach.

### A product decision, not a defect

Whether a nap missed by an outage should ring late. The engine's rule for alarms says yes; a nap is
minutes-scale, so one resurrected hours later by a boot may be worse than none. The loss is now
recorded either way (`nap_expired_undelivered`), so the silent-failure half is closed regardless.

## 9. 0.3.114 — the away rows say what they can actually do

Found by eye, on the phone, in about four seconds: with no sentinel set up, "Don't ring if the
sentinel is away" can be switched on. It reads ON and does nothing. `RingGate.roleVerdict` treats a
peer it has never heard from as present — every branch fails toward ringing, which is correct — so
an inert block was indistinguishable from a working one, and the only report was a morning that rang
when the owner expected silence.

The gate was right. The screen was lying by omission.

**`RoleVerdict` now has four variants where it had two**, and that is the whole fix:

| variant | blocks? | what an armed switch means |
| --- | --- | --- |
| `Away` | yes | would suppress a scheduled alarm firing now, whatever the other role says |
| `Present` | no | **working** — will suppress once the phone has been gone past the floor |
| `Vetoed` | no | **inert** — power save, low battery, unfinished setup, our own link down |
| `Unknown` | no | **inert** — no phone of that role has reported since this app started |

`Present` and `Vetoed` were one `Here`. The gate treats all four exactly as it treated two, and
every `why` token is byte-identical, so no decision moved and history reads the same. The split
exists so the row routes on a tag rather than recognising a string, and so that a *working* block is
never described in the same words as an inert one.

The first attempt at this shipped that second failure: one sentence for all of `Here`, so an armed
block with the sentinel at home, plugged in, reporting every 30 s said "cannot stop a ring right now
(present:3s), so the alarm will ring" — every night, until the owner learned to ignore the line and
then ignored it on the night it was true. Caught by adversarial review before it was committed, and
`working-block-called-broken` is now the mutant that stops it coming back.

**The rest.**

- **One place gathers the gate's inputs.** `RingGate.views(…)` builds the list and
  `RingGate.fold(settings, views)` decides from it; `Svc.views()` is the only caller of the former,
  and `buildSnapshot` evaluates it once for both `ringBlock` and `away`. Previously the snapshot's
  block reason and its rows each read `SystemClock`, `Group.listenerUpSinceElapsed` and `presenceOf`
  separately, so "the row cannot disagree with the decision" was true by luck. `RingGate.verdict`,
  `RingGate.armedRoles` and `Svc.ringBlockReason` are deleted -- the first two became wrappers with
  one caller each, which is how two paths start. `ringBlockDecision` is still the only thing
  that logs.
- `RingGate.armed(settings, role)` replaces the arming `buildList`, so the UI asks the gate whether
  a row is armed instead of reading the two booleans itself. `Role.ALARM` errors: the alarm phone's
  own absence is not a thing, and answering "not armed" would hide the caller's mistake.
- **Every sentence is row-local and present-tense**, which took three attempts. `fold` ORs the
  armed roles, so one role being unable to block says nothing about whether the alarm rings: with
  both switches on, the sentinel unknown and the controller away, "the alarm will ring" on the
  sentinel row would contradict the controller row directly below it, and be the wrong one. Only an
  armed `Away` may speak of the ring, because the fold returns on the first of them. The tense
  matters for the same reason the role does: the gate decides at fire time, a phone can return in
  between, and a nap bypasses the gate entirely — so "the **next** alarm will not ring" was wrong
  twice over, and the three sentences that promise silence now say "scheduled".
- `Vetoed`'s sentence names no role, deliberately: `own_link_unstable` is the alarm phone's own
  group being down, and the row must not blame the peer for it. A test pins that exception.
- The controller-away caveat — a wall-mounted controller going quiet usually means the *link* broke,
  which is exactly when the alarm must ring — moved out of the `Settings` field's doc comment onto
  the row that arms it. One copy, where it can change a decision.

**Proof.** 135 tests, was 121. 46 mutants, was 32: seven refreshed because the `Here` split moved
their target lines, fourteen new.

The claim this change rests on is that no ring decision moved, and reading the diff is not proof of
it. So the pre-split `RingGate` was taken verbatim from `HEAD~1` as an oracle and compared against
the live gate over the cartesian product of both switches, five away limits, five link states, and
every peer shape that reaches a distinct branch -- silence either side of the 120 s and floor
boundaries, battery -1/0/19/20/80, plugged, power save, unreported power save, failing gates, and
absent -- for both roles. **240,300 combinations, no divergence**, comparing the whole `RingVerdict`
including the `Blocked` reason and the joined `Allowed` whys, so the strings that reach history are
pinned too. The probe was proved non-vacuous by changing `< 20` to `in 0..19` in the oracle, which
reddened it, and then deleted: the old code is gone, so a permanent copy of it would be cruft. The two that matter are `inert-block-says-nothing` (reinstates the
shipped defect) and `working-block-called-broken` (reinstates the one review caught).

### Deferred, recorded rather than done

- **The Away section renders only on the alarm phone** (`isAlarmRole`), so the controller cannot see
  or change these two settings though it edits every other setting remotely. A product decision, not
  a defect, and not what was asked for. The wire carries `away` faithfully, so it is a one-line UI
  edit — but whoever makes it must render a **missing** view for an armed role as *unknown*, not as
  nothing to say, or the original defect comes back on that phone. `Wire.awayFrom` drops a view
  whose role or state this build does not recognise, which is deliberate: throwing would fail the
  whole snapshot decode, on the phone that dismisses the alarm, over a field it may not render.
- **`Status.kt`'s sentinel line uses a hard-coded `120_000`** while the gate uses `awayFloorMs`
  (default 600 s). With the sentinel silent five minutes, Status says "away 5m" and the new row says
  it is present. Both are correct — they answer different questions, "is it talking" and "is it
  absent enough to block" — but on one screen they read as a contradiction. The fix is wording, not
  logic, and it predates this diff.
- **`Vetoed`'s raw token in the row** (`power_save`, `low_battery:14`, `gates:a|b`). Each has a
  different owner action, so plain English would need a tag per reason — five variants' worth of
  types for copy, when Status already renders the peer's battery, power-save state and failing gates
  as proper rows.

## 10. 0.3.115 — the sentinel had no way to be told where to look

Reported from the phones: the sentinel sat on a spinner, "alarm phone never found", next to the
alarm phone on the same Wi-Fi.

`MainActivity` passed `trying = if (chosen == Role.CONTROLLER) ({ Trying() }) else null`. `Trying()`
is the only screen carrying the group name and passphrase fields, so a sentinel never saw them --
and 0.3 had removed the sentinel's Settings tab to strip its authority, which closed its only other
route to `Pairing`. With both credentials blank, `LinkService`'s SENTINEL branch skips the join
entirely and falls through to `SentinelWatch.poll()`, polling a host whose address it has never
learned. Forever, at 30 s intervals.

Being on the same Wi-Fi cannot help: `PeerLink.alarmLanIp` is *learned* from a snapshot received
while a path was working, so a phone that has never connected has only the P2P path.

The same class of defect as the 0.3 sentinel crash, in the same file: a role treated as "controller
or not" when there are three. `trying` is now `chosen != Role.ALARM`, and the parameter's KDoc said
CONTROLLER, which is why the gap was invisible on reading. The screen carries no authority -- a
title, a permission button, two fields, a log, and a save that writes this phone's OWN settings --
so widening it grants a sentinel nothing it should not have.

### Debt this turned up

- **`Actor` has no SENTINEL.** `Trying()`'s save hard-codes `Actor.CONTROLLER`, so a sentinel
  editing its own join credentials is logged as a controller. Adding the variant touches the wire
  encoding and history rendering for a cosmetic label on a local write, so it was left alone rather
  than widening a bug fix.
- **No test covers the role-to-screen mapping.** Both this defect and the 0.3 crash lived in that
  mapping, in the same expression, and neither was catchable by the suite: it is a `@Composable`
  wiring decision in `:app`. Making it a pure `Role -> ScreenSlots` function in `:core` would put
  it under the mutation harness. That is the right fix and it is not a one-liner.

### 0.3.117 — the same defect again, found by being asked why the roles differ

Asked why the sentinel and controller do not share one first-link flow, the answer turned out to be
a third instance of the same bug rather than a decision.

`Pairing(role)` was `if (role == Role.CONTROLLER) { joiner summary; return }` followed by the
alarm phone's hosting editor. A SENTINEL is not CONTROLLER, so it fell through and was offered the
**host's** credential editor -- and that path gates editing on `snap.settings.hasPassword`, the
ALARM phone's password, so a sentinel could not see its own join details until it unlocked another
phone's settings.

The axis that matters is hosting, not role: one phone hosts the group, the others join it. It is now
an exhaustive `when (role)` producing `hosts: Boolean`, so a fourth role is a compile error rather
than an inheritance of whichever path is the `else`.

**The pattern, now three for three.** Every role defect in this app has been a two-way branch over
three roles, in the UI, with the sentinel silently taking the `else`:

| where | branch | what the sentinel got |
| --- | --- | --- |
| `RootScreen` (0.3) | `isAlarmRole && s == null` | fell through to `s!!` -- crash on launch |
| `MainActivity` (0.3.116) | `trying = if (chosen == CONTROLLER)` | no connect screen, so no way to enter credentials |
| `Pairing` (0.3.117) | `if (role == CONTROLLER)` | the host's editor, locked behind the alarm's password |

`:service` has never had one, because it routes on `when (role)` with all three arms and the
compiler enforces it. `:ui` and `:app` used booleans and `else`. That is the whole difference, and
it is the argument for the deferred item below rather than a style preference.

**Checked and correct, not the same bug:** `isLocal = role == ALARM` (local vs remote client),
`deviceSettings` alarm-only, `onExport` controller-only, `sentinelExtras` sentinel-only, and the
role-pick permission prompt firing only for ALARM -- both joiners reach the same permission button
on the connect screen, which also handles the twice-denied case.

### 0.3.118 — instance four, in the Status screen

`val controller = if (iAmAlarm) s.peer else s.self` resolved to the sentinel ITSELF, so the Power
section labelled the sentinel's own battery "Controller". The real controller is not in a sentinel's
snapshot at all: `HttpStateClient.withSelf` overwrites `peer` with the phone it polled, which for a
sentinel is the alarm phone. Now an exhaustive `when (s.self.role)`, plus a correctly labelled
"This phone" row for the sentinel -- load-bearing, because the gate vetoes its block below 20%
unplugged and the owner needs to see why a block cannot fire.

Still open, a judgement rather than a defect: a sentinel receives the controller's nag cards
(`Nag.evaluate` is gated on `isAlarmRole`). Reasonable on a carried phone, noise on one with no
authority that may be elsewhere.
