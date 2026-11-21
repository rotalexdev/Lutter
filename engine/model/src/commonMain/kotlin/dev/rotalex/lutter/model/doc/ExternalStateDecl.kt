package dev.rotalex.lutter.model.doc

import kotlinx.serialization.Serializable

/**
 * §12.1's third way to give a declaration its value: the host supplies it as a stream.
 *
 * [initial] and [derived] are values the engine can compute or hold; neither can express "this
 * number comes from the phone's sensors", because that needs something that keeps producing
 * values after the composition is built. The embedding application has that something, and §11.6
 * already declares what a document may ask of it — so the row names an **adapter** on
 * `AppHost`, and the generated holder collects it.
 *
 * ### Why the record has one field and not a type
 *
 * A `Flow<T>` cannot be spelled by [dev.rotalex.lutter.model.type.TypeRef], which has nineteen
 * variants and no generic application. That is not a gap worth closing for this row: the
 * element type is already [StateDecl.type], the wrapper is the generator's business, and a
 * document that had to spell `Flow<int32>` would be spelling the emission rather than the
 * intent. The adapter's type is therefore *derived*: `fun <adapter>(): Flow<StateDecl.type>`.
 *
 * ### What it does not say
 *
 * Nothing about buffering, replay or backpressure. Those are the host implementation's, and a
 * document that could set them would be configuring a stream the engine does not own.
 */
@Serializable
public data class ExternalStateDecl(

    /**
     * The adapter's name, and the name the generated holder's constructor parameter takes.
     *
     * One name for both because they are one fact: the `AppHost` member the application
     * implements and the value handed to the holder are the same stream, and two names would let
     * them disagree.
     */
    public val adapter: String,
)
