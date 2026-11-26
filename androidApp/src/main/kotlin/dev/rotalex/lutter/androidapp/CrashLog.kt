package dev.rotalex.lutter.androidapp

import java.io.File

/**
 * Records the next uncaught exception in [target], then lets the platform handle it.
 *
 * ### Why the app records its own crash
 *
 * The build this activity ships in is a nightly, and the person running it is the person who
 * reports what broke. Android's own record of a crash is the logcat buffer, which needs a
 * developer, a cable and `adb` to read — so a trace that only reaches logcat is a trace nobody
 * sends. The editor's crash report screen shows what this file holds on the next launch,
 * selectable, and the only thing the reporter has to do is copy it into a message.
 *
 * ### Why the handler chains
 *
 * Writing the file is bookkeeping, not error handling: the exception still goes to whatever was
 * installed before (the platform's handler, which finishes the process), so a crash stays a crash
 * and a broken frame is not left running. A write that fails is swallowed for the same reason —
 * losing the trace must not change what the app does about the exception.
 */
internal fun installCrashLog(target: File) {
    val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching { target.writeText(error.stackTraceToString()) }
        if (previous != null) previous.uncaughtException(thread, error) else throw error
    }
}

/** The trace [installCrashLog] left, or null when the last launch ended cleanly. */
internal fun readCrashLog(target: File): String? =
    if (target.isFile) target.readText().takeIf { it.isNotBlank() } else null

/** Forgets the record, so the launch after the report opens the editor. */
internal fun clearCrashLog(target: File) {
    runCatching { target.delete() }
}
