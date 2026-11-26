package dev.rotalex.lutter.editor.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The settings file, and the mapping from its keys to the editor's own values.
 *
 * Two halves, because they fail for different reasons. The mapping is pure and asserted without a
 * file: a key renamed in one place and not the other is a setting that silently forgets, and that
 * is the failure this half catches. The file half is the round trip a restart performs, which is
 * the only claim the store actually makes.
 */
class PreferencesSettingsStoreTest {

    @Test
    fun `a file with no keys is the editor's defaults`() {
        assertEquals(EditorSettings.Default, emptyPreferences().toSettings())
    }

    @Test
    fun `every stored key is read back`() {
        val preferences = preferencesOf(
            PreferencesSettingsStoreKeys.THEME to "Dark",
            PreferencesSettingsStoreKeys.CODE_SCALE to 1.2f,
            PreferencesSettingsStoreKeys.AUTOSAVE to false,
        )

        val settings = preferences.toSettings()

        assertEquals(ThemeChoice.Dark, settings.theme)
        assertEquals(1.2f, settings.codeTextScale)
        assertEquals(false, settings.autosave)
    }

    @Test
    fun `a theme this build does not know falls back without losing the rest`() {
        val preferences = preferencesOf(
            PreferencesSettingsStoreKeys.THEME to "Solarized",
            PreferencesSettingsStoreKeys.CODE_SCALE to 1.3f,
        )

        val settings = preferences.toSettings()

        assertEquals(ThemeChoice.System, settings.theme)
        assertEquals(1.3f, settings.codeTextScale)
    }

    @Test
    fun `a code size outside the offered range is brought back into it`() {
        val settings = preferencesOf(PreferencesSettingsStoreKeys.CODE_SCALE to 4f).toSettings()

        assertEquals(EditorSettings.CODE_SCALE_RANGE.endInclusive, settings.codeTextScale)
    }

    /**
     * The restart: a second store over the same file reads what the first one wrote.
     *
     * The first store's scope is cancelled and joined before the second is created, because
     * DataStore permits one instance per file per process and releases the file when the scope
     * that owns it completes. That is the same order a process restart gets from the operating
     * system, made explicit.
     */
    @Test
    // `runBlocking<Unit>`: the block's last expression is `file.delete()`, and a test method that
    // returns Boolean is not a test method as far as JUnit4 is concerned.
    fun `the settings survive a restart`() = runBlocking<Unit> {
        val file: File = File.createTempFile("lutter-settings", ".preferences_pb").apply { delete() }
        file.parentFile?.mkdirs()

        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PreferencesSettingsStore(file.path, firstScope).update {
            it.copy(theme = ThemeChoice.Dark, codeTextScale = 1.2f, autosave = false)
        }
        firstScope.coroutineContext.job.cancelAndJoin()

        assertTrue(file.isFile, "the write reported success and left no file behind")

        val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val reopened = PreferencesSettingsStore(file.path, secondScope)
        val stored = withTimeout(10_000) {
            reopened.settings.first { it.theme == ThemeChoice.Dark }
        }
        secondScope.coroutineContext.job.cancelAndJoin()

        assertEquals(1.2f, stored.codeTextScale)
        assertEquals(false, stored.autosave)
        file.delete()
    }
}
