package dev.rotalex.lutter.editor.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * The key names, outside the store so the pure mapping below can be tested without a file.
 *
 * Two declarations of one name would be a rename that half-lands, and a `companion object` inside
 * the store would be invisible to the function that reads the same keys.
 */
internal object PreferencesSettingsStoreKeys {
    val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
    val CODE_SCALE: Preferences.Key<Float> = floatPreferencesKey("code_text_scale")
    val AUTOSAVE: Preferences.Key<Boolean> = booleanPreferencesKey("autosave")
}

/**
 * The settings as a DataStore Preferences file, which is what both hosts actually use.
 *
 * ### Why a file and not three rows somewhere
 *
 * There is no server, no account and no database in this project, so the answer to "where does a
 * preference live" is a small file the platform writes atomically. DataStore is that file with the
 * three problems already solved: a partial write cannot be read as a value, two writers cannot
 * interleave, and the reader is a `Flow` instead of a poll.
 *
 * ### One instance per file, per process
 *
 * DataStore enforces this and throws when it is violated, because two instances over one file are
 * two caches that disagree. Creating the store in a composition would violate it on every Android
 * configuration change, so the hosts own the instance (a companion object on Android, a top-level
 * `val` on the desktop) and this class is created once around it.
 *
 * ### Failure is defaults, never a crash
 *
 * A settings file is not worth a startup failure: an unreadable one is replaced by
 * [emptyPreferences], and a read that throws mid-stream is caught and answered with the last value
 * this store published. The editor opening with a theme it did not ask for is a bad minute; an
 * editor that refuses to open is a lost document.
 *
 * [scope] is the store's own lifetime, not the window's: a write must finish even while the UI is
 * being torn down, and the host decides how long that is.
 */
public class PreferencesSettingsStore(
    path: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : SettingsStore {

    private val file: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { path.toPath() },
    )

    private val state: MutableStateFlow<EditorSettings> = MutableStateFlow(EditorSettings.Default)

    override val settings: StateFlow<EditorSettings> = state.asStateFlow()

    init {
        // Collected for the life of the store rather than per reader: the theme wraps the whole
        // window, so the value is read at the root and a pull per reader would be the same file
        // read twice.
        scope.launch {
            file.data
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    // Keep what this store already published: the defaults before the first read,
                    // or the last good answer after a successful one.
                }
                .collect { preferences -> state.value = preferences.toSettings() }
        }
    }

    override suspend fun update(transform: (EditorSettings) -> EditorSettings) {
        val next: EditorSettings = transform(state.value).normalised()
        // Published before the write: the control moves under the finger that moved it and the
        // file catches up. A failed write leaves the value on screen and the next launch showing
        // the old one, which is a failure the reader can see and retry.
        state.value = next
        file.edit { preferences ->
            preferences[PreferencesSettingsStoreKeys.THEME] = next.theme.name
            preferences[PreferencesSettingsStoreKeys.CODE_SCALE] = next.codeTextScale
            preferences[PreferencesSettingsStoreKeys.AUTOSAVE] = next.autosave
        }
    }
}

/**
 * The file's contents as settings, defaulting every key it does not carry.
 *
 * An unknown theme spelling falls back for that key alone instead of failing the whole read: a
 * file written by a newer build with a theme this one has never heard of still has a code size
 * worth honouring.
 */
internal fun Preferences.toSettings(): EditorSettings = EditorSettings(
    theme = this[PreferencesSettingsStoreKeys.THEME]
        ?.let { stored -> ThemeChoice.entries.firstOrNull { it.name == stored } }
        ?: ThemeChoice.System,
    codeTextScale = this[PreferencesSettingsStoreKeys.CODE_SCALE] ?: 1f,
    autosave = this[PreferencesSettingsStoreKeys.AUTOSAVE] ?: true,
).normalised()
