package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.schema.component.PlatformTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hilt's answers: an Android-only lookup, and a refusal with two axes.
 *
 * The platform axis is the one no other container has, and it is the reason the seam grew a second
 * `refusal` parameter: a document that targets iOS or desktop cannot carry `hiltViewModel()` no
 * matter which state strategy it selects.
 */
class HiltInjectionTest {

    private val screens: String = "com.example.app.screens"

    @Test
    fun `hilt keeps the state strategy's parameter and changes only who builds it`() {
        val declared: KtParam = ViewModelState.pageParameter("Home", screens)
        val injected: KtParam = Hilt.holderParameter("Home", ViewModelState, screens)

        assertEquals(declared.name, injected.name)
        assertEquals(declared.type, injected.type)
        assertEquals("hiltViewModel()", printedDefault(injected))
    }

    /** The two artifacts the emitted lookup needs, at the versions the catalog carries. */
    @Test
    fun `hilt carries the container and the compose lookup`() {
        assertEquals(
            setOf(GeneratedLibrary.HiltAndroid, GeneratedLibrary.HiltNavigationCompose),
            Hilt.libraries,
        )
    }

    @Test
    fun `hilt refuses a document that is not android only`() {
        assertNull(Hilt.refusal(ViewModelState, setOf(PlatformTag.Android)))
        for (targets in listOf(
            setOf(PlatformTag.Android, PlatformTag.Ios),
            setOf(PlatformTag.Desktop),
            PlatformTag.ALL,
        )) {
            assertNotNull(Hilt.refusal(ViewModelState, targets), "expected a refusal for $targets")
        }
    }

    @Test
    fun `hilt refuses a holder a ViewModelStore does not own`() {
        assertNull(Hilt.refusal(ViewModelState))
        assertNotNull(Hilt.refusal(ComposeSnapshotState))
    }

    /** The platform refusal is the one that fires first: it is about the project, not the page. */
    @Test
    fun `the platform refusal does not depend on the state strategy`() {
        val message: String? = Hilt.refusal(ComposeSnapshotState, setOf(PlatformTag.Ios))
        assertNotNull(message)
        assertTrue("Android-only" in message, message)
    }

    private fun printedDefault(param: KtParam): String {
        val expression: KtExpr = param.default ?: error("a holder parameter always has a default")
        val property: KtDeclaration = KtDeclaration.Property(
            name = "probe",
            annotations = emptyList(),
            type = null,
            mutable = false,
            initializer = expression,
            delegate = null,
            getter = null,
            visibility = "private",
        )
        return KtPrinter().print(KtFile("com.example.app.screens", null, listOf(property)))
            .lineSequence()
            .first { it.contains("private val probe") }
            .substringAfter("= ")
            .trim()
    }
}
