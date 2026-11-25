package dev.rotalex.lutter.codegen

/**
 * §20:1598's ResourceStrategy: how a resource reference spells in generated code.
 *
 * The reference itself is `Value.Ref(kind=resource, id)`; what varies per host is only
 * where the generated `Res` class lives — the scaffold (§33) wires compose-resources and
 * owns that package, so the generator is told rather than guessing. `None` is the default:
 * a generation with no resources configured refuses a resource emission with a finding,
 * the same fail-fast every other strategy uses for a shape it was given nothing to spell.
 *
 * The vocabulary mapping is not per-host: `drawable` for images and `string` for strings
 * is compose-resources' own layout, so the generator holds it and the strategy only holds
 * the package.
 */
public interface ResourceStrategy {

    /** The package the scaffold's `Res` class generates into, or null when there is none. */
    public val resPackage: String?

    public companion object {
        /** No resources wired. A resource emission refused, not spelled against nothing. */
        public val None: ResourceStrategy = object : ResourceStrategy {
            override val resPackage: String? = null
        }
    }
}

/** The compose-resources spelling, for a scaffold that wired the resources plugin (§33). */
public class ComposeResourceStrategy(
    /** The package the resources plugin generates `Res` into — `packageOfRes` in the build. */
    override val resPackage: String,
) : ResourceStrategy
