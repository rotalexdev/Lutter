// The editing layer: the engine-agnostic mutation surface an editor drives.
//
// PLAN §22.1 names what lives here and §33.6 the files. `DocumentController` owns
// the document, `Command` states intent, `PatchOp` is the only mechanism that
// changes anything, and `History` turns patches into undo/redo. The patches, the
// commands, the clipboard surface (`NodeTemplate.capture`, `IdRemapper`) and the
// drop-legality surface (`DropTargets`) are merged; `diff/DocumentDiff.kt` and
// `migration/SpecMigrationPass.kt` are later work units of Phase 9.
//
// Nothing here draws. The visual editor is `:app:editor` (PLAN §4.6 D28) and it
// consumes this module; no `:engine:*` module may name it in
// `ModuleGraphRules.ALLOWED`, which is what keeps the engine independent of the
// editor rather than merely arranged that way.
package dev.rotalex.lutter.editing