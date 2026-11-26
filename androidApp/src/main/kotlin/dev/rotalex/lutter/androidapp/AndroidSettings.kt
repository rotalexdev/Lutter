package dev.rotalex.lutter.androidapp

import dev.rotalex.lutter.editor.settings.PreferencesSettingsStore
import java.io.File

/**
 * The activity's one settings store, created on first use and kept for the process's life.
 *
 * DataStore permits exactly one instance per file per process and throws when a second one is
 * created, so this cannot live in the composition: an Android activity is destroyed and rebuilt on
 * every rotation, and a store built beside it would be the second one while the first is still
 * holding the file.
 *
 * `filesDir` is the same private directory the document and the session record live in — no
 * permission, no shared location, removed with the app.
 */
internal object AndroidSettings {

    @Volatile
    private var created: PreferencesSettingsStore? = null

    /** The store whose file is `settings.preferences_pb` inside [directory]. */
    fun inDirectory(directory: File): PreferencesSettingsStore {
        created?.let { return it }
        return synchronized(this) {
            created ?: PreferencesSettingsStore(File(directory, FILE).path).also { created = it }
        }
    }

    private const val FILE: String = "settings.preferences_pb"
}
