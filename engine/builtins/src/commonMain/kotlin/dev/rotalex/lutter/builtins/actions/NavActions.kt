package dev.rotalex.lutter.builtins.actions

import dev.rotalex.lutter.model.ids.ActionId
import dev.rotalex.lutter.model.type.RefKind
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.action.ActionEmit
import dev.rotalex.lutter.schema.action.ActionMetadata
import dev.rotalex.lutter.schema.action.ActionSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * §11.4's `nav.*` pair, both intrinsic: §11.4 makes navigation engine-owned and §11.5 emits
 * `navigator.navigate(Route.Profile)` for it.
 *
 * `navigate` declares `page` alone: §13.1 spells its args `page: Ref(page,"p_profile"),
 * <paramName>: expr` and then makes the route arguments the *target's* `ParamDecl`s, so a static
 * list of them could not be right for every page. `back` declares none — §13.2's `back(): Boolean`
 * has no parameter.
 */
public object NavActions {

    /**
     * `nav.navigate(page)`. `prop<Nothing>` because `PropertySpec<T>`'s parameter is the access
     * type a renderer reads the value through, and no handler or emitter exists to name one.
     */
    public val navigate: ActionSpec = ActionSpec(
        id = ActionId("nav.navigate"),
        metadata = ActionMetadata("Navigate"),
        params = listOf(prop<Nothing>("page", TypeRef.Ref(RefKind.Page), required = true)),
        emit = ActionEmit.Intrinsic,
    )

    /** `nav.back`: §13.2's `back()` reads the navigator's own stack, so it takes no argument. */
    public val back: ActionSpec = ActionSpec(
        id = ActionId("nav.back"),
        metadata = ActionMetadata("Back"),
        params = emptyList(),
        emit = ActionEmit.Intrinsic,
    )

    /** Every spec here, in §11.4's order. */
    public val all: List<ActionSpec> = listOf(navigate, back)
}
