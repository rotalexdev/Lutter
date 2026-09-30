package dev.rotalex.lutter.schema.function

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.ids.FunctionId
import dev.rotalex.lutter.model.ids.ModifierType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.action.action
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.component
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.modifier.ModifierEmit
import dev.rotalex.lutter.schema.modifier.ModifierMetadata
import dev.rotalex.lutter.schema.modifier.ModifierSpec
import dev.rotalex.lutter.schema.modifier.modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S3 function surface: the §10.3 shape, element-type generics, and the S1 joint it binds.
 *
 * The coverage rule (§10.3:848) is asserted from the spec side: every registered spec resolves
 * under its own id, so no spec is orphaned or mis-keyed before the later phase adds its impl.
 * Each promise carries its own failure: the purity default, the duplicate key, the mis-keyed
 * lookup.
 */
class FunctionSpecTest {

    private val isNotEmptySpec: FunctionSpec = FunctionSpec(
        id = FunctionId("list.isNotEmpty"),
        params = listOf(ParamSig("list", TypeSig.ListOf(TypeSig.Element("T")))),
        returns = TypeSig.Exact(TypeRef.Bool),
        kotlin = FunctionEmit("{0}.isNotEmpty()"),
    )

    private val getSpec: FunctionSpec = FunctionSpec(
        id = FunctionId("list.get"),
        params = listOf(
            ParamSig("list", TypeSig.ListOf(TypeSig.Element("T"))),
            ParamSig("index", TypeSig.Exact(TypeRef.Int32)),
        ),
        returns = TypeSig.Nullable(TypeSig.Element("T")),
        kotlin = FunctionEmit(
            pattern = "{0}.getOrNull({1})",
            precedence = FunctionPrecedence.Call,
        ),
    )

    @Test
    fun `isNotEmpty threads the element variable through a concrete return`() {
        assertEquals(FunctionId("list.isNotEmpty"), isNotEmptySpec.id)
        assertEquals(
            TypeSig.ListOf(TypeSig.Element("T")),
            isNotEmptySpec.params.single().type,
        )
        assertEquals(TypeSig.Exact(TypeRef.Bool), isNotEmptySpec.returns)
        assertEquals("{0}.isNotEmpty()", isNotEmptySpec.kotlin.pattern)
    }

    @Test
    fun `a fresh spec is pure emits a call and imports nothing`() {
        assertEquals(true, getSpec.pure)
        assertEquals(FunctionPrecedence.Call, getSpec.kotlin.precedence)
        assertEquals(emptyList(), getSpec.kotlin.imports)
        assertIs<TypeSig.Nullable>(getSpec.returns)
        assertEquals(TypeSig.Element("T"), assertIs<TypeSig.Nullable>(getSpec.returns).inner)
    }

    @Test
    fun `every registered spec resolves under its own id`() {
        val schema: FunctionSchema<String, String, String, String> =
            Schema.build<String, String, String, FunctionSpec, String> {
                function(isNotEmptySpec)
                function(getSpec)
            }

        assertSame(isNotEmptySpec, schema.functions.require(FunctionId("list.isNotEmpty")))
        assertSame(getSpec, schema.functions.require(FunctionId("list.get")))
        assertEquals(2, schema.functions.all().size)
    }

    @Test
    fun `two specs on one key fail naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<String, String, String, FunctionSpec, String> {
                function(isNotEmptySpec)
                function(isNotEmptySpec)
            }
        }

        assertTrue(failure.message?.contains("list.isNotEmpty") == true)
        assertTrue(failure.message?.contains("functions") == true)
    }

    @Test
    fun `an unknown function fails at read time`() {
        val schema: FunctionSchema<String, String, String, String> =
            Schema.build<String, String, String, FunctionSpec, String> {
                function(isNotEmptySpec)
            }

        assertFailsWith<NoSuchElementException> {
            schema.functions.require(FunctionId("list.get"))
        }
    }

    @Test
    fun `the three joints compose leaving only the type registry open`() {
        val textSpec: ComponentSpec = componentSpec(ComponentType("m3.Text"), version = 1) {
            metadata(displayName = "Text", category = Category.Basic)
            intrinsic()
        }
        val paddingSpec: ModifierSpec = ModifierSpec(
            type = ModifierType("layout.padding"),
            metadata = ModifierMetadata("Padding"),
            params = emptyList(),
            emit = ModifierEmit(KotlinSymbol("androidx.compose.foundation.layout", "padding")),
        )
        val navigateSpec: ActionSpec = ActionSpec(
            id = ActionId("nav.navigate"),
            metadata = ActionMetadata("Navigate"),
            params = emptyList(),
            emit = ActionEmit.Intrinsic,
        )
        val schema: Schema<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, String> =
            Schema.build<ComponentSpec, ModifierSpec, ActionSpec, FunctionSpec, String> {
                component(textSpec)
                modifier(paddingSpec)
                action(navigateSpec)
                function(isNotEmptySpec)
            }

        assertSame(textSpec, schema.components.require(ComponentType("m3.Text")))
        assertSame(paddingSpec, schema.modifiers.require(ModifierType("layout.padding")))
        assertSame(navigateSpec, schema.actions.require(ActionId("nav.navigate")))
        assertSame(isNotEmptySpec, schema.functions.require(FunctionId("list.isNotEmpty")))
    }
}
