package dev.rotalex.lutter.model.ids

import kotlinx.serialization.Serializable

/**
 * The typed identifiers every record in the document is keyed by.
 *
 * Each one is a `value class` wrapping a `String`, and each validates its own syntax in
 * `init`. That combination buys three things:
 *
 *  * **A wrong id cannot be constructed.** There is no `NodeId("")` and no
 *    `ComponentType("no dot")`, so the question "is this identifier well formed" is
 *    answered once, at construction, instead of at every use.
 *  * **The id is free at runtime.** A value class erases to its `String` on the JVM and to
 *    the underlying type everywhere else. A `Map<NodeId, Node>` has no allocation per key
 *    that a regular class would have.
 *
 * Note the absence of `@JvmInline`. PLAN §5.2 writes these as `@JvmInline value class`,
 * which is the JVM spelling; the annotation lives in `kotlin.jvm` and does not resolve in
 * `commonMain`. Inline behaviour is what a `value class` already gets on the JVM, so the
 * annotation was never needed here — and the Wasm canary target is what proved it, by
 * failing to compile.
 *  * **An id reads as itself.** `toString()` returns the raw value, so a diagnostic says
 *    `n_1` and not `NodeId(value=n_1)`. That matters more than it sounds: ids are the
 *    vocabulary these diagnostics are written in.
 *
 * Deserialization runs the same `init`, so a document carrying a malformed id fails when
 * it is read rather than somewhere downstream of it. Syntax is the one thing the model can
 * check without the schema, and it checks it.
 *
 * @see IdSyntax for the two shapes and why there are two.
 */

// ---------------------------------------------------------------------------------------
// Simple ids — names something inside a document.
// ---------------------------------------------------------------------------------------

/** A node in the document tree. */
@Serializable
public value class NodeId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid NodeId: '$value'" }
    }

    override fun toString(): String = value
}

/** A page. */
@Serializable
public value class PageId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid PageId: '$value'" }
    }

    override fun toString(): String = value
}

/** A document-defined reusable component. */
@Serializable
public value class ComponentDeclId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid ComponentDeclId: '$value'" }
    }

    override fun toString(): String = value
}

/** A state declaration: a page, a component or the app. */
@Serializable
public value class StateId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid StateId: '$value'" }
    }

    override fun toString(): String = value
}

/** A theme declaration. */
@Serializable
public value class ThemeId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid ThemeId: '$value'" }
    }

    override fun toString(): String = value
}

/** A resource declaration. */
@Serializable
public value class ResourceId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid ResourceId: '$value'" }
    }

    override fun toString(): String = value
}

/** A data model declaration — a structured type the document declares for itself. */
@Serializable
public value class DataModelId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid DataModelId: '$value'" }
    }

    override fun toString(): String = value
}

/** A type the document declares: an enum type or an object type. */
@Serializable
public value class TypeId(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid TypeId: '$value'" }
    }

    override fun toString(): String = value
}

/** The name of a parameter of a page or a component declaration. */
@Serializable
public value class ParamName(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid ParamName: '$value'" }
    }

    override fun toString(): String = value
}

/**
 * A property key, as written in a node's `props` map.
 *
 * A key rather than an id, because it names a parameter of a component specification and
 * not a thing in the document. The distinction shows up in the generated code, where a
 * property becomes an argument name.
 */
@Serializable
public value class PropertyKey(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid PropertyKey: '$value'" }
    }

    override fun toString(): String = value
}

/** The name of a slot — a named child area of a node. */
@Serializable
public value class SlotName(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid SlotName: '$value'" }
    }

    override fun toString(): String = value
}

/** An event key, such as `onClick`. */
@Serializable
public value class EventKey(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid EventKey: '$value'" }
    }

    override fun toString(): String = value
}

/** The name of a branch — a `when` arm in a document-defined component. */
@Serializable
public value class BranchName(public val value: String) {
    init {
        require(IdSyntax.isValidSimple(value)) { "Invalid BranchName: '$value'" }
    }

    override fun toString(): String = value
}

// ---------------------------------------------------------------------------------------
// Namespaced ids — registry keys, resolved through a schema.
// ---------------------------------------------------------------------------------------

/**
 * A component type, such as `core.Column` or `m3.Text`.
 *
 * Namespaced because a plugin has to be able to contribute component types without
 * colliding with the built-ins, and the namespace is what makes that decidable.
 */
@Serializable
public value class ComponentType(public val value: String) {
    init {
        require(IdSyntax.isValidNamespaced(value)) { "Invalid ComponentType: '$value'" }
    }

    override fun toString(): String = value
}

/** A modifier type, such as `layout.padding`. */
@Serializable
public value class ModifierType(public val value: String) {
    init {
        require(IdSyntax.isValidNamespaced(value)) { "Invalid ModifierType: '$value'" }
    }

    override fun toString(): String = value
}

/** An action id, such as `nav.navigate`. */
@Serializable
public value class ActionId(public val value: String) {
    init {
        require(IdSyntax.isValidNamespaced(value)) { "Invalid ActionId: '$value'" }
    }

    override fun toString(): String = value
}

/** A function id in the expression language, such as `list.isNotEmpty`. */
@Serializable
public value class FunctionId(public val value: String) {
    init {
        require(IdSyntax.isValidNamespaced(value)) { "Invalid FunctionId: '$value'" }
    }

    override fun toString(): String = value
}

/** A plugin id, such as `forge.material3`. */
@Serializable
public value class PluginId(public val value: String) {
    init {
        require(IdSyntax.isValidNamespaced(value)) { "Invalid PluginId: '$value'" }
    }

    override fun toString(): String = value
}
