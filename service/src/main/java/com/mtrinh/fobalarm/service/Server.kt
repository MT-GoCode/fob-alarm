package com.mtrinh.fobalarm.service

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
 *  :8765 CONTROL  -- bound to the P2P interface only. Mutating. Dismiss and nap are
 *                    ungated by design, so exposing this on home WiFi would let every
 *                    device on the LAN kill the alarm.
 *  :8766 LOGS     -- all interfaces, GET-only, read-only. The logcat replacement.
 *
 * No application-layer signing on the control port: anyone who can reach it has already
 * joined a WPA2 group using that passphrase. WPA2 is the boundary. SPEC.md section 1.
 */
object Server {
    const val CONTROL_PORT = 8765
    const val LOG_PORT = 8766

    @Volatile var controlStatus = "stopped"
    @Volatile var logStatus = "stopped"
    @Volatile private var started = false

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
            if (control && !Group.running) {
                controlStatus = "waiting for group"
                Thread.sleep(5_000)
                continue
            }
            runCatching {
                val server = if (control)
                    ServerSocket(port, 50, runCatching { InetAddress.getByName("192.168.49.1") }.getOrNull())
                else ServerSocket(port)
                if (control) controlStatus = "listening" else logStatus = "listening"
                Svc.log(if (control) "server_started" else "log_server_started", "port" to port.toString())
                backoffMs = 1_000L
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

    private fun handle(ctx: Context, sock: Socket, control: Boolean) = sock.use { s ->
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        val request = r.readLine() ?: return@use
        val parts = request.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path = parts.getOrElse(1) { "/" }

        var contentLength = 0
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true))
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        // Content-Length is BYTES and this reader yields CHARS, and one read() returns
        // as soon as any data is available. Loop, and stop at the char count we get.
        val body = if (contentLength > 0) {
            val sb = StringBuilder()
            val buf = CharArray(contentLength)
            var guard = 0
            while (sb.length < contentLength && guard++ < 64) {
                val n = r.read(buf, 0, contentLength)
                if (n <= 0) break
                sb.appendRange(buf, 0, n)
                if (!r.ready()) break
            }
            sb.toString()
        } else ""

        val (code, payload) = if (control) route(ctx, method, path, body) else routeLogs(path)
        respond(s, code, payload)
    }

    private fun routeLogs(path: String): Pair<Int, String> = when {
        path.startsWith("/v1/logs") -> 200 to JSONArray().apply {
            Svc.recentEvents(200).forEach { put(Wire.eventToJson(it)) }
        }.toString(2)
        path.startsWith("/v1/state") -> 200 to runCatching {
            Wire.snapshotToJson(Svc.snapshot()).toString(2)
        }.getOrElse { """{"error":"${it.message}"}""" }
        path.startsWith("/v1/history") -> {
            val since = param(path, "since")?.toLongOrNull() ?: 0
            val limit = param(path, "limit")?.toIntOrNull() ?: 200
            200 to JSONObject().put("events", JSONArray().apply {
                Svc.history(since, limit).forEach { put(Wire.eventToJson(it)) }
            }).toString(2)
        }
        else -> 200 to """{"endpoints":["/v1/logs","/v1/state","/v1/history"]}"""
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
                path.startsWith("/v1/snapshot") -> {
                    // The controller piggybacks its own device on the poll it already
                    // makes, so the alarm phone can render both devices. No new endpoint.
                    param(path, "deviceId")?.let { id ->
                        Svc.peerDevice = com.mtrinh.fobalarm.core.DeviceView(
                            batteryPct = param(path, "batteryPct")?.toIntOrNull() ?: -1,
                            plugged = param(path, "plugged") == "true",
                            appVersion = param(path, "appVersion") ?: "?",
                            variant = runCatching {
                                com.mtrinh.fobalarm.core.Variant.valueOf(param(path, "variant") ?: "LIVE")
                            }.getOrDefault(com.mtrinh.fobalarm.core.Variant.LIVE),
                            role = com.mtrinh.fobalarm.core.Role.CONTROLLER,
                            deviceId = id,
                            lastSeenMs = System.currentTimeMillis())
                    }
                    200 to Wire.snapshotToJson(Svc.snapshot()).toString()
                }

                path.startsWith("/v1/dismiss") && method == "POST" ->
                    200 to Wire.snapshotToJson(
                        Svc.dismiss(o.getString("ringId"), rid, Actor.CONTROLLER)).toString()

                path.startsWith("/v1/nap") && method == "POST" ->
                    200 to Wire.snapshotToJson(
                        if (o.optBoolean("clear")) Svc.clearNap(rid, Actor.CONTROLLER)
                        else Svc.nap(o.optInt("minutes", 20), rid, Actor.CONTROLLER)).toString()

                path.startsWith("/v1/tomorrow") && method == "POST" ->
                    200 to Wire.snapshotToJson(Svc.setOverride(
                        o.optString("kind", "NONE"),
                        o.optString("time").takeIf { it.isNotEmpty() && it != "null" },
                        if (o.isNull("shiftMinutes")) null else o.optInt("shiftMinutes"),
                        rid, Actor.CONTROLLER)).toString()

                path.startsWith("/v1/settings") && method == "POST" -> {
                    val patch = Wire.settingsFrom(o, Svc.settings)
                    200 to Wire.snapshotToJson(Svc.patchSettings(
                        o.optLong("ifVersion", -1), patch, rid,
                        o.optString("token").takeIf { it.isNotEmpty() && it != "null" },
                        Actor.CONTROLLER)).toString()
                }

                path.startsWith("/v1/unlock") && method == "POST" ->
                    200 to JSONObject().put("token", Svc.unlock(o.getString("secret"))).toString()

                path.startsWith("/v1/history") -> {
                    val since = param(path, "since")?.toLongOrNull() ?: 0
                    val limit = param(path, "limit")?.toIntOrNull() ?: 200
                    200 to JSONObject().put("events", JSONArray().apply {
                        Svc.history(since, limit).forEach { put(Wire.eventToJson(it)) }
                    }).toString()
                }

                path.startsWith("/v1/export") ->
                    200 to Wire.backupToJson(Svc.export()).toString()

                path.startsWith("/v1/import") && method == "POST" ->
                    200 to Wire.snapshotToJson(Svc.import(Wire.backupFrom(o),
                        o.optString("token").takeIf { it.isNotEmpty() && it != "null" })).toString()

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
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error"
        }
        s.getOutputStream().apply {
            write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
    }
}
