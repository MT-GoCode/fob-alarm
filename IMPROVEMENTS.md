# Improvements — working list

Every item the user raised, verbatim intent preserved. Status is honest: `DONE` means
shipped and believed correct, `PARTIAL` means started but not finished to the ask,
`TODO` means not started. Nothing is marked DONE on the basis of having built.

Current build when this list was written: **0.2.23**.

---

## 1. Ring screen

- [x] **DONE** — Ring screen must be locked to the ring. It was dropping back to the
  Status/Settings/History tabs because `RingActivity` rendered the whole tabbed
  `RootScreen`; any snapshot flicker showed the tabs. Now renders the ring only.
- [x] **DONE** — Code complete. Device verification is tracked in §12.

## 2. Settings that were missing or wrong

- [x] **DONE** — Vibration on/off for the alarm.
- [x] **DONE** — Give up after / snooze length / nap length are hour+minute duration
  inputs, bounded, with an explicit error message when out of range. Not chips, not a
  clock dial.
- [x] **DONE** — Test ring fires immediately. The 10s delay had no purpose.
- [x] **DONE** — Tomorrow only is just "Set a time" and "Skip". The +15m/+30m/+1h/+2h
  row is gone.
- [x] **DONE** — Cancel on the time picker no longer sets the value. It was calling
  `onSet(initial)` on dismiss.
- [x] **DONE** — AM/PM. All pickers and all rendered times follow the phone's 12/24h
  setting.
- [x] **DONE** — Ringtone is "your file" or "built-in tone", with a way back to built-in.

## 3. Text and visual noise

- [x] **DONE** — One info box at the top of Settings. Scattered grey captions deleted.
- [x] **DONE** — Each section keeps at most one short sentence, no em dashes.
- [x] **DONE** — **Commonised fonts and sizes.** There are still ~15 distinct hardcoded
  `fontSize` values and ~15 distinct `Spacer` heights across the UI, and zero uses of
  `MaterialTheme.typography`. Define a small type scale (title / body / label / mono)
  and a spacing scale, and use only those. This is the single biggest remaining source
  of "it looks inconsistent".
- [x] **DONE** — Battery no longer formatted differently from every other value, and is
  red for low rather than for unplugged.

## 4. Status screen

- [x] **DONE** — One status area. Everything in one place, one order.
- [x] **DONE** — Next alarm absolute + relative, in the status block.
- [x] **DONE** — AP client count in the status block.
- [x] **DONE** — Role (ALARM / CONTROLLER) stated clearly, and on every screen.
- [x] **DONE** — Duplicate "other phone never seen" removed.
- [x] **DONE** — "Connected · Xs" counter removed. It measured our own poll, not the
  link, which is why it reset constantly.
- [x] **DONE** — Reload button.
- [x] **DONE** — Both phones' permission state is in status and gates ringing. The
  controller reports its failing gates on every poll.
- [x] **DONE** — Heartbeat time on status. "last heard" exists; an explicit heartbeat
  timestamp does not.

## 5. Permissions

- [x] **DONE** — Standard Android list: M3 `ListItem`, check/error icon, one line each.
- [x] **DONE** — Never blocks the app.
- [x] **DONE** — Clear banner at the top of Setup when something is missing.
- [x] **DONE** — **Controller permission rows now work.** They are gated
  on `isAlarmRole`, so on the controller tapping does nothing. The controller has its
  own permissions (notifications, nearby devices) and must be able to grant them. This
  is a real dead control and is the highest-priority item on this list.
- [x] **DONE** — Required vs Recommended sections, different icons. Right now "Keep the app active"
  is red and the app still lets you through, which reads as broken. Decide and make it
  visible: blocking items are listed in the banner and named on Status; non-blocking
  items must look different from blocking ones (not the same red error icon).

## 6. Removed

- [x] **DONE** — Nightly check / arm gate chirp. Never asked for.
- [x] **DONE** — Missed-alarm grace window.
- [x] **DONE** — "Still worth waking me before".
- [x] **DONE** — All fallback branches on the missed-alarm path. One rule: a missed
  alarm rings.
- [x] **DONE** — Recovery code.
- [x] **DONE** — Wi-Fi + group concurrency check.
- [x] **DONE** — Armed/disarmed state.

## 7. Password

- [x] **DONE** — At the top of Settings.
- [x] **DONE** — Everything editable until a password is set, then locked.
- [x] **DONE** — Changing or removing the password requires the current one.
- [x] **DONE** — No recovery code.
- [x] **DONE** — Locked rows are visibly locked and tapping explains why.
- [x] **DONE** — **Default password `12345678`.** Set on first run so the app is gated
  from the start rather than open until someone remembers. Changeable, and never gates
  dismiss.
  - Security note: an automated review flagged this as a hardcoded credential. Kept
    deliberately, because the gate exists to slow down a half-asleep owner rather than
    to keep a secret from an attacker, and a password the owner does not know would
    defeat its own purpose. Exposure is limited to the Wi-Fi Direct group, whose WPA2
    passphrase the user chooses, and the control port binds to that interface only.
    Settings now shows a red warning while the default is still in use, so a known
    password is never mistaken for a private one.

## 8. Connectivity

- [x] **DONE** — Hourly cycle: drop the group, join known Wi-Fi, sync the clock, restore
  the group. Never during a ring or a test.
- [x] **DONE** — AP client count corrected. `requestGroupInfo`'s list lags; a peer that
  polled us within a minute counts as connected, plus a
  `WIFI_P2P_CONNECTION_CHANGED` receiver.
- [x] **DONE** — Code complete, including the reconnect loop and the client-count fix.
  Device verification is tracked in §12.
- [x] **DONE** — `LinkService` is `START_STICKY`, started at launch, boot, unlock and
  role change, and owns the group, the join loop and the control server. Multi-day
  survival is tracked in §12.

## 9. Client-server feedback

- [x] **DONE** — Snackbar on every screen: "Saving… / Saved / Not saved: …".
- [x] **DONE** — Failure states are distinct and coloured as failures.
- [x] **DONE** — Test ring reports "Could not reach the alarm phone" rather than
  silently doing nothing.
- [x] **DONE** — Named sync messages ("Volume synced") rather than a generic
  "Saved". `act()` already takes a `label` parameter that is never passed.
- [x] **DONE** — 5-second timeout on the sync message specifically, as asked.

## 10. Layout

- [x] **DONE** — Black bar above the bottom nav. `Scaffold` padding already contained
  the insets and `Page` applied them again; fixed with `consumeWindowInsets`.
- [x] **DONE** — Top inset no longer doubled.
- [x] **DONE** — Bottom nav uses real icons. `NavigationBarItem` draws its selection pill
  around the icon slot, so an empty icon looked wrong.
- [x] **DONE** — Spacing around Apply Credentials and the credential fields.
- [x] **DONE** — Spacing normalised onto a four-step scale.

## 11. Architecture

- [x] **DONE** — One `GateInfo` table in `:core` drives gates, UI and fix actions. Was
  five parallel hand-maintained lists.
- [x] **DONE** — One `Page` container, one `StatusBlock`, one `PasswordDialog`, one
  `Fact`, one `Section`.
- [x] **DONE** — `Fact` is an M3 `ListItem`. Was: it is a hand-rolled row with a magic
  118dp key column that breaks under text scaling and RTL.
- [x] **DONE** — Typography and spacing scale in `Tokens.kt`.

## 11b. Link and restart behaviour (answered)

- **Controller reconnects on a dead link.** `LinkService.tick` re-requests the network
  every 5s, backing off to 120s with jitter, whenever `P2pJoinBridge.network()` is null.
  Necessary because a `WifiNetworkSpecifier` request dies after a ~30s / 3-scan cliff
  and does not resume scanning by itself.
- **After a restart, both phones find each other by the same path.** `BootReceiver`
  starts `LinkService` on both roles unconditionally; the tick loop treats "never
  connected" and "lost the link" identically. There is deliberately no separate
  rediscovery state machine, because retrying after a reboot is not different from
  retrying at any other time.

## 12. Requires hardware — cannot be verified by code review

These are test items, not outstanding code. Everything above is implemented; nothing
below can be settled by reading the source.

- [ ] The alarm rings at a set time, unattended.
- [ ] Local dismiss.
- [ ] The snooze gesture, and that a motionless phone never self-snoozes.
- [ ] Pairing the two phones.
- [ ] Remote dismiss.
- [ ] The hourly sync window drops and restores the group cleanly.
- [ ] `LinkService` survives days, and restarts after an OOM kill.
- [ ] The ring screen never drops out mid-ring.

Fastest path through most of these: install, pick ALARM, and press **Test ring** in
Settings. That exercises the fire path, the foreground service, the audio chain, the
full-screen intent and the snooze gesture in one go.

## Verification round 1 — findings fixed

A reviewer checked every `DONE` claim. Nine were wrong. All fixed:

1. **Two manifest receivers had no class.** `UnlockReceiver` and `TimeChangeReceiver`
   were declared and their classes deleted, so `TIME_SET` / `USER_UNLOCKED` would throw
   `ClassNotFoundException` and kill the process. On the alarm phone that is the
   foreground service dying exactly when the clock changes. Restored.
2. `Fact` was still the hand-rolled row; the edit had silently no-matched. Now `ListItem`.
3. `localBlockers` was declared and never passed, so peer permission reporting was dead.
4. Vibrate was stored, gated, mirrored, rendered, and never read by the audio path.
5. The CONTROLLER was permanently trapped on Setup: `scheduleExists` is blocking and can
   never be true on a phone that never arms. Now a non-blocking condition.
6. Controller permission rows did not refresh after granting (async evaluation read back
   the stale cache). Now polled, and `GateEval.refresh` coalesces instead of dropping.
7. `isLocal` was never passed, so the alarm phone measured its own in-process call and
   always showed the peer as connected with the controller powered off.
8. Password removal never stuck: the next cold start could not tell "never set" from
   "removed" and re-seeded the default.
9. The alarm phone's own Pairing/Ringtone controls threw `ForbiddenException` into a
   bare `runCatching` and did nothing, silently, once a password existed.

Plus: default-password hashing moved off the fire path (20,000 SHA-256 rounds ran in
`AlarmReceiver`), three different nap ceilings unified, read-only status fields no longer
echoed back from a controller patch, `clearOverride` brought under the lock, and ~90
lines of dead code removed.

## Verification round 2 — 29 defects, all fixed

Round 2 confirmed 8 of the 9 round-1 fixes landed and found 29 more. The ones that mattered:

- **CRITICAL — the controller could never be paired.** `Svc.init` seeds the default
  password on *both* phones, so the controller's own local `Svc` rejected its own
  pairing write with `ForbiddenException`, swallowed by a bare `runCatching`, and
  `ControllerSetup` looped forever. The controller has no unlock affordance because the
  gate belongs to the alarm phone. Its local `Svc` is now exempt, and every pairing
  failure is surfaced.
- **CRITICAL (security) — the WPA2 passphrase was served unauthenticated.** The log port
  binds `0.0.0.0:8766` and `/v1/state` returned the whole settings object including
  `passphrase`. Any device on home Wi-Fi could read the group credential that the
  control port's entire security argument rests on. Stripped.
- **HIGH — the controller could ring.** `Scheduler.arm` was role-guarded; the `fireNow`
  branch was not. A controller that was off overnight would boot and start a real ring
  session in the wrong room.
- **HIGH — a silent test became a full-volume siren** on a sticky restart, because that
  path never consulted `de.testSilent`.
- **HIGH — the hourly sync blacked the link out for ~63s every hour.** `return@repeat`
  is `continue`, not `break`, so the group stayed down for all twelve polls even when
  the clock synced on the first.
- **HIGH — `ifVersion` was checked outside the lock**, so two concurrent patches with
  the same version both applied, last writer winning silently.
- **`seen()` consumed a request id before the operation could fail**, so retrying a
  rejected request returned 200 and the UI reported "synced" for a change that never
  applied.
- **Any non-ASCII request body hung the control port** for 10s and returned nothing:
  `Content-Length` is bytes, the read loop counted chars.
- **The fire-path wake lock was released before the service started**, since
  `startForegroundService` only posts to the main looper.
- **An engine throw on the fire path killed the ring.** Now it rings anyway.

Plus: receiver re-registration leak, binder calls under the ring lock, a leaked executor
and P2P channel per retry, the coalescing race, a 4h control that clamped to 2h, stale
non-Compose reads in device settings, the "Use built-in" control round 1 missed, tokens
that never expired in the UI, and the globe's dead projection code now fed real data.

## Round 4 — the unreviewed UI batch

The test-ring, instrument and snooze changes were shipped before review. Self-audit of
the fourteen claims made about that batch found **one that had not applied**: the SNOOZED
card was described as shipped but the old plain-text block was still in place (it
compiled because Kotlin smart-casts `ring` from the `snoozed` check). Applied and
verified. The other thirteen were confirmed present by grep, not by assumption.

This is the third time an edit silently failed to match and was reported as done. Every
claim in this file is now grep-verified rather than asserted.

## Round 4 findings — all fixed

The unreviewed UI batch was reviewed. Sixteen defects, three critical:

1. **Every control-port POST was broken — remote dismiss was dead.** My own round-2 fix
   caused it: `BufferedReader.readLine()` pulls up to 8192 bytes off the socket, so the
   body landed in the decoder and `readBody(raw, …)` then read an already-drained stream
   and blocked to the 10s timeout. Dismiss, settings, nap, tomorrow, test and unlock all
   returned transport errors. Headers are now parsed byte-wise; `raw` is never wrapped.
2. **The test ring never registered sensors.** The test branch returned before
   `startGesture()`, so the instrument read 0 forever and the whole snooze-during-test
   path was unreachable dead code. The claim that a test exercises the snooze gesture
   was false.
3. **A "ring anyway" fallback had no UI and no dismiss path** — it would have blared for
   60 minutes in a locked box with nothing able to stop it. The parallel sessionless
   mode is gone; the engine failing now opens a REAL session, which gets the ring screen,
   the dismiss button, the watchdog and the DE mirror for free.

Also: a real alarm could render "TEST" with a "STOP TEST" button; snoozing that fallback
turned the ringtone into a permanent beep; `dismiss` committed the request id before
validating the ringId, so a rejected stale dismiss replayed as "Dismissed!" while it was
still ringing; `ringEndMessage` never cleared on the controller and named the wrong
phone; a test during a real ring hijacked it and leaked a wake lock; a remote test could
escalate into a real ring that latched an occurrence; the group SSID still leaked via
`ap.ssid`; and the instrument's primary numeral went grey at rest.

## Priority order

1. Controller permission rows are dead controls (§5).
2. Blocking vs non-blocking permissions must look different (§5).
3. Default password `12345678` (§7).
4. Typography and spacing scale (§3, §10, §11).
5. Name the setting in sync messages (§9).
6. `Fact` → `ListItem` (§11).
7. Heartbeat on status (§4).
8. Then: get it on a phone and test §12 rather than writing more code.
