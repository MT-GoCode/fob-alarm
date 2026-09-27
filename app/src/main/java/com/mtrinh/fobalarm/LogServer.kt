package com.mtrinh.fobalarm

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * There is no logcat on these phones, so the app is its own log sink.
 * Read-only, GET-only, bound on all interfaces so the Mac can curl it over home WiFi.
 * Nothing here can change state.
 */
object LogServer {
    const val PORT = 8766
    @Volatile private var started = false
    @Volatile var status: String = "stopped"

    fun start(ctx: Context) {
        if (started) return
        started = true
        thread(isDaemon = true, name = "logserver") {
            try {
                val server = ServerSocket(PORT)
                status = "listening on $PORT"
                Log.e("logserver_started", "port" to PORT)
                while (true) {
                    val sock = server.accept()
                    thread(isDaemon = true) {
                        runCatching {
                            sock.use { s ->
                                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                                val line = r.readLine() ?: return@use
                                val path = line.split(" ").getOrNull(1) ?: "/"
                                val body = when {
                                    path.startsWith("/v1/logs") -> Log.asJson()
                                    path.startsWith("/v1/facts") -> factsJson(ctx)
                                    path.startsWith("/v1/state") -> stateJson()
                                    else -> """{"endpoints":["/v1/logs","/v1/facts","/v1/state"]}"""
                                }
                                respond(s.getOutputStream(), body)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                status = "failed: ${e.message}"
                Log.e("logserver_failed", "error" to e.toString())
                started = false
            }
        }
    }

    private fun respond(out: OutputStream, body: String) {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes); out.flush()
    }

    private fun factsJson(ctx: Context): String {
        val sb = StringBuilder("{\n")
        Probe.collect(ctx).forEachIndexed { i, f ->
            if (i > 0) sb.append(",\n")
            sb.append("  \"${f.key}\": ${org.json.JSONObject.quote("${f.value}  [${f.verdict}]")}")
        }
        return sb.append("\n}").toString()
    }

    private fun stateJson(): String = org.json.JSONObject()
        .put("nextFireAtMs", DeState.nextFireAtMs)
        .put("hasOpenSession", DeState.hasOpenSession)
        .put("sessionEndsByMs", DeState.sessionEndsByMs)
        .put("ringing", RingService.running)
        .put("audible", RingService.lastAudible)
        .put("defaultAlarmTime", DeState.defaultAlarmTime)
        .put("serverTimeMs", System.currentTimeMillis())
        .toString(2)
}
