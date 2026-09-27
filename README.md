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
prints a QR. On each phone: Chrome, scan, download, allow "Install unknown apps" for
Chrome, install. Updating is the same: install over the top. Settings, pairing, password
and history survive.

**There is no ADB on these phones.** Developer Options are carrier-locked. Nothing in
the workflow needs them.

### Reading the phones (there is no logcat)

```
./logs                      # find both phones on the LAN and summarise them
./logs <ip>                 # health: HTTP 200 and "ok": true when nothing is wrong, 503 and sentences otherwise
./logs <ip> logs            # recent event log
./logs <ip> state           # live snapshot
./logs <ip> history         # full history
./backup <alarm-phone-ip>   # pull state + history into backups/ and commit
```

Port **8766**, read-only, on all interfaces; it never shows the passphrase. The control
port (**8765**) is bound to the Wi-Fi Direct address only, so nothing on home Wi-Fi can
dismiss an alarm.

---

## First use, in order

1. Install on both phones. Open the app. Pick **the alarm phone** on one, **the
   controller** on the other. Each asks you to confirm.
2. Alarm phone, **Setup** tab: tap Allow on each red row until the card turns green
   ("Done"). Then under "Pair the other phone" type a name and passphrase and Save. The
   password is `12345678` until you change it in Settings.
3. Controller: type that name and passphrase, Connect. Its Setup goes green when
   Notifications and Nearby devices are allowed. Within a minute Status says
   "Alarm phone: connected".
4. Settings on either phone: alarm time, volume, vibrate, stop-after, snooze. Every
   change says "saved" or "not saved" with the reason. Change the password on the
   alarm phone.
5. Settings, **Test ring** on the controller. The alarm phone rings within a second; the
   controller shows "ALARM PHONE IS RINGING" and DISMISS IT stops it.

That is the whole product. Status answers "will it ring, and can I stop it from here" in
red or green. Red means it will not ring, and says why.

---

## Hardware test before trusting it (do once per build)

Nothing here can be verified by reading code. Run it with a stopwatch and `./logs`.

| step | do | must see |
|---|---|---|
| A1 | Set the alarm 3 minutes out, lock both phones, wait | Alarm phone rings at the minute. `logs`: `alarm_fired`, `ring_start`, `foreground_started` |
| A2 | While ringing, press the power button, rotate the box a quarter turn | Screen comes back to the ring. Globe crosses the threshold, turns green, "SNOOZED" |
| A3 | Controller, DISMISS IT | Stops in under 5 s. `logs`: `dismiss_remote` |
| B1 | Alarm phone: Android Settings, force stop the app. Reopen it | Status still shows the next alarm. `logs`: `boot`, `recompute` |
| B2 | Reboot the alarm phone and do **not** unlock it. Set the alarm 3 minutes out from the controller first | It rings. Ring screen shows over the lock screen |
| B3 | Install a new build over the top with the alarm 5 minutes out | It rings. `logs`: `package_replaced` |
| C1 | Turn the controller off for a minute, turn it back on, lock it in another room. Alarm 3 minutes out | Controller lights up "ALARM PHONE IS RINGING" and the notification DISMISS works cold, under 10 s |
| C2 | Alarm phone `./logs <ip>` after an hour | HTTP 200, `"ok": true`. `logs`: `sync_group_down` then `sync_group_up` and `server_started`, then `probe_ok` |
| D | Seven nights unattended | `history` shows one `ring_start` per night and no `missed`, `capped`, `crash` or `force_stopped_detected` |

Any failure is a bug in this repo, not something to work around.

---

## What each Setup row means

Three rows block ringing on the alarm phone: **Notifications** (the ring screen and the
link run as a foreground service), **Exact alarms** (granted at install; if it is ever
missing, reinstall) and **Show over the lock screen**. On the controller, **Nearby
devices** also blocks, because it cannot join the link without it.

**Keep the app active** is the one to care about over months. Android pauses apps that
are not opened for a while, which cancels every alarm. The setting is under the app's
info page, named "Pause app activity if unused" on most phones. Turn it off on the alarm
phone. The row stays visible until it is.

---

## Recovery

**Forgotten password.** There is no recovery code. The password only guards settings;
the alarm still rings and can still be dismissed. To reset it: uninstall, reinstall, set
everything again from `backups/` by hand (settings are a dozen values).

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
