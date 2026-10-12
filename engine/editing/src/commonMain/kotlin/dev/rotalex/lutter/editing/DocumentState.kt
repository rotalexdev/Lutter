package dev.rotalex.lutter.editing

import dev.rotalex.lutter.model.doc.UiDocument

/**
 * The engine's whole observable position at one moment: the document, how many applied changes
 * produced it, and what the undo stack can currently do.
 *
 * PLAN §25.2 declares this with four fields and declares them here with those four. Its own
 * comment on the property line lists a fifth — *"document, revision, canUndo, canRedo,
 * lastPatch"* — and the data class below it does not have one. **The class is the normative
 * half**, for two reasons that are not a preference: the comment describes what the property
 * line above it is for, and a `lastPatch` on the state would duplicate [DocumentChange.patch]
 * as a second reader of the same edit. An editor that wants the last edit collects
 * [DocumentController.changes]; one that wants the current document reads [document]. What it
 * must not do is read both from one record and discover they disagree, and a revision that
 * advanced without an emission would make that possible.
 *
 * ### Why there is a revision at all
 *
 * `UiDocument` is an immutable value, so two revisions can hold equal documents and every
 * equality test on the type will say they are the same. An editor cannot tell "nothing was
 * edited" from "an edit was made that happened to be a no-op", and a collaborative client
 * cannot tell whether it has missed a change without one.
 *
 * [revision] is a counter of **applied patches**, and it advances by one for every patch the
 * controller applies to the document — forwards, backwards through [DocumentController.undo]
 * and [DocumentController.redo], and forwards again when the same edit is redone. It is not a
 * version string and it is not persisted: it describes this session's history of applied
 * patches and is meaningless in a document read from disk, which is exactly what §26.3's rule
 * that history is session state asks for.
 *
 * ### Why [canUndo] and [canRedo] are here and not read from a [History]
 *
 * An editor's undo button is enabled from a boolean in the state it already collects, and
 * reaching through the controller into its history to ask the same question would give two
 * answers to one. They are carried, denormalised, and [DocumentController] writes them on every
 * publish — the one place that knows both.
 *
 * @see DocumentController for the only writer of this record.
 * @see DocumentChange for one applied patch, which is what moves a revision.
 */
public data class DocumentState(

    /** The document as it stands. Immutable: every edit produces a new one. */
    public val document: UiDocument,

    /**
     * How many patches have been applied to [document] this session.
     *
     * Zero for the document the controller was constructed with, and one more for every
     * applied patch from there on. Undo and redo both advance it, because both change the
     * document.
     */
    public val revision: Long,

    /** Whether [DocumentController.undo] has anything to undo. */
    public val canUndo: Boolean,

    /** Whether [DocumentController.redo] has anything to redo. */
    public val canRedo: Boolean,
)