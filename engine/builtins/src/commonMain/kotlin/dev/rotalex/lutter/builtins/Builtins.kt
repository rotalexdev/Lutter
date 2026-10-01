package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.component

/**
 * The schema half of the plugin pair: contributes the skeleton specs.
 *
 * The runtime half is `registerBuiltinRenderers` in `:engine:builtins-compose`.
 */
public fun <M : Any, A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, M, A, F, T>.registerBuiltinSpecs(): Unit {
    component(ColumnSpec.spec)
    component(TextSpec.spec)
}
