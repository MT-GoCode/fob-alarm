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

    @Volatile var pending: String? = null

    fun reportPending(ctx: Context) {
        com.mtrinh.fobalarm.service.Crash.refresh(ctx)
        val f = file(ctx)
        if (!f.exists()) return
        pending = runCatching { f.readText() }.getOrNull()
        Svc.log("crash_recovered")
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        pending = null
        com.mtrinh.fobalarm.service.Crash.pending = false
    }
}
