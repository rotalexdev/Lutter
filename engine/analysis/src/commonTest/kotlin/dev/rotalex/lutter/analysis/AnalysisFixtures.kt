package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.modifier.modifier
import dev.rotalex.lutter.schema.types.EnumEntrySpec
import dev.rotalex.lutter.schema.types.EnumTypeSpec
import dev.rotalex.lutter.schema.types.TypeSpec
import dev.rotalex.lutter.schema.types.type

/** The walking-skeleton vocabulary: a layout, a leaf, a link and a card. */
internal val ColumnType: ComponentType = ComponentType("core.Column")
internal val TextType: ComponentType = ComponentType("m3.Text")
internal val LinkType: ComponentType = ComponentType("core.Link")
internal val CardType: ComponentType = ComponentType("m3.Card")
internal val PaddingType: ModifierType = ModifierType("layout.padding")
internal val SizeType: ModifierType = ModifierType("layout.size")
internal val WeightType: ModifierType = ModifierType("layout.weight")
internal val ColumnScope: ScopeId = ScopeId("ColumnScope")

/** A schema with real specs, so dispatch always goes through the registry. */
internal fun testSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
    Schema.build<ComponentSpec, ModifierSpec, String, String, String> { walkingSkeleton() }

/**
 * The same vocabulary with the types slot bound to [TypeSpec], so a registered `EnumTypeSpec`
 * is readable. A schema binding a stub there cannot answer an enum question at all.
 */
internal fun enumSchema(): Schema<ComponentSpec, ModifierSpec, String, String, TypeSpec> =
    Schema.build<ComponentSpec, ModifierSpec, String, String, TypeSpec> {
        walkingSkeleton()
        type(AlignType)
    }

// One declaration, bound twice: the components and modifiers do not depend on what the types
// slot holds, and duplicating them per binding is a copy that can drift.
private fun <A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, ModifierSpec, A, F, T>.walkingSkeleton(): Unit {
    component(
        componentSpec(ColumnType, 1) {
            metadata("Column", Category.Layout)
            property(prop<Float>("spacing", TypeRef.Dp, default = Value.Dp(0f)))
            property(prop<String>("axis", TypeRef.Str))
            slot("children", Cardinality.Many, provides = setOf(ColumnScope))
            rule(PropertyRule.MutuallyExclusive(setOf(PropertyKey("spacing"), PropertyKey("axis"))))
            composeCall(KotlinSymbol("androidx.compose.foundation.layout", "Column"))
        },
    )
    component(
        componentSpec(TextType, 1) {
            metadata("Text", Category.Basic)
            property(prop<String>("text", TypeRef.Str, required = true))
            property(prop<Int>("maxLines", TypeRef.Int32, default = Value.Int32(1)))
            property(prop<String>("tag", TypeRef.Str, bindable = false))
            property(prop<String>("style", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography))))
            property(prop<String>("align", TypeRef.Enum(TypeId("Align"))))
            rule(PropertyRule.Range(PropertyKey("maxLines"), min = 1.0, max = 10.0))
            composeCall(KotlinSymbol("androidx.compose.material3", "Text"))
        },
    )
    component(
        componentSpec(LinkType, 1) {
            metadata("Link", Category.Basic)
            property(prop<String>("target", TypeRef.Ref(RefKind.Page)))
            property(prop<String>("icon", TypeRef.Ref(RefKind.Resource)))
            property(prop<String>("payload", TypeRef.Nullable(TypeRef.Object(TypeId("User")))))
            composeCall(KotlinSymbol("androidx.compose.foundation.text", "ClickableText"))
        },
    )
    component(
        componentSpec(CardType, 1) {
            metadata("Card", Category.Basic)
            slot("content", Cardinality.ExactlyOne, accepts = ChildFilter.Only(setOf(TextType)))
            composeCall(KotlinSymbol("androidx.compose.material3", "Card"))
        },
    )
    modifier(
        ModifierSpec(
            type = PaddingType,
            metadata = ModifierMetadata("Padding"),
            params = listOf(prop<Float>("all", TypeRef.Dp)),
            emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "padding")),
        ),
    )
    modifier(
        ModifierSpec(
            type = SizeType,
            metadata = ModifierMetadata("Size"),
            // The one modifier whose arguments are all required, so it is what a missing-argument
            // check has to be tested against.
            params = listOf(
                prop<Float>("width", TypeRef.Dp, required = true),
                prop<Float>("height", TypeRef.Dp, required = true),
            ),
            emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "size")),
        ),
    )
    modifier(
        ModifierSpec(
            type = WeightType,
            metadata = ModifierMetadata("Weight"),
            params = listOf(prop<Float>("weight", TypeRef.Float32)),
            requiresScope = setOf(ColumnScope),
            emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "weight")),
        ),
    )
}

/** One page named Home; the body builds its tree. */
internal fun homeDocument(block: PageScope.() -> Unit): UiDocument = buildDocument("demo") {
    page(name = "Home", route = "home", id = PageId("p_home"), block = block)
}

/** A valid Column holding one Text: the baseline every violation mutates. */
internal fun validDocument(): UiDocument = homeDocument {
    node(ColumnType) {
        val title = node(TextType) { prop("text", Value.Str("Hi")) }
        slot("children", listOf(title))
    }
}

/** Wraps a literal as the constant the modifier map expects. */
internal fun constOf(value: Value): PropertyValue = PropertyValue.Const(value)

/** The enum the `align` property draws its entries from. */
internal val AlignTypeId: TypeId = TypeId("Align")
internal val AlignDecl: EnumTypeDecl = EnumTypeDecl(
    AlignTypeId,
    "Align",
    listOf(EnumEntryDecl("Start"), EnumEntryDecl("Center")),
)

/** The same vocabulary the schema side owns, which is where the pass reads it from. */
internal val AlignType: EnumTypeSpec = EnumTypeSpec(
    AlignTypeId,
    listOf(
        EnumEntrySpec("Start", KotlinSymbol("com.example", "Align.Start")),
        EnumEntrySpec("Center", KotlinSymbol("com.example", "Align.Center")),
    ),
)
