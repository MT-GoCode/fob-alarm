package com.mtrinh.fobalarm

import android.content.Context
import com.mtrinh.fobalarm.service.Svc
import java.io.File

/** A stack trace nobody can read is not a diagnostic. Persist it, surface it at next launch. */
object Crash {
    private fun file(ctx: Context) = com.mtrinh.fobalarm.service.Crash.file(ctx)

    fun write(ctx: Context, thread: String, e: Throwable) {
        com.mtrinh.fobalarm.service.Crash.pending = true
        runCatching {
            file(ctx).writeText(buildString {
                appendLine("at ${System.currentTimeMillis()}")
                appendLine("thread $thread")
                appendLine(e.stackTraceToString().take(8000))
            })
        }
    }

    /** Log the trace into the event log, then delete it. It has been reported. */
    fun reportPending(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        val trace = runCatching { f.readText() }.getOrNull() ?: ""
        Svc.log("crash", "trace" to trace.take(2000))
        runCatching { f.delete() }
        com.mtrinh.fobalarm.service.Crash.pending = false
    }


}
