package dev.rotalex.lutter.analysis.pass

import dev.rotalex.lutter.analysis.diagnostic.Diagnostic
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticCodes
import dev.rotalex.lutter.analysis.diagnostic.DiagnosticLocation
import dev.rotalex.lutter.analysis.diagnostic.Severity
import dev.rotalex.lutter.analysis.resolved.selectTheme
import dev.rotalex.lutter.analysis.resolved.themeKnows
import dev.rotalex.lutter.model.doc.Node
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.ModifierSpec

/**
 * Pass 4: references name live targets — pages, components, resources, models and tokens.
 *
 * Needs no expression knowledge: only constant values can carry a reference in this phase.
 */
internal class ReferencePass(
    private val schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) : AnalysisPass {
    override val name: String = "reference"
    override val blocking: Boolean = false

    override fun run(document: UiDocument): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        for (id in document.nodes.ids()) {
            val node = document.nodes[id] ?: continue
            val spec = schema.components[node.type]
            for ((key, actual) in node.props) {
                val declared = spec?.properties?.firstOrNull { it.key == key }?.type
                checkTopLevel(document, node, key, null, declared, actual, diags)
            }
            for ((index, entry) in node.modifiers.withIndex()) {
                val params = schema.modifiers[entry.type]?.params
                for ((key, actual) in entry.args) {
                    val declared = params?.firstOrNull { it.key == key }?.type
                    checkTopLevel(document, node, key, index, declared, actual, diags)
                }
            }
        }
        return diags
    }

    private fun checkTopLevel(
        document: UiDocument,
        node: Node,
        key: PropertyKey,
        modifierIndex: Int?,
        declared: TypeRef?,
        actual: PropertyValue,
        diags: MutableList<Diagnostic>,
    ) {
        val const = (actual as? PropertyValue.Const)?.value ?: return
        // A reference of the wrong kind is a kind error, not a dangling one.
        if (declared is TypeRef.Ref && const is Value.Ref && declared.kind != const.kind) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.RefKindMismatch,
                DiagnosticLocation(nodeId = node.id, property = key, modifierIndex = modifierIndex),
                "Reference '${key.value}' expects kind '${declared.kind}' but found '${const.kind}' (node '${node.id}')",
                mapOf("node" to node.id.value, "property" to key.value),
            )
            return
        }
        scanValue(document, node.id, key, modifierIndex, const, diags)
    }

    private fun scanValue(
        document: UiDocument,
        nodeId: NodeId,
        key: PropertyKey,
        modifierIndex: Int?,
        const: Value,
        diags: MutableList<Diagnostic>,
    ) {
        fun location(): DiagnosticLocation =
            DiagnosticLocation(nodeId = nodeId, property = key, modifierIndex = modifierIndex)

        when (const) {
            is Value.Ref -> checkRef(document, const, location(), diags)
            is Value.ListOf -> const.items.forEach { scanValue(document, nodeId, key, modifierIndex, it, diags) }
            is Value.MapOf -> {
                scanValue(document, nodeId, key, modifierIndex, const.value, diags)
                const.entries.values.forEach { scanValue(document, nodeId, key, modifierIndex, it, diags) }
            }
            is Value.Obj -> {
                checkObj(document, const, location(), diags)
                const.fields.values.forEach { scanValue(document, nodeId, key, modifierIndex, it, diags) }
            }
            is Value.Token -> checkToken(document, const, location(), diags)
            is Value.Null, is Value.Bool, is Value.Int32, is Value.Int64, is Value.Float32,
            is Value.Float64, is Value.Str, is Value.Color, is Value.Dp, is Value.Sp,
            is Value.Enum, is Value.Url, is Value.Icon -> Unit
        }
    }

    private fun checkRef(
        document: UiDocument,
        ref: Value.Ref,
        location: DiagnosticLocation,
        diags: MutableList<Diagnostic>,
    ) {
        val known = when (ref.kind) {
            RefKind.Page -> document.pages.keys.any { it.value == ref.id }
            RefKind.Resource -> document.resources.keys.any { it.value == ref.id }
            RefKind.Component -> document.components.keys.any { it.value == ref.id }
            RefKind.DataModel -> document.dataModels.keys.any { it.value == ref.id }
        }
        if (!known) {
            // Resources own a code; every other dangling reference shares one.
            val code = if (ref.kind == RefKind.Resource) DiagnosticCodes.ResourceUnknown else DiagnosticCodes.RefDangling
            diags += Diagnostic(
                Severity.Error, code, location,
                "Reference to ${ref.kind} '${ref.id}' names nothing in the document",
                mapOf("kind" to ref.kind.name, "id" to ref.id),
            )
        }
    }

    private fun checkObj(
        document: UiDocument,
        obj: Value.Obj,
        location: DiagnosticLocation,
        diags: MutableList<Diagnostic>,
    ) {
        val known = document.enums.containsKey(obj.typeId) ||
            document.dataModels.keys.any { it.value == obj.typeId.value }
        if (!known) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.RefDangling, location,
                "Object of type '${obj.typeId}' names an undeclared data model",
                mapOf("id" to obj.typeId.value),
            )
        }
    }

    private fun checkToken(
        document: UiDocument,
        token: Value.Token,
        location: DiagnosticLocation,
        diags: MutableList<Diagnostic>,
    ) {
        val theme = selectTheme(document)
        if (theme == null || !themeKnows(theme, token.name)) {
            diags += Diagnostic(
                Severity.Error, DiagnosticCodes.TokenUnknown, location,
                "Token '${token.name}' is not declared by the selected theme",
                mapOf("name" to token.name),
            )
        }
    }
}
