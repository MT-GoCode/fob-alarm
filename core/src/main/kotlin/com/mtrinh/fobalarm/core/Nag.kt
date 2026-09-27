package com.mtrinh.fobalarm.core


/**
 * The controller's fallback nag. It does NOT compute its own schedule -- a second
 * implementation of the fire/latch/DST path, on the device with no clock sync and a stale
 * cache, would double the surface of the #1 risk in order to cover it.
 *
 * Instead it watches two INDEPENDENT failure signals that happen to share one guard:
 *
 *   (1 AND 2)  silent failure -- the alarm phone published a nextFire that is now well in
 *              the past, while still answering polls. "Alive, polling fine, didn't ring."
 *   (2 AND 3)  liveness -- no contact at all for 20 minutes.
 *
 * These were ANDed together in an earlier draft, which made the headline case unreachable:
 * if the phone is alive and polling, condition 3 is false by construction, so the
 * conjunction could never fire. SPEC.md, controller fallback nag.
 */
object Nag {
    const val SILENT_FAILURE_GRACE_MS = 90_000L
    const val NO_CONTACT_MS = 20 * 60_000L

    enum class Reason { NONE, SILENT_FAILURE, NO_CONTACT }

    fun evaluate(s: Snapshot?, lastOkMs: Long, nowMs: Long = System.currentTimeMillis()): Reason {
        // (2) the night was not deliberately silent. Applies to both branches.
        val deliberatelySilent = s?.tomorrow?.kind == "SKIP"
        if (deliberatelySilent) return Reason.NONE

        // (3) liveness. Independent of (1). `lastOkMs == 0` means we have NEVER reached
        // the alarm phone, which is the pairing-failure case and needs the nag most.
        if (lastOkMs == 0L) return if (s == null) Reason.NO_CONTACT else Reason.NONE
        if (nowMs - lastOkMs > NO_CONTACT_MS) return Reason.NO_CONTACT

        // (1) silent failure: the alarm phone's OWN published nextFire has gone stale,
        // and no ring session ever opened for it.
        val next = s?.nextFire?.atMs ?: return Reason.NONE
        if (s.ring != null) return Reason.NONE
        if (nowMs - next > SILENT_FAILURE_GRACE_MS) return Reason.SILENT_FAILURE

        return Reason.NONE
    }

    fun headline(r: Reason): String = when (r) {
        Reason.SILENT_FAILURE -> "THE ALARM DID NOT RING"
        Reason.NO_CONTACT -> "Can't reach the alarm phone"
        Reason.NONE -> ""
    }

    fun message(r: Reason): String = when (r) {
        Reason.SILENT_FAILURE -> "It was due and never started. Go and check it."
        Reason.NO_CONTACT -> "Nothing heard for 20 minutes. It still rings on its own."
        Reason.NONE -> ""
    }
}
