package dev.rotalex.lutter.schema.action

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.ids.BranchName
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.Schema
import dev.rotalex.lutter.schema.SchemaBuildException
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.prop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The S3 action surface: the §11.3 shape, both emit arms, and the S1 joint it binds.
 *
 * Each promise carries its own failure: the empty branch list, the template imports, and
 * the duplicate key are asserted alongside the shapes they guard.
 */
class ActionSpecTest {

    private val navigateSpec: ActionSpec = ActionSpec(
        id = ActionId("nav.navigate"),
        metadata = ActionMetadata("Navigate"),
        params = listOf(prop<String>("route", TypeRef.Str, required = true)),
        emit = ActionEmit.Intrinsic,
    )

    private val ifSpec: ActionSpec = ActionSpec(
        id = ActionId("flow.if"),
        metadata = ActionMetadata("Branch", description = "Runs then or else"),
        params = listOf(prop<Boolean>("condition", TypeRef.Bool, required = true)),
        branches = listOf(
            BranchSpec(BranchName("then"), required = true),
            BranchSpec(BranchName("else")),
        ),
        emit = ActionEmit.Intrinsic,
    )

    private val logSpec: ActionSpec = ActionSpec(
        id = ActionId("analytics.log"),
        metadata = ActionMetadata("Log event"),
        params = listOf(prop<String>("name", TypeRef.Str, required = true)),
        emit = ActionEmit.Template(
            pattern = "analytics.log({name})",
            imports = listOf(KotlinSymbol("com.example.analytics", "Analytics")),
        ),
    )

    @Test
    fun `navigate is intrinsic with one required param`() {
        assertEquals(ActionId("nav.navigate"), navigateSpec.id)
        assertEquals("Navigate", navigateSpec.metadata.displayName)
        assertEquals(emptyList(), navigateSpec.branches)
        assertIs<ActionEmit.Intrinsic>(navigateSpec.emit)
    }

    @Test
    fun `flow if names both arms and requires only then`() {
        assertEquals(2, ifSpec.branches.size)
        assertEquals(BranchName("then"), ifSpec.branches[0].name)
        assertEquals(true, ifSpec.branches[0].required)
        assertEquals(false, ifSpec.branches[1].required)
        assertEquals("", ifSpec.branches[0].doc)
        assertEquals("Runs then or else", ifSpec.metadata.description)
    }

    @Test
    fun `a plugin action carries its template and imports`() {
        val emit = assertIs<ActionEmit.Template>(logSpec.emit)

        assertEquals("analytics.log({name})", emit.pattern)
        assertEquals(
            listOf(KotlinSymbol("com.example.analytics", "Analytics")),
            emit.imports,
        )
    }

    @Test
    fun `the joint registers the spec under its own id`() {
        val schema: ActionSchema<String, String, String, String> =
            Schema.build<String, String, ActionSpec, String, String> {
                action(navigateSpec)
                action(ifSpec)
                action(logSpec)
            }

        assertSame(navigateSpec, schema.actions.require(ActionId("nav.navigate")))
        assertSame(ifSpec, schema.actions.require(ActionId("flow.if")))
        assertSame(logSpec, schema.actions.require(ActionId("analytics.log")))
    }

    @Test
    fun `two specs on one key fail naming the key and the registry`() {
        val failure = assertFailsWith<SchemaBuildException> {
            Schema.build<String, String, ActionSpec, String, String> {
                action(navigateSpec)
                action(navigateSpec)
            }
        }

        assertTrue(failure.message?.contains("nav.navigate") == true)
        assertTrue(failure.message?.contains("actions") == true)
    }

    @Test
    fun `an unknown action fails at read time`() {
        val schema: ActionSchema<String, String, String, String> =
            Schema.build<String, String, ActionSpec, String, String> {
                action(navigateSpec)
            }

        assertFailsWith<NoSuchElementException> {
            schema.actions.require(ActionId("flow.if"))
        }
    }
}
