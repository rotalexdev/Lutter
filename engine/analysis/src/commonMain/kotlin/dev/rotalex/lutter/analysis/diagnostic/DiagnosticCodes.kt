package dev.rotalex.lutter.analysis.diagnostic

/**
 * The diagnostic codes passes 1, 3 and 4 emit — exactly PLAN §17.3's rows for those passes.
 *
 * Passes 2, 5, 6 and 8 own the remaining rows and do not exist yet; their codes arrive
 * with them rather than as untested constants here.
 */
public object DiagnosticCodes {
    public val StructDuplicateId: DiagnosticCode = DiagnosticCode("struct.duplicate_id")
    public val StructMissingNode: DiagnosticCode = DiagnosticCode("struct.missing_node")
    public val StructCycle: DiagnosticCode = DiagnosticCode("struct.cycle")
    public val StructOrphan: DiagnosticCode = DiagnosticCode("struct.orphan")
    public val StructMultipleParents: DiagnosticCode = DiagnosticCode("struct.multiple_parents")
    public val StructMissingRoot: DiagnosticCode = DiagnosticCode("struct.missing_root")
    public val ComponentUnknown: DiagnosticCode = DiagnosticCode("component.unknown")
    public val ComponentSlotUnknown: DiagnosticCode = DiagnosticCode("component.slot_unknown")
    public val ComponentSlotCardinality: DiagnosticCode = DiagnosticCode("component.slot_cardinality")
    public val ComponentChildNotAllowed: DiagnosticCode = DiagnosticCode("component.child_not_allowed")
    public val ComponentConflictingProperties: DiagnosticCode =
        DiagnosticCode("component.conflicting_properties")
    public val PropUnknown: DiagnosticCode = DiagnosticCode("prop.unknown")
    public val PropRequiredMissing: DiagnosticCode = DiagnosticCode("prop.required_missing")
    public val PropTypeMismatch: DiagnosticCode = DiagnosticCode("prop.type_mismatch")
    public val PropEnumEntryInvalid: DiagnosticCode = DiagnosticCode("prop.enum_entry_invalid")
    public val PropRange: DiagnosticCode = DiagnosticCode("prop.range")
    public val PropNotBindable: DiagnosticCode = DiagnosticCode("prop.not_bindable")
    public val ValueNonFinite: DiagnosticCode = DiagnosticCode("value.non_finite")
    public val ModifierUnknown: DiagnosticCode = DiagnosticCode("modifier.unknown")
    public val ModifierScopeMissing: DiagnosticCode = DiagnosticCode("modifier.scope_missing")
    public val ModifierArgInvalid: DiagnosticCode = DiagnosticCode("modifier.arg_invalid")
    public val RefDangling: DiagnosticCode = DiagnosticCode("ref.dangling")
    public val RefKindMismatch: DiagnosticCode = DiagnosticCode("ref.kind_mismatch")
    public val TokenUnknown: DiagnosticCode = DiagnosticCode("token.unknown")
    public val ResourceUnknown: DiagnosticCode = DiagnosticCode("resource.unknown")
}
