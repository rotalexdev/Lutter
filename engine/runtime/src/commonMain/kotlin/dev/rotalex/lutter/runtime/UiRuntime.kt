package dev.rotalex.lutter.runtime

import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * The live renderer: registries plus the interpreter seam, coverage-checked.
 *
 * PLAN §15.2 field for field. Construction fails naming every spec type without
 * a renderer, so a gap surfaces here and not as a blank screen.
 */
public class UiRuntime(
    public val renderers: RendererRegistry,
    public val modifiers: ModifierApplierRegistry,
    public val implementations: Implementations,
    schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
) {
    init {
        RuntimeCoverage.check(schema, this)
    }
}

/**
 * The §4.5 fail-fast: every spec in the schema renders, or construction refuses.
 *
 * Document-overlay types (`doc.*`) are not schema members, so they are not checked
 * here; their renderer is resolved per node at render time instead.
 */
public object RuntimeCoverage {
    /** Every component type in [schema] must read back out of [runtime]'s registry. */
    public fun check(schema: SchemaView<ComponentSpec, *, *, *, *>, runtime: UiRuntime): Unit {
        val missing = schema.components.all()
            .map { it.type }
            .filter { it !in runtime.renderers }
            .sortedBy { it.value }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "UiRuntime has no renderer for ${missing.joinToString { "'$it'" }}",
            )
        }
    }
}
