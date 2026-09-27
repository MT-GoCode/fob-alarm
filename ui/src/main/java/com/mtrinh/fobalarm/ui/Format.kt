package com.mtrinh.fobalarm.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every timestamp renders as an AGE, and an unknown renders as a PROBLEM -- never blank,
 * never "OK". SPEC.md invariant 6.
 */
object Fmt {
    private fun f(p: String) = SimpleDateFormat(p, Locale.getDefault())

    fun clock(ms: Long): String = f("HH:mm").format(Date(ms))
    fun absolute(ms: Long): String = f("EEE d MMM HH:mm").format(Date(ms))

    /** An unknown timestamp is a problem, so it says so. */
    fun age(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0L) return "never"
        val d = now - ms
        if (d < 0) return "in ${duration(-d)}"
        return "${duration(d)} ago"
    }

    fun until(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = ms - now
        return if (d <= 0) "now" else "in ${duration(d)}"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d ${(s % 86400) / 3600}h"
        }
    }

    fun battery(pct: Int, plugged: Boolean): String =
        if (pct < 0) "unknown" else "$pct%, ${if (plugged) "charging" else "on battery"}"
}
