package com.mtrinh.fobalarm.service

import com.mtrinh.fobalarm.core.Role

import android.content.Context
import com.mtrinh.fobalarm.core.Actor
import com.mtrinh.fobalarm.data.Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Two servers, deliberately.
 *
 *  :8765 CONTROL  -- all interfaces. Mutating.
 *  :8766 LOGS     -- all interfaces, GET-only, read-only. The logcat replacement.
 *
 * **The control port used to bind to the P2P address only**, so that nothing on home Wi-Fi could
 * dismiss an alarm. That was given up deliberately, because binding it there also meant the
 * controller was useless the moment Wi-Fi Direct membership flapped -- and it flaps for reasons
 * that have nothing to do with where anyone is standing: a 10 s client-group idle timeout, a 45 s
 * rejoin guard, this phone rebuilding its own group with up to 120 s of backoff. Home Wi-Fi is
 * unaffected by all of it, so it is now a fallback path for dismiss, nap and settings.
 *
 * There is still no application-layer signing, and the owner's threat model is explicit: the
 * defence is not against an attacker on the LAN, it is against the OWNER at 04:00 turning a pocket
 * phone into a controller and dismissing from bed rather than walking to the box. `roleAvailableTo`
 * is that block -- a UI-level refusal, trivially bypassable by anyone willing to craft an HTTP
 * request, and not pretending otherwise. SPEC.md section 1.
 */
object Server {
    const val CONTROL_PORT = 8765
    const val LOG_PORT = 8766

    @Volatile var controlStatus = "stopped"
    @Volatile var logStatus = "stopped"
    @Volatile private var started = false
    /** Held so the group teardown can force a rebind rather than leave a dead listener. */
    @Volatile private var controlSocket: ServerSocket? = null

    /** Called when the P2P interface goes away; the socket bound to it is now useless. */
    fun closeControl() {
        runCatching { controlSocket?.close() }
        controlSocket = null
        controlStatus = "rebinding"
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        thread(isDaemon = true, name = "control") { serve(ctx, CONTROL_PORT, true) }
        thread(isDaemon = true, name = "logs") { serve(ctx, LOG_PORT, false) }
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(8)

    private fun serve(ctx: Context, port: Int, control: Boolean) {
        // The control socket binds to 192.168.49.1, which does not exist until the
        // group is up -- and one transient accept() failure used to kill the listener
        // for the life of the process, making remote dismiss permanently impossible.
        // Both get an outer retry loop.
        var backoffMs = 1_000L
        var quietFailures = 0
        while (true) {
            // The P2P address only exists while the group is up. Waiting for it is the
            // normal state before pairing, not an error worth logging every 15 seconds.
            // The controller never serves control: a listener on its own 192.168.49.1
            // would answer its own polls and it would watch itself instead of the alarm.
            // Only the ALARM phone serves control. A listener on a controller's own 192.168.49.1
            // would answer its own polls and it would watch itself. The group no longer has to be
            // up: the socket binds on all interfaces, so home Wi-Fi works before pairing and
            // during any P2P outage.
            if (control && Svc.settings.role != Role.ALARM) {
                controlStatus = "waiting for group"
                Thread.sleep(5_000)
                continue
            }
            runCatching {
                // All interfaces for both. The control socket is kept in a field so a group
                // teardown can still force a rebind, which is why it is not just `ServerSocket(port)`.
                val server = ServerSocket(port).also { if (control) controlSocket = it }
                if (control) controlStatus = "listening" else logStatus = "listening"
                Svc.log(if (control) "server_started" else "log_server_started", "port" to port.toString())
                backoffMs = 1_000L; quietFailures = 0
                server.use { srv ->
                    while (true) {
                        val sock = srv.accept()
                        sock.soTimeout = 10_000          // a half-open peer must not park a thread forever
                        runCatching { pool.execute { runCatching { handle(ctx, sock, control) } } }
                            .onFailure { runCatching { sock.close() } }
                    }
                }
            }.onFailure {
                if (control) controlStatus = "retrying: ${it.message}" else logStatus = "retrying: ${it.message}"
                // Log the first few, then go quiet: a permanently unbindable address
                // must not bury every other diagnostic on a phone with no logcat.
                if (quietFailures++ < 3) {
                    Svc.log("server_retry", "port" to port.toString(), "error" to it.toString())
                }
            }
            Thread.sleep(backoffMs + (Math.random() * 500).toLong())
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        }
    }

    /** Blocks until exactly [len] bytes are read, or the stream ends. */
    private fun readBody(input: java.io.InputStream, len: Int): String {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    /** One header line, read byte-wise so the body is never swallowed by a decoder. */
    private fun readLine(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) {
                var line = out.toString("UTF-8")
                if (line.endsWith("\r")) line = line.dropLast(1)
                return line
            }
            out.write(b)
        }
    }

    private fun handle(ctx: Context, sock: Socket, control: Boolean) = sock.use { s ->
        val raw = java.io.BufferedInputStream(s.getInputStream())
        val request = readLine(raw) ?: return@use
        val parts = request.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path = parts.getOrElse(1) { "/" }

        var contentLength = 0
        while (true) {
            val line = readLine(raw) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) readBody(raw, contentLength) else ""

        val (code, payload) = if (control) route(ctx, method, path, body) else routeLogs(path)
        Health.lastRequestMs = System.currentTimeMillis()
        respond(s, code, payload)
    }

    private fun routeLogs(path: String): Pair<Int, String> = when {
        path.startsWith("/v1/logs") -> 200 to JSONArray().apply {
            Svc.recentEvents(200).forEach { put(Wire.eventToJson(it)) }
        }.toString(2)
        // Diagnostics only. The group passphrase must never leave the P2P interface:
        // this port listens on every interface, including home WiFi.
        path.startsWith("/v1/state") -> 200 to runCatching {
            val o = Wire.snapshotToJson(Svc.snapshot())
            o.optJSONObject("settings")?.apply {
                remove("passphrase")
                remove("ssid")
            }
            o.optJSONObject("ap")?.remove("ssid")
            o.toString(2)
        }.getOrElse { """{"error":"${it.message}"}""" }
        path.startsWith("/v1/history") -> 200 to historyJson(path, indent = 2)
        path.startsWith("/v1/health") -> {
            val problems = Health.everything()
            (if (problems.isEmpty()) 200 else 503) to JSONObject()
                .put("ok", problems.isEmpty()).put("problems", JSONArray(problems)).toString(2)
        }
        else -> 200 to """{"endpoints":["/v1/health","/v1/logs","/v1/state","/v1/history"]}"""
    }

    /**
     * `/v1/history` is served on BOTH ports and the two copies used to be seven
     * duplicated lines that had to be edited together. One reader, one cap.
     */
    private fun historyJson(path: String, indent: Int): String {
        val since = param(path, "since")?.toLongOrNull() ?: 0
        val limit = (param(path, "limit")?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val o = JSONObject().put("events", JSONArray().apply {
            Svc.history(since, limit).forEach { put(Wire.eventToJson(it)) }
        })
        return if (indent > 0) o.toString(indent) else o.toString()
    }

    private fun param(path: String, key: String): String? =
        path.substringAfter('?', "").split("&")
            .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")

    private fun route(ctx: Context, method: String, path: String, body: String): Pair<Int, String> {
        val parsed = runCatching { if (body.isBlank()) JSONObject() else JSONObject(body) }
        if (parsed.isFailure) {
            // Swallowing this into an empty object made /v1/tomorrow silently CLEAR the
            // user's override and report 200 OK.
            Svc.log("bad_request_body", "path" to path)
            return 400 to """{"error":"malformed json body"}"""
        }
        val o = parsed.getOrThrow()
        val rid = o.optString("requestId").takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        return try {
            when {
                // Loopback self-test target: exercises the full request path (headers,
                // Content-Length, multi-byte body) with no side effects.
                path.startsWith("/v1/ping") && method == "POST" ->
                    200 to JSONObject().put("echo", o.optString("echo"))
                        .put("bytes", body.toByteArray(Charsets.UTF_8).size).toString()

                path.startsWith("/v1/snapshot") -> {
                    // The peer piggybacks its own device on the poll it already makes, so the
                    // alarm phone can render both devices. No new endpoint.
                    val id = param(path, "deviceId")
                    // A MISSING role means CONTROLLER: builds before the sentinel existed send no
                    // role, and this endpoint hardcoded CONTROLLER. Never guess SENTINEL.
                    val role = runCatching {
                        com.mtrinh.fobalarm.core.Role.valueOf(param(path, "role") ?: "CONTROLLER")
                    }.getOrDefault(com.mtrinh.fobalarm.core.Role.CONTROLLER)
                    if (id != null && !Svc.roleAvailableTo(role, id)) {
                        Svc.log("role_claim_rejected", "role" to role.name, "deviceId" to id)
                        // 409 with a reason and NO snapshot: the rejected phone renders the words.
                        409 to JSONObject()
                            .put("reason", "another phone is already the " +
                                "${role.name.lowercase()}; turn that one off for two minutes to take over")
                            .toString()
                    } else {
                        if (id != null) Svc.notePresence(role, Svc.Presence(
                            deviceId = id,
                            lastSeenElapsed = android.os.SystemClock.elapsedRealtime(),
                            batteryPct = param(path, "batteryPct")?.toIntOrNull() ?: -1,
                            plugged = param(path, "plugged") == "true",
                            powerSave = param(path, "powerSave") == "true",
                            reportedPowerSave = param(path, "powerSave") != null,
                            missingGates = param(path, "blockers")
                                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                            appVersion = param(path, "appVersion") ?: "?"))
                        200 to Wire.snapshotToJson(Svc.snapshot()).toString()
                    }
                }

                path.startsWith("/v1/dismiss") && method == "POST" ->
                    200 to Wire.snapshotToJson(
                        Svc.dismiss(o.getString("ringId"), rid, Actor.CONTROLLER)).toString()

                path.startsWith("/v1/nap") && method == "POST" ->
                    200 to Wire.snapshotToJson(
                        when {
                            o.optBoolean("clear") -> Svc.clearNap(rid, Actor.CONTROLLER)
                            o.has("until") -> Svc.napUntil(o.getString("until"), rid, Actor.CONTROLLER)
                            else -> Svc.nap(o.optInt("minutes", 20), rid, Actor.CONTROLLER)
                        }).toString()

                path.startsWith("/v1/tomorrow") && method == "POST" ->
                    200 to Wire.snapshotToJson(Svc.setOverride(
                        o.optString("kind", "NONE"),
                        o.optString("time").takeIf { it.isNotEmpty() && it != "null" },
                        rid, Actor.CONTROLLER)).toString()

                path.startsWith("/v1/settings") && method == "POST" -> {
                    val patch = Wire.settingsFrom(o, Svc.settings)
                    200 to Wire.snapshotToJson(Svc.patchSettings(
                        o.optLong("ifVersion", -1), patch, rid,
                        o.optString("token").takeIf { it.isNotEmpty() && it != "null" },
                        Actor.CONTROLLER)).toString()
                }

                path.startsWith("/v1/clock") && method == "POST" ->
                    200 to Wire.snapshotToJson(Svc.checkClock()).toString()
                path.startsWith("/v1/test") && method == "POST" ->
                    200 to Wire.snapshotToJson(
                        if (o.optBoolean("stop")) Svc.stopTest()
                        else Svc.testRing(o.optBoolean("silent"), rid, fromController = true)).toString()

                // There is deliberately NO /v1/unlock. The settings password is never
                // sent over the link; unlocking happens on the alarm phone, in the hand.
                path.startsWith("/v1/history") -> 200 to historyJson(path, indent = 0)

                path.startsWith("/v1/export") ->
                    200 to Wire.backupToJson(Svc.export()).toString()

                else -> 404 to """{"error":"no such endpoint"}"""
            }
        } catch (e: StaleRingException) {
            409 to JSONObject().put("reason", "stale_ring")
                .put("snapshot", Wire.snapshotToJson(e.snapshot)).toString()
        } catch (e: ConflictException) {
            409 to JSONObject().put("reason", "stale_version")
                .put("snapshot", Wire.snapshotToJson(e.snapshot)).toString()
        } catch (e: ForbiddenException) {
            403 to """{"error":"password required"}"""
        } catch (e: Exception) {
            500 to JSONObject().put("error", e.toString()).toString()
        }
    }

    private fun respond(s: Socket, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            503 -> "Service Unavailable"; else -> "Error"
        }
        s.getOutputStream().apply {
            write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
    }
}
