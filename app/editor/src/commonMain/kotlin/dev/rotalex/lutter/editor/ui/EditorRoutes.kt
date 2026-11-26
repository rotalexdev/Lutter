package dev.rotalex.lutter.editor.ui

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Where the editor can be.
 *
 * ### A back stack, not a flag
 *
 * The frame used to hold a `settingsOpen` boolean and an `EditorDestination`, and each of them
 * was a second copy of the answer to "which screen is this". Two screens fit in a boolean; a
 * third destination turns the same question into a stack, and the stack is also what makes
 * "back" mean something: Settings is *pushed*, so leaving it is a pop and the platform's own
 * back gesture does it without the frame knowing which screen it came from.
 *
 * ### Why `data object` and `NavKey`
 *
 * `rememberNavBackStack` saves the stack across a configuration change by serialising its keys,
 * which is why each one is `@Serializable` and implements [NavKey]. They are objects because
 * none of them carries an argument: the document is the session's, and a destination that took
 * a node id would be a second selection to keep in step with the first.
 */
@Serializable
internal data object EditorRoute : NavKey

/** The code the document becomes: read-only, and the same generator the acceptance test runs. */
@Serializable
internal data object CodeRoute : NavKey

/** What the editor remembers. Pushed on top of whichever screen opened it. */
@Serializable
internal data object SettingsRoute : NavKey

/**
 * How the back stack is written down when the composition is rebuilt.
 *
 * `rememberNavBackStack` saves the stack through `NavKey`'s *open polymorphism*, and on every
 * platform but Android it refuses to guess which subclasses exist: the keys are registered here,
 * by hand, which is also the list that would break if a destination were added and this file were
 * not told. Android alone has an overload that reflects its way to the same answer, and a frame
 * that relied on it would be a frame whose rotation behaviour differs between the two hosts.
 */
internal val EditorSavedState: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(EditorRoute::class, EditorRoute.serializer())
            subclass(CodeRoute::class, CodeRoute.serializer())
            subclass(SettingsRoute::class, SettingsRoute.serializer())
        }
    }
}

/**
 * The destinations the primary navigation lists, in the order it lists them.
 *
 * Settings is not here on purpose: it is not somewhere the editor *is*, it is something the
 * frame opens over it, and a bar that offered it beside the two screens would say otherwise.
 */
internal val PrimaryRoutes: List<NavKey> = listOf(EditorRoute, CodeRoute)

/** What the navigation suite calls [route]. */
internal fun labelOf(route: NavKey): String = when (route) {
    EditorRoute -> "Editor"
    CodeRoute -> "Code"
    SettingsRoute -> "Settings"
    else -> route.toString()
}

/**
 * Makes [route] the destination on screen, the way a tap on a top-level item does.
 *
 * A top-level move is not a push: the stack keeps one entry, so Editor and Code do not pile up
 * under each other and back from either leaves the app instead of walking a history nobody asked
 * for. Anything pushed on top (Settings) goes with it.
 */
internal fun MutableList<NavKey>.switchTo(route: NavKey) {
    while (size > 1) removeAt(lastIndex)
    if (isEmpty()) add(route) else this[0] = route
}

/** Pushes [route] over the current one: how Settings arrives. */
internal fun MutableList<NavKey>.push(route: NavKey) {
    add(route)
}

/**
 * Removes the top entry, if there is one under it.
 *
 * At the root there is nothing to go back to and the answer is `false`: back belongs to the
 * platform there (it closes the window, it leaves the activity), and a frame that popped its own
 * last entry would be an app with no way out.
 */
internal fun MutableList<NavKey>.pop(): Boolean {
    if (size <= 1) return false
    removeAt(lastIndex)
    return true
}
