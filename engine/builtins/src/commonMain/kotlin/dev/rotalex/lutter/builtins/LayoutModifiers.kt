package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.component.EmitCase
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/** `layout.fillMaxSize`: both axes take the space the parent offers. No arguments. */
public object FillMaxSizeModifier {
    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.fillMaxSize"),
        metadata = ModifierMetadata("Fill max size", "Takes all the space the parent offers"),
        params = emptyList(),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "fillMaxSize")),
    )
}

/** `layout.fillMaxWidth`: the horizontal axis only. */
public object FillMaxWidthModifier {
    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.fillMaxWidth"),
        metadata = ModifierMetadata("Fill max width", "Takes the width the parent offers"),
        params = emptyList(),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "fillMaxWidth")),
    )
}

/** `layout.fillMaxHeight`: the vertical axis only. */
public object FillMaxHeightModifier {
    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.fillMaxHeight"),
        metadata = ModifierMetadata("Fill max height", "Takes the height the parent offers"),
        params = emptyList(),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "fillMaxHeight")),
    )
}

/**
 * `layout.padding`: one inset for every edge, or one per axis.
 *
 * The four cases are D7's shape on a modifier: which arguments are present picks the call, so
 * a document setting a single axis does not have to write the other as zero. The generator
 * refuses modifier cases until W4 teaches it to read them, so this is the one spec here
 * whose emission is declared ahead of its codegen.
 */
public object PaddingModifier {
    /** The same inset on all four edges. Wins when both shapes are present. */
    public val all: PropertySpec<Float?> = prop("all", TypeRef.Nullable(TypeRef.Dp))

    /** The inset on the two horizontal edges. */
    public val horizontal: PropertySpec<Float?> = prop("horizontal", TypeRef.Nullable(TypeRef.Dp))

    /** The inset on the two vertical edges. */
    public val vertical: PropertySpec<Float?> = prop("vertical", TypeRef.Nullable(TypeRef.Dp))

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.padding"),
        metadata = ModifierMetadata("Padding", "Insets the content inside its bounds"),
        params = listOf(all, horizontal, vertical),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "padding"),
            listOf(
                EmitCase(setOf(all.key), "padding({all})"),
                EmitCase(
                    setOf(horizontal.key, vertical.key),
                    "padding(horizontal = {horizontal}, vertical = {vertical})",
                ),
                EmitCase(setOf(horizontal.key), "padding(horizontal = {horizontal})"),
                EmitCase(setOf(vertical.key), "padding(vertical = {vertical})"),
            ),
        ),
    )
}

/** `layout.size`: an exact width and height, both required for a number to mean anything. */
public object SizeModifier {
    public val width: PropertySpec<Float> = prop("width", TypeRef.Dp, required = true)

    public val height: PropertySpec<Float> = prop("height", TypeRef.Dp, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.size"),
        metadata = ModifierMetadata("Size", "An exact width and height"),
        params = listOf(width, height),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "size")),
    )
}

/** `layout.width`: an exact width; the height is left to the content. */
public object WidthModifier {
    public val value: PropertySpec<Float> = prop("width", TypeRef.Dp, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.width"),
        metadata = ModifierMetadata("Width", "An exact width"),
        params = listOf(value),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "width")),
    )
}

/** `layout.height`: an exact height; the width is left to the content. */
public object HeightModifier {
    public val value: PropertySpec<Float> = prop("height", TypeRef.Dp, required = true)

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.height"),
        metadata = ModifierMetadata("Height", "An exact height"),
        params = listOf(value),
        emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "height")),
    )
}

/**
 * `layout.weight`: the share of the row's free space this node takes.
 *
 * Gated on the row scope, which is D6's whole point: outside a row it is `scope_missing`
 * rather than generated code that does not compile. The name is `RowScope`'s member, so it
 * carries no import and resolves through the receiver `Row`'s content lambda opens.
 */
public object WeightModifier {
    /** The share against its siblings. One when a document says nothing. */
    public val weight: PropertySpec<Float> = prop(
        "weight",
        TypeRef.Float32,
        default = Value.Float32(1f),
    )

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.weight"),
        metadata = ModifierMetadata("Weight", "The share of the row's free space"),
        params = listOf(weight),
        requiresScope = setOf(LayoutScopes.Row),
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "weight"),
            scopeMember = true,
        ),
    )
}

/**
 * `layout.align`: where the node sits inside the axis its parent aligns.
 *
 * Ungated, because the axis belongs to the open scope rather than to the modifier: a row
 * aligns its children vertically, a column horizontally and a box on both. One entry names
 * a position on both, so each scope reads the projection it needs.
 *
 * The three tables below must agree with `AlignmentMaps` in `:engine:builtins-compose`: the
 * runtime applies the same projections, and conformance compares the two against each other.
 */
public object AlignModifier {
    /** One of the entries of `Alignment`. */
    public val alignment: PropertySpec<String> = prop(
        "alignment",
        AlignmentSpec.Type,
        required = true,
    )

    /**
     * A row moves its children vertically, so it reads the band the entry names and ignores
     * the side. Written out because Compose splits its own vocabulary in two, and which half a
     * scope reads is a fact about Compose rather than a rule this module may compute.
     */
    private val vertical: Map<String, KotlinSymbol> = mapOf(
        "TopLeft" to bias("Top"), "TopCenter" to bias("Top"),
        "TopRight" to bias("Top"),
        "CenterLeft" to bias("CenterVertically"),
        "Center" to bias("CenterVertically"),
        "CenterRight" to bias("CenterVertically"),
        "BottomLeft" to bias("Bottom"), "BottomCenter" to bias("Bottom"),
        "BottomRight" to bias("Bottom"),
    )

    /** A column's counterpart: the side, and the centre of the horizontal axis. */
    private val horizontal: Map<String, KotlinSymbol> = mapOf(
        "TopLeft" to absolute("Left"), "TopCenter" to bias("CenterHorizontally"),
        "TopRight" to absolute("Right"),
        "CenterLeft" to absolute("Left"), "Center" to bias("CenterHorizontally"),
        "CenterRight" to absolute("Right"),
        "BottomLeft" to absolute("Left"), "BottomCenter" to bias("CenterHorizontally"),
        "BottomRight" to absolute("Right"),
    )

    /** Each entry under each scope, which is the axis that scope's `align` takes. */
    public val scopeEntries: Map<ScopeId, Map<String, KotlinSymbol>> = mapOf(
        LayoutScopes.Row to vertical,
        LayoutScopes.Column to horizontal,
        LayoutScopes.Box to AlignmentSpec.entries.associate { it.name to it.kotlin },
    )

    public val spec: ModifierSpec = ModifierSpec(
        type = ModifierType("layout.align"),
        metadata = ModifierMetadata("Align", "Where the node sits inside its parent"),
        params = listOf(alignment),
        // A pattern rather than a call: the three `align` parameters are named
        // `alignment`, `horizontalAlignment` and `verticalAlignment`, so only a positional
        // argument is valid in all of them.
        emit = ModifierEmit(
            KotlinSymbol("androidx.compose.foundation.layout", "align"),
            listOf(EmitCase(setOf(alignment.key), "align({alignment})")),
            scopeMember = true,
            scopeEntries = scopeEntries,
        ),
    )

    private fun bias(entry: String): KotlinSymbol =
        KotlinSymbol("androidx.compose.ui", "Alignment.$entry")

    private fun absolute(entry: String): KotlinSymbol =
        KotlinSymbol("androidx.compose.ui", "AbsoluteAlignment.$entry")
}

/**
 * The layout family, in the order the schema registers it.
 *
 * Named after the objects above rather than beside them: a registry is a list of what the
 * module declares, so it reads last.
 */
public object LayoutModifiers {
    public val all: List<ModifierSpec> = listOf(
        FillMaxSizeModifier.spec,
        FillMaxWidthModifier.spec,
        FillMaxHeightModifier.spec,
        PaddingModifier.spec,
        SizeModifier.spec,
        WidthModifier.spec,
        HeightModifier.spec,
        WeightModifier.spec,
        AlignModifier.spec,
    )
}
