package com.mtrinh.fobalarm.data

import com.mtrinh.fobalarm.core.*

/**
 * The frontend never knows whether it is local or remote. It talks to this; only the
 * injected implementation differs. SPEC.md section 5.
 *
 * Note that LocalStateClient returns Result too -- so offline and error rendering are
 * exercised on BOTH phones, and the local path is not a privileged shortcut.
 */
interface StateClient {
    suspend fun snapshot(): Result<Snapshot>
    suspend fun dismiss(ringId: String, requestId: String): Result<Snapshot>
    suspend fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?): Result<Snapshot>
    suspend fun nap(minutes: Int, requestId: String): Result<Snapshot>
    suspend fun clearNap(requestId: String): Result<Snapshot>
    suspend fun setOverride(kind: String, time: String?, shiftMinutes: Int?, requestId: String): Result<Snapshot>
    suspend fun clearOverride(requestId: String): Result<Snapshot>
    suspend fun history(sinceSeq: Long, limit: Int): Result<List<Event>>
    suspend fun export(): Result<Backup>
    suspend fun import(backup: Backup, token: String?): Result<Snapshot>
    suspend fun unlock(secret: String): Result<String>
}

/** Distinguishing these three is what stops the UI saying "fetch the key" about a healthy phone. */
sealed class ClientError(message: String) : Exception(message) {
    class Transport(val cause2: String) : ClientError("transport: $cause2")
    class StaleRing(val snapshot: Snapshot) : ClientError("409 stale ringId")
    class Forbidden : ClientError("403 password required")
    class Conflict(val snapshot: Snapshot) : ClientError("409 stale stateVersion")
    class Server(val code: Int, val body: String) : ClientError("$code $body")
}

/**
 * Implemented by :service. LocalStateClient calls it in-process; the HTTP server calls
 * the same methods for remote requests, so both paths run identical code.
 */
interface AlarmHost {
    fun snapshot(): Snapshot
    fun dismiss(ringId: String, requestId: String, actor: Actor): Snapshot
    fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?, actor: Actor): Snapshot
    fun nap(minutes: Int, requestId: String, actor: Actor): Snapshot
    fun clearNap(requestId: String, actor: Actor): Snapshot
    fun setOverride(kind: String, time: String?, shiftMinutes: Int?, requestId: String, actor: Actor): Snapshot
    fun clearOverride(requestId: String, actor: Actor): Snapshot
    fun history(sinceSeq: Long, limit: Int): List<Event>
    fun export(): Backup
    fun import(backup: Backup, token: String?): Snapshot
    fun unlock(secret: String): String
}

/** ALARM role. In-process; still returns Result so the renderer is exercised identically. */
class LocalStateClient(private val host: AlarmHost) : StateClient {
    override suspend fun snapshot() = runCatching { host.snapshot() }
    override suspend fun dismiss(ringId: String, requestId: String) =
        runCatching { host.dismiss(ringId, requestId, Actor.ALARM) }
    override suspend fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?) =
        runCatching { host.patchSettings(ifVersion, patch, requestId, token, Actor.ALARM) }
    override suspend fun nap(minutes: Int, requestId: String) =
        runCatching { host.nap(minutes, requestId, Actor.ALARM) }
    override suspend fun clearNap(requestId: String) =
        runCatching { host.clearNap(requestId, Actor.ALARM) }
    override suspend fun setOverride(kind: String, time: String?, shiftMinutes: Int?, requestId: String) =
        runCatching { host.setOverride(kind, time, shiftMinutes, requestId, Actor.ALARM) }
    override suspend fun clearOverride(requestId: String) =
        runCatching { host.clearOverride(requestId, Actor.ALARM) }
    override suspend fun history(sinceSeq: Long, limit: Int) = runCatching { host.history(sinceSeq, limit) }
    override suspend fun export() = runCatching { host.export() }
    override suspend fun import(backup: Backup, token: String?) = runCatching { host.import(backup, token) }
    override suspend fun unlock(secret: String) = runCatching { host.unlock(secret) }
}
