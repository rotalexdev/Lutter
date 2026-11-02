package dev.rotalex.lutter.builtins.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.rotalex.lutter.analysis.resolved.ResolvedNode
import dev.rotalex.lutter.builtins.ImageSpec
import dev.rotalex.lutter.model.expr.PropertyValue
import dev.rotalex.lutter.model.ids.ResourceId
import dev.rotalex.lutter.model.value.Value
import dev.rotalex.lutter.runtime.ComponentRenderer
import dev.rotalex.lutter.runtime.LoadedResource
import dev.rotalex.lutter.runtime.RenderScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PLAN §7.4's renderer for the wave-2 image row (§10:795).
 *
 * The source prop holds a `Value.Ref(kind=resource, id)` — read raw off the resolved node,
 * because a ref is not a string and the reader's decode would be the wrong question. The
 * load runs once per id: a resource's bytes do not change while a node is on screen, so a
 * re-render subscribes the same loaded value rather than re-reading the provider.
 *
 * A load that misses draws nothing: a renderer cannot invent pixels, and the missing
 * resource is analysis's to have refused (a reference to nothing is `resource.unknown`).
 */
internal object ImageRenderer : ComponentRenderer {
    @Composable
    override fun Render(node: ResolvedNode, scope: RenderScope): Unit {
        val id = resourceRef(node) ?: return
        val fit = scope.props(node)[ImageSpec.fit]?.let(ContentScaleMap::get) ?: ContentScale.Fit
        val description = scope.props(node)[ImageSpec.contentDescription]

        val loaded = produceState<LoadedResource?>(null, id) {
            value = withContext(Dispatchers.Default) {
                scope.environment.resources.load(id)
            }
        }.value

        val bitmap = (loaded as? LoadedResource.Image)?.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = fit,
                modifier = scope.modifierFor(node),
            )
        } else {
            // Nothing yet (or a miss): hold the node's place so the layout does not jump
            // when the bytes arrive.
            Box(modifier = scope.modifierFor(node))
        }
    }

    /** The raw `Value.Ref` the source prop holds, as a [ResourceId]. */
    private fun resourceRef(node: ResolvedNode): ResourceId? {
        val prop = node.props[ImageSpec.source.key] ?: return null
        val const = (prop.value as? PropertyValue.Const)?.value ?: return null
        val ref = const as? Value.Ref ?: return null
        if (ref.kind != dev.rotalex.lutter.model.type.RefKind.Resource) return null
        return ResourceId(ref.id)
    }
}

/** The `fit` entry's Compose scale, the same member-qualified vocabulary the spec declares. */
private val ContentScaleMap: Map<String, ContentScale> = mapOf(
    "Crop" to ContentScale.Crop,
    "FillBounds" to ContentScale.FillBounds,
    "FillWidth" to ContentScale.FillWidth,
    "FillHeight" to ContentScale.FillHeight,
    "Fit" to ContentScale.Fit,
    "FillMaxDimensions" to ContentScale.FillMaxDimensions,
    "Inside" to ContentScale.Inside,
)
