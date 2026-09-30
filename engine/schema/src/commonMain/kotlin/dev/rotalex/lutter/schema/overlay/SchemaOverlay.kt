package dev.rotalex.lutter.schema.overlay

import dev.rotalex.lutter.model.doc.ComponentDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentMetadata
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.SpecOrigin
import dev.rotalex.lutter.schema.registry.Registry
import dev.rotalex.lutter.schema.registry.RegistryBuilder

/** The namespace synthesized specs live in. Reserved: static schemas never use it. */
private const val DOCUMENT_PREFIX: String = "doc."

/**
 * Synthesizes the spec for one document component (§5.5:465).
 *
 * Params become required-typed properties, slots become `Many` with no scope (§5.5:361),
 * codegen is intrinsic (the generator owns instances), and the version is the document's
 * recorded contract (D9), defaulting to the only version an unrecorded decl can have.
 */
public fun documentComponentSpec(
    decl: ComponentDecl,
    componentVersions: Map<ComponentType, Int> = emptyMap(),
    category: Category = Category.Basic,
): ComponentSpec {
    val type = ComponentType("$DOCUMENT_PREFIX${decl.id}")
    return ComponentSpec(
        type = type,
        version = componentVersions[type] ?: 1,
        // Display data only; validation and codegen never read it, so the default
        // matters to the editor alone.
        metadata = ComponentMetadata(displayName = decl.name, category = category),
        properties = decl.params.map { param ->
            PropertySpec<Any?>(
                key = PropertyKey(param.name.value),
                type = param.type,
                required = param.required,
            )
        },
        slots = decl.slots.map { slot -> SlotSpec(slot.name, Cardinality.Many) },
        events = emptyList(),
        codegen = CodegenBinding.Intrinsic,
        origin = SpecOrigin.Document(decl.id),
    )
}

/**
 * The static schema plus the document's components, as one view (§8.4).
 *
 * Takes a `SchemaView` and returns one, so analysis, runtime and codegen treat document
 * components as first-class without the static schema ever naming them. Only components
 * merge; the other four registries delegate untouched.
 */
public class SchemaOverlay<M : Any, A : Any, F : Any, T : Any>(
    schema: SchemaView<ComponentSpec, M, A, F, T>,
    document: UiDocument,
) : SchemaView<ComponentSpec, M, A, F, T> {
    override public val components: Registry<ComponentType, ComponentSpec> =
        merged(schema.components, document)

    override public val modifiers: Registry<ModifierType, M> = schema.modifiers

    override public val actions: Registry<ActionId, A> = schema.actions

    override public val functions: Registry<FunctionId, F> = schema.functions

    override public val types: Registry<TypeId, T> = schema.types

    private companion object {
        /**
         * Static entries plus one synthesized spec per declaration, sorted by key.
         * A static `doc.*` key fails: the namespace is the overlay's, not a shadow.
         */
        private fun merged(
            static: Registry<ComponentType, ComponentSpec>,
            document: UiDocument,
        ): Registry<ComponentType, ComponentSpec> {
            val builder = RegistryBuilder<ComponentType, ComponentSpec>()
            builder.registerAll(static.all().map { spec -> spec.type to spec })
            for (decl in document.components.values) {
                val spec = documentComponentSpec(decl, document.meta.componentVersions)
                check(spec.type !in static) {
                    "Static schema shadows reserved key '${spec.type}': the 'doc.' namespace belongs to the overlay"
                }
                builder.register(spec.type, spec)
            }
            return builder.build()
        }
    }
}
