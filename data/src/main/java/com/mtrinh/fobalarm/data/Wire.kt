package com.mtrinh.fobalarm.data

import com.mtrinh.fobalarm.core.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * One JSON codec used by BOTH the server and the client, so the local and remote paths
 * cannot drift. Unknown fields are ignored on read: a newer controller must be able to
 * talk to an older alarm phone rather than break. SPEC.md section 13 (version skew).
 */
object Wire {

    fun snapshotToJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("stateVersion", s.stateVersion)
        put("serverTimeMs", s.serverTimeMs)
        put("bootedAtMs", s.bootedAtMs)
        put("mode", s.mode.name)
        put("gates", JSONObject().apply {
            put("evaluatedAtMs", s.gates.evaluatedAtMs)
            put("scheduleExists", s.gates.scheduleExists)
            put("exactAlarm", s.gates.exactAlarm)
            put("foregroundService", s.gates.foregroundService)
            put("p2pSupported", s.gates.p2pSupported)
            put("gyroscopePresent", s.gates.gyroscopePresent)
            put("staApConcurrent", true)
            put("groupCredentialsSet", s.gates.groupCredentialsSet)
            put("localNetworkPermission", s.gates.localNetworkPermission)
            put("notificationPolicyAccess", s.gates.notificationPolicyAccess)
            put("dndAllowsAlarms", s.gates.dndAllowsAlarms)
            put("volumeNotFixed", s.gates.volumeNotFixed)
            put("fullScreenIntent", s.gates.fullScreenIntent)
            put("notHibernating", s.gates.notHibernating)
            put("thermalOk", s.gates.thermalOk)
            put("audioPlayable", s.gates.audioPlayable)
            put("powerOk", s.gates.powerOk)
            put("vibrationEnabled", s.gates.vibrationEnabled)
            put("freeDiskOk", s.gates.freeDiskOk)
            put("noBluetoothAudio", s.gates.noBluetoothAudio)
            put("allPass", s.gates.allPass)
        })
        put("armGate", s.armGate?.let {
            JSONObject().put("lastRunAtMs", it.lastRunAtMs).put("result", it.result)
                .put("failingGates", JSONArray(it.failingGates))
        } ?: JSONObject.NULL)
        put("nextFire", s.nextFire?.let {
            JSONObject().put("atMs", it.atMs).put("source", it.source.name).put("label", it.label)
        } ?: JSONObject.NULL)
        put("ring", s.ring?.let {
            JSONObject().put("ringId", it.ringId).put("startedAtMs", it.startedAtMs)
                .put("trigger", it.trigger.name).put("phase", it.phase.name)
                .put("snoozeCount", it.snoozeCount)
                .put("snoozeUntilMs", it.snoozeUntilMs ?: JSONObject.NULL)
                .put("endsByMs", it.endsByMs).put("rotationDeg", it.rotationDeg)
                .put("thresholdDeg", it.thresholdDeg).put("gyroBiasDps", it.gyroBiasDps)
                .put("gyroStale", it.gyroStale).put("rvStale", it.rvStale)
                .put("audible", it.audible)
        } ?: JSONObject.NULL)
        put("clock", JSONObject().put("lastSyncAttemptMs", s.clock.lastSyncAttemptMs)
            .put("lastSyncOkMs", s.clock.lastSyncOkMs).put("offsetAppliedMs", s.clock.offsetAppliedMs)
            .put("source", s.clock.source).put("staleByMs", s.clock.staleByMs))
        put("ap", JSONObject().put("ssid", s.ap.ssid ?: JSONObject.NULL).put("running", s.ap.running)
            .put("clientCount", s.ap.clientCount).put("lastStartedAtMs", s.ap.lastStartedAtMs)
            .put("lastError", s.ap.lastError ?: JSONObject.NULL))
        put("self", deviceToJson(s.self))
        put("peer", s.peer?.let { deviceToJson(it) } ?: JSONObject.NULL)
        put("lastOutcome", s.lastOutcome?.let {
            JSONObject().put("kind", it.kind.name).put("atMs", it.atMs)
                .put("occurrenceId", it.occurrenceId).put("ringId", it.ringId ?: JSONObject.NULL)
                .put("snoozeCount", it.snoozeCount)
        } ?: JSONObject.NULL)
        put("appVersion", s.appVersion)
        put("settingsSchemaVersion", s.settingsSchemaVersion)
        put("tomorrow", JSONObject().put("kind", s.tomorrow.kind)
            .put("timeMs", s.tomorrow.timeMs ?: JSONObject.NULL)
            .put("replacesMs", s.tomorrow.replacesMs ?: JSONObject.NULL))
        put("nap", JSONObject().put("armed", s.nap.armed).put("atMs", s.nap.atMs ?: JSONObject.NULL))
        put("settings", settingsToJson(s.settings))
        put("lastEvents", JSONArray().apply { s.lastEvents.forEach { put(eventToJson(it)) } })
    }

    private fun deviceToJson(d: DeviceView) = JSONObject()
        .put("batteryPct", d.batteryPct).put("plugged", d.plugged)
        .put("appVersion", d.appVersion).put("variant", d.variant.name)
        .put("role", d.role?.name ?: JSONObject.NULL).put("deviceId", d.deviceId)
        .put("lastSeenMs", d.lastSeenMs)

    private fun deviceFrom(o: JSONObject) = DeviceView(
        batteryPct = o.optInt("batteryPct", -1),
        plugged = o.optBoolean("plugged", false),
        appVersion = o.optString("appVersion", "?"),
        variant = runCatching { Variant.valueOf(o.optString("variant")) }.getOrDefault(Variant.LIVE),
        role = o.optString("role").takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { Role.valueOf(it) }.getOrNull() },
        deviceId = o.optString("deviceId", "?"),
        lastSeenMs = o.optLong("lastSeenMs", 0),
    )

    fun settingsToJson(s: Settings) = JSONObject()
        .put("role", s.role?.name ?: JSONObject.NULL)
        .put("defaultAlarmTime", s.defaultAlarmTime)
        .put("alarmVolumePercent", s.alarmVolumePercent)
        .put("ringtoneUri", s.ringtoneUri ?: JSONObject.NULL)
        .put("snoozeSeconds", s.snoozeSeconds)
        .put("snoozeThresholdDegrees", s.snoozeThresholdDegrees)
        .put("maxRingMinutes", s.maxRingMinutes)
        .put("napMinutes", s.napMinutes)
        .put("vibrate", s.vibrate)
        .put("armed", s.armed)
        .put("ssid", s.ssid ?: JSONObject.NULL)
        // The passphrase is transmitted: the controller must be able to render and edit it,
        // and the link that carries it is the WPA2 group it unlocks.
        .put("passphrase", s.passphrase ?: JSONObject.NULL)
        .put("hasPassword", s.hasPassword)

    /** Patches carry only the keys present; absent keys keep their current value. */
    fun settingsFrom(o: JSONObject, base: Settings) = base.copy(
        role = o.optString("role").takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: base.role,
        defaultAlarmTime = o.optString("defaultAlarmTime", base.defaultAlarmTime),
        alarmVolumePercent = o.optInt("alarmVolumePercent", base.alarmVolumePercent),
        ringtoneUri = if (o.has("ringtoneUri") && !o.isNull("ringtoneUri")) o.getString("ringtoneUri") else base.ringtoneUri,
        snoozeSeconds = o.optInt("snoozeSeconds", base.snoozeSeconds),
        snoozeThresholdDegrees = o.optInt("snoozeThresholdDegrees", base.snoozeThresholdDegrees),
        maxRingMinutes = o.optInt("maxRingMinutes", base.maxRingMinutes),
        napMinutes = o.optInt("napMinutes", base.napMinutes),
        vibrate = o.optBoolean("vibrate", base.vibrate),
        armed = o.optBoolean("armed", base.armed),
        hasPasswordRemote = o.optBoolean("hasPassword", base.hasPasswordRemote),
        ssid = if (o.has("ssid") && !o.isNull("ssid")) o.getString("ssid") else base.ssid,
        passphrase = if (o.has("passphrase") && !o.isNull("passphrase")) o.getString("passphrase") else base.passphrase,
    )

    fun eventToJson(e: Event) = JSONObject()
        .put("seq", e.seq).put("atMs", e.atMs).put("type", e.type)
        .put("actor", e.actor.name).put("stateVersion", e.stateVersion)
        .put("detail", JSONObject(e.detail as Map<*, *>))

    fun eventFrom(o: JSONObject): Event {
        val d = o.optJSONObject("detail")
        val map = mutableMapOf<String, String>()
        d?.keys()?.forEach { map[it] = d.optString(it) }
        return Event(
            seq = o.optLong("seq"), atMs = o.optLong("atMs"), type = o.optString("type"),
            actor = runCatching { Actor.valueOf(o.optString("actor")) }.getOrDefault(Actor.ALARM),
            stateVersion = o.optLong("stateVersion"), detail = map)
    }

    fun snapshotFrom(o: JSONObject): Snapshot {
        val g = o.getJSONObject("gates")
        fun gb(k: String) = g.optBoolean(k, false)
        val gates = Gates(
            evaluatedAtMs = g.optLong("evaluatedAtMs"),
            scheduleExists = gb("scheduleExists"), exactAlarm = gb("exactAlarm"),
            foregroundService = gb("foregroundService"), p2pSupported = gb("p2pSupported"),
            gyroscopePresent = gb("gyroscopePresent"), staApConcurrent = gb("staApConcurrent"),
            groupCredentialsSet = gb("groupCredentialsSet"),
            localNetworkPermission = gb("localNetworkPermission"),
            notificationPolicyAccess = gb("notificationPolicyAccess"),
            dndAllowsAlarms = gb("dndAllowsAlarms"), volumeNotFixed = gb("volumeNotFixed"),
            fullScreenIntent = gb("fullScreenIntent"), notHibernating = gb("notHibernating"),
            thermalOk = gb("thermalOk"), audioPlayable = gb("audioPlayable"),
            powerOk = gb("powerOk"), vibrationEnabled = gb("vibrationEnabled"),
            freeDiskOk = gb("freeDiskOk"), noBluetoothAudio = gb("noBluetoothAudio"))
        val r = o.optJSONObject("ring")
        val c = o.getJSONObject("clock")
        val ap = o.getJSONObject("ap")
        val t = o.getJSONObject("tomorrow")
        val n = o.getJSONObject("nap")
        val lo = o.optJSONObject("lastOutcome")
        val agO = o.optJSONObject("armGate")
        val nf = o.optJSONObject("nextFire")
        return Snapshot(
            stateVersion = o.optLong("stateVersion"),
            serverTimeMs = o.optLong("serverTimeMs"),
            bootedAtMs = o.optLong("bootedAtMs"),
            mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.WAITING),
            gates = gates,
            armGate = agO?.let {
                ArmGate(it.optLong("lastRunAtMs"), it.optString("result"),
                    it.optJSONArray("failingGates")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList())
            },
            nextFire = nf?.let {
                NextFire(it.optLong("atMs"),
                    runCatching { OccurrenceSource.valueOf(it.optString("source")) }.getOrDefault(OccurrenceSource.SCHEDULED),
                    it.optString("label"))
            },
            ring = r?.let {
                RingView(it.optString("ringId"), it.optLong("startedAtMs"),
                    runCatching { OccurrenceSource.valueOf(it.optString("trigger")) }.getOrDefault(OccurrenceSource.SCHEDULED),
                    runCatching { RingPhase.valueOf(it.optString("phase")) }.getOrDefault(RingPhase.RINGING),
                    it.optInt("snoozeCount"),
                    if (it.isNull("snoozeUntilMs")) null else it.optLong("snoozeUntilMs"),
                    it.optLong("endsByMs"), it.optDouble("rotationDeg", 0.0),
                    it.optInt("thresholdDeg", 120), it.optDouble("gyroBiasDps", 0.0),
                    it.optBoolean("gyroStale"), it.optBoolean("rvStale"), it.optString("audible"))
            },
            clock = ClockView(c.optLong("lastSyncAttemptMs"), c.optLong("lastSyncOkMs"),
                c.optLong("offsetAppliedMs"), c.optString("source"), c.optLong("staleByMs")),
            ap = ApView(ap.optString("ssid").takeIf { it.isNotEmpty() && it != "null" },
                ap.optBoolean("running"), ap.optInt("clientCount"),
                ap.optLong("lastStartedAtMs"),
                ap.optString("lastError").takeIf { it.isNotEmpty() && it != "null" }),
            self = deviceFrom(o.getJSONObject("self")),
            peer = o.optJSONObject("peer")?.let { deviceFrom(it) },
            lastOutcome = lo?.let {
                LastOutcome(runCatching { Outcome.valueOf(it.optString("kind")) }.getOrDefault(Outcome.MISSED),
                    it.optLong("atMs"), it.optString("occurrenceId"),
                    it.optString("ringId").takeIf { s -> s.isNotEmpty() && s != "null" },
                    it.optInt("snoozeCount"))
            },
            appVersion = o.optString("appVersion"),
            settingsSchemaVersion = o.optInt("settingsSchemaVersion"),
            tomorrow = TomorrowView(t.optString("kind", "NONE"),
                if (t.isNull("timeMs")) null else t.optLong("timeMs"),
                if (t.isNull("replacesMs")) null else t.optLong("replacesMs")),
            nap = NapView(n.optBoolean("armed"), if (n.isNull("atMs")) null else n.optLong("atMs")),
            settings = settingsFrom(o.getJSONObject("settings"), Settings()),
            lastEvents = o.optJSONArray("lastEvents")?.let { a ->
                (0 until a.length()).map { eventFrom(a.getJSONObject(it)) }
            } ?: emptyList(),
        )
    }

    fun backupToJson(b: Backup) = JSONObject()
        .put("schemaVersion", b.schemaVersion)
        .put("exportedAtMs", b.exportedAtMs)
        .put("settings", settingsToJson(b.settings))
        .put("auth", JSONObject()
            .put("passwordHash", b.settings.passwordHash ?: JSONObject.NULL)
            .put("passwordSalt", b.settings.passwordSalt ?: JSONObject.NULL))
        .put("events", JSONArray().apply { b.events.forEach { put(eventToJson(it)) } })

    fun backupFrom(o: JSONObject) = Backup(
        schemaVersion = o.optInt("schemaVersion"),
        settings = settingsFrom(o.getJSONObject("settings"), Settings()).let { base ->
            val a = o.optJSONObject("auth") ?: return@let base
            fun str(k: String) = a.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            base.copy(passwordHash = str("passwordHash"), passwordSalt = str("passwordSalt"))
        },
        events = o.optJSONArray("events")?.let { a -> (0 until a.length()).map { eventFrom(a.getJSONObject(it)) } } ?: emptyList(),
        exportedAtMs = o.optLong("exportedAtMs"))
}
