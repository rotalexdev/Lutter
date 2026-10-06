package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.scope.ScopeAnalysis
import dev.rotalex.lutter.model.doc.ModifierEntry
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.kind.ValueKinds
import dev.rotalex.lutter.schema.types.EnumTypeSpec

/**
 * Pass 3: every node against its spec — props, rules, slots, modifiers and their scopes.
 *
 * Computed values only fail when the property refuses bindings; typing them is pass 5.
 */
internal class SchemaPass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) : AnalysisPass {
    override val name: String = "schema"
    override val blocking: Boolean = false

    override fun run(document: UiDocument): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        val scopes = ScopeAnalysis.computeScopes(document) { schema.components[it] }
        for (id in document.nodes.ids()) {
            val node = document.nodes[id] ?: continue
            val spec = schema.components[node.type]
            if (spec == null) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ComponentUnknown, DiagnosticLocation(nodeId = id),
                    "Unknown component type '${node.type}' (node '$id')",
                    mapOf("type" to node.type.value, "node" to id.value),
                )
                continue
            }
            checkProps(document, node, spec, diags)
            checkRules(node, spec, diags)
            checkSlots(document, node, spec, diags)
            checkModifiers(document, node, scopes[id] ?: emptySet(), diags)
        }
        return diags
    }

    private fun checkProps(
        document: UiDocument,
        node: Node,
        spec: ComponentSpec,
        diags: MutableList<Diagnostic>,
    ) {
        for ((key, actual) in node.props) {
            val declared = spec.properties.firstOrNull { it.key == key }
            if (declared == null) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.PropUnknown,
                    DiagnosticLocation(nodeId = node.id, property = key),
                    "Property '${key.value}' is not declared by '${node.type}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "property" to key.value),
                )
                continue
            }
            checkValue(document, node.id, key, declared.type, declared.bindable, actual, null, diags)
        }
        for (declared in spec.properties) {
            if (declared.required && declared.key !in node.props) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.PropRequiredMissing,
                    DiagnosticLocation(nodeId = node.id, property = declared.key),
                    "Property '${declared.key.value}' is required by '${node.type}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "property" to declared.key.value),
                )
            }
        }
    }

    private fun checkValue(
        document: UiDocument,
        nodeId: NodeId,
        key: PropertyKey,
        declared: TypeRef,
        bindable: Boolean,
        actual: PropertyValue,
        modifierIndex: Int?,
        diags: MutableList<Diagnostic>,
    ) {
        fun location(): DiagnosticLocation =
            DiagnosticLocation(nodeId = nodeId, property = key, modifierIndex = modifierIndex)

        val const = (actual as? PropertyValue.Const)?.value
        if (const == null) {
            // A computed value on a property that refuses bindings; typing is pass 5.
            if (!bindable) {
                val code = if (modifierIndex == null) DiagnosticCodes.PropNotBindable else DiagnosticCodes.ModifierArgInvalid
                diags += Diagnostic(
                    Severity.Error, code, location(),
                    "Property '${key.value}' does not accept a computed value (node '$nodeId')",
                    mapOf("node" to nodeId.value, "property" to key.value),
                )
            }
            return
        }
        if (!acceptsValue(declared, const)) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.PropTypeMismatch, location(),
                "Property '${key.value}' expects ${labelOf(declared)} but found ${labelOf(const)}",
                mapOf("node" to nodeId.value, "property" to key.value, "expected" to labelOf(declared), "found" to labelOf(const)),
            )
            return
        }
        if (declared is TypeRef.Enum && const is Value.Enum) {
            val entries = enumEntries(document, declared.id)
            if (entries == null || const.entry !in entries) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.PropEnumEntryInvalid, location(),
                    "Entry '${const.entry}' is not a member of enum '${declared.id}' (node '$nodeId')",
                    mapOf("node" to nodeId.value, "property" to key.value, "entry" to const.entry),
                )
            }
            return
        }
        if (!isFiniteValue(const)) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.ValueNonFinite, location(),
                "Property '${key.value}' holds a non-finite number (node '$nodeId')",
                mapOf("node" to nodeId.value, "property" to key.value),
            )
        }
    }

    // PLAN §9.1's row: an enum entry is valid when it exists, against the `EnumTypeSpec` the
    // schema registered for the type. Only a `TypeId` the schema declares nothing for falls back
    // to the document's own enum, so a plugin vocabulary is never re-declared per document.
    // The cast is total: an assembly binding a stub in the types slot has no entry here, and the
    // document answers for it rather than the pass throwing.
    private fun enumEntries(document: UiDocument, id: TypeId): List<String>? =
        (schema.types[id] as? EnumTypeSpec)?.entries?.map { it.name }
            ?: document.enums[id]?.entries?.map { it.name }

    private fun checkRules(node: Node, spec: ComponentSpec, diags: MutableList<Diagnostic>) {
        val present = node.props.keys
        for (rule in spec.rules) {
            if (rule is PropertyRule.MutuallyExclusive) {
                val hit = rule.keys.filter { it in present }.sortedBy { it.value }
                if (hit.size > 1) {
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.ComponentConflictingProperties,
                        DiagnosticLocation(nodeId = node.id, property = hit.first()),
                        "Properties ${hit.map { "'${it.value}'" }} are mutually exclusive (node '${node.id}')",
                        mapOf("node" to node.id.value),
                    )
                }
            } else if (rule is PropertyRule.RequiresOneOf) {
                if (rule.keys.none { it in present }) {
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.ComponentConflictingProperties,
                        DiagnosticLocation(nodeId = node.id, property = rule.keys.sortedBy { it.value }.firstOrNull()),
                        "One of ${rule.keys.sortedBy { it.value }.map { "'${it.value}'" }} is required (node '${node.id}')",
                        mapOf("node" to node.id.value),
                    )
                }
            } else if (rule is PropertyRule.Range) {
                val const = (node.props[rule.key] as? PropertyValue.Const)?.value ?: continue
                val number = numberOf(const) ?: continue
                // Locals, not smart casts: min/max are cross-module public properties.
                val min = rule.min
                val max = rule.max
                if ((min != null && number < min) || (max != null && number > max)) {
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.PropRange,
                        DiagnosticLocation(nodeId = node.id, property = rule.key),
                        "Property '${rule.key.value}' is outside its range (node '${node.id}')",
                        mapOf("node" to node.id.value, "property" to rule.key.value),
                    )
                }
            } else if (rule is PropertyRule.SlotRequires) {
                if (node.slots[rule.slot]?.isNotEmpty() == true && rule.keys.none { it in present }) {
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.ComponentConflictingProperties,
                        DiagnosticLocation(nodeId = node.id, property = rule.keys.sortedBy { it.value }.firstOrNull()),
                        "Slot '${rule.slot.value}' requires one of ${rule.keys.sortedBy { it.value }.map { "'${it.value}'" }} (node '${node.id}')",
                        mapOf("node" to node.id.value, "slot" to rule.slot.value),
                    )
                }
            }
        }
    }

    private fun checkSlots(
        document: UiDocument,
        node: Node,
        spec: ComponentSpec,
        diags: MutableList<Diagnostic>,
    ) {
        for ((slotName, children) in node.slots) {
            val declared = spec.slots.firstOrNull { it.name == slotName }
            if (declared == null) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ComponentSlotUnknown,
                    DiagnosticLocation(nodeId = node.id, path = listOf(slotName.value)),
                    "Slot '${slotName.value}' is not declared by '${node.type}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "slot" to slotName.value),
                )
                continue
            }
            val fits = when (declared.cardinality) {
                Cardinality.ExactlyOne -> children.size == 1
                Cardinality.ZeroOrOne -> children.size <= 1
                Cardinality.Many -> true
            }
            if (!fits) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ComponentSlotCardinality,
                    DiagnosticLocation(nodeId = node.id, path = listOf(slotName.value)),
                    "Slot '${slotName.value}' holds ${children.size} children but takes ${declared.cardinality} (node '${node.id}')",
                    mapOf("node" to node.id.value, "slot" to slotName.value, "found" to children.size.toString()),
                )
            }
            val filter = declared.accepts
            for ((index, child) in children.withIndex()) {
                val childNode = document.nodes[child] ?: continue
                if (schema.components[childNode.type] == null) continue
                val allowed = when (filter) {
                    is ChildFilter.Any -> true
                    is ChildFilter.Only -> childNode.type in filter.types
                    is ChildFilter.Except -> childNode.type !in filter.types
                }
                if (!allowed) {
                    diags += Diagnostic(
                        Severity.Error, DiagnosticCodes.ComponentChildNotAllowed,
                        DiagnosticLocation(nodeId = node.id, path = listOf(slotName.value, index.toString())),
                        "Slot '${slotName.value}' does not accept '${childNode.type}' (node '${node.id}')",
                        mapOf("node" to node.id.value, "slot" to slotName.value, "child" to child.value),
                    )
                }
            }
        }
    }

    private fun checkModifiers(
        document: UiDocument,
        node: Node,
        scopes: Set<ScopeId>,
        diags: MutableList<Diagnostic>,
    ) {
        for ((index, entry) in node.modifiers.withIndex()) {
            val declared = schema.modifiers[entry.type]
            if (declared == null) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ModifierUnknown,
                    DiagnosticLocation(nodeId = node.id, modifierIndex = index),
                    "Unknown modifier type '${entry.type}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "modifier" to entry.type.value),
                )
                continue
            }
            checkModifierArgs(document, node, entry, declared, index, diags)
            val missing = declared.requiresScope - scopes
            if (missing.isNotEmpty()) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ModifierScopeMissing,
                    DiagnosticLocation(nodeId = node.id, modifierIndex = index),
                    "Modifier '${entry.type}' needs ${missing.joinToString(", ") { "'$it'" }} (node '${node.id}')",
                    mapOf("node" to node.id.value, "modifier" to entry.type.value),
                )
            }
        }
    }

    private fun checkModifierArgs(
        document: UiDocument,
        node: Node,
        entry: ModifierEntry,
        declared: ModifierSpec,
        index: Int,
        diags: MutableList<Diagnostic>,
    ) {
        for ((key, actual) in entry.args) {
            val param = declared.params.firstOrNull { it.key == key }
            if (param == null) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ModifierArgInvalid,
                    DiagnosticLocation(nodeId = node.id, property = key, modifierIndex = index),
                    "Modifier '${entry.type}' has no argument '${key.value}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "modifier" to entry.type.value, "property" to key.value),
                )
                continue
            }
            checkValue(document, node.id, key, param.type, param.bindable, actual, index, diags)
        }
        // A required argument the entry does not carry is the modifier's problem, not the value's,
        // so it reports as `arg_invalid` like its other two failures: unknown, or wrongly typed.
        // Nothing downstream survives it — the applier returns the chain untouched and codegen
        // would emit a call Compose rejects for want of an argument.
        for (param in declared.params) {
            if (param.required && param.key !in entry.args) {
                diags += Diagnostic(
                    Severity.Error, DiagnosticCodes.ModifierArgInvalid,
                    DiagnosticLocation(nodeId = node.id, property = param.key, modifierIndex = index),
                    "Modifier '${entry.type}' requires argument '${param.key.value}' (node '${node.id}')",
                    mapOf("node" to node.id.value, "modifier" to entry.type.value, "property" to param.key.value),
                )
            }
        }
    }
}

// Internal rather than private because pass 6 asks the same question of an action argument, and
// two copies of "does this value fit that type" would be two answers the day one of them moved.
internal fun acceptsValue(declared: TypeRef, value: Value): Boolean {
    // Dimension has no inhabitant yet: any value on it fails validation, not lookup.
    if (declared == TypeRef.Dimension) return false
    return ValueKinds.kindFor(declared).accepts(value)
}

// These two are internal for pass 6's messages, so a handler argument that does not fit its
// declaration reads exactly as a property that does not — the same fact, one wording.
internal fun labelOf(type: TypeRef): String = type::class.simpleName ?: "unknown"

internal fun labelOf(value: Value): String = value::class.simpleName ?: "unknown"

private fun isFiniteValue(value: Value): Boolean =
    when (value) {
        is Value.Float32 -> value.v.toDouble().isFinite()
        is Value.Float64 -> value.v.isFinite()
        is Value.Dp -> value.v.toDouble().isFinite()
        is Value.Sp -> value.v.toDouble().isFinite()
        else -> true
    }

private fun numberOf(value: Value): Double? =
    when (value) {
        is Value.Int32 -> value.v.toDouble()
        is Value.Int64 -> value.v.toDouble()
        is Value.Float32 -> value.v.toDouble()
        is Value.Float64 -> value.v
        is Value.Dp -> value.v.toDouble()
        is Value.Sp -> value.v.toDouble()
        else -> null
    }
