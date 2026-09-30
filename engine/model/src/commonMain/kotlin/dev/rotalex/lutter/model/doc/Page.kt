package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.PageId
import dev.rotalex.lutter.model.ids.ParamName
import dev.rotalex.lutter.model.type.TypeRef
import kotlinx.serialization.Serializable

/**
 * A typed input of a page, a component or a host function.
 *
 * PLAN §5.5 names this type three times and declares it nowhere; §33.1 puts it in this file,
 * beside a `Page` that is not here yet. The three fields are quoted from §13.1's analysis rule
 * — *"provided args match the target's `ParamDecl`s (name, type, required)"* — and their types
 * are the only ones the surrounding vocabulary permits: §10.1's `RefTarget.Param` and §13.2's
 * `Map<ParamName, Value>` fix the first, §5.5's "typed inputs" fixes the second.
 *
 * `required` defaults to true, which §903 does not say. §6.2's canonical writer encodes no
 * defaults, so this makes the common case the one that is not written and leaves the wire form
 * identical for a caller that spells it out.
 */
@Serializable
public data class ParamDecl(

    /** The name a call site binds and `RefTarget.Param` reads. */
    public val name: ParamName,

    /** The declared type. Bounds belong to the spec, as they do for every other type. */
    public val type: TypeRef,

    /** Whether a caller has to supply it. */
    public val required: Boolean = true,
)

/**
 * A screen: what `nav.navigate` arrives at, and what the codegen emits a composable for.
 *
 * PLAN §5.5 declares all six fields. Two of the comments in the plan are the specification
 * and are kept here: [name] is a Kotlin identifier because §16.4 turns it into a composable
 * name, and [route] plus [params] *are* the typed destination — §13.1's comment says so, and
 * it is why `AppSpec.navigation` has no field to declare the destination in.
 *
 * [root] is a `NodeId` into `UiDocument.nodes` and not a [Node]: the page owns no nodes, it
 * points at one. §5.6's whole trade is that a child is a reference, and a page that held its
 * tree would be §5.6's rejected nested option with one less layer of nesting.
 */
@Serializable
public data class Page(

    /** The id, stable across a rename (§6.2). */
    public val id: PageId,

    /** A Kotlin identifier, emitted as the composable's name. */
    public val name: String,

    /** The route a `nav.navigate` action names. Unique across the document (§13.1). */
    public val route: String,

    /** The typed inputs this screen's route takes. Defaults because most screens take none. */
    public val params: List<ParamDecl> = emptyList(),

    /** State scoped to this page's composition (§12.1). */
    public val state: List<StateDecl> = emptyList(),

    /** The node this screen's tree starts at. Not owned: it lives in the global table. */
    public val root: NodeId,
)
