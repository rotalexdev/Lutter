package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.runtime.RendererRegistryBuilder

/**
 * The runtime half of the plugin pair: wires the skeleton renderers.
 *
 * The schema half is `registerBuiltinSpecs` in `:engine:builtins`.
 */
public fun RendererRegistryBuilder.registerBuiltinRenderers(): Unit {
    register(ColumnSpec.spec.type, ColumnRenderer)
    register(TextSpec.spec.type, TextRenderer)
}
