package dev.rotalex.lutter.desktopapp

import java.io.File

/**
 * Records the next uncaught exception in [target], then lets the platform handle it.
 *
 * ### Why the window records its own crash
 *
 * A packaged build is launched from a menu, so its standard error goes nowhere a person can read:
 * double-clicking the launcher on Windows shows a window that disappears and nothing else. The
 * editor's crash report screen shows what this file holds on the next launch, selectable, so the
 * trace reaches the person who can report it instead of the void behind a shortcut.
 *
 * ### Why the handler chains
 *
 * Writing the file is bookkeeping, not error handling: the exception still goes to whatever was
 * installed before (the toolkit's handler, which ends the process), so a crash stays a crash. A
 * write that fails is swallowed for the same reason — losing the trace must not change what the
 * app does about the exception.
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
