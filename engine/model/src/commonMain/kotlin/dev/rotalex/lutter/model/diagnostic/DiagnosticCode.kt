package dev.rotalex.lutter.model.diagnostic

import kotlin.jvm.JvmInline

/**
 * A catalogued code such as `struct.orphan`: the stable contract tests assert.
 *
 * A value class over the catalog in `DiagnosticCodes`, never a free string at a call site.
 *
 * ### Why this is in `:engine:model` and the catalog is not (D29)
 *
 * The distinction is between the *shape* of a finding and the *vocabulary* of one. A code is
 * a value every producer of a finding has to supply, so the type belongs where producers can
 * reach it — and `:engine:editing` is a producer, since a refused command is a finding.
 * The catalog is `DiagnosticCodes`, it is a list of §17.3's rows, and it lives in
 * `:engine:analysis` beside the passes that raise them.
 *
 * That split is also what lets two vocabularies coexist without either one borrowing the
 * other's authority: `struct.orphan` says the document is malformed, `edit.slot_cardinality`
 * says one edit is illegal, and a caller can tell them apart by the prefix without reading a
 * message.
 */
@JvmInline
public value class DiagnosticCode(public val value: String) {
    override fun toString(): String = value
}