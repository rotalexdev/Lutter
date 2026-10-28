package dev.rotalex.lutter.model.annotations

/**
 * Marks a declaration that is `public` only so another Forge module can reach it, and whose
 * shape is still expected to move.
 *
 * Kotlin has no friend modules and no package-private visibility that spans a module
 * boundary, so the usual substitute would be to either over-promise stability or to resort
 * to `internal` plus reflection. Neither is acceptable across a module graph, so the
 * compromise is an explicit opt-in: a consumer that uses one of these has to say so at the
 * call site, which turns "this API may change" from a paragraph in a design document into
 * something the compiler reports.
 *
 * The level is [RequiresOptIn.Level.WARNING] rather than `ERROR` on purpose. An `ERROR`
 * opt-in turns a routine refactor into a build failure everywhere the declaration is used,
 * including in the module that owns it, and the pressure that creates is to widen the
 * exemption rather than to stabilise the API. A warning still shows up in the build log,
 * and `forge.warningsAsErrors` turns it into a failure in CI, so an unacknowledged use
 * cannot land silently.
 *
 * Usage:
 * ```
 * @EngineInternalApi
 * public interface DocumentStorage { ... }
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is an engine-internal API. It is public so other Forge modules can reach it, " +
        "and it may change or be removed without notice. Depend on it deliberately.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class EngineInternalApi
