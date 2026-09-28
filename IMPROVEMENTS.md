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
