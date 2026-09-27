package com.mtrinh.fobalarm.data

import android.net.Network
import com.mtrinh.fobalarm.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CONTROLLER role. Plain JSON over the bound P2P network.
 *
 * Binds PER SOCKET, never per process: bindProcessToNetwork would also sever this phone
 * from the Mac, breaking its own update channel and log pull. SPEC.md section 1.
 */
class HttpStateClient(
    private val hostProvider: () -> String?,
    private val networkProvider: () -> Network?,
    private val selfProvider: () -> DeviceView,
) : StateClient {

    companion object { const val PORT = 8765 }

    private suspend fun request(
        method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 4000,
    ): String = withContext(Dispatchers.IO) {
        val host = hostProvider() ?: throw ClientError.Transport("no group owner address")
        val url = URL("http://$host:$PORT$path")
        val net = networkProvider()
        val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
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
            when {
                code in 200..299 -> text
                code == 403 -> throw ClientError.Forbidden()
                code == 409 -> {
                    val o = JSONObject(text)
                    val snap = Wire.snapshotFrom(o.getJSONObject("snapshot"))
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
        withSelf(Wire.snapshotFrom(JSONObject(request("GET", "/v1/snapshot"))))
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

    override suspend fun clearNap(requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/nap",
            JSONObject().put("clear", true).put("requestId", requestId)))))
    }

    override suspend fun setOverride(kind: String, time: String?, shiftMinutes: Int?, requestId: String) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/tomorrow",
            JSONObject().put("kind", kind).put("time", time ?: JSONObject.NULL)
                .put("shiftMinutes", shiftMinutes ?: JSONObject.NULL)
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

    override suspend fun import(backup: Backup, token: String?) = runCatching {
        withSelf(Wire.snapshotFrom(JSONObject(request("POST", "/v1/import",
            Wire.backupToJson(backup).put("token", token ?: JSONObject.NULL), 15000))))
    }

    override suspend fun unlock(secret: String) = runCatching {
        JSONObject(request("POST", "/v1/unlock", JSONObject().put("secret", secret))).getString("token")
    }
}
