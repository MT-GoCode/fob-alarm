package com.mtrinh.fobalarm.service

import android.content.Context
import com.mtrinh.fobalarm.core.GateInfo
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Failures that announce themselves. Everything here is something a reviewer had to
 * find by reading; now the phone reports it, and `./logs <ip> health` is pass/fail.
 */
object Health {
    @Volatile var lastRequestMs = 0L
    @Volatile var lastProbeMs = 0L
    @Volatile var lastProbeOk: Boolean? = null
    @Volatile var lastProbeError: String? = null

    /**
     * Loopback self-test: a real POST with a multi-byte body to our own control port.
     * Catches a broken request parser, a dead listener after the group flapped, and a
     * bound-to-nothing socket -- the three transport failures that killed remote
     * dismiss in earlier builds -- on the actual device, every ten minutes, forever.
     */
    fun probe(ctx: Context) {
        if (Server.controlStatus != "listening") return  // nothing to reach yet
        if (System.currentTimeMillis() - lastProbeMs < 10 * 60_000) return
        lastProbeMs = System.currentTimeMillis()
        Thread({
            val result = runCatching {
                val conn = URL("http://192.168.49.1:${Server.CONTROL_PORT}/v1/ping")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val payload = JSONObject().put("echo", "pröbe ✓").toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val back = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (code != 200) error("HTTP $code")
                if (JSONObject(back).optString("echo") != "pröbe ✓") error("echo mismatch")
            }
            lastProbeOk = result.isSuccess
            lastProbeError = result.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
            if (result.isSuccess) Svc.log("probe_ok")
            else {
                Svc.log("probe_fail", "error" to (lastProbeError ?: "?"))
                Server.closeControl()          // heal, not just detect: force a rebind
            }
        }, "self-probe").start()
    }

    /** Only things that mean the alarm will NOT ring. Empty means it will. */
    fun problems(): List<String> = buildList {
        val s = Svc.settings
        if (s.role == com.mtrinh.fobalarm.core.Role.ALARM) {
            if (Svc.lastNextFire == null) add("No alarm is scheduled")
            if (!Scheduler.fireArmed) add("The alarm is not registered with Android")
            Svc.session?.let { if (it.endsByMs < System.currentTimeMillis()) add("A ring is stuck open") }
        }
        val gates = GateEval.current(Svc.app, s, Svc.lastNextFire != null)
        gates.failing().filter { GateInfo.of(it)?.blocking == true }
            .forEach { add("Missing permission: " + (GateInfo.of(it)?.label ?: it)) }
    }

    /** Worth attention, but the alarm still rings. Never drives the red headline. */
    fun warnings(): List<String> = buildList {
        val s = Svc.settings
        if (s.role == com.mtrinh.fobalarm.core.Role.ALARM) {
            if (lastProbeOk == false) add("Remote dismiss self-test failed")
            if (!s.ssid.isNullOrBlank() && !Group.running) add("Not reachable by the controller right now")
            if (!Svc.selfDevice().plugged) add("Alarm phone is not plugged in")
        }
    }

    /** The `/v1/health` verdict: problems OR warnings is a 503. */
    fun everything(): List<String> = problems() + warnings()
}
