package dev.rotalex.lutter.analysis

import dev.rotalex.lutter.model.doc.EnumEntryDecl
import dev.rotalex.lutter.model.doc.EnumTypeDecl
import dev.rotalex.lutter.model.doc.StateDecl
import dev.rotalex.lutter.model.doc.UiDocument
import dev.rotalex.lutter.model.dsl.PageScope
import dev.rotalex.lutter.model.dsl.buildDocument
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.EventKey
import dev.rotalex.lutter.model.ids.FunctionId
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
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.ArgRule
import dev.rotalex.lutter.schema.action.ArgShape
import dev.rotalex.lutter.schema.action.BranchSpec
import dev.rotalex.lutter.schema.action.action
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventArgSpec
import dev.rotalex.lutter.schema.component.EventSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertyRule
import dev.rotalex.lutter.schema.component.ScopeId
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionEmit
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.function.ParamSig
import dev.rotalex.lutter.schema.function.TypeSig
import dev.rotalex.lutter.schema.function.function
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

/**
 * Not part of the walking skeleton: it exists because §10.4's nullability rules need a
 * `T?` destination, and no other property here takes one except a token.
 */
internal val BadgeType: ComponentType = ComponentType("m3.Badge")
internal val PaddingType: ModifierType = ModifierType("layout.padding")
internal val SizeType: ModifierType = ModifierType("layout.size")
internal val WeightType: ModifierType = ModifierType("layout.weight")
internal val ColumnScope: ScopeId = ScopeId("ColumnScope")

/**
 * Also not part of the walking skeleton, and for the same reason as [BadgeType]: §11.2's event
 * argument needs a component whose spec declares the event, or the argument has nothing to bind.
 */
internal val FieldType: ComponentType = ComponentType("m3.Field")

/** §11.2's handler argument, named the way a text field's edit names it. */
internal val ChangeEvent: EventKey = EventKey("onChange")

/** §10.3's exact-signature shape: one `Str` in, one `Str` out. */
internal val UpperId: FunctionId = FunctionId("str.upper")

/** §10.3's element-variable shape, so a signature that binds `T` has something to bind. */
internal val FirstId: FunctionId = FunctionId("list.first")

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

/**
 * The same vocabulary plus one component with a `T?` destination, so §10.4's nullability
 * rules are reachable at all: nothing in the walking skeleton accepts a nullable but a token.
 */
internal fun nullableSchema(): Schema<ComponentSpec, ModifierSpec, String, String, String> =
    Schema.build<ComponentSpec, ModifierSpec, String, String, String> {
        walkingSkeleton()
        component(
            componentSpec(BadgeType, 1) {
                metadata("Badge", Category.Basic)
                property(prop<String>("label", TypeRef.Str, required = true))
                property(prop<String>("caption", TypeRef.Nullable(TypeRef.Str)))
                composeCall(KotlinSymbol("androidx.compose.material3", "Text"))
            },
        )
    }

/**
 * The same vocabulary with the functions slot bound to [FunctionSpec], so a call the checker
 * is meant to *accept* is reachable. [testSchema] binds a stub there, where every call is an
 * unknown function until §10.2's seed set arrives with `:engine:builtins`.
 */
internal fun functionSchema(): Schema<ComponentSpec, ModifierSpec, String, FunctionSpec, String> =
    Schema.build<ComponentSpec, ModifierSpec, String, FunctionSpec, String> {
        walkingSkeleton()
        function(
            FunctionSpec(
                id = UpperId,
                params = listOf(ParamSig("value", TypeSig.Exact(TypeRef.Str))),
                returns = TypeSig.Exact(TypeRef.Str),
                kotlin = FunctionEmit("upper()"),
            ),
        )
        function(
            FunctionSpec(
                id = FirstId,
                params = listOf(ParamSig("items", TypeSig.ListOf(TypeSig.Element(ELEMENT)))),
                returns = TypeSig.Element(ELEMENT),
                kotlin = FunctionEmit("first()"),
            ),
        )
    }

/** The element variable [FirstId]'s signature binds and returns. Named in the plan's own style. */
private const val ELEMENT: String = "T"

/**
 * The walking skeleton with the actions slot bound to [ActionSpec] and §11.4's MVP ids
 * registered, so a handler has something to be right or wrong about.
 *
 * Three of the five have something for pass 6 to check: [navigate] is the only spec with a
 * typed parameter and the only one with a page reference, which is what a route argument and an
 * unknown argument both need; [branching] is the only one with arms; [writeState] is the only one
 * whose arguments are shapes rather than types. They are declared here rather than imported from
 * `:engine:builtins` because this module cannot depend on it — the same reason
 * [walkingSkeleton] restates the builtins' components.
 */
internal fun actionSchema(): Schema<ComponentSpec, ModifierSpec, ActionSpec, String, String> =
    Schema.build<ComponentSpec, ModifierSpec, ActionSpec, String, String> {
        walkingSkeleton()
        component(
            componentSpec(FieldType, 1) {
                metadata("Field", Category.Input)
                property(prop<String>("value", TypeRef.Str, required = true))
                event(EventSpec(ChangeEvent, listOf(EventArgSpec("text", TypeRef.Str))))
                composeCall(KotlinSymbol("androidx.compose.material3", "TextField"))
            },
        )
        action(navigate)
        action(ActionSpec(ActionId("nav.back"), ActionMetadata("Back"), emptyList(), emit = ActionEmit.Intrinsic))
        action(writeState)
        action(ActionSpec(ActionId("ui.showSnackbar"), ActionMetadata("Snackbar"), emptyList(), emit = ActionEmit.Intrinsic))
        action(branching)
    }

/** `nav.navigate(page)`: `prop<Nothing>` because no handler or emitter names an access type. */
internal val navigate: ActionSpec = ActionSpec(
    id = ActionId("nav.navigate"),
    metadata = ActionMetadata("Navigate"),
    params = listOf(prop<Nothing>("page", TypeRef.Ref(RefKind.Page), required = true)),
    emit = ActionEmit.Intrinsic,
)

/**
 * The state a write names, and the value it writes.
 *
 * Declared above [writeState] because a file's properties initialize in the order they are
 * written, and a key that were null while the spec was built would be a fixture nobody can read.
 */
internal val TargetKey: PropertyKey = PropertyKey("target")
internal val ValueKey: PropertyKey = PropertyKey("value")

/**
 * `state.set`: the one spec whose arguments no `TypeRef` can type, so it declares shapes.
 *
 * The two keys are shared with the tests that build a step, so a typo in one is a red test rather
 * than a document nothing ever refuses.
 */
internal val writeState: ActionSpec = ActionSpec(
    id = ActionId("state.set"),
    metadata = ActionMetadata("Set state"),
    argRules = listOf(
        ArgRule(TargetKey, ArgShape.StateRef, required = true),
        ArgRule(ValueKey, ArgShape.TargetValue, required = true),
    ),
    emit = ActionEmit.Intrinsic,
)

/** `flow.if`: `then` required because an `if` whose consequent is missing has nothing to do. */
internal val branching: ActionSpec = ActionSpec(
    id = ActionId("flow.if"),
    metadata = ActionMetadata("If"),
    params = emptyList(),
    branches = listOf(BranchSpec(BranchName("then"), required = true), BranchSpec(BranchName("else"))),
    emit = ActionEmit.Intrinsic,
)

// One declaration, bound twice: the components and modifiers do not depend on what the types
// slot holds, and duplicating them per binding is a copy that can drift.
internal fun <A : Any, F : Any, T : Any> SchemaBuilder<ComponentSpec, ModifierSpec, A, F, T>.walkingSkeleton(): Unit {
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

/** One page named Home, declaring [state]; the body builds its tree. */
internal fun homeDocument(
    state: List<StateDecl> = emptyList(),
    block: PageScope.() -> Unit,
): UiDocument = buildDocument("demo") {
    page(name = "Home", route = "home", id = PageId("p_home"), state = state, block = block)
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
