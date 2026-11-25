package dev.rotalex.lutter.androidapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * The second screen, on the host that has one.
 *
 * The value half of the Code screen is `GeneratedCodeTest`'s, in `:app:editor`. What this proves is
 * the wiring a unit test cannot: the tab exists, tapping it reaches the generator with the schema
 * and document the activity actually opened, and the result reaches the screen. A host that passed
 * the wrong schema, or a screen that read the document before the workspace opened, fails here and
 * passes there.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityCodeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_code_tab_shows_the_generated_kotlin() {
        // The workspace opens off the main thread, so the tabs are not there on the first frame.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasText("Code")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Code").performClick()

        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag("code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("code").assertIsDisplayed()
        assertTrue(
            compose.onAllNodes(hasText("package ", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            "the listing shows file paths but no Kotlin: the screen is drawing a header, not code",
        )
    }

    private companion object {
        const val TIMEOUT_MS: Long = 10_000
    }
}
