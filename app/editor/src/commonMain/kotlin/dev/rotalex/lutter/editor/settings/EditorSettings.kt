package dev.rotalex.lutter.editor.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which of the two colour schemes the editor draws, or the platform's own answer.
 *
 * `System` is a real choice and not "no choice": it is what a person picks when they want the
 * editor to follow the phone or the desktop's own setting, including while it changes. That is why
 * the value is a three-way enum and not the `Boolean` the theme has underneath — a boolean cannot
 * say "ask the platform", and every caller would have to answer a question the theme is better at.
 */
public enum class ThemeChoice {
    System,
    Light,
    Dark,
}

/**
 * What the editor remembers about how its reader wants to read it.
 *
 * Deliberately small. A setting is a promise that somebody will maintain it, so a value lands here
 * only when it changes what a person sees and has no better home: the document holds what the app
 * *is*, and these three hold how it is shown.
 *
 * The defaults are today's behaviour — follow the platform, code at its normal size, autosave on —
 * because an editor that changes itself for someone who never opened this screen is an editor that
 * cannot be trusted with the next release.
 */
public data class EditorSettings(
    val theme: ThemeChoice = ThemeChoice.System,
    /** Multiplier on the code font, `0.8` to `1.4`. */
    val codeTextScale: Float = 1f,
    /** When false, the frame offers a `Save` action and writes nothing on its own. */
    val autosave: Boolean = true,
) {

    /** [codeTextScale] brought inside the range the screen offers, whatever the file said. */
    public fun normalised(): EditorSettings = if (codeTextScale in CODE_SCALE_RANGE) {
        this
    } else {
        copy(codeTextScale = codeTextScale.coerceIn(CODE_SCALE_RANGE))
    }

    public companion object {
        /** The smallest and largest multiplier the settings screen can produce. */
        public val CODE_SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.8f..1.4f

        public val Default: EditorSettings = EditorSettings()
    }
}

/**
 * Where the settings live.
 *
 * An interface for the same reason the document storage seam is one: the frame and its
 * tests must be able to draw a settings screen without a directory, and the two hosts own the only
 * thing the store actually needs — a path.
 *
 * [settings] is a `StateFlow` rather than a `Flow` so a host can wrap its whole window in the
 * theme without a placeholder frame: the store's first value is the defaults, read before the file
 * answers, and the file's answer arrives as the next emission.
 */
public interface SettingsStore {

    public val settings: StateFlow<EditorSettings>

    /**
     * Replaces the settings with [transform]'s answer and persists the result.
     *
     * Suspends until the write is done, so a caller that shows "saved" after this returns is
     * telling the truth. A store with nowhere to write still updates [settings].
     */
    public suspend fun update(transform: (EditorSettings) -> EditorSettings)
}

/**
 * The store with no file behind it: the defaults, and whatever this process changes them to.
 *
 * Used by a host with no directory and by every test, which is the point — the frame cannot tell
 * the difference, so a test that drives the settings screen is testing the screen.
 */
public class InMemorySettingsStore(
    initial: EditorSettings = EditorSettings.Default,
) : SettingsStore {

    private val state: MutableStateFlow<EditorSettings> = MutableStateFlow(initial.normalised())

    override val settings: StateFlow<EditorSettings> = state.asStateFlow()

    override suspend fun update(transform: (EditorSettings) -> EditorSettings) {
        state.update { transform(it).normalised() }
    }
}
