package dev.rotalex.lutter.analysis.diagnostic

/**
 * The diagnostic codes passes 1, 3, 4 and 5 emit — exactly PLAN §17.3's rows for those passes.
 *
 * Passes 2, 6 and 8 own the remaining rows and do not exist yet; their codes arrive
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

    /**
     * The expression rows (§17.3), emitted by pass 5. A fourth member of the same family —
     * `ref.dangling` and `ref.kind_mismatch` — already answers for references outside an
     * expression, so an expression's own failures name the expression instead of borrowing a
     * code that says nothing about where it was found.
     */
    public val ExprUnknownFunction: DiagnosticCode = DiagnosticCode("expr.unknown_function")
    public val ExprTypeMismatch: DiagnosticCode = DiagnosticCode("expr.type_mismatch")
    public val ExprNullableAccess: DiagnosticCode = DiagnosticCode("expr.nullable_access")
    public val ExprUnresolvedRef: DiagnosticCode = DiagnosticCode("expr.unresolved_ref")

    /**
     * The codegen feasibility rows (§17.3). Owned by pass 8, defined here so the
     * generator's result already speaks the catalog before that pass exists.
     */
    public val CodegenNoBinding: DiagnosticCode = DiagnosticCode("codegen.no_binding")
    public val CodegenStrategyUnsupported: DiagnosticCode = DiagnosticCode("codegen.strategy_unsupported")
    public val CodegenNameCollision: DiagnosticCode = DiagnosticCode("codegen.name_collision")

    /**
     * A `TypeRef` the generator cannot spell as Kotlin. Separate from `codegen.no_binding`
     * because the binding was found and read: what is missing is a Kotlin name for a type,
     * which is a fact about the type and not about the component that carries it.
     */
    public val CodegenNoTypeSpelling: DiagnosticCode = DiagnosticCode("codegen.no_type_spelling")
}
