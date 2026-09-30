package dev.rotalex.lutter.model.doc

import dev.rotalex.lutter.model.ids.ComponentDeclId
import dev.rotalex.lutter.model.ids.NodeId
import dev.rotalex.lutter.model.ids.SlotName
import kotlinx.serialization.Serializable

/**
 * A reusable component the document declares for itself.
 *
 * PLAN §5.5 declares all six fields, and D5 is what makes the record this small: a document
 * component is *composition only* — the generated signature is
 * `@Composable fun Foo(param, content: @Composable () -> Unit)`, so [params] and [slots] are
 * the complete override surface and there is no structural-override field to carry.
 *
 * [root] is a `NodeId` for the same reason [Page.root] is: the body lives in
 * `UiDocument.nodes` and the declaration points at it.
 */
@Serializable
public data class ComponentDecl(

    /** The id, and the tail of the `doc.<id>` component type its instances carry (§5.7). */
    public val id: ComponentDeclId,

    /** A Kotlin identifier, emitted as the composable's name. */
    public val name: String,

    /** The typed inputs an instance binds. */
    public val params: List<ParamDecl>,

    /** The named content areas an instance fills. */
    public val slots: List<SlotDecl>,

    /** State scoped to each instance's composition (§12.1). */
    public val state: List<StateDecl> = emptyList(),

    /** The node this component's body starts at. */
    public val root: NodeId,
)

/**
 * A named content area a document component exposes to its instances.
 *
 * **One field, and the four it does not have are a design rather than a loss.** PLAN §7.1
 * declares `SlotSpec` with `name`, `cardinality`, `accepts`, `provides` and `iteration`, and
 * §33.2 puts `Cardinality` and `IterationSpec` in `:engine:schema` — which §23.3 makes
 * unreachable from `:engine:model`. §5.5:465 is what turns that into a design: the engine
 * synthesizes a `ComponentSpec` for each `ComponentDecl`, and the synthesis is where a
 * document slot acquires `Cardinality.Many` and no `ScopeId`.
 *
 * A later session that adds those fields here breaks §23.3, not just §5.5.
 */
@Serializable
public data class SlotDecl(

    /** The slot's name, as the instance writes it. */
    public val name: SlotName,
)
