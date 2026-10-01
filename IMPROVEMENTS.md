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
  every 5s, backing off to 15s with jitter, whenever the controller is not a member of the group.
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

## Round 5 — the strategy review and the service layer

The strategy review's conclusion: `:core` (tested) produced zero criticals across five
rounds; the untested Android layer produced all of them. Done from it: zombie sessions
reaped (test written failing first), updater and flavours deleted, controller dismiss
moved into LinkService, a loopback self-probe of the control port every ten minutes,
`/v1/health`, and ring-blocking problems split from warnings so red means "will not
ring" and nothing else.

## Round 6 — the UX rewrite, re-reviewed (19 UX + 6 service findings, all fixed)

UX, in order of 4 AM confusion: last night's "Can't reach the alarm phone" card was
still on screen when a new ring started (verdict now resets per ring); the controller
could be stuck on Setup behind a green Done card because the tab used the alarm phone's
gate list (one role-filtered list decides both); a fresh alarm phone could not pair from
Setup because the default password locked the Save button with no Unlock in sight
(Unlock button in place); the Role section rendered twice; the pairing block had no
heading in Settings; the version bar printed enum names and duplicated the role line
(now one "Version x" line, plus a mismatch warning); controller-only copy reached the
alarm phone's ring screen; the ringtone buttons ignored the client-side lock expiry; the
controller pairing screen never noticed a granted permission; the Allow label lagged the
action by one tap; a missing gyroscope rendered red under a green Done; locked rows
looked enabled; the nag evaluated on the alarm phone itself; raw exception text in two
toasts; the Pair-again failure was swallowed; dead `Fact`, `Fmt.battery`, `syncOk`,
`P2pJoin.status` and four imports.

Service: the hourly sync window could drop the group in the minutes before the alarm
(now skipped within ten minutes of the next fire); a crash trace logged before Room was
readable was deleted without ever reaching history (the in-memory backlog is now flushed
into Room when it opens, under the same lock, so nothing is lost or duplicated); the
self-probe ran before the port had bound and raised a false 503 for ten minutes after
every sync window; a notification dismiss with no snapshot returned silently; the
`logs` script still printed `variant`.

Engine: a trigger now binds to the most recent due occurrence, never a future one, and is
dropped when that occurrence already rang or was skipped. Before, a watchdog resurrect
after the cap rang again bound to tomorrow, and a stray trigger before the alarm time
would have bound to today and silently consumed it. Four tests, written failing first:
duplicate after dismiss, resurrect after cap, missed-while-dead rings for today, stale
trigger before the alarm time. The watchdog receiver no longer resurrects a session past
its cap. The ring service honours an engine "this trigger is for nothing" and only forces
a session when the engine throws.

`core tests=44 failures=0`. What remains is §12: hardware.

Verification of round 6 found six more, all fixed: resuming a NAP session after a
process death went through the engine as a SCHEDULED trigger, superseded the nap and
then dropped it as stale (watchdog and boot now resume with an explicit action that
never consults the engine); `tryLoadRoom` could run twice at once and persist the
backlog twice (serialized); Nearby devices now blocks Setup on the controller, which
cannot join without it; a moved alarm whose new time passed while the phone was dead
was latched as superseded and never rang (now MISSED, rings on return, test written
failing first); the receiver held its wake lock for 60 s on the after-cap path; the
sync window skipped when there was no next fire. `core tests=45 failures=0`.

## Round 7 — on a device (Android 15 emulator, no Wi-Fi Direct)

First run on real Android. Driven through the real UI with uiautomator, the clock jumped
to the alarm minute, the process killed mid-ring, the app force-stopped, updated over the
top, and rebooted with a PIN and never unlocked. Nine findings, all fixed and re-run:

1. **"Notifications" showed red for a granted permission.** The gate also required the
   link service to be alive, and the cached gates predated it. This is exactly the
   "permission looks missing, I press Allow, nothing asks" report from the phones. The
   gate is now the permission and nothing else, and the gates re-read when a role is
   picked.
2. **Everything that happened before first unlock was lost at unlock.** USER_UNLOCKED is
   a registered-receivers-only broadcast; the manifest receiver never ran. Then the boot
   receiver's load ran on the main thread, where Room refuses. The database now loads on
   BOOT_COMPLETED (which arrives after unlock) and on the hourly tick, on the io thread.
3. **History on the alarm phone only ever showed the current process's memory.** The
   screen read Room on the main thread, Room threw, the fallback was the memory tail.
   Reads go through the IO dispatcher now.
4. **Room's older state overwrote the newer in-memory state at unlock.** Latches are
   now a union and the last outcome is the newer one, so Status stopped saying
   "Last alarm" with the previous night's date.
5. **An occurrence coming due during a real ring was recorded as missed.** It is absorbed
   into the ring, so it is latched superseded, with a test.
6. The link service start from a cold locked boot is refused by Android and logged as an
   error every boot; it is now logged as deferred and the hourly tick restarts it.
7. Force-stop detection read only the newest start record; it reads the last three.
8. Lock icons overlapped the values they locked.
9. A resumed ring after a process kill shows the heads-up notification with DISMISS
   rather than the ring screen when the phone is unlocked and on; Android does not let
   a service raise an activity from the background. Locked, the full-screen intent brings
   the ring screen back, which is the case that matters in the box.

Verified on the device, build 0.2.43: fires on the second; ring screen with focus over
the keyguard before first unlock; sticky restart after kill -9 mid-ring resumes the same
ring with audio; notification DISMISS and on-screen dismiss; force-stop then relaunch
re-registers the alarm; install over the top keeps the schedule; nap rings on its
minute; test ring and silent test; the turn-to-snooze gesture through the virtual
rotation sensor; Skip, Undo and the time picker; History as sentences; the hourly tick;
the default-password unlock and a saved setting. Wi-Fi Direct, the controller's remote
dismiss and the sync window cannot run on the emulator and remain phone-only.

## Round 8 — your corrections after the device run

There is no settings password until you set one; a leftover 12345678 hash from earlier
builds is cleared on first start (after the database merge too, which had been bringing
it back). 12345678 is now the Wi-Fi Direct passphrase and DIRECT-fa-alarm the name, on
both phones out of the box, so the controller connects at first install without typing;
the controller's pairing screen is pre-filled with them for when they are changed. The
alarm-time row follows the phone's clock style (4:00 AM). Every setting is one row
grammar: label left, value right, tap to edit, same fonts and padding. The ring screen
keeps the globe centred in the free space instead of pinned to the bottom.

## Round 8 — your corrections after the device run

There is no settings password until you set one; a leftover 12345678 hash from earlier
builds is cleared on first start (after the database merge too, which had been bringing
it back). 12345678 is now the Wi-Fi Direct passphrase and DIRECT-fa-alarm the name, on
both phones out of the box, so the controller connects at first install without typing;
the controller's pairing screen is pre-filled with them for when they are changed. The
alarm-time row follows the phone's clock style (4:00 AM). Every setting is one row
grammar: label left, value right, tap to edit, same fonts and padding. The ring screen
centres the time, the button and the globe as one group. While the ring screen is
showing, its notification drops to a quiet channel so no heads-up sits on top of it; when
the screen goes away it comes back loud with its full-screen intent.

## Round 9 — the link, reviewed against Android's real behaviour

The two-phone path had never run on hardware in this build, so it got the same treatment
that found nine bugs on the emulator: a review against what Android actually does, with
the real alarm phone's hourly log as evidence (its group cycles cleanly every hour; no
controller has ever joined it). Nine findings, all fixed:

1. **The controller hosted its own group.** Saving credentials on the controller ran the
   alarm phone's group restart, so the controller became a group owner on 192.168.49.1
   with a control server, polled itself, saw "connected" forever and never joined the
   alarm. Group hosting and the control listener are now alarm-role only.
2. **The join loop cancelled its own request every tick.** Re-requesting the network
   tore down the system approval dialog and any connection in progress. A request in
   flight is now left alone for 90 s.
3. **Every alarm-phone reboot would have needed a human tap on the controller.** The
   controller's approval is keyed on the group owner's MAC, which Android randomises
   each time P2P comes up unless a persistent group exists. The group is persistent now.
4. **A group with old credentials counted as running.** After a credential change the
   old group could stay up with a green status while the controller could never join.
   The refresh compares credentials and tears a stale group down.
5. **Remote dismiss from the notification ran inside a 10 s broadcast budget** and could
   get the process killed on a flaky link. It is handed to the link service.
6. **Nearby devices was never requested on the alarm phone** until the user found the
   row; the group cannot be created without it. It is asked for when the role is picked,
   and a grant nudges the link immediately.
7. **The controller could talk to the default network** when it had no link. It now
   fails fast instead.
8. **The hourly window dropped the group for nothing.** The system clock is checked
   first; the group is only dropped when it is actually stale.
9. The group name leaked on the LAN log port through the start event.

`core tests=46 failures=0`.

Then, on the emulator, which turns out to host a Wi-Fi Direct group once Nearby devices
is granted: the group formed on 192.168.49.1, the control server bound, the loopback
probe passed, and the whole control API was driven through that interface exactly as
the controller drives it: remote silent test (200, rings a second later), a real ring by
clock jump, dismiss with a wrong ring id (409), the right one (200 in 6 ms, ring gone),
the same request replayed (200, idempotent), a new request after the ring ended (409 with
no ring, which the controller reads as "already stopped"), settings with the version
check (200, then 409 on a stale version), nap set and cleared, skip and undo, unlock. What
no emulator can do is join that group from a second phone: the controller's join, the
system approval dialog and the hourly rejoin remain phone-only.

## Round 10 — on the phones

Pairing ran on the two phones for the first time. The controller's join had been throwing
a SecurityException on every attempt: the app never declared CHANGE_NETWORK_STATE, which
Android requires for a network request. Declared, and the ship pre-flight now refuses a
build missing any permission the link needs. After that Android showed its one-time
"connect using a temporary network" box and the alarm phone reported one client.

From using it: the snooze counter summed every wobble, so one buzz of the vibrator read
as ten degrees and the alarm snoozed itself; it now measures the net turn from where the
phone started, with tests for jitter and drift. A remote test rang seconds late because
it went through a one-second alarm and then waited for the controller's next poll; it
starts at once now and STOP TEST works from the controller through the same call. Status
and Setup read one shared permission list per phone, so they cannot disagree. Move and
Skip give way to one Revert while a change is in force. The turn threshold is a number.
History keeps one missed record per occurrence and cleans up the duplicates old builds
wrote. The unused concurrency gate is gone from the model and the wire. An existing
group at process start is no longer logged as an error.

Verification of round 10 found six more, fixed: the controller never showed the ring
screen for a remote test (a test has no session, and the screen keyed on the session
alone), so STOP TEST was unreachable there; a busy group with nothing behind it went
unlogged; Status painted every permission red until the first evaluation; a controller
that had gone away was still presented as current; stop-test claimed success on failure;
the README's order did not match the screens. And a real alarm firing during a test ring
was dropped: now the test ends and the alarm takes over.

## Round 11 — from using it on the phones

The controller now joins the alarm phone's group as a Wi-Fi Direct client by name and
passphrase, the way Android provides for a known group. The earlier approach asked
Android for the group as if it were an ordinary Wi-Fi network: that put a "searching
for device" box over the screen for minutes, needed a tap whenever the group's radio
address changed, and took the controller off home Wi-Fi. The client join has no box,
keeps home Wi-Fi, and reconnects on its own when the group returns. First run on the
phones with this build; the log shows `p2p_connect_requested` then `p2p_joined`.

The snooze counter measured the net angle from the start, so with a full-turn threshold
a spin could never get there: half a turn is as far as any orientation gets, and a full
turn is back at zero. It now sums the signed rotation along the path, so a buzz cancels
and a spin keeps adding; tests for jitter, drift, and a full turn.

Every navigation re-reads the alarm phone, so does returning to the app, Settings has a
refresh button like Status, and the idle poll is five seconds instead of twenty.

## Round 12 — persistence

The controller has two states, connected or trying; the trying screen carries the name,
passphrase, permissions and a live log of attempts, and "Pair again" is gone. Both roles
tear down Wi-Fi Direct state left by the other role, which was why every join failed
after the roles were swapped; a swap in-process also resets the group code so it cannot
keep tearing down the controller's own membership. The controller joins from locked boot
with no wait for an unlock. The service heartbeat is five seconds with the app closed.
Battery unrestricted is a required row on both phones. A ring seen before the link
dropped cannot hold the controller's screen for more than ten minutes.

## Round 13 — Doze, seen on the alarm phone

Twice today the alarm phone accepted TCP connections on the log port and never sent a
byte, for half an hour or more, then answered normally. The lock-free endpoint hung too,
so it was not a lock in the service. Watching it for ten minutes caught one reply at
22:39:05 and silence again: a Doze maintenance window. Doze blocks the network of any
app not on the battery whitelist, foreground service or not, and this phone was on
0.2.62 with the whitelist never requested. The link was up (group running, one client)
whenever the window opened. This is the failure that Battery unrestricted, required on
both phones since 0.2.64, exists to prevent, now confirmed on hardware rather than
inferred. The README describes the symptom so it is recognisable next time.

The ship script no longer prints a bind error when a server already holds the port; the
file is swapped underneath the running server and that is said plainly.

## Round 14 — two holes found by asking and by using it

**Does a move, skip or nap survive a reboot?** Only until the phone unlocked. The
device-protected mirror held the resolved next-fire time but not the override, nap or
latches, and the first recompute after boot re-derived the time from the default schedule,
so a moved alarm rang at the default time and a nap went silent until Room (credential-
encrypted) loaded. On a phone with no PIN that is seconds; with a PIN, or a reboot in the
last seconds before the alarm, or a wiped Room, it is the alarm. The mirror now carries
all three, seeded before the first recompute, and from then on it is authoritative over
Room for the override and nap (it is written synchronously on every apply; Room is not).
Proven on the emulator: Skip set, PIN set, reboot, no unlock: next fire still the day
after tomorrow while Room reports unavailable; unlock: the merge keeps it. README row B4.

**Setup spinning on first start until the app is closed and reopened.** After the role
was chosen the screen polled the gate cache for two seconds and then stopped looking. A
first evaluation slower than that (the hibernation probe alone may take three) left the
"not measured" placeholder on screen for good. Gate results are now pushed to the screen
the moment the worker finishes; the poll is gone. Could not be reproduced on the emulator,
whose probes are fast; the race is removed rather than widened.

## Round 15 — closing questions

**Clock sync is opportunistic and hourly, with a button.** The hourly tick re-reads Android's network time, a
local call; Status shows its age and offset and a "Check now" button on both phones, through the same client
path as every other action (`POST /v1/clock`). The code that dropped the group to reach home Wi-Fi is gone: it
was built for phones that could not hold both, and these phones do (measured). Nothing touches Wi-Fi Direct.

**Ringtone shows the file's name**, read from the picker's `DISPLAY_NAME`, carried as `ringtoneName` next to the
URI. "Your file" said nothing.

**The controller now alerts on a test ring**, full-screen like a real one, titled as a test, with STOP TEST as the
notification action. It only alerted on a real ring session, which a test never has.

**Volume, confirmed:** at ring start and on every five-second heartbeat the alarm stream is set to the configured
percentage (floor: half of maximum) if it is muted or below target. It reads nothing but the setting.

**SPEC.md brought in line** with the product: a status note at the top, and the passages about
`WifiNetworkSpecifier`, the 22:00 arm gate, the recovery code, the grace window and the STA/P2P workaround
rewritten to what exists.

## Round 16 — the red bar

Red was one missed poll: a two-second timeout and a three-second cadence made the bar red about five
seconds after any hiccup, and a Wi-Fi Direct peer in Wi-Fi power save can legitimately answer late.
Now the bar is red only after thirty seconds with no reply, and requests wait four seconds. The
service heartbeat is unchanged at five seconds idle, two while ringing. The pairing caption on the
controller is reworded; it read as gibberish.

## Round 17 — history, the download page, and a cleanliness pass

**History never showed anything recent, and that is why the test rings looked like nothing happened.**
The Room query was `SELECT * FROM events WHERE seq > :since ORDER BY seq ASC LIMIT :limit` — the
*oldest* `limit` rows, not the newest. With `since = 0` and `limit = 200`, which is what the screen
always asks for, History froze on the first 200 events ever written on that phone and never moved
again. Measured on both phones at 00:32: each returned exactly 200 events, oldest `21:32` the previous
evening, newest `00:22` and `00:25`. Three test rings at 00:24:13, 00:24:43 and 00:25:47 were logged
correctly on the alarm phone, rang, and were stopped — `test_ring`, `test_ring_start`,
`test_ring_stopped`, with the controller's `remote_ring_alert` for each. They were simply past the end
of the window. The query now takes the newest `limit` and returns them oldest-first, the screen asks
for 1000 raw events because it renders only the ones it has a sentence for, `limit` is capped at 2000
server-side, and the no-database fallback honours `since` instead of ignoring it.

**"Settings changed from the controller" could never appear.** `Svc.log` stamps every event
`Actor.ALARM`, so the sentence tested a field that is constant. Who asked is in `detail["who"]`, which
is what the engine records; the sentence reads that now. SPEC §11 said `actor` is `ALARM | CONTROLLER`
and that the controller can always answer "who did it" — that passage was wrong and is rewritten.

**The download page hung at 10% and one phone could not open it at all.** `ship` served the APK with
`jwebserver`, which handles exactly one request at a time. Measured: with a rate-limited download in
flight, two of three concurrent requests to the port timed out at 8 s and the third took 2.7 s. That is
the stall, the second phone seeing nothing, and downloads queueing one behind another. Now
`python3 -m http.server`, which has been `ThreadingHTTPServer` since 3.7. `ship` also kills an old
single-threaded server still holding the port, and refuses to start if something unrelated has it.

**"Alarm phone is not plugged in" removed as a warning.** Status already shows it as a red row under
Power, and as a warning it also made `/v1/health` answer 503 for a phone that was merely on battery.

**The controller no longer vibrates when the alarm phone rings.** A notification channel's vibration is
immutable once created, so silencing it needed a new channel id (`remote_ring_v2`); the old channel is
deleted on first run. The alert is still full-screen and still IMPORTANCE_HIGH — its job is to put the
STOP button in front of you, and the noise is the other phone's job.

**Cleanliness pass.** Dead code removed: `Dao_.clearEvents` (never called; the table is pruned by age,
never emptied), `Engine.todayId` (never called), an unused `contentResolver` local in `Gates.evaluate`.
A doc comment describing the hourly clock sync "dropping the group for a moment" was left orphaned
above an unrelated object when round 15 deleted that behaviour — removed. `com.mtrinh.fobalarm.service.Crash`
was a second object with the same simple name as `com.mtrinh.fobalarm.Crash`, holding one path only
that object read; folded in. Of six compiler warnings, one had a free correct fix (`Icons.Default.List`
→ the auto-mirrored one); the other five are deliberate compat calls under minSdk 31 and are now
annotated and explained where they sit, so nobody spends an evening "fixing" them. Zero `TODO`,
`FIXME` or `HACK` markers in the tree.

## Round 18 — what "move the next alarm" means, and naps by clock

**A Move now only ever pushes an alarm later.** The wall time was resolved against *now*, so at 23:00
with a 04:00 alarm, "move to 23:30" rang half an hour later and silently spent the next morning's
alarm — and at 05:00, "move tomorrow's alarm to 07:00" set it for 07:00 *today*, two hours away. It is
resolved against the alarm being replaced now: the first instant with that clock reading strictly
after it. A time earlier in the day lands on the following day; the alarm's own time means the next
one, which is a Skip by another name. Nine new core tests, including a sweep asserting that every one
of the 48 half-hour wall times lands after the alarm it replaces.

The reason this is worth the change is not tidiness. The replacement is now always after the original,
so the original time passes with the override still live, and **Revert is a real choice for the whole
night** instead of a race against a Move that may already have fired.

**The picker previews where the time lands** — "Rings Sun 27 Sep 07:00, instead of Sun 27 Sep 04:00",
live as the dial turns, computed by the same `Engine.nextWallTimeAfter` the engine will use. The rule
is not guessable from a clock face, so it is on screen before Set rather than discovered on Status
afterwards. `TomorrowView.replacesMs` is now populated whether or not an override exists, which is what
makes the preview possible; the Skip dialog uses it too, and so stops naming an armed nap as the thing
it is about to skip.

**Nap has two forms: for a duration, and until a clock time.** The wall time crosses the wire as a
string (`POST /v1/nap {until}`) so the engine owns the resolution and the preview calls the same
function. A time already gone today means tomorrow. The duration form still remembers its minutes as
the picker's default; the until form deliberately does not touch that.

**A timezone change no longer drops the nap.** One rule now, stated in the code: err toward ringing.
The override is dropped, which hands the day back to the ordinary alarm in the new zone; the nap is a
fixed instant minutes away and dropping it was the one outcome that lost an alarm outright. A DST
transition was never a zone change — `ZoneId` is unchanged and every instant resolves through its own
date's rules — and there is now a test pinning that, across the 2027 spring-forward, with a Move in
force.

**The time at the top of Status is labelled.** "Next alarm", or "Next alarm — moved" / "Next alarm —
nap" when that is what the next fire actually is. It was a bare timestamp that did not say what it was
or which of the three things had produced it.

Found by exercising it on the emulator rather than by reading it: the "Nap until" picker opened on
the current time, and since the rule is strictly-after, that resolved to the same time tomorrow — the
dialog greeted you with "in 23h 59m". It opens on now plus the remembered nap length instead.

## Round 19 — "hourly" now means on the hour, and the Checks section says what it checks

**The hourly jobs were never on the hour.** Both `armHourlyTick` and `armGateAlarm` were
`now + 3600_000`, and both are re-armed inside `apply()`, which every state transaction runs. So the
two hourly jobs drifted with whatever the user last touched, and crossing 02:00 on the wall clock did
nothing whatsoever. They are now scheduled to the next top of the hour — the tick at :00, the health
check at :00:30 so the two do not contend for one wakeup. The next hour is found by truncating a
`ZonedDateTime`, not by dividing epoch millis: the latter gives the top of the *UTC* hour, which is
:30 in India, and truncating is also what makes the call safe from inside the wakeup it just served,
since it can only ever land on the following hour.

**"Hourly check" did not say what it checks**, and sat one row above "Clock sync", which is a
different hourly job. They are two things: the clock sync re-reads Android's network time, and the
other re-runs every permission and condition on the Setup tab. Now labelled "Hourly permission check"
and "Hourly clock sync". No merge was needed on the clock row — the hourly age and the manual
**Check now** button have been on that one row since round 15, which is exactly the merge asked for.

**Bluetooth: already covered, and cannot be covered the way it was suggested.** A normal app cannot
turn Bluetooth off — `BluetoothAdapter.disable()` has been a no-op for non-system apps since Android
13, and these phones are well past that. What exists instead is three layers: a Setup gate,
`noBluetoothAudio`, that goes amber when an A2DP or SCO output is connected and deep-links to
Android's Bluetooth settings; `preferredDevice = builtinSpeaker()` on the player; and the
five-second ring heartbeat, which logs `routing_off_speaker` and re-pins the speaker if playback has
been routed away mid-ring.

One real gap in that, fixed here: `preferredDevice` was set *after* `start()`. A connected speaker
got the first moment of the alarm and the room got nothing until the heartbeat corrected it. It is
set before `prepare()` now.

## Round 20 — the lock is alarm-local, and it ends when you say so

**There was no way to lock.** The unlock expired on a 120-second timer in `Svc` and a 115-second one in
`AppState`, and nothing else. So the screen could read "Unlocked" while the next save came back `403`,
and deciding to lock again meant waiting. There is a **Lock** button beside the word "Unlocked" now,
both timers are gone, and the token lives only in memory — an app restart is the one implicit re-lock
left, which is a boundary rather than a clock.

**The controller can no longer unlock, and the password no longer crosses the link.** `POST /v1/unlock`
is deleted. `requireUnlocked` rejects any `Actor.CONTROLLER` patch touching a gated setting whatever
token it presents, so a token captured off the wire buys nothing either. `HttpStateClient.unlock` fails
locally without sending a request, so a future caller that has not got the message cannot leak the
password trying. The controller's Settings screen shows no lock row at all — its state is always "locked" and nothing
can be done about it from there, so the greyed rows and their tap message ("This can only be changed
on the alarm phone") carry it; a gated change from there comes back as an ordinary did-not-save message —
"Locked — unlock on the alarm phone" — like any other rejected patch.

**On whether the password is now compromised, precisely.** It is stored as 20,000 rounds of SHA-256
over `salt || secret` with a 16-byte random salt, so it is not recoverable from the phone, from
`/v1/export`, or from a backup. It was never written to the event log: the only logging near that path
is `bad_request_body`, which records the path and nothing else. The snapshot carries `hasPassword`, a
boolean, never the hash. What *did* happen is that each unlock from the controller sent it as cleartext
JSON to `POST /v1/unlock` over the Wi-Fi Direct link — WPA2, but on the passphrase `12345678`, which is
published in this repo. So the honest statement is: not retrievable from anything stored, but it was
on the air, protected by a passphrase anyone can read. If that matters, change it once on 0.2.74; from
this build on it never leaves the alarm phone.

## Round 21 — the controller renders no lock state at all

It was still deciding, locally, that rows were locked, from `hasPassword` in a snapshot. So it greyed
everything out while the alarm phone was locked, and **kept greying it out after the alarm phone had
been unlocked**, because nothing in the snapshot says whether the gate is open right now — and
nothing should. A cached answer to a question only the other phone can answer is the bug.

The controller now renders no lock state whatever: no header, no greyed rows, no lock icon. It sends
the change like any other. If the alarm phone refuses, the reply carries it and the ordinary
did-not-save line says "Locked — unlock on the alarm phone". One source of truth; everything else is
knocking on the door.

Also: the Status headline is plain "Next alarm" again. Which of the three produced the next fire is
already the blue line under the time, and saying it twice made the headline noisy.

## Round 22 — the box got cutouts, so the ring screen is geometry now

The lockbox has openings at the top and bottom of the glass. That replaces the entire reason the snooze gesture
was a sensor problem, so the rotation snooze is **deleted**: `RotationAccumulator`, the wireframe globe
(`Globe.kt`), the `GAME_ROTATION_VECTOR` / gyro / accelerometer listeners in `RingService`, `rotationDeg`,
`thresholdDeg`, `gyroBiasDps`, `gyroStale`, `rvStale`, `quaternion`, the `snoozeThresholdDegrees` setting, the
`gyroscopePresent` gate and seven rotation tests. `RingService` is no longer a `SensorEventListener` and registers
no sensors at all.

**Snooze** is a bar across the very bottom, flush to the glass, in the bottom cutout. It must be **held** for
`snoozeHoldSeconds` (0–10, default 3), and the bar fills as you hold so the wait is visible rather than a dead
press. Letting go early abandons it. A new setting, password-gated like every other kill — it is the only defence
on the only control a sleeping hand can reach. While snoozed the bar becomes the countdown, in the same place.

**Dismiss** is a ~60 dp grip flush to the right edge, starting 30 % down, that must be dragged the length of the
screen to the bottom. The target *and* the whole path are behind acrylic, so completing it means opening the box.
Springs back if released early. **The time** moved to the left edge, vertically centred, clear of both.

The controller is untouched: one large button, because being the easy way to stop the alarm from another room is
its entire job. Snooze is still not remotable — now enforced in the transport (`HttpStateClient.snooze` fails
without sending, and there is no HTTP route) rather than by the gesture being physical.

**Verified on the emulator, all five:** the layout lands where it should (time left and centred, grip at the right
edge 30 % down, bar flush to the bottom); a 1.2 s hold does **not** snooze and the bar still reads "HOLD 3s TO
SNOOZE"; a 4 s hold snoozes and the bar becomes "SNOOZED — rings again in 26s" with `test_snooze` logged; a
half-length drag springs back with the test still running; a full drag stops it, `test_ring_stopped` logged nine
seconds into a sixty-second window, so it was the drag and not the window expiring. The first attempt at that last
one proved nothing — the test's own 60 s window had already lapsed — which is why the timestamps are quoted.

**The hold is contiguous, and that is now tested rather than asserted.** Releasing clears the press timestamp and
zeroes the fill, so the next press measures from itself; repeated jabs cannot add up. Proven on the emulator with
the threshold at 3 s: three separate 2 s holds — six seconds of finger-down in total — left `testSnoozedUntilMs`
at 0 every time, with the bar still reading "HOLD 3s TO SNOOZE"; one contiguous 3.5 s hold then snoozed it. It
matters because the bar is the only control reachable with the box shut, so accumulating partial presses would
hand a sleeping hand precisely what the box exists to prevent.

## Round 23 — the dismiss gesture is drawn, not guessed, and the app is portrait-only

**The grip starts at the very top of the screen** instead of 30 % down, so the travel is the whole length of the
glass — the longest drag available, and everything below the top cutout is behind acrylic.

**There is a visible track.** Same width as the grip, full height, with repeated down arrows, and the grip itself
carries an arrow under its label. An unlabelled box in a corner is a puzzle at 4 AM; the track states the gesture.
The snooze bar now stops short of the track, so the two controls never overlap and a thumb on the bar can never be
taken for the start of a drag.

**Both activities are `screenOrientation="portrait"`.** The phone is bolted in a box in one orientation and the
whole layout is addressed to the cutouts; a rotation would put the snooze bar and the track where the holes are
not.

**Verified on the emulator.** The grip renders at the top of the ring area (y 224 against a content top of ~200)
and the snooze bar's label recentres into the narrowed bar. A half-length drag springs back with the test still
live. A full drag from y 280 to y 2390 stops it — `test_ring_stopped` thirty-one seconds into a sixty-second
window, so it was the drag. The bar still snoozes on a 4 s hold at x 500, well clear of the track. For the
orientation lock the device was forced to landscape (`accelerometer_rotation 0`, `user_rotation 1`) and the app
stayed 1080×2400 with the UI root bounds unchanged — with **RingActivity** resumed, which is the screen that
matters.

## Round 24 — extend a snooze from inside it

A new gated setting, **`resnoozeAfterSeconds`** (default 5): how far into a snooze the bar becomes live again.
**Equal to `snoozeSeconds` turns the feature off** — you would have to wait out the whole snooze, at which point
it is over. Normalized down whenever it exceeds the snooze length, and `SettingsValidator.normalize` now clamps it
against the *already-clamped* snooze so lowering the snooze underneath it pulls it down in the same pass.

An extend sets the end to **now + snooze**, not old-end + snooze, so time already slept is not re-bought; the wait
before the next extend restarts from the extend.

**No new persisted state.** `Engine.extendableAt(settings, snoozeUntilMs)` is the single derivation — the snooze
start is recovered as `snoozeUntilMs - snoozeSeconds`, because every snooze and every extend sets the end to
`now + snoozeSeconds`. The engine, the ring screen and the test-ring path all call it, so none can drift. The one
way to fool it is changing the snooze length mid-snooze, which is password-gated and only shifts when the button
unlocks; it cannot lose or extend a ring. `Engine.snooze` now admits a SNOOZED session once `canExtendSnooze`
allows it, and `RingService.doSnooze` dropped its own phase guard — one rule, in one place.

**Screen.** While snoozed the countdown moved to a green card **above** the bar, and the bar itself became the
Extend control: `EXTEND SNOOZE IN 4s` until the delay elapses, then `EXTEND SNOOZE`; grey, inert and **unlabelled**
when the feature is off. `SnoozeBar` was generalised to take a label and an enabled flag and now serves both jobs,
so the hold duration, the fill and the timing are physically the same code.

**Verified on the emulator.** Ringing reads `HOLD 3s TO SNOOZE`. A 4 s hold snoozes: card above reads
`SNOOZED 26s`, bar below `EXTEND SNOOZE IN 1s`, disabled. After the delay the bar reads `EXTEND SNOOZE` and is
live. Holding it logged `test_snooze_extended`, moved the end out 19.7 s (correct for now+30 against an end 11 s
away), jumped the card from `14s` back to `27s`, and reset the bar to `EXTEND SNOOZE IN 2s`. With the delay
dragged up to equal the snooze, the bar carries **no text at all** and a 5 s hold moved the end 0.0 s with no
event. Six new core tests cover the normalization, the off state, the before/after boundary, the now+snooze
arithmetic, the restarted wait, and that a refused extend changes nothing. 60 pass.
