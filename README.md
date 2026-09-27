# Fob Alarm

Two phones. One APK. The alarm phone lives locked in a box on the bed and rings at 04:00
no matter what; the controller lives in another room and is the only way to dismiss it
without getting the key.

This file is the **runbook**. Design rationale lives in `SPEC.md`. If you are reading this
years from now and remember nothing, everything you need is here.

---

## Build and install

```
./ship            # dev flavour: update channel + debug screen compiled in
./ship live       # live flavour: both compiled out
```

`./ship` runs the core tests, builds a signed APK, pre-flights the manifest, archives the
build under `releases/`, serves it on the LAN, and prints a QR. On each phone: Chrome →
scan → download → allow "Install unknown apps" for Chrome → install.

**There is no ADB on these phones.** Developer Options are carrier-locked. Nothing in the
workflow needs them.

### Updating after the first install

Debug screen → enable dev mode (60 min, expires on its own) → set the Mac's IP →
*Check for update*. One tap. Dev mode is off at rest, so the phone does not listen for
builds unless you deliberately open the window.

### Pulling logs (there is no logcat)

```
./logs <phone-ip>           # event log
./logs <phone-ip> state     # live snapshot
./logs <phone-ip> history   # full history
```

Served on port **8766**, read-only, GET-only, on all interfaces. The control port
(**8765**) is bound to the Wi-Fi Direct interface only, so nothing on home WiFi can
dismiss an alarm.

---

## The INIT gates, and what each one means

The app will not arm until every blocking row is green.

| gate | meaning | fix |
|---|---|---|
| `scheduleExists` | No alarm is scheduled at all — a dropped setting or bad migration | Check `defaultAlarmTime` |
| `exactAlarm` | Ring time not guaranteed | Grant exact alarms |
| `notHibernating` | **The single most likely total kill.** Hibernation force-stops the app and cancels every alarm | Settings → unused app restrictions → off |
| `fullScreenIntent` | Ring screen cannot appear over the lock screen | Grant full-screen intent |
| `audioPlayable` | The chosen ringtone cannot be opened | Re-pick the audio file |
| `dndAllowsAlarms` | A DND or Bedtime rule can mute the alarm stream | Delete the rule |
| `volumeNotFixed` | Alarm stream muted or unsettable | Sound settings |
| `gyroscopePresent` | No gyroscope: the snooze gesture cannot work | Wrong phone model — see succession below |
| `vibrationEnabled` | Vibration off, removing the last backstop | Sound → vibration on |
| `powerOk` | Not plugged in, or under 50% | Plug it in |
| `staApConcurrent` | Cannot host the group and stay on home WiFi at once | Log pull and updates need a maintenance window |

**Hibernation is the one to care about.** Its timer is driven only by user interaction —
running a foreground service and holding alarms do not reset it — and the design says you
will never touch the alarm phone. If it hibernates, the alarm dies and the 22:00 warning
cannot fire, because that warning is itself a cancelled alarm.

---

## The nightly arm gate (22:00)

Pass → **absolutely nothing happens.** Silence means healthy.
Fail → **three short chirps** on the alarm phone plus a red controller screen naming the
failing gate. It never disarms: a failing gate that silenced the alarm would be the worst
bug in the system.

---

## Recovery procedures

### Forgotten password

Use the **recovery code** shown once at setup, written on the printed copy of this file in
the box. If both are lost: factory reset + restore from a backup (below).

### The alarm phone died

The alarm phone is the sole source of truth for settings, pairing, password and history.

1. Get the most recent `backups/export-*.json` from this repo.
2. Provision the replacement phone per `SPEC.md` §12.
3. Install, pick ALARM, restore the export.
4. Re-enter **the same SSID and passphrase**. The controller's approval matches on SSID +
   security type and ignores BSSID, so it re-pairs with no action on that phone.

Run `./backup <alarm-phone-ip>` regularly. The controller also keeps a copy.

### Rolling back a bad build

A release-signed APK **cannot be downgraded in place**. Rollback is uninstall + reinstall
from `releases/` + restore from backup. That destroys the database, so take a backup
first. This is why `releases/` keeps every build.

### The app crashed

The trace is persisted and shown on the next launch. `./logs <ip>` also has it.

---

## Keystore

`keystore/fobalarm.jks`, 4096-bit RSA, **valid until 2056**, password in the password
manager and on offline media with the spare key to the box.

**Lose this file and there is no route forward but uninstall/reinstall, which erases
everything.** Never rotate it. The signing key is also registered with Google for
developer verification (below), so a change means re-registration too.

---

## Developer verification

Apps installed on certified Android devices must come from a registered developer —
globally from 2027. This does **not** require Developer Options.

1. Register a **limited distribution account** (free, no government ID, up to 20 devices)
   with `applicationId` `com.mtrinh.fobalarm` and the keystore above. Both become
   immutable at registration, so do it before v1.0.
2. Scan the console's QR once per phone to authorize it. Normal installs work after that.

A replacement phone in 2029 needs the same one-time authorization.

---

## Maintenance schedule

| when | what |
|---|---|
| Annually | Open the box. Check for display lift, back-cover gap, or the phone rocking on a flat surface — battery swelling is invisible to every software gate |
| Year 4 | Replace the alarm phone's battery, or the phone. On schedule, not at failure |
| 2028-12-31 | Security support for this SKU ends |
| Before v1.0 | Register the limited-distribution account |

The box must be **vented**, the phone must not touch bedding, and the charger must be
**wired** — vibration is suppressed entirely on some wireless chargers.

Do **not** raise `targetSdk` above 36 without re-running the transport spike: Local
Network Protection is triggered by targetSdk 37, not by an OS update.

---

## Deliberate non-goals (V1)

- **No anti-distraction lockdown.** No Device Owner, no kiosk, no removed browser. It is a
  normal Android phone in a locked box. Sign into nothing and disable Chrome, but do not
  tell yourself it is handled.
- **Snooze is not remotable.** It requires picking up the box and rotating it 120°. A
  bathroom snooze button would buy you 30 seconds and defeat the mechanism.
- **Force-stop is recoverable but not preventable.** Launching the app re-arms it.

---

## Device identity

| tag | phone | lives |
|---|---|---|
| _fill in after first install_ | | |

`deviceId` is a UUID generated on first run. Record which tag is which physical phone —
after a factory reset it changes, which is exactly when you will be reading logs.
