package com.mtrinh.fobalarm.data

import com.mtrinh.fobalarm.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CONTROLLER role. Plain JSON to the group owner's address, which only exists while this
 * phone is a client of the group: no address, no link, and the call fails at once.
 */
class HttpStateClient(
    private val hostProvider: () -> String?,
    private val selfProvider: () -> DeviceView,
    /** This phone's own failing blocking gates, sent so the peer can show them. */
    private val localBlockers: () -> List<String>,
    /**
     * Anything else this phone reports about itself on the poll. Supplied from :service, where a
     * Context exists -- role, power-save mode, the LAN address. Values are URL-encoded here.
     */
    private val extraParams: () -> Map<String, String>,
    /**
     * The alarm phone's ordinary-network address, learned from its own snapshot while the P2P link
     * was up. Tried only when the P2P host is missing or unreachable.
     */
    private val fallbackHostProvider: () -> String?,
) : StateClient {

    companion object { const val PORT = 8765 }

    /** Which path the last successful request took: "p2p", "wifi", or null if none has. */
    @Volatile override var via: String? = null; private set

    /**
     * P2P first, then the LAN address if P2P is missing or the connection fails.
     *
     * Wi-Fi Direct membership is the fragile part of this system -- a 10 s client-group idle
     * timeout, a 45 s rejoin guard, the alarm phone rebuilding its own group. None of that touches
     * home Wi-Fi, so the fallback turns most of those outages into nothing the owner notices.
     */
    private suspend fun request(
        method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 4000,
    ): String = withContext(Dispatchers.IO) {
        val p2p = hostProvider()
        val lan = fallbackHostProvider()
        if (p2p == null && lan == null) throw ClientError.Transport("not joined to the alarm phone")
        var lastTransport: ClientError.Transport? = null
        for ((candidate, host) in listOfNotNull(p2p?.let { "p2p" to it }, lan?.let { "wifi" to it })) {
            try {
                val out = attempt(candidate, host, method, path, body, timeoutMs)
                via = candidate
                return@withContext out
            } catch (e: ClientError.Transport) {
                // Only a transport failure is worth retrying on the other path. A 403, 409 or a
                // malformed body is the alarm phone's considered answer and must not be re-asked.
                lastTransport = e
            }
        }
        throw lastTransport ?: ClientError.Transport("unreachable")
    }

    private fun attempt(
        via: String, host: String,
        method: String, path: String, body: JSONObject?, timeoutMs: Int,
    ): String {
        val url = URL("http://$host:$PORT$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doInput = true
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            return when {
                code in 200..299 -> text
                code == 403 -> throw ClientError.Forbidden()
                code == 409 -> {
                    val o = runCatching { JSONObject(text) }.getOrNull()
                        ?: throw ClientError.Server(code, text)
                    // A 409 WITHOUT a snapshot is the role-already-claimed answer. Reading
                    // `snapshot` unconditionally used to throw JSONException here, which became a
                    // Transport error, so the rejected phone showed a dead link instead of saying
                    // which role was taken.
                    val snapObj = o.optJSONObject("snapshot")
                        ?: throw ClientError.RoleTaken(o.optString("reason").ifBlank { "role taken" })
                    val snap = Wire.snapshotFrom(snapObj)
                    if (o.optString("reason") == "stale_ring") throw ClientError.StaleRing(snap)
                    else throw ClientError.Conflict(snap)
                }
                else -> throw ClientError.Server(code, text)
            }
        } catch (e: ClientError) {
            throw e
        } catch (e: Exception) {
            // LNP denial surfaces as a concrete errno (EPERM / ECONNABORTED), not a hang.
            // Log the errno rather than treating silence as health.
            throw ClientError.Transport("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * self is filled from LOCAL APIs, never from a round trip -- otherwise rendering our own
     * battery fails exactly when the link is down, which is when you care.
     */
    private fun withSelf(remote: Snapshot): Snapshot =
        remote.copy(self = selfProvider(), peer = remote.self.copy(lastSeenMs = System.currentTimeMillis()))

    override suspend fun snapshot(): Result<Snapshot> = runCatching {
        val me = selfProvider()
        val q = "?deviceId=${me.deviceId}&batteryPct=${me.batteryPct}&plugged=${me.plugged}" +
                "&appVersion=${me.appVersion}" +
                "&blockers=" + localBlockers().joinToString(",") +
                extraParams().entries.joinToString("") { (k, v) ->
                    "&" + k + "=" + java.net.URLEncoder.encode(v, "UTF-8")
                }
        withSelf(Wire.snapshotFrom(JSONObject(request("GET", "/v1/snapshot$q"))))
    }

    override suspend fun dismiss(ringId: String, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/dismiss",
            JSONObject().put("ringId", ringId).put("requestId", requestId)))))
    }

    override suspend fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?) = runCatching {
        val body = Wire.settingsToJson(patch)
            .put("ifVersion", ifVersion).put("requestId", requestId)
            .put("token", token ?: JSONObject.NULL)
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/settings", body))))
    }

    override suspend fun nap(minutes: Int, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/nap",
            JSONObject().put("minutes", minutes).put("requestId", requestId)))))
    }

    override suspend fun napUntil(hhmm: String, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/nap",
            JSONObject().put("until", hhmm).put("requestId", requestId)))))
    }

    override suspend fun clearNap(requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/nap",
            JSONObject().put("clear", true).put("requestId", requestId)))))
    }

    override suspend fun setOverride(kind: String, time: String?, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/tomorrow",
            JSONObject().put("kind", kind).put("time", time ?: JSONObject.NULL)
                .put("requestId", requestId)))))
    }

    override suspend fun clearOverride(requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/tomorrow",
            JSONObject().put("kind", "NONE").put("requestId", requestId)))))
    }

    override suspend fun history(sinceSeq: Long, limit: Int) = runCatching {
        val arr = JSONObject(request("GET", "/v1/history?since=$sinceSeq&limit=$limit")).getJSONArray("events")
        (0 until arr.length()).map { Wire.eventFrom(arr.getJSONObject(it)) }
    }

    override suspend fun export() = runCatching {
        Wire.backupFrom(JSONObject(request("GET", "/v1/export", timeoutMs = 15000)))
    }

    override suspend fun testRing(silent: Boolean, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/test",
            JSONObject().put("silent", silent).put("requestId", requestId)))))
    }

    override suspend fun stopTest() = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/test", JSONObject().put("stop", true)))))
    }
    override suspend fun checkClock() = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/clock", JSONObject()))))
    }

    /**
     * Always fails, locally, WITHOUT a request. The settings password never crosses the
     * link: there is no `/v1/unlock` on the alarm phone to send it to, and this exists
     * so that a caller which has not got that message cannot leak it trying.
     */
    override suspend fun unlock(secret: String): Result<String> =
        Result.failure(ClientError.Forbidden())

    /** Snooze is not remotable, by design. Fails here, sends nothing. */
    override suspend fun snooze(): Result<Snapshot> = Result.failure(ClientError.Forbidden())

    /** Nothing to drop: this phone can never hold an unlock. */
    override suspend fun lock() = Result.success(Unit)
}
