package dev.rotalex.lutter.builtins

import dev.rotalex.lutter.builtins.enums.ContentScaleSpec
import dev.rotalex.lutter.model.doc.ResourceKind
import dev.rotalex.lutter.model.ids.ComponentType
import dev.rotalex.lutter.model.type.TypeRef
import dev.rotalex.lutter.schema.component.Category
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.KotlinSymbol
import dev.rotalex.lutter.schema.component.PropertySpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.component.componentSpec
import dev.rotalex.lutter.schema.component.prop

/**
 * The `m3.Image` spec: a resource reference drawn as a bitmap (§10:795's resource row).
 *
 * `source` is a `Str` property whose value is a `Value.Ref(kind=resource, id)` — the
 * reference shape §10:795's table gives the resource/image row. Declaring
 * [PropertySpec.resourceKind] is what makes the shape legal: a spec that names a resource
 * kind accepts a resource reference where its type is `Str`, validation checks the
 * reference exists (`ReferencePass`) and that the kind matches (SchemaPass), and the RUNTIME
 * resolves the reference through the environment's `ResourceProvider` at render time.
 * GENERATED code emits `Res.drawable.<name>` through compose-resources (§20:1598's
 * ResourceStrategy).
 */
public object ImageSpec {

    /**
     * The image to draw: a resource reference.
     *
     * Required: an image without a source draws nothing, and the resource reference is
     * the only shape the plan gives this row.
     */
    public val source: PropertySpec<String> =
        prop("source", TypeRef.Str, required = true, resourceKind = ResourceKind.Image)

    /**
     * The accessibility text for the image, or nothing.
     *
     * Material's `contentDescription: String?` — the null spell is an omitted parameter,
     * which is this wave's rule for optionals.
     */
    public val contentDescription: PropertySpec<String> =
        prop("contentDescription", TypeRef.Str)

    /** How the image fills its box, Compose's `ContentScale.Fit` as the default. */
    public val fit: PropertySpec<String> = prop("fit", ContentScaleSpec.Type)

    /** The spec renderers, validation and codegen share. */
    public val spec: ComponentSpec = componentSpec(ComponentType("m3.Image"), version = 1) {
        metadata(displayName = "Image", category = Category.Basic)
        property(source)
        property(contentDescription)
        property(fit)
        composeCall(KotlinSymbol("androidx.compose.foundation", "Image")) {
            // Material 3 has no Image of its own — the call is Foundation's, whose Kotlin
            // parameters differ from this spec's document names: the source prop binds
            // `bitmap` (spelled painterResource(Res.drawable.<name>) by the resource
            // strategy), and the fit prop binds `contentScale`.
            param("bitmap", from = source, emit = ValueEmit.Resource)
            param("contentDescription", from = contentDescription)
            param("contentScale", from = fit)
        }
    }
}
