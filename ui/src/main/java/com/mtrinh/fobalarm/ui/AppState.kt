package com.mtrinh.fobalarm.ui

import androidx.compose.runtime.*
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.data.ClientError
import com.mtrinh.fobalarm.data.StateClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** The three remote-dismiss outcomes, never conflated. SPEC.md section 7. */
sealed class DismissUi {
    object Idle : DismissUi()
    object Waiting : DismissUi()
    object Dismissed : DismissUi()
    data class RingChanged(val message: String) : DismissUi()
    data class Unreachable(val message: String) : DismissUi()
}

/**
 * One view model for both roles. It holds a StateClient and never knows whether that
 * client is in-process or across a Wi-Fi Direct link.
 */
class AppState(
    private val client: StateClient,
    private val scope: CoroutineScope,
    /** ALARM role: this client is in-process, so our own poll says nothing about the link. */
    private val isLocal: Boolean = false,
    /** Controller-side: persist each export locally so the alarm phone is not the only copy. */
    private val onExport: ((Backup) -> Unit)? = null,
) {

    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var lastOkMs by mutableLongStateOf(0L); private set
    var dismissUi by mutableStateOf<DismissUi>(DismissUi.Idle); private set
    var token by mutableStateOf<String?>(null); internal set
    private var unlockedAtMs by mutableLongStateOf(0L)
    /** Matches the server's two-minute window, so the screen never claims a dead unlock. */
    val unlocked: Boolean get() = token != null && nowMs - unlockedAtMs < 115_000
    var history by mutableStateOf<List<Event>>(emptyList()); private set
    /** This phone's own gates, even when the snapshot describes the other phone. */
    var localGates by mutableStateOf<Gates?>(null)
    /** Live sensor values during a TEST ring, which has no engine session. */
    var testRotationDeg by mutableStateOf(0.0)
    var testQuaternion by mutableStateOf<DoubleArray?>(null)

    /** Ring changes disable the button briefly so a reflexive second tap cannot kill the new alarm. */
    var buttonLockedUntilMs by mutableLongStateOf(0L); private set
    private var lastRingId: String? = null

    /**
     * Link health means "have the two phones heard from each other", never "did my own
     * in-process call succeed". On the ALARM role that is peer.lastSeenMs; on the
     * CONTROLLER it is our last successful poll.
     */
    private val linkAtMs: Long
        get() = if (isLocal) (snapshot?.peer?.lastSeenMs ?: 0L) else lastOkMs

    val connected: Boolean get() = linkAtMs != 0L && nowMs - linkAtMs < 60_000

    /** One ticking clock for the whole UI, so screens do not each run their own loop. */
    var nowMs by mutableLongStateOf(System.currentTimeMillis()); private set
    /** When this screen started trying, so "still not connected" is measured from a real attempt. */
    val startedMs: Long = System.currentTimeMillis()

    fun startPolling(fastMs: Long = 500, idleMs: Long = 20_000) {
        scope.launch {
            while (true) { nowMs = System.currentTimeMillis(); delay(500) }
        }
        scope.launch {
            var sinceBackup = 0L
            while (true) {
                refresh()
                // The alarm phone is the sole source of truth (invariant 3), so the
                // controller keeps a copy. Piggybacks the poll; no new endpoint.
                if (onExport != null && System.currentTimeMillis() - sinceBackup > 6 * 3600_000L) {
                    client.export().onSuccess { onExport.invoke(it); sinceBackup = System.currentTimeMillis() }
                }
                val s = snapshot
                val live = s?.ring != null || (s != null && s.testUntilMs > s.serverTimeMs)
                delay(if (live) fastMs else idleMs)
            }
        }
    }

    suspend fun refresh() {
        client.snapshot()
            .onSuccess { s ->
                if (s.ring?.ringId != lastRingId) {
                    if (lastRingId != null && s.ring != null) {
                        buttonLockedUntilMs = System.currentTimeMillis() + 1500
                    }
                    lastRingId = s.ring?.ringId
                    // A new ring is a new question; last night's verdict must not answer it.
                    dismissUi = DismissUi.Idle
                }
                snapshot = s
                lastOkMs = System.currentTimeMillis()
            }
            .onFailure { }
    }

    fun dismiss() {
        val ringId = snapshot?.ring?.ringId ?: return
        if (System.currentTimeMillis() < buttonLockedUntilMs) return
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                dismissUi = DismissUi.Waiting
                val r = client.dismiss(ringId, requestId)   // idempotent by content
                r.onSuccess {
                    snapshot = it; lastOkMs = System.currentTimeMillis()
                    ringEndMessage = "Alarm dismissed"
                    dismissUi = DismissUi.Dismissed
                    delay(2000); dismissUi = DismissUi.Idle
                    return@launch
                }
                val e = r.exceptionOrNull()
                when (e) {
                    // 409 is NEVER retried: the alarm phone is healthy, the ring just changed.
                    is ClientError.StaleRing -> {
                        snapshot = e.snapshot; lastOkMs = System.currentTimeMillis()
                        if (e.snapshot.ring == null) {
                            // Already stopped, by the other button or the other phone.
                            ringEndMessage = "Alarm dismissed"
                            dismissUi = DismissUi.Dismissed
                            delay(2000); dismissUi = DismissUi.Idle
                        } else {
                            dismissUi = DismissUi.RingChanged("The alarm changed. Press again in a moment.")
                        }
                        return@launch
                    }
                    is ClientError.Forbidden -> {
                        dismissUi = DismissUi.Unreachable(
                            if (isLocal) "Could not stop it." else "Could not stop it from here. Use the key.")
                        return@launch
                    }
                    else -> {
                        if (System.currentTimeMillis() - startedAt > 10_000) {
                            dismissUi = DismissUi.Unreachable(
                                if (isLocal) "Could not stop it." else "Can't reach the alarm phone. Use the key.")
                            return@launch
                        }
                        delay(700)
                    }
                }
            }
        }
    }

    var testMessage by mutableStateOf<String?>(null); private set
    var testOk by mutableStateOf(true); private set

    /** Same path as every other command, so a remote test proves the link too. */
    fun testRing(silent: Boolean) {
        scope.launch {
            testOk = true
            testMessage = "Ringing"
            client.testRing(silent, UUID.randomUUID().toString())
                .onSuccess { snapshot = it; lastOkMs = System.currentTimeMillis(); delay(1500); refresh() }
                .onFailure {
                    testOk = false
                    testMessage = if (it is ClientError.Transport)
                        "Can't reach the alarm phone" else "It is already ringing"
                }
            delay(11_000); testMessage = null
        }
    }

    /** Called when returning to the app, so a just-granted permission flips immediately. */
    fun refreshNow() { scope.launch { refresh() } }

    fun nap(minutes: Int) = act("Nap") { client.nap(minutes, UUID.randomUUID().toString()) }
    fun clearNap() = act("Nap") { client.clearNap(UUID.randomUUID().toString()) }
    fun overrideTime(hhmm: String) = act("Next alarm") { client.setOverride("TIME", hhmm, UUID.randomUUID().toString()) }
    fun overrideSkip() = act("Skip") { client.setOverride("SKIP", null, UUID.randomUUID().toString()) }
    fun clearOverride() = act("Undo") { client.clearOverride(UUID.randomUUID().toString()) }

    fun patch(label: String = "Setting", edit: (Settings) -> Settings) {
        val s = snapshot ?: return
        act(label) {
            client.patchSettings(s.stateVersion, edit(s.settings), UUID.randomUUID().toString(), token)
        }
    }

    fun unlock(secret: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            client.unlock(secret)
                .onSuccess { token = it; unlockedAtMs = System.currentTimeMillis(); onResult(true) }
                .onFailure { onResult(false) }
        }
    }

    fun loadHistory(limit: Int = 200) {
        scope.launch { client.history(0, limit).onSuccess { history = it.reversed() } }
    }

    /** "Saved" / "Not saved" for every change, because the other phone may not have it. */
    var syncMessage by mutableStateOf<String?>(null); private set

    private fun act(label: String = "Setting", block: suspend () -> Result<Snapshot>) {
        scope.launch {
            syncMessage = "Saving $label"
            // 5s deadline: a change that has not landed by then is reported as failed
            // rather than left spinning.
            val timeout = launch {
                delay(5000)
                if (syncMessage?.startsWith("Saving") == true) {
                    syncMessage = "$label not saved. No reply from the alarm phone."
                }
            }
            block()
                .onSuccess {
                    timeout.cancel()
                    snapshot = it; lastOkMs = System.currentTimeMillis()
                    syncMessage = "$label saved"
                    delay(1800); syncMessage = null
                }
                .onFailure { e ->
                    timeout.cancel()
                    if (e is ClientError.Forbidden) token = null   // stale token reads as locked
                    syncMessage = when (e) {
                        is ClientError.Forbidden -> "$label not saved. Unlock first."
                        is ClientError.Conflict -> "$label not saved. It was changed on the other phone."
                        else -> "$label not saved. Can't reach the alarm phone."
                    }
                    if (e is ClientError.Conflict) snapshot = e.snapshot
                    delay(5000); syncMessage = null
                }
        }
    }

    fun reload() { scope.launch { refresh() } }

    var ringEndMessage by mutableStateOf<String?>(null); private set
    fun clearRingEndMessage() { ringEndMessage = null }

    /** Same path on both phones: the client stops it, wherever it rings. */
    fun stopTest() {
        scope.launch {
            client.stopTest()
                .onSuccess { snapshot = it; lastOkMs = System.currentTimeMillis(); ringEndMessage = "Test stopped" }
                .onFailure { ringEndMessage = "Could not stop the test" }
        }
    }
}
