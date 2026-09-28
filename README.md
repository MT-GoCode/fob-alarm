# Fob Alarm

Two phones. One APK. The alarm phone lives locked in a box on the bed and rings at 04:00
no matter what; the controller lives in another room and is the only way to dismiss it
without getting the key.

This file is the **runbook**. Design rationale lives in `SPEC.md`; what changed and why
in `IMPROVEMENTS.md`. If you are reading this years from now and remember nothing,
everything you need is here.

---

## Build and install

```
./ship
```

Runs the core tests, builds one signed release APK (versionCode = git commit count),
pre-flights the manifest, archives the build under `releases/`, serves it on the LAN and
prints a QR. The server is threaded on purpose: the single-threaded one it replaced
served one request at a time, so a phone downloading the 40 MB APK made the page hang
at 10% for the other phone. On each phone: Chrome, scan, download, allow "Install unknown apps" for
Chrome, install. Updating is the same: install over the top. Settings, pairing, password
and history survive.

**There is no ADB on these phones.** Developer Options are carrier-locked. Nothing in
the workflow needs them.

### Reading the phones (there is no logcat)

```
./logs                      # find both phones on the LAN and summarise them
./logs <ip>                 # health: HTTP 200 and "ok": true when nothing is wrong, 503 and sentences otherwise
./logs <ip> logs            # the last 200 events in memory — wiped when the process restarts
./logs <ip> state           # live snapshot
./logs <ip> history         # the persisted history, 90-day retention, survives restarts
./backup <alarm-phone-ip>   # pull state + the last 2000 events into backups/ and commit
```

Port **8766**, read-only, on all interfaces; it never shows the passphrase. The control
port (**8765**) is bound to the Wi-Fi Direct address only, so nothing on home Wi-Fi can
dismiss an alarm.

---

## First use, in order

1. Install on both phones. Open the app. Pick **the alarm phone** on one, **the
   controller** on the other. Each asks you to confirm.
2. Alarm phone, **Setup** tab: tap Allow on each red row until the card turns green
   ("Done"). Both phones ship with the same link name and passphrase (`DIRECT-fa-alarm`
   / `12345678`), so pairing needs no typing. Change them under "Pair the other phone"
   only if you want to.
3. Controller: it starts trying on its own with the same name and passphrase. Its
   screen shows what it is looking for, the permissions it needs (Allow them), and a
   log of each attempt. It joins the alarm phone's group as a Wi-Fi Direct client, no
   box to tap, while staying on your home Wi-Fi. Within a minute the bar at the top of
   both phones is green: "updated 2s ago". Then the Setup tab asks for the rest.
4. Settings on either phone: alarm time, volume, vibrate, stop-after, snooze. Every
   change says "saved" or "not saved" with the reason. Nothing is locked until you set
   a password on the alarm phone; the same password removes it again.

   **Once a password is set, unlocking happens on the alarm phone only.** Unlock at the
   top of its Settings, and **Lock** in the same place when you are done — there is no
   timer, and closing the app locks it too. The controller has no lock row at all: a
   gated change from there comes back "Locked — unlock on the alarm phone", and tapping
   a greyed row says the same. This is why the password never travels over the link.
5. Settings, **Test ring** on the controller. The alarm phone rings within a second; the
   controller shows "ALARM PHONE IS RINGING" and DISMISS IT stops it.

That is the whole product. Status answers "will it ring, and can I stop it from here" in
red or green. Red means it will not ring, and says why.

---

## Move, Skip and Nap

Three one-off changes, all on the Status tab, all bound to **one** alarm. The one after is never
touched, so nothing has to be undone the next day.

- **Move it** — ring the next alarm at a different time. A Move only ever pushes an alarm **later**:
  the time you pick is the first one *after* the alarm it replaces. So with a 04:00 alarm, picking
  07:00 means 07:00 that morning; picking 02:00 means 02:00 the *following* night, not two hours
  before the alarm you were moving. The picker shows the answer as you turn the dial — "Rings Tue 29
  Sep 2:00 AM, instead of Mon 28 Sep 4:00 AM" — so it is never a surprise.
- **Skip it** — the next alarm does not ring. The one after does.
- **Nap** — independent of the schedule. **Nap for** a duration, or **Nap until** a clock time
  (the first one from now, so a time already gone today means tomorrow). Whichever comes first rings,
  nap or alarm; if the real alarm rings first the nap is dropped.

Once a Move or Skip is in force the buttons become **Revert**. To change a Move, Revert and set it
again. Because the replacement always lands after the original, the original time passes with the
change still in force, so Revert is a real choice all night rather than a race.

A Move or Skip clears itself once that alarm has rung or gone by, and is wiped if you change the
default alarm time. All three survive a reboot with nobody unlocking the phone.

**Timezone.** A daylight-saving change is not a timezone change and needs nothing: alarms stay at
their wall clock time. Actually moving the phone to another zone errs toward ringing — the Move is
dropped so the ordinary alarm takes the day back in the new zone, and the nap is kept, because it is
a fixed instant minutes away and dropping it is the only outcome that loses an alarm.

---

## Hardware test before trusting it (do once per build)

Nothing here can be verified by reading code. Rows marked ✓ passed on an Android 15
emulator (IMPROVEMENTS.md rounds 7 and 9); the rest need the two phones. Pairing itself
passed on the phones on 2026-09-27 with build 0.2.48.

| step | do | must see |
|---|---|---|
| A1 ✓ | Set the alarm 3 minutes out, lock both phones, wait | Alarm phone rings at the minute. `logs`: `alarm_fired`, `ring_start`, `foreground_started` |
| A2 ✓ | While ringing, press the power button, rotate the box a quarter turn | Screen comes back to the ring. Globe crosses the threshold, turns green, "SNOOZED" |
| A3 | Controller, DISMISS IT | Stops in under 5 s. `logs`: `dismiss_remote` |
| B1 ✓ | Alarm phone: Android Settings, force stop the app. Reopen it | Status still shows the next alarm. `logs`: `boot`, `recompute` |
| B2 ✓ | Reboot the alarm phone and do **not** unlock it. Set the alarm 3 minutes out from the controller first | It rings. Ring screen shows over the lock screen |
| B4 ✓ | Skip tomorrow (or move it, or set a nap), then reboot the alarm phone and do **not** unlock it | Status still shows the skipped, moved or napped time. `logs`: `recompute init:de` carries that time, before `db_loaded` |
| B3 ✓ | Install a new build over the top with the alarm 5 minutes out | It rings. `logs`: `package_replaced` |
| C1 | Turn the controller off for a minute, turn it back on, lock it in another room. Alarm 3 minutes out | Controller lights up "ALARM PHONE IS RINGING" and the notification DISMISS works cold, under 10 s |
| C2 | Alarm phone `./logs <ip>` after an hour | HTTP 200, `"ok": true`. `logs`: `probe_ok` every ten minutes, `recompute hourly_tick` **at the top of the hour** and `health` thirty seconds later, and no `ap_error` |
| D | Seven nights unattended | `history` shows one `ring_start` per night and no `missed`, `capped`, `crash` or `force_stopped_detected` |

Any failure is a bug in this repo, not something to work around.

---

## What each Setup row means

Three rows block ringing on the alarm phone: **Notifications** (the ring screen and the
link run as a foreground service), **Exact alarms** (granted at install; if it is ever
missing, reinstall) and **Show over the lock screen**. Two more are required on both
phones for the link to last: **Nearby devices** (joining the group) and **Battery:
unrestricted**. Without the second, Doze cuts the app's network whenever the phone has
sat still for a while, opening it again only for a minute every few hours. Seen on the
alarm phone: `./logs` hangs with the port open, the controller's bar goes red, and both
recover for one minute at a time. Granting it ends that.

**No Bluetooth speaker** is amber, not blocking, and worth understanding. If a paired speaker or
pair of earbuds is connected to the alarm phone, Android can route the alarm out of the room. The app
pins playback to the built-in speaker before it starts and re-pins it every five seconds during a
ring (`routing_off_speaker` in the log), but it **cannot turn Bluetooth off** — that has been
blocked for normal apps since Android 13. So: pair nothing with the alarm phone.

**Keep the app active** is the one to care about over months. Android pauses apps that
are not opened for a while, which cancels every alarm. The setting is under the app's
info page, named "Pause app activity if unused" on most phones. Turn it off on the alarm
phone. The row stays visible until it is.

---

## Recovery

**Forgotten password.** There is no recovery code. The password only guards settings;
the alarm still rings and can still be dismissed. To get rid of it: Android Settings →
Apps → Fob Alarm → Storage → **Clear storage**. The hash lives in the app's own data,
both the device-protected mirror and the database, and clearing wipes both. No uninstall
and no re-download needed.

It is a full reset, though: role, pairing, alarm time, ringtone and history go too, and
you land back at the role picker. Set the dozen settings again from `backups/`.

**The alarm phone died.** It is the sole source of truth. `./backup` keeps a copy in
this repo and the controller keeps its own. Provision the replacement per `SPEC.md`
section 12, install, pick the alarm phone, set the same name and passphrase, and the
controller reconnects with no action on that phone.

**Rolling back a bad build.** A release-signed APK cannot be downgraded in place:
uninstall, reinstall from `releases/`, set up again. Take a backup first.

**The app crashed.** The trace is written to the event log on the next start:
`./logs <ip> logs` shows a `crash` event with it.

---

## Keystore

`keystore/fobalarm.jks`, 4096-bit RSA, **valid until 2056**, password in the password
manager and on offline media with the spare key to the box.

**Lose this file and there is no route forward but uninstall and reinstall, which erases
everything.** Never rotate it. The signing key is registered with Google for developer
verification, so a change means re-registration too.

---

## Developer verification

Apps installed on certified Android devices must come from a registered developer,
globally from 2027. This does **not** require Developer Options.

1. Register a **limited distribution account** (free, no government ID, up to 20 devices)
   with `applicationId` `com.mtrinh.fobalarm` and the keystore above. Both become
   immutable at registration.
2. Scan the console's QR once per phone to authorize it. Normal installs work after that.

A replacement phone needs the same one-time authorization.

---

## Maintenance schedule

| when | what |
|---|---|
| Weekly | `./logs` from the Mac. Both phones answer, both `"ok": true` |
| Monthly | `./backup <alarm-phone-ip>` |
| Annually | Open the box. Check for display lift, back-cover gap, or the phone rocking on a flat surface: battery swelling is invisible to software |
| Year 4 | Replace the alarm phone's battery, or the phone. On schedule, not at failure |
| 2028-12-31 | Security support for this SKU ends |

The box must be **vented**, the phone must not touch bedding, and the charger must be
**wired**: vibration is suppressed entirely on some wireless chargers.

Do **not** raise `targetSdk` above 36 without re-running the transport spike: Local
Network Protection is triggered by targetSdk 37, not by an OS update.

---

## Deliberate non-goals

- **No anti-distraction lockdown.** No Device Owner, no kiosk. It is a normal Android
  phone in a locked box. Sign into nothing and disable Chrome, but do not tell yourself
  it is handled.
- **Snooze is not remotable.** It requires picking up the box and turning it. A bathroom
  snooze button would buy you 30 seconds and defeat the mechanism.
- **No fallbacks.** A missed alarm rings when the phone comes back. Nothing else is
  layered on top of the fire path.

---

## Device identity

| tag | phone | lives |
|---|---|---|
| _fill in after first install_ | | |

`deviceId` is a UUID generated on first run. Record which tag is which physical phone:
after a factory reset it changes, which is exactly when you will be reading logs.
