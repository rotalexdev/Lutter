package dev.rotalex.lutter.builtins.compose

import dev.rotalex.lutter.builtins.BoxSpec
import dev.rotalex.lutter.builtins.ButtonSpec
import dev.rotalex.lutter.builtins.CardSpec
import dev.rotalex.lutter.builtins.ColumnSpec
import dev.rotalex.lutter.builtins.RowSpec
import dev.rotalex.lutter.builtins.SpacerSpec
import dev.rotalex.lutter.builtins.TextFieldSpec
import dev.rotalex.lutter.builtins.TextSpec
import dev.rotalex.lutter.runtime.RendererRegistryBuilder

/**
 * The runtime half of the plugin pair: wires the skeleton renderers.
 *
 * The schema half is `registerBuiltinSpecs` in `:engine:builtins`.
 */
public fun RendererRegistryBuilder.registerBuiltinRenderers(): Unit {
    register(ColumnSpec.spec.type, ColumnRenderer)
    register(RowSpec.spec.type, RowRenderer)
    register(BoxSpec.spec.type, BoxRenderer)
    register(SpacerSpec.spec.type, SpacerRenderer)
    register(TextSpec.spec.type, TextRenderer)
    register(ButtonSpec.spec.type, ButtonRenderer)
    register(TextFieldSpec.spec.type, TextFieldRenderer)
    register(CardSpec.spec.type, CardRenderer)
}
