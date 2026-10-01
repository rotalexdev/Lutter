package dev.rotalex.lutter.analysis.diagnostic

import kotlin.jvm.JvmInline

/**
 * A catalogued diagnostic code such as `struct.orphan`: the stable contract tests assert.
 *
 * A value class over the catalog in [DiagnosticCodes], never a free string at a call site.
 */
@JvmInline
public value class DiagnosticCode(public val value: String) {
    override fun toString(): String = value
}
