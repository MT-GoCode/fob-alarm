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
    data class Waiting(val attempt: Int) : DismissUi()
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
    var lastError by mutableStateOf<String?>(null); private set
    var dismissUi by mutableStateOf<DismissUi>(DismissUi.Idle); private set
    var token by mutableStateOf<String?>(null); private set
    var history by mutableStateOf<List<Event>>(emptyList()); private set

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
    val linkAgeMs: Long get() = if (linkAtMs == 0L) Long.MAX_VALUE / 2 else nowMs - linkAtMs

    /** One ticking clock for the whole UI, so screens do not each run their own loop. */
    var nowMs by mutableLongStateOf(System.currentTimeMillis()); private set

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
                val ringing = snapshot?.ring != null
                delay(if (ringing) fastMs else idleMs)
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
                }
                snapshot = s; lastOkMs = System.currentTimeMillis(); lastError = null
            }
            .onFailure { lastError = it.message }
    }

    fun dismiss() {
        val ringId = snapshot?.ring?.ringId ?: return
        if (System.currentTimeMillis() < buttonLockedUntilMs) return
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            var attempt = 0
            val startedAt = System.currentTimeMillis()
            while (true) {
                attempt++
                dismissUi = DismissUi.Waiting(attempt)
                val r = client.dismiss(ringId, requestId)   // idempotent by content
                r.onSuccess {
                    snapshot = it; lastOkMs = System.currentTimeMillis()
                    dismissUi = DismissUi.Dismissed
                    delay(2000); dismissUi = DismissUi.Idle
                    return@launch
                }
                val e = r.exceptionOrNull()
                when (e) {
                    // 409 is NEVER retried: the alarm phone is healthy, the ring just changed.
                    is ClientError.StaleRing -> {
                        snapshot = e.snapshot; lastOkMs = System.currentTimeMillis()
                        dismissUi = DismissUi.RingChanged("Alarm changed — press again")
                        return@launch
                    }
                    is ClientError.Forbidden -> {
                        dismissUi = DismissUi.RingChanged("Rejected"); return@launch
                    }
                    else -> {
                        if (System.currentTimeMillis() - startedAt > 10_000) {
                            dismissUi = DismissUi.Unreachable("Can't reach alarm phone — use the key")
                            return@launch
                        }
                        delay(700)
                    }
                }
            }
        }
    }

    var testMessage by mutableStateOf<String?>(null); private set

    /** Same path as every other command, so a remote test proves the link too. */
    fun testRing(silent: Boolean) {
        scope.launch {
            testMessage = "Ringing in 10s — lock the phone now"
            client.testRing(silent, UUID.randomUUID().toString())
                .onSuccess { snapshot = it; lastOkMs = System.currentTimeMillis() }
                .onFailure { testMessage = "Test failed: ${it.message}" }
            delay(6000); testMessage = null
        }
    }

    /** Called when returning to the app, so a just-granted permission flips immediately. */
    fun refreshNow() { scope.launch { refresh() } }

    fun nap(minutes: Int) = act { client.nap(minutes, UUID.randomUUID().toString()) }
    fun clearNap() = act { client.clearNap(UUID.randomUUID().toString()) }
    fun overrideTime(hhmm: String) = act { client.setOverride("TIME", hhmm, null, UUID.randomUUID().toString()) }
    fun overrideShift(minutes: Int) = act { client.setOverride("TIME", null, minutes, UUID.randomUUID().toString()) }
    fun overrideSkip() = act { client.setOverride("SKIP", null, null, UUID.randomUUID().toString()) }
    fun clearOverride() = act { client.clearOverride(UUID.randomUUID().toString()) }

    fun patch(edit: (Settings) -> Settings) {
        val s = snapshot ?: return
        act { client.patchSettings(s.stateVersion, edit(s.settings), UUID.randomUUID().toString(), token) }
    }

    fun unlock(secret: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            client.unlock(secret)
                .onSuccess { token = it; onResult(true) }
                .onFailure { onResult(false) }
        }
    }

    fun loadHistory(limit: Int = 200) {
        scope.launch { client.history(0, limit).onSuccess { history = it.reversed() } }
    }

    private fun act(block: suspend () -> Result<Snapshot>) {
        scope.launch {
            block()
                .onSuccess { snapshot = it; lastOkMs = System.currentTimeMillis(); lastError = null }
                .onFailure { e ->
                    lastError = when (e) {
                        is ClientError.Forbidden -> "Password required"
                        is ClientError.Conflict -> "Changed elsewhere — reloading"
                        else -> e.message
                    }
                    if (e is ClientError.Conflict) { snapshot = e.snapshot }
                }
        }
    }
}
