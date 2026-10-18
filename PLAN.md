# PLAN — Location sentinel role

Status: **v1 rejected by review. Staged v2 below.** Stage 1 is the deliverable; stage 2 is blocked
on measurement and one product decision.

Reviewed adversarially on three axes — functionality, robustness, simplicity. 28 findings.
**Six could stop the alarm while the owner is home asleep.**

## 1. Problem

The alarm is deliberately unconditional. But if the owner is not home, a 40-minute siren in a shared
house wakes everyone else for nothing, and the only existing remedy is remembering to press Skip.

## 2. Mechanism

**Reachability over the Wi-Fi Direct group is the proximity proof.** A third phone — the *sentinel* —
joins the alarm phone's group as the controller does. Range ~50-100 m indoors. No GPS, no relay, no
internet, no location permission; nothing is sent or stored.

This part survived review. What failed is everything built on top of it.

## 3. Why v1 was rejected

### 3a. My feasibility evidence was invalid, twice over

I claimed "0 `p2p_lost` in 11 days, on an unplugged phone" as proof the heartbeat holds. Both halves
were wrong:

1. **`p2p_lost` is logged only by `P2pJoin` on the *joining* phone** (`P2pJoin.kt:98`). The alarm
   phone never emits it, so querying its history for it measured nothing at all. Heartbeat gaps are
   logged nowhere — `Server.kt:212` simply overwrites `peerDevice`.
2. Even as a controller measurement it would not transfer. The controller holds a **permanent
   `PARTIAL_WAKE_LOCK` that `LinkService.kt:91-96` grants to `CONTROLLER` only**, and it never leaves
   range. A sentinel would get neither property.

### 3b. The mechanism does not survive CPU suspend

`LinkService`'s tick is `Handler.postDelayed` (`LinkService.kt:143`), which runs on `uptimeMillis`
and **does not advance while the CPU is suspended**. Battery-unrestricted lifts Doze's *network*
block; it does not keep the CPU awake. A sentinel in a pocket therefore stops polling, reads as
absent, and would block the alarm — the exact failure v1 claimed to have removed.

### 3c. "Absent, last seen healthy" usually means the link broke, not that the owner left

Common, not rare:

| event | why it reads as "left" |
|---|---|
| Sentinel fails to rejoin on coming home | `P2pJoin.join` has a 45 s guard and `connect` can fail repeatedly; the held report is the healthy one from when the owner left |
| Alarm phone's own group down | `Group.start` backs off to 120 s (`LinkService.kt:119`); the control socket closes on group drop (`Group.kt:96`), rebinding with up to 30 s backoff (`Server.kt:96`) |
| P2P client group idle | Android sets `p2p_group_idle` to **10 s** on client groups, so a brief radio fade drops membership |
| Airplane mode / Wi-Fi off at bedtime | routine; last report was healthy |
| OTA reboot around 03:00 | routine |
| Forward clock correction | `lastSeenMs` is `System.currentTimeMillis()` (`Server.kt:219`), and the `fireNow` path is *produced by* clock jumps |
| Role swap or reinstall | a new `deviceId` (`Svc.kt:73`) leaves the old slot holding a stale healthy report, blocking **every** morning |

### 3d. Logic defects v1 would have shipped

- **Infinite recursion.** `suppressOccurrence` with no explicit occurrence falls back to
  `nextUnlatchedScheduled`, which at fire time returns *tomorrow* (`at > now`, `Engine.kt:105`).
  Recompute then latches today MISSED and sets `fireNow`, which re-enters the gate, which suppresses
  again — inside `Svc.apply`.
- **`peerBlockers` blanks at exactly the absence floor.** `Svc.kt:335` empties it once the peer has
  been silent 120 s — identical to the proposed floor. Every absent sentinel would read "gates
  green" and be blocked.
- **The MISSED latch is written before the gate runs** on the `fireNow` path (`Engine.kt:199`), so a
  block would also record a false `missed` — the one signal Round 26 exists to keep honest.
- **The new settings would not be locked.** `requireUnlocked`'s list (`Svc.kt:407`) must be extended
  or the controller can enable them without the password. Same lock bug as rounds 20 and 21.
- **Naps would be blocked.** `ACTION_FIRE` carries NAP triggers too, so a nap set in person twenty
  minutes earlier would be suppressed because the sentinel is in another room.
- **409 renders as a dead link.** `HttpStateClient.kt:46` reads `snapshot` from any 409 and throws
  `Transport` without it.
- **The role toggle only knows two roles** (`MainActivity.kt:473`).

### 3e. Simplicity cuts, accepted

- The sentinel's ENABLED/BLOCKED headline and transition notifications. While away it *cannot reach
  the alarm phone*, so the verdict would be a local re-run of the policy — a second copy of truth
  that drifts. It may report only its own link state.
- `blockRingIfControllerAway`. The controller is wall-mounted; when it vanishes the link has failed,
  which is exactly when the alarm must ring. The sentinel role exists so the phone that *leaves* has
  a role of its own; gating on the controller is strictly worse.
- One-slot-per-role, 409, `forget`, Q4 — one slot *per role* already yields one of each under
  last-writer-wins, without the lockout the rejection created.
- The `Peers` map, replaced by two `@Volatile` fields.
- DE-mirrored presence, replaced by none. After a reboot "never seen" means *ring*, the safe
  direction, and nothing can drift.
- The six-row health table, replaced by one boolean expression.

## 4. BUILT — single build, not staged

The owner chose **(b)**: accept the airplane-mode / sentinel-reboot residual, no self-ringing.
The decisive change from v1 is the **second presence channel**, which closes four of the six
failure rows by not depending on Wi-Fi Direct alone:

> **present = reachable over P2P, OR reachable over home Wi-Fi**

`SentinelLan` has the alarm phone **pull** the sentinel's read-only `:8766/v1/health` at its
last-reported `lanIp`, and only once the P2P heartbeat has gone quiet for a minute. Pull, never
push: a push endpoint reachable from the home network would let anything on that network suppress
an alarm, which is strictly worse than letting it dismiss one.

| failure | one channel | as built |
|---|---|---|
| P2P client group idle (10 s) | reads "left" | present via Wi-Fi |
| Fails to rejoin P2P on coming home | reads "left" | present via Wi-Fi |
| Alarm phone's own group down | reads "left" | present via Wi-Fi, **and** the `listenerUpSinceElapsed` guard refuses to block while this phone's own link is unstable |
| Sentinel in Doze | reads "left" | wake lock now granted to SENTINEL |
| Role swap / reinstall | blocks every morning | fixed by deletion — two in-memory slots, last writer wins, no 409 |
| **Airplane mode / sentinel rebooting** | reads "left" | **accepted residual (b)** |

### What was built

- `Role.SENTINEL` through the role picker, the three-way role toggle, and `buildClient`.
- `LinkService` grants the **permanent wake lock** to SENTINEL as well as CONTROLLER — the fix for
  3b. Its own tick branch joins and polls every 30 s (presence needs minute granularity, not 5 s)
  and calls `SentinelWatch`, never `ControllerWatch`: no alert, no dismiss, no authority.
- `Svc.Presence` per role, in memory only, stamped with `SystemClock.elapsedRealtime()`.
- `Svc.ringBlockReason()` — one predicate, every term failing toward ringing, each refusal logged
  with `ring_block_skipped{why}`.
- `Engine.suppressTrigger` using `occurrenceBeingFired`, latching SKIPPED, never setting `fireNow`.
- **One gate**, at `ACTION_FIRE` only, skipped entirely when a session is already open, and NAP
  exempt.
- `blockRingIfAway`, default **off**, in `requireUnlocked`, `Wire` and `Engine.diff`.
- One Status row plus a `Next ring blocked:` line shown only when a block is actually armed.
- Logging for the measurement: `peer_gap`, `sentinel_gap`, `sentinel_seen_on_lan`,
  `sentinel_lan_unreachable`, `sentinel_poll_failed`, `ring_blocked`, `ring_block_skipped`.

### Absence floor

**10 minutes**, not the 2 I invented. Deliberately conservative, and it costs the owner nothing —
leaving before a 04:00 alarm means being gone far longer. `peer_gap` exists so it can be tuned down
from data later.

## 5. Superseded: the old staged plan

**The sentinel role, passive. Nothing gates the alarm.**

1. `Role.SENTINEL` in the role picker, persisted in the DE mirror, with the role toggle aware of
   three roles.
2. It follows the controller's link path and **holds the same permanent wake lock** — the fix for
   3b, and the thing that makes the measurement below meaningful.
3. It raises **no** ring alert; `ControllerWatch` stays controller-only.
4. Its screen is `LinkBar` plus the Setup tab. It reports its own link state, nothing more.
5. Its poll sends `role` and `powerSave`. A **missing `role` means CONTROLLER**, so older controller
   builds keep working (`Server.kt:217` hardcodes that today).
6. The alarm phone keeps `controller` and `sentinel` as two in-memory `DeviceView?`s, last writer
   wins per role. Status gains one row: `Sentinel   here / away 3h / not set up`.
7. **Instrumentation, the point of this stage.** The alarm phone logs `peer_gap{role,seconds}`
   whenever a peer's silence exceeds a threshold, so the real distribution of absences becomes
   visible in history instead of invented.

Nothing here can suppress a ring. Installing it is risk-free.

## 5. Stage 2 — blocked on two gates

Kept for the record. **Gate A, measurement.** Run with the real sentinel phone, unplugged, in a pocket, for a
week of nights, then read `peer_gap` from history. The absence floor is set from that distribution.
Two minutes was an invented number.

**Gate B, one product decision.** 3c cannot be closed by inference: several ordinary events are
indistinguishable from "the owner left". The one design that genuinely closes it —

> if the sentinel is unlinked at the alarm's own `nextFire` time, **it rings itself**

— means the owner wakes either way: in bed if they are home and the link merely broke, or in their
pocket if they left. It converts the failure from silence into a noise in the wrong place, which is
the right direction for an alarm clock. It is new product behaviour and is the owner's call.

If stage 2 proceeds it also requires: the gate only where a **new session** opens (verified — of
seven `RingService.start`/`forceSession` call sites, only `Receivers.kt:43` and `Svc.kt:236` open
new sessions); `SystemClock.elapsedRealtime()` for presence; the occurrence taken from
`occurrenceBeingFired` (`Engine.kt:429`); NAP exempt; the decision made once per occurrence and
latched; the setting added to `requireUnlocked`, `Wire.settingsFrom` and `Engine.diff`; and a
"link up since" guard so an outage on the *alarm phone* can never read as the owner leaving.

## 6. Non-goals

- No mid-ring shutdown. Any future gate is evaluated only where a session opens.
- No distance readout. Presence is binary; a number in metres would be a lie.
- No sentinel authority. It cannot dismiss, snooze, change settings or unlock.
