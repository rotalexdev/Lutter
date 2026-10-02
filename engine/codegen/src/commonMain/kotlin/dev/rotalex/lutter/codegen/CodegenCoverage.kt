package dev.rotalex.lutter.codegen

import dev.rotalex.lutter.model.ids.PropertyKey
import dev.rotalex.lutter.schema.SchemaView
import dev.rotalex.lutter.schema.component.CodegenBinding
import dev.rotalex.lutter.schema.component.ComponentSpec
import dev.rotalex.lutter.schema.component.ValueEmit
import dev.rotalex.lutter.schema.modifier.ModifierSpec

/**
 * The §4.5 fail-fast for generation: every spec emits, or construction refuses.
 *
 * Each refusal mirrors a generator diagnostic lifted to build time, so a gap
 * fails when the generator is created rather than when a document arrives.
 */
public object CodegenCoverage {
    /** Every component in [schema] must emit through [extensions]; otherwise throws. */
    public fun check(
        schema: SchemaView<ComponentSpec, ModifierSpec, *, *, *>,
        extensions: CodegenExtensions = CodegenExtensions.None,
    ): Unit {
        val missing = schema.components.all().mapNotNull { spec -> problem(spec, extensions) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Codegen has no binding for " + missing.joinToString { "'${it.first}': ${it.second}" },
            )
        }
    }

    private fun problem(
        spec: ComponentSpec,
        extensions: CodegenExtensions,
    ): Pair<String, String>? {
        val binding = spec.codegen
        if (binding is CodegenBinding.ComposeCall) {
            val reason = callProblem(spec, binding) ?: return null
            return spec.type.value to reason
        }
        if (binding is CodegenBinding.Custom) {
            if (extensions.emitters.containsKey(binding.emitterId)) return null
            return spec.type.value to "custom emitter '${binding.emitterId}' is not registered"
        }
        val reason = intrinsicProblem(spec) ?: return null
        return spec.type.value to reason
    }

    // The engine-owned path reads a node's properties and the synthesis around it; a slot or an
    // event on the spec is one that path has nowhere to put, so it fails rather than vanishes.
    private fun intrinsicProblem(spec: ComponentSpec): String? {
        spec.slots.firstOrNull()?.let { return "slot '${it.name.value}' emits nothing yet" }
        spec.events.firstOrNull()?.let { return "event '${it.key.value}' emits nothing yet" }
        return null
    }

    // One reason per spec: the first gap is the fix, the rest is the same fix repeated.
    private fun callProblem(spec: ComponentSpec, binding: CodegenBinding.ComposeCall): String? {
        if (binding.events.isNotEmpty()) return "events emit nothing yet"
        for (param in binding.params) {
            paramProblem(spec, param.param, param.from)?.let { return it }
            val cases = param.emit as? ValueEmit.Cases ?: continue
            if (cases.cases.isEmpty()) return "param '${param.param}' has no emission case"
            for (cased in cases.cases) {
                for (key in cased.whenPresent) {
                    if (spec.properties.none { it.key == key }) {
                        return "param '${param.param}' matches on undeclared '${key.value}'"
                    }
                }
                patternProblem(spec, param.param, cased.pattern)?.let { return it }
            }
        }
        for (slot in binding.slots) {
            if (spec.slots.none { it.name == slot.slot }) {
                return "slot '${slot.slot}' is not declared"
            }
            if (slot.receiver != null) return "scoped slots emit nothing yet"
        }
        return null
    }

    private fun paramProblem(
        spec: ComponentSpec,
        param: String,
        from: List<PropertyKey>,
    ): String? {
        if (from.isEmpty()) return "param '$param' binds no property"
        for (key in from) {
            if (spec.properties.none { it.key == key }) {
                return "param '$param' reads undeclared '${key.value}'"
            }
        }
        return null
    }

    // Same `{key}` scan as the generator: an unclosed brace is text, not a key.
    private fun patternProblem(spec: ComponentSpec, param: String, pattern: String): String? {
        var index = 0
        while (index < pattern.length) {
            val open = pattern.indexOf('{', index)
            if (open < 0) return null
            val close = pattern.indexOf('}', open)
            if (close < 0) return null
            val raw = pattern.substring(open + 1, close)
            val key = try {
                PropertyKey(raw)
            } catch (_: IllegalArgumentException) {
                return "param '$param' has a bad pattern key '{$raw}'"
            }
            if (spec.properties.none { it.key == key }) {
                return "param '$param' fills undeclared '{$raw}'"
            }
            index = close + 1
        }
        return null
    }
}
