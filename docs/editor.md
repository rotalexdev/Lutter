# The editor

Lutter ships an editor in this repository (PLAN §32 Phase 17, D28's amended decision). It is
two screens over one document. The **Editor** screen is three panes: a palette on the left, a
live preview in the middle, an inspector on the right — the desktop window shows them in a row,
the Android app shows the same three in a column. The **Code** screen is what that document
becomes: the Kotlin `:engine:codegen` emits for it, one block per file, read-only.

Both hosts reach the two screens the same way — a bottom navigation bar on a phone, a navigation
rail on a desktop, both over one back stack — and both keep the save line visible on either
screen, because "did that save?" is a question the Code screen raises too.

This page describes the editor as it exists in the code — what each pane asks the engine,
what the session persists, and what the preview guarantees. Behaviour here is pinned by the
tests in `app/editor`, `desktopApp` and `androidApp`, so if this page and the editor ever
disagree, that is a reportable bug.

## Running it

From the repository root:

```
gradle :desktopApp:run
```

The window opens on the starter document,
`desktopApp/src/commonMain/resources/default-document.json`: an About screen built from the
palette — a surface, a header row with an icon and a headline, a tagline, a divider, three
label/value rows and a button — plus a second page the button navigates to. It is a preset, so it
shows what the components do without pretending to be an app, and it is the only document in the
repository that no other harness owns: both read paths are pinned by `BundledDocumentTest`, which
also resolves it with the engine so a schema change that invalidates it fails there.

Pass another document as the first argument:

```
gradle :desktopApp:run --args="path/to/document.json"
```

The Android host is `:androidApp`, a single activity. It packages that same starter file as an
asset — the desktop module's resources directory is one of its asset roots, so the phone opens on
the screen the desktop opens on, and there is no second copy to fall behind.

### The packaged application

A packaged build has no repository around it: the working directory belongs to whoever
double-clicked the launcher, and `DEFAULT_DOCUMENT` — module-relative — resolves to nothing.
`readDocument` therefore falls back to the same file read out of the jar. There is no second
copy to drift: `DEFAULT_DOCUMENT` **is** the resource, so `gradle run` reads it from disk and a
packaged launcher reads the identical bytes from the classpath. What the fallback needs is a
document the engine accepts, and `BundledDocumentTest` resolves the bundled copy with the same
`BuiltinEngine` the window builds — a present but unrunnable default would otherwise be found by
a user rather than by CI.

### Where to download a build

| | Built by | When |
| --- | --- | --- |
| `.msi`, `.deb`, `.apk` | `.github/workflows/nightly.yml` | every push to `main` (a promotion), or a manual dispatch |
| a pull request's `.msi`, `.deb` | `.github/workflows/editor-artifacts.yml` | that pull request, as proof it packages and launches |

`nightly` publishes one rolling pre-release with all three installable builds of that commit,
named after the commit count (`LutterEditor-431.msi`, `lutter-editor_431_amd64.deb`,
`lutter-editor-431-debug.apk`). If any of the three legs fails, nothing is published: a "nightly"
missing the installer somebody came for is worse than a red run. There is no schedule — the
version *is* the commit, so rebuilding an unchanged tree would only invent a new number for the
same program.

Nothing is signed: Windows SmartScreen warns about the `.msi`, the `.deb` has no gpg signature, and
the APK is debug-signed because the repository holds no release keystore.

jpackage cannot cross-build, so each runner packages its own OS: `ubuntu-latest` produces a `.deb`
and an app-image, `windows-latest` an `.msi` and an app-image. Every leg then runs
`scripts/probe-desktop-package.sh`, which launches the packaged binary and distinguishes a broken
package ("Failed to launch JVM") from a runner with no display — the difference a green build had
not been able to make.

## The frame

`EditorShell` — in `:app:editor`, drawn by both hosts — owns everything between the window and
the panes: the app bar, the primary navigation, the destination the back stack is showing, and
the save line. Each host now only opens a window, a theme and a session: the arrangement is not
implemented twice, which is also why the Android activity and the desktop window stopped
disagreeing about the theme.

**Where the app is, is a stack.** `EditorShell` holds one `rememberNavBackStack(EditorRoute)` and
renders it with `NavDisplay`; the three destinations are `@Serializable` `NavKey`s. Editor and
Code are what the navigation offers, so switching between them replaces the stack's entry rather
than piling up; Settings is pushed over whichever screen opened it, so leaving it is a pop and the
platform's own back gesture performs it. A configuration change restores the stack, because the
keys are serialisable — a boolean "the settings are showing" would have restored a flag and
forgotten which screen it was showing over.

The routes and the three list operations (`switchTo`, `push`, `pop`) live in
`ui/EditorRoutes.kt` and are asserted in `EditorRoutesTest` without a window: what a phone draws
is the composition's business, and what the stack holds afterwards is the file's.

**Two arrangements, chosen by width.** Under 600dp — the Material 3 compact boundary — a
`NavigationBar` sits at the bottom edge and one pane gets the window: the canvas, or the generated
code, with the palette behind a floating button and the inspector behind the selection's
`Properties` action, both as `ModalBottomSheet`s. At or above 600dp a `NavigationRail` takes the
leading edge instead. The
platform's sheet brings its own dismissal, drag handle, back navigation and screen-reader focus
order; a hand-rolled overlay would have to re-earn all four. At or above 600dp the palette, the
canvas and the inspector sit side by side in one row, which is the desktop window's arrangement.

**Settings is an action, not a tab.** The app bar's "more options" (⋮) button opens a
`DropdownMenu` with `Settings`; the settings destination has a back arrow and no navigation suite,
because it is not somewhere the editor *is*. The icons are Material's, from
`material-icons-extended` (`rootLibs`), not hand-drawn paths.

**A selection has three actions.** On a narrow screen they dock above the save line, where the
thumb already is: `Properties` opens the inspector sheet, `Duplicate` copies the subtree, and
`Delete` removes it — with `Undo` in the app bar, one tap away, because a delete that can be
taken back does not need a confirmation. Undo and redo go through `EditorSession.undo`/`redo`
rather than the controller, so the selection is reconciled with them: stepping back over an
insert leaves nothing selected, instead of leaving an inspector open on a node that no longer
exists.

## The three panes

### Palette

`Palette.of(schema)` groups the schema's components by their declared `Category` and returns
the sections in a fixed order, with each component's display name and keywords. That is
metadata the schema already holds, so the palette is a view of the schema rather than a list
someone maintains twice.

Clicking an entry calls `EditorSession.dropTargets(type)` — `DropTargets.forNode`'s own
answer, the check the planner will run anyway — and inserts at the first target it returns.
An entry with no target is still shown: a component that cannot go anywhere right now is a
fact worth knowing, and the palette does not hide it.

What lands is a **template**, not a bare node: `paletteSubtree` adds the least a type needs to be
visible — a `m3.Button` gets a `m3.Text` in its `content` slot reading `Button`, a `m3.Text` gets
its required `text`, a `core.Column` gets one child. The shape comes from the schema (the first
slot that admits the label type, or the required `Str` properties), never from a list of type
names, so a plugin's components get the same treatment and a schema without the builtin label
type inserts the bare node instead of guessing one. Properties are still `analysis`'s to default:
a template adds *structure*, and the label it writes is the author's own word for the thing they
just added.

### Canvas

`CanvasPane` draws what the preview resolved. The preview is the runtime's own `UiApp` — the
same renderer the shipped applications use — so what the canvas shows and what a built app
shows cannot drift apart.

**The canvas is in edit mode, not in run mode.** A transparent overlay sits above the preview
and owns every pointer, so tapping a `m3.Button` on the canvas *selects* it instead of pressing
it. That is the split the Android Studio layout editor makes too, and the alternative — a preview
that runs the app you are editing — is how an author loses the ability to reach the node they
just dropped.

**Selection is visible.** A click selects the deepest node under the pointer, and the selected
node gets a ring drawn from its measured box. A click on the margin clears the selection, the way
every layout editor does it.

**A node can be moved.** A long press picks the node up and a drop on a container that admits its
type calls `EditorSession.move`; the container under the finger is outlined while the drag is
over it. The list of legal homes is `session.dropTargets(type)` — the engine's own answer, asked
once at the start of the drag — so a drag can only end where the planner would have allowed the
move. A node's own subtree is never offered, because a move into it would cut the branch out from
under the node being moved.

Positions on the canvas are *measured*, not stored: a document is a layout and its nodes have no
`x`/`y`. `RenderHooks.onNodeMeasured` reports each node's box from `:engine:runtime`, and
`NodeBounds` keeps those boxes for the overlay. Free positioning therefore is not a missing
feature but a different document format, and nothing in the editor pretends otherwise.

### Inspector

`Inspector.of(document, schema, nodeId)` returns one row per declared property: the key, the
declared type, whether it is required, the spec's help text, and the current value in
canonical text. Committing a row goes through `EditorSession.setProperty`, which parses the
text and dispatches one `SetProperty` command.

A row commits when you leave it — focus moves elsewhere, or the keyboard's **Done** — and not on
every keystroke: parsing a half-typed expression would report a refusal the author is about to
finish typing past. Leaving a field you did not type in commits nothing, which is what keeps
undo honest; the rule is `isNewEdit(sent, draft)` and it is tested without a screen. When the
engine refuses a commit its own diagnostic appears under the field instead of the text quietly
reverting.

The expression field is the interesting half. Text is a view of the AST, never the truth
(§10.8): `PropertyText.apply` parses what was typed, `PropertyText.of` prints the canonical
form of a value, and a commit that parses to a literal becomes a `PropertyValue.Const` rather
than an expression that happens to evaluate to one. `parse(print(expr)) == expr` is
`ExprParser`'s own guarantee, and the editor relies on it instead of re-implementing it.

Literals spell the way the language spells them: `42`, `42L`, `1.5`, `1.5f`, `true`, `null`,
`"text"`, and the unit-suffixed numbers `16.0.dp` and `12.0.sp`. The units are not decoration: a
palette row fills every required `Dp` or `Sp` property with zero, so the first tap on a `Row`
with spacing asks this field to spell one. A value whose kind the grammar has no literal for — a
colour, an icon, a token — shows an empty field rather than taking the editor down with it;
typing a new value replaces the stored one.

A node the schema cannot describe — a document that outlived the plugin that authored it —
gets no inspector rows rather than an empty panel. The canvas is where that diagnostic is
already visible, so the panel says nothing instead of claiming the component has no
properties.

## The Code screen

`generatedCode(schema, resolved, packageName)` calls the same `KotlinGenerator` the CLI and the
acceptance test call, and `CodeScreen` lists what it returns: one card per file, path in the
label, the file's text selectable and monospaced. Nothing is re-formatted, re-sorted or
summarised — `GeneratedFiles` already sorts by path, and a second ordering would be a second
answer to one question.

It reads the **preview's** resolved document rather than analysing again, so the code on that
screen is the code for the tree the canvas draws, not a second resolution that could disagree
with it. It follows the preview while it is shown, for the same reason: the panes are out of the
composition on that screen, and a preview that stopped following would show the code for a
document the author has already edited.

A document the generator cannot handle is a message, not a crash. Refusals arrive as
diagnostics with a location; a *throw* is the generator's own bug, and `generatedCode` catches it
and reports it as one — the screen is a view of a pure function, and a bug in that function must
cost the author a sentence, not the composition they were in.

Read-only is the design, not a missing feature. The document is the source and Kotlin is a
projection of it (ADR-011), so an edit made here would be lost at the next regeneration. When the
generator refuses a document it returns no files *and* its diagnostics; the screen shows the
reason rather than an empty list.

## The session

`EditorSession` holds a `DocumentController` and a selection, and nothing else (§25.1: an
editor holds a controller, never a copy of the model). Every edit goes through `dispatch`;
`insert`, `move`, `duplicate`, `remove` and `setProperty` are the named shapes of that one
gateway, and a host that needed another edit would add it here rather than reaching for the
document.

The selection lives in the session and not in the document, because which node a person
clicked is not a fact about the document. It must not enter history and it must not survive a
command that removes the node, so `dispatch` is the one place that reconciles it — a UI that
had to remember to re-check after every command would forget once, and the failure is an
inspector showing a node that is gone.

## What is persisted

`EditorSessionStore` writes one JSON record through the serialization storage seam
(`DocumentStorage`), so a host can keep it in a file, a `Preferences` entry or a map:

| Field | What it is |
|---|---|
| `documentLocation` | Where the document lives, in `DocumentStorage`'s spelling |
| `selection` | `None`, a node id, or a page id |
| `panels` | Which panes the host had open; the host decides what "open" means |

**History is not persisted** (§26.3). A reopened session has an empty undo stack: the record
says where to look and what was selected, never how the document got there. An undo stack in
a session file would be a second, unverified history competing with the document's own.

## Settings

Three values describe how the editor is *shown* rather than what the document *is*, and they live
in a DataStore Preferences file (`settings.preferences_pb`) inside the host's private directory:

| Setting | Default | What it changes |
|---|---|---|
| Theme | `System` | The colour scheme at the root of the window, on both hosts |
| Code text size | `1.0×` | The size of the generated Kotlin on the Code screen |
| Autosave | on | With it off, the frame offers a `Save` action and writes only when it is pressed |

`SettingsStore` is the seam: `app/editor` owns the interface and an in-memory implementation, and
each host owns the one instance per process, because DataStore refuses a second instance over the
same file and an activity is rebuilt on every rotation. The `-core` artifact carries no Android
dependency, so the desktop window and the activity read the same file format.

The defaults are the behaviour the editor had before the screen existed, which is what makes the
screen additive: nobody who never opens it sees a different editor. A file that cannot be read is
replaced by the defaults instead of stopping the launch — a wrong theme is a bad minute, a lost
document is not.

## What the preview guarantees

`EditorPreview` re-resolves on the controller's revision and keeps the last good render when
an edit does not resolve:

- **`refresh()`** returns the current state untouched when the revision has not moved, so a
  renderer is never woken for a revision it already drew.
- **`follow()`** collects `controller.changes` and refreshes on each; cancellation is
  rethrown, because it means the window is gone.
- **`watch(location)`** polls an external file and refreshes when its bytes change, so an edit
  outside the editor still reaches the canvas.
- **`PreviewState.Refused`** carries `lastRendered` rather than the preview keeping a
  document off to one side: "this edit does not resolve" and "the screen still shows the last
  thing that did" are one fact about one screen.
- A refused revision is not recorded as rendered, so the next refresh retries it.

The preview never writes. It decodes the bytes it is given, resolves them, and reports what it
found; persistence stays the session store's job.

## Saving

The editor saves on every change and has no Save button, because there is nothing to decide: it
owns one document at one location, chosen once when it opens. `WorkspaceSaver` combines
`controller.state` (a `StateFlow`, so not Compose snapshot state — `snapshotFlow` would see
nothing) with `selection` and writes the pair; the desktop window's close handler then performs one
final synchronous write, because a coroutine would be cancelled by `exitApplication()` first.

The rule lives in `:app:editor` (`EditorWorkspace`, `WorkspaceSaver`, `Autosave`, `SaveStatusBar`)
and both hosts use it. A host decides only **where** the two locations point, through the
`DocumentStorage` seam.

### Desktop

| Opened with | Document | Session record |
| --- | --- | --- |
| `LutterEditor my-document.json` | `my-document.json`, **in place** | `<workspace>/session.json` |
| no path (`gradle run`, a launcher) | `<workspace>/document.json`, seeded from the starter | `<workspace>/session.json` |

`<workspace>` is `LUTTER_EDITOR_HOME` when set, otherwise the platform's application-data
directory — `%APPDATA%\LutterEditor` on Windows, `~/Library/Application Support/LutterEditor` on
macOS, `$XDG_DATA_HOME/lutter-editor` (or `~/.local/share/lutter-editor`) elsewhere. The starter
is a **seed**, never a file the editor writes back to: `gradle run` does not dirty the repository,
and a packaged launch writes somewhere the user owns.

### Android

The document is `<filesDir>/document.json` and the record `<filesDir>/session.json` — the app's
private storage, removed with the app, no permission asked. The first run seeds it from the
bundled `button_navigate.json`.

Turning the phone **recreates the activity**, and that is now harmless: the activity reopens its
workspace on every creation, so a rotation lands on the document as it was last written, selection
included. Before this host saved, a rotation silently discarded every edit.

The window is drawn **edge to edge** — at `targetSdk` 37 the system enforces it — and the content,
not the window, is inset from the system bars. Before that the palette's first row rendered under
the clock and the status line sat under the gesture handle.

### What every write promises

Desktop writes go to a sibling temporary file moved into place with `ATOMIC_MOVE`, so a process
killed mid-save leaves either the old bytes or the new ones — never a truncated document. Android
cannot use `java.nio.file` on every supported API level, so `AtomicFileStorage` uses
`File.renameTo`, which is `rename(2)` on Android's filesystems and replaces the target in one step.

A document that does not parse is **kept** as `<name>.corrupt` and the seed opens instead, with
the reason in the status bar; throwing it away would discard the only copy of somebody's work. The
line under the panes always says which of those happened, and names the file rather than its path:
`document.json`, not `/data/user/0/dev.rotalex.lutter/files/document.json`. It printed the whole
location until it was seen on a phone, where a private path nobody can open took the bottom two
lines of the window. "NOT SAVED" with the reason is the one message it exists for.

`EditorSessionRecord` carries the selection and not the undo stack (§26.3), so reopening restores
what was being looked at and starts a fresh history.

## What the editor does not do yet

- **No page or state authoring.** The session edits nodes and their properties; creating a
  page, declaring state or editing actions is not in this phase. A document that needs a second
  page is authored as JSON for now.
- **No plugin-driven palette without a host.** The palette reads `schema`, so a plugin's
  components appear when the host installs it — the hosts build the builtin engine only.
- **No dex payload.** A plugin container's `android-only` and `full` builds are declared and
  refused by the packager (ADR-018); the editor loads nothing at runtime.

## What is proven

`app/editor`'s acceptance test authors a document through the session and only the session —
one `dropTargets` answer, one insert, two property commits — then compares it with its own
JSON form three ways:

1. encoding the decoded form reproduces the bytes (the codec is canonical);
2. both resolve to the same tree (the runtime draws `resolved`, so equal trees are equal
   pictures);
3. both generate the same files, byte for byte.

The generation half runs on the builtin schema, because codegen bindings are per component
spec and the module's synthetic fixture schema deliberately carries none. A separate test
round-trips the expression field through `ExprParser`/`ExprPrinter` for each family of
expression the field can hold.
