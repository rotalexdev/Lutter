package dev.rotalex.lutter.testsupport.generator

import dev.rotalex.lutter.builtins.registerBuiltinActions
import dev.rotalex.lutter.builtins.registerBuiltinEnums
import dev.rotalex.lutter.builtins.registerBuiltinIconSets
import dev.rotalex.lutter.builtins.registerBuiltinFunctions
import dev.rotalex.lutter.builtins.registerBuiltinModifiers
import dev.rotalex.lutter.builtins.registerBuiltinSpecs
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.model.ids.SlotName
import dev.rotalex.lutter.model.ids.TypeId
import dev.rotalex.lutter.model.type.TokenKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuilder
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.Cardinality
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ChildFilter
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.EventArgSpec
import dev.rotalex.lutter.schema.component.SlotSpec
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop
import dev.rotalex.lutter.schema.function.FunctionSpec
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.types.TypeSpec

// The schema the document generator generates against, and the three component types it adds
// to the builtins.
//
// ### Why this file exists at all
//
// A generator that can only place what `BuiltinEngine.schema()` registers cannot reach half of
// §7.1's `SlotSpec` vocabulary, and the gap is not a detail: the builtins declare `Any`, `Many`
// and `ExactlyOne` and never declare `Only`, `Except` or `ZeroOrOne`. That is not an oversight
// in the builtins — it is three shapes no shipped component needs — but it means a generator
// built on the builtins alone would never produce a document that exercises a filtered slot, and
// a filtered slot is where the interesting bugs are. `ChildFilter` refusals, a `ZeroOrOne`
// filled to two, a `ExactlyOne` left empty: none of that is reachable from the builtins.
//
// So the pack below adds exactly the three missing shapes and nothing else. It is a test pack,
// not a contribution to the engine: no engine module registers these types, and a component
// added here is a component a test-only module contributed, which is §7.4's claim about
// `ThirdPartyComponentTest` stated three component specs earlier.
//
// ### What is and is not covered here
//
// Every [Cardinality] and every [ChildFilter] variant is reachable from [generatorSchema], and
// that is the whole claim. Value shapes are the other half of it, and `gen.Probe` carries the
// ones no builtin property uses: a list, a url, a map, an icon, a document-declared object, a
// document-declared enum and a theme token. Without it, `Value` would be exercised only through
// `Str`, `Dp`, `Color` and the three schema enums, and the eighteen-variant union of §5.4 would
// be tested through six of them.
//
// `TypeRef.Dimension` is the one inhabitable type with no inhabitant — `SchemaPass.acceptsValue`
// refuses every value on it by name — so no property here declares one. That is not a coverage
// gap to close later; it is a type nothing can satisfy today, and a generator that declared one
// would produce an invalid document on purpose.

/** A component whose one slot is `ZeroOrOne` and closed: the case the builtins never declare. */
public val SINGLE_PANEL: ComponentType = ComponentType("gen.SinglePanel")

/** A component whose one slot is negative-filtered, so a refusal is about the child alone. */
public val BANNER: ComponentType = ComponentType("gen.Banner")

/** A leaf carrying one property per value shape the builtins leave unexercised. */
public val PROBE: ComponentType = ComponentType("gen.Probe")

/** The types the two leaf-only shapes of the pack admit, named once for both filters. */
public val TEXT: ComponentType = ComponentType("m3.Text")
public val SPACER: ComponentType = ComponentType("core.Spacer")

/** The object type [PROBE]'s `record` property names; the document declares the model. */
public val PROBE_MODEL: TypeId = TypeId("GenProbe")

/** The enum type [PROBE]'s `grade` property names; the document declares the entries. */
public val PROBE_GRADE: TypeId = TypeId("GenGrade")

/** The icon set [PROBE]'s `badge` property names. Nothing validates the name inside it. */
public const val PROBE_ICON_SET: String = "gen"

/** The event [PROBE] raises, and the one argument its handler binds. */
public const val PROBE_EVENT: String = "onProbe"
public const val PROBE_EVENT_ARG: String = "probeValue"

/**
 * The builtins plus this module's three components: the schema [DocumentGenerator] generates
 * against and the validity test analyses against.
 *
 * Built fresh on every call rather than cached in a `val`, because `Schema.build` is cheap and a
 * shared static schema is shared mutable state by another name — the thing
 * `NoMutableObjectStateTest` exists to forbid. A caller that wants one schema for many documents
 * should hold it in its own `val`, which is what the tests here do.
 */
public fun generatorSchema(): Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> =
    Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec> {
        registerBuiltinSpecs()
        registerBuiltinModifiers()
        registerBuiltinEnums()
        registerBuiltinIconSets()
        registerBuiltinFunctions()
        registerBuiltinActions()
        generatorVocabulary()
    }

/**
 * The three components this module adds. Split from [generatorSchema] so the registration block
 * reads as "the builtins, then ours".
 *
 * ### Why `component(spec)` needs an import and `component(type, spec)` does not
 *
 * Both shapes carry the same name and only one of them is a member. `SchemaBuilder.component(type,
 * spec)` is a member of the class; the one-argument `component(spec)` that files a spec under its
 * own type is a **top-level extension** in `dev.rotalex.lutter.schema.component`, so it is only
 * in scope with an import. Without that import the call still resolves — to the member — and the
 * diagnostic names the member's first parameter rather than the missing import, which is the part
 * worth knowing the next time it happens.
 *
 * The one-argument shape is the one used here because it is what `Builtins.kt` uses for the nine
 * builtin specs, and because spelling the type twice would make a reader check that the two
 * spellings agree. The two-argument member is the fallback if the import ever goes missing, and
 * it is the only one of the two that is visible without an import.
 */
private fun SchemaBuilder<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, TypeSpec>
    .generatorVocabulary(): Unit {
    component(
        componentSpec(SINGLE_PANEL, version = 1) {
            metadata("Single panel", Category.Layout)
            property(prop<String>("label", TypeRef.Str, required = true))
            property(prop<Boolean>("emphasis", TypeRef.Bool))
            // ZeroOrOne and a closed filter together: the fullness check and the filter check
            // are independent here, which is the reason a generated `Many` slot cannot stand in
            // for it. `PlannerFixtures` makes the same argument about its own toy vocabulary.
            slot(SlotSpec(SlotName("body"), Cardinality.ZeroOrOne, ChildFilter.Only(setOf(TEXT, SPACER))))
            intrinsic()
        },
    )
    component(
        componentSpec(BANNER, version = 1) {
            metadata("Banner", Category.Layout)
            property(prop<String>("title", TypeRef.Str))
            // The negative filter, for the reason above: a child this slot refuses can never
            // also be one it has no room for.
            slot(SlotSpec(SlotName("banner"), Cardinality.Many, ChildFilter.Except(setOf(TEXT))))
            intrinsic()
        },
    )
    component(
        componentSpec(PROBE, version = 1) {
            metadata("Probe", Category.Basic)
            property(prop<List<String>>("items", TypeRef.ListOf(TypeRef.Str)))
            property(prop<String>("link", TypeRef.Url))
            property(prop<Map<PropertyKey, Value>>("tags", TypeRef.MapOf(TypeRef.Str)))
            property(prop<String>("badge", TypeRef.Icon(PROBE_ICON_SET)))
            property(prop<Map<PropertyKey, Value>>("record", TypeRef.Object(PROBE_MODEL)))
            property(prop<String>("grade", TypeRef.Enum(PROBE_GRADE)))
            property(prop<String>("accent", TypeRef.Nullable(TypeRef.Token(TokenKind.Color))))
            property(prop<String>("caption", TypeRef.Nullable(TypeRef.Token(TokenKind.Typography))))
            property(prop<Float>("ratio", TypeRef.Nullable(TypeRef.Float32)))
            property(prop<Float>("inset", TypeRef.Nullable(TypeRef.Dp)))
            property(prop<Float>("scale", TypeRef.Nullable(TypeRef.Sp)))
            property(prop<Boolean>("enabled", TypeRef.Bool))
            property(prop<Long>("serial", TypeRef.Int64))
            property(prop<Double>("score", TypeRef.Float64))
            property(prop<Int>("count", TypeRef.Int32))
            property(prop<String>("label", TypeRef.Str))
            // An event that binds an argument, because the builtins' two events bind none and
            // `RefTarget.EventArg` would otherwise never be resolvable from a handler.
            event(PROBE_EVENT, listOf(EventArgSpec(PROBE_EVENT_ARG, TypeRef.Str)))
            intrinsic()
        },
    )
}
