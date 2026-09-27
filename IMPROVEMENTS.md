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
- [ ] **TODO** — Verify on device that it never drops out mid-ring. Untested.

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
- [ ] **TODO** — **Commonise fonts and sizes.** There are still ~15 distinct hardcoded
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
- [ ] **TODO** — Heartbeat time on status. "last heard" exists; an explicit heartbeat
  timestamp does not.

## 5. Permissions

- [x] **DONE** — Standard Android list: M3 `ListItem`, check/error icon, one line each.
- [x] **DONE** — Never blocks the app.
- [x] **DONE** — Clear banner at the top of Setup when something is missing.
- [ ] **TODO** — **Controller phone: permission rows are not clickable.** They are gated
  on `isAlarmRole`, so on the controller tapping does nothing. The controller has its
  own permissions (notifications, nearby devices) and must be able to grant them. This
  is a real dead control and is the highest-priority item on this list.
- [ ] **TODO** — **Make "proceeds while red" coherent.** Right now "Keep the app active"
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
- [ ] **TODO** — **Default password `12345678`.** Set on first run so the app is gated
  from the start rather than open until someone remembers. Must still be changeable,
  and must never gate dismiss.

## 8. Connectivity

- [x] **DONE** — Hourly cycle: drop the group, join known Wi-Fi, sync the clock, restore
  the group. Never during a ring or a test.
- [x] **DONE** — AP client count corrected. `requestGroupInfo`'s list lags; a peer that
  polled us within a minute counts as connected, plus a
  `WIFI_P2P_CONNECTION_CHANGED` receiver.
- [ ] **TODO** — **Verify the group actually works end to end.** Never tested. The
  reported symptom was "other phone is connected but the first phone doesn't know".
- [ ] **TODO** — **Service alive 24/7.** `LinkService` is a `START_STICKY` foreground
  service started at launch, boot and role change. Confirm it survives days, and that
  it restarts after an OOM kill.

## 9. Client-server feedback

- [x] **DONE** — Snackbar on every screen: "Saving… / Saved / Not saved: …".
- [x] **DONE** — Failure states are distinct and coloured as failures.
- [x] **DONE** — Test ring reports "Could not reach the alarm phone" rather than
  silently doing nothing.
- [ ] **TODO** — Name the setting in the message ("Volume synced") rather than a generic
  "Saved". `act()` already takes a `label` parameter that is never passed.
- [ ] **TODO** — 5-second timeout on the sync message specifically, as asked.

## 10. Layout

- [x] **DONE** — Black bar above the bottom nav. `Scaffold` padding already contained
  the insets and `Page` applied them again; fixed with `consumeWindowInsets`.
- [x] **DONE** — Top inset no longer doubled.
- [x] **DONE** — Bottom nav uses real icons. `NavigationBarItem` draws its selection pill
  around the icon slot, so an empty icon looked wrong.
- [x] **DONE** — Spacing around Apply Credentials and the credential fields.
- [ ] **TODO** — Spacing everywhere else, via the spacing scale in §3.

## 11. Architecture

- [x] **DONE** — One `GateInfo` table in `:core` drives gates, UI and fix actions. Was
  five parallel hand-maintained lists.
- [x] **DONE** — One `Page` container, one `StatusBlock`, one `PasswordDialog`, one
  `Fact`, one `Section`.
- [ ] **TODO** — `Fact` should be an M3 `ListItem`; it is a hand-rolled row with a magic
  118dp key column that breaks under text scaling and RTL.
- [ ] **TODO** — Typography and spacing scale (see §3). This is the "commonize
  everything" ask and it is not done.

## 12. Still unverified on hardware

Everything below has been built and never executed on a phone. This is the honest
boundary and it has not moved all session.

- The alarm actually ringing at a set time.
- Local dismiss.
- The snooze gesture.
- Pairing the two phones.
- Remote dismiss.
- The hourly sync window.
- Whether `LinkService` survives days.

## Priority order

1. Controller permission rows are dead controls (§5).
2. Blocking vs non-blocking permissions must look different (§5).
3. Default password `12345678` (§7).
4. Typography and spacing scale (§3, §10, §11).
5. Name the setting in sync messages (§9).
6. `Fact` → `ListItem` (§11).
7. Heartbeat on status (§4).
8. Then: get it on a phone and test §12 rather than writing more code.
