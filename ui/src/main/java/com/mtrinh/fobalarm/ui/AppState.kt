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
    /**
     * The ONE clock this object reads. Injected because five separate
     * `System.currentTimeMillis()` calls made the "one ticking clock for the whole UI" below a
     * claim rather than a fact, and because a screen test cannot otherwise build the state that
     * matters most -- a joiner holding a snapshot it has not heard about for a minute.
     */
    private val now: () -> Long = System::currentTimeMillis,
) {

    var snapshot by mutableStateOf<Snapshot?>(null); private set
    var lastOkMs by mutableLongStateOf(0L); private set
    var dismissUi by mutableStateOf<DismissUi>(DismissUi.Idle); private set
    var token by mutableStateOf<String?>(null); internal set
    /**
     * Which path the link is using: "p2p", "wifi", or null when local/never. Shown in the bar so a
     * silent fallback is visible -- a controller working over Wi-Fi while Wi-Fi Direct is down
     * looks identical to a healthy link otherwise, and that is worth knowing before bed.
     */
    var via by mutableStateOf<String?>(null); internal set

    /**
     * No clock in this. An unlock that lapses on a timer lets the screen read "Unlocked" while
     * the next save returns 403. It ends when Lock is pressed or the process dies, nothing else.
     */
    val unlocked: Boolean get() = token != null
    var history by mutableStateOf<List<Event>>(emptyList()); private set
    /** This phone's own gates, even when the snapshot describes the other phone. */
    var localGates by mutableStateOf<Gates?>(null)
    /** Live sensor values during a TEST ring, which has no engine session. */

    /** Ring changes disable the button briefly so a reflexive second tap cannot kill the new alarm. */
    var buttonLockedUntilMs by mutableLongStateOf(0L); private set
    private var lastRingId: String? = null

    /**
     * Link health means "have the two phones heard from each other", never "did my own
     * in-process call succeed". On the ALARM role that is peer.lastSeenMs; on the
     * CONTROLLER it is our last successful poll.
     */
    val linkAtMs: Long
        get() = if (isLocal) (snapshot?.peer?.lastSeenMs ?: 0L) else lastOkMs
    /** The last time a request to the other phone failed; a failure flips the link red at once. */
    var lastFailMs by mutableLongStateOf(0L); private set
    fun noteFailure() { lastFailMs = now() }

    /**
     * Connected means: the last thing we heard from the other phone was a success, and it
     * was recent. The controller polls every three seconds, so silence is short-lived.
     */
    // Red means thirty seconds without a reply, not one missed poll: a Wi-Fi Direct peer
    // in power save can be late by a few seconds, and the user should not see red for that.
    val connected: Boolean get() = linkAtMs != 0L && nowMs - linkAtMs < 30_000

    /** One ticking clock for the whole UI, so screens do not each run their own loop. */
    var nowMs by mutableLongStateOf(now()); private set

    /** What the ticking loop does, named so a test can do it without running an infinite loop. */
    internal fun syncClock() { nowMs = now() }

    /** Constantly: half a second while ringing, three seconds idle, one second while red. */
    fun startPolling(fastMs: Long = 500, idleMs: Long = 3_000, retryMs: Long = 1_000) {
        scope.launch {
            while (true) { syncClock(); delay(500) }
        }
        scope.launch {
            var sinceBackup = 0L
            while (true) {
                refresh()
                // The alarm phone is the sole source of truth (invariant 3), so the
                // controller keeps a copy. Piggybacks the poll; no new endpoint.
                if (onExport != null && now() - sinceBackup > 6 * 3600_000L) {
                    client.export().onSuccess { onExport.invoke(it); sinceBackup = now() }
                }
                val s = snapshot
                // Same bound as the ring screen, for the same reason: the fast cadence has
                // to last as long as the ring can, not ten minutes.
                val liveUntil = maxOf(s?.ring?.endsByMs ?: 0L, s?.testUntilMs ?: 0L)
                val live = (s?.ring != null || (s != null && s.testUntilMs > s.serverTimeMs)) &&
                        (isLocal || now() < liveUntil)
                delay(if (live) fastMs else if (!isLocal && !connected) retryMs else idleMs)
            }
        }
    }

    /**
     * The ONE way a snapshot is installed.
     *
     * A new ringId briefly locks the dismiss button and clears the previous verdict, so a
     * reflexive second tap cannot answer a question it never saw. EVERY install goes through
     * here, including the 409 StaleRing path -- that path is precisely where the ring has just
     * changed, so a direct assignment there would leave the button live for the new one.
     */
    private fun accept(s: Snapshot) {
        if (s.ring?.ringId != lastRingId) {
            if (lastRingId != null && s.ring != null) {
                buttonLockedUntilMs = now() + 1500
            }
            lastRingId = s.ring?.ringId
            dismissUi = DismissUi.Idle
        }
        snapshot = s
        lastOkMs = now()
    }

    suspend fun refresh() {
        via = client.via
        client.snapshot()
            .onSuccess { accept(it) }
            .onFailure { if (!isLocal) noteFailure() }
    }

    /**
     * The snooze bar. Local to the alarm phone -- HttpStateClient refuses without
     * sending, so pressing it on the controller can never snooze anything.
     */
    fun snooze() {
        scope.launch { client.snooze().onSuccess { accept(it) } }
    }

    fun dismiss() {
        val ringId = snapshot?.ring?.ringId ?: return
        if (now() < buttonLockedUntilMs) return
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            val startedAt = now()
            while (true) {
                dismissUi = DismissUi.Waiting
                val r = client.dismiss(ringId, requestId)   // idempotent by content
                r.onSuccess {
                    accept(it)
                    ringEndMessage = "Alarm dismissed"
                    dismissUi = DismissUi.Dismissed
                    delay(2000); dismissUi = DismissUi.Idle
                    return@launch
                }
                val e = r.exceptionOrNull()
                when (e) {
                    // 409 is NEVER retried: the alarm phone is healthy, the ring just changed.
                    is ClientError.StaleRing -> {
                        accept(e.snapshot)
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
                        if (!isLocal) noteFailure()
                        if (now() - startedAt > 10_000) {
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
                .onSuccess { accept(it); delay(1500); refresh() }
                .onFailure {
                    if (it is ClientError.Transport && !isLocal) noteFailure()
                    testOk = false
                    testMessage = if (it is ClientError.Transport)
                        "Can't reach the alarm phone" else "It is already ringing"
                }
            delay(11_000); testMessage = null
        }
    }

    fun nap(minutes: Int) = act("Nap") { client.nap(minutes, UUID.randomUUID().toString()) }
    fun napUntil(hhmm: String) = act("Nap") { client.napUntil(hhmm, UUID.randomUUID().toString()) }
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

    fun lock() { scope.launch { client.lock(); token = null } }

    fun unlock(secret: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            client.unlock(secret)
                .onSuccess { token = it; onResult(true) }
                .onFailure { onResult(false) }
        }
    }

    /**
     * The screen shows only events it has a sentence for, and most of what a
     * live phone logs (link churn, recomputes, probes) has none. So it pulls a
     * wide raw window and filters: 200 raw events is under three hours.
     */
    fun loadHistory(limit: Int = 1000) {
        scope.launch {
            client.history(0, limit)
                .onSuccess { history = it.reversed(); lastOkMs = now() }
                .onFailure { if (!isLocal) noteFailure() }
        }
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
                    accept(it)
                    syncMessage = "$label saved"
                    launch { delay(1000); refresh() }     // follow-on state (a test starting, a nap arming)
                    delay(1800); syncMessage = null
                }
                .onFailure { e ->
                    timeout.cancel()
                    if (e is ClientError.Transport && !isLocal) noteFailure()
                    if (e is ClientError.Forbidden) token = null   // stale token reads as locked
                    syncMessage = when (e) {
                        is ClientError.RoleTaken -> "$label not saved. ${e.reason}."
                        is ClientError.Forbidden ->
                            if (isLocal) "$label not saved. Unlock first."
                            else "$label not saved. Locked \u2014 unlock on the alarm phone."
                        is ClientError.Conflict -> "$label not saved. It was changed on the other phone."
                        else -> "$label not saved. Can't reach the alarm phone."
                    }
                    if (e is ClientError.Conflict) accept(e.snapshot)
                    delay(5000); syncMessage = null
                }
        }
    }

    fun reload() { scope.launch { refresh() } }

    var ringEndMessage by mutableStateOf<String?>(null); private set
    fun clearRingEndMessage() { ringEndMessage = null }

    /** Same path on both phones: the client stops it, wherever it rings. */
    fun checkClock() {
        scope.launch {
            client.checkClock()
                .onSuccess { accept(it) }
                .onFailure { if (!isLocal) noteFailure() }
        }
    }

    fun stopTest() {
        scope.launch {
            client.stopTest()
                .onSuccess { accept(it); ringEndMessage = "Test stopped" }
                .onFailure { if (!isLocal) noteFailure(); ringEndMessage = "Could not stop the test" }
        }
    }
}
