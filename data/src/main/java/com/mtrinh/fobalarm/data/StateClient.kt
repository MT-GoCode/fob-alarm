package com.mtrinh.fobalarm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.mtrinh.fobalarm.core.*

/**
 * The frontend never knows whether it is local or remote. It talks to this; only the
 * injected implementation differs. SPEC.md section 5.
 *
 * Note that LocalStateClient returns Result too -- so offline and error rendering are
 * exercised on BOTH phones, and the local path is not a privileged shortcut.
 */
interface StateClient {
    /**
     * Which path the last successful call took -- "p2p", "wifi", or null if none has yet. Part of
     * the interface because the link bar renders it; the alternative was the caller downcasting to
     * the HTTP implementation to find out, which is the caller knowing who it is talking to.
     */
    val via: String? get() = null

    suspend fun snapshot(): Result<Snapshot>
    suspend fun dismiss(ringId: String, requestId: String): Result<Snapshot>
    suspend fun patchSettings(ifVersion: Long, patch: Settings, requestId: String, token: String?): Result<Snapshot>
    suspend fun nap(minutes: Int, requestId: String): Result<Snapshot>
    /** Nap until a wall clock "HH:mm". The instant is resolved on the alarm phone. */
    suspend fun napUntil(hhmm: String, requestId: String): Result<Snapshot>
    suspend fun clearNap(requestId: String): Result<Snapshot>
    suspend fun setOverride(kind: String, time: String?, requestId: String): Result<Snapshot>
    suspend fun clearOverride(requestId: String): Result<Snapshot>
    suspend fun history(sinceSeq: Long, limit: Int): Result<List<Event>>
    suspend fun export(): Result<Backup>
    /** ALARM-local only. The HTTP implementation refuses without sending anything. */
    suspend fun unlock(secret: String): Result<String>
    /** Drop the unlock. Local to whichever phone holds it, which is only ever the alarm. */
    suspend fun lock(): Result<Unit>
    /** ALARM-local only: snoozing has to mean reaching the box. Never sent. */
    suspend fun snooze(): Result<Snapshot>
    /** Touches no latch, no schedule, no override. Invokable from either phone. */
    suspend fun testRing(silent: Boolean, requestId: String): Result<Snapshot>
    suspend fun stopTest(): Result<Snapshot>
    /** Re-reads Android's network time and reports the offset. Local read, no packets. */
    suspend fun checkClock(): Result<Snapshot>
}

/** Distinguishing these three is what stops the UI saying "fetch the key" about a healthy phone. */
sealed class ClientError(message: String) : Exception(message) {
    class Transport(val cause2: String) : ClientError("transport: $cause2")
    class StaleRing(val snapshot: Snapshot) : ClientError("409 stale ringId")
    class Forbidden : ClientError("403 password required")
    class Conflict(val snapshot: Snapshot) : ClientError("409 stale stateVersion")
    /**
     * Another phone already holds this role on the alarm phone. Not a security boundary -- it is
     * the surface-level block that stops a half-asleep owner turning their pocket phone into a
     * controller and dismissing the alarm from bed instead of walking to the box.
     */
    class RoleTaken(val reason: String) : ClientError("409 $reason")
    /**
     * The alarm phone understood and the state says no -- it is already ringing. Its own type, so
     * the server catches exactly this: catching `IllegalStateException` also swallows our own
     * faults as a 409, and a snapshot-less 409 is read by [HttpStateClient] as [RoleTaken], which
     * would tell the owner a role was taken when the truth is "not now".
     */
    class Busy(val reason: String) : ClientError("409 $reason")
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
    fun napUntil(hhmm: String, requestId: String, actor: Actor): Snapshot
    fun clearNap(requestId: String, actor: Actor): Snapshot
    fun setOverride(kind: String, time: String?, requestId: String, actor: Actor): Snapshot
    fun clearOverride(requestId: String, actor: Actor): Snapshot
    fun history(sinceSeq: Long, limit: Int): List<Event>
    fun export(): Backup
    fun unlock(secret: String): String
    fun lock()
    fun snooze(): Snapshot
    fun testRing(silent: Boolean, requestId: String): Snapshot
    fun stopTest(): Snapshot
    fun checkClock(): Snapshot
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
    override suspend fun napUntil(hhmm: String, requestId: String) =
        runCatching { host.napUntil(hhmm, requestId, Actor.ALARM) }
    override suspend fun clearNap(requestId: String) =
        runCatching { host.clearNap(requestId, Actor.ALARM) }
    override suspend fun setOverride(kind: String, time: String?, requestId: String) =
        runCatching { host.setOverride(kind, time, requestId, Actor.ALARM) }
    override suspend fun clearOverride(requestId: String) =
        runCatching { host.clearOverride(requestId, Actor.ALARM) }
    // Room refuses reads on the main thread; these two are the only calls that read it.
    override suspend fun history(sinceSeq: Long, limit: Int) =
        withContext(Dispatchers.IO) { runCatching { host.history(sinceSeq, limit) } }
    override suspend fun export() = withContext(Dispatchers.IO) { runCatching { host.export() } }
    override suspend fun unlock(secret: String) = runCatching { host.unlock(secret) }
    override suspend fun lock() = runCatching { host.lock() }
    override suspend fun snooze() = runCatching { host.snooze() }
    override suspend fun testRing(silent: Boolean, requestId: String) =
        runCatching { host.testRing(silent, requestId) }
    override suspend fun stopTest() = runCatching { host.stopTest() }
    override suspend fun checkClock() = runCatching { host.checkClock() }
}
