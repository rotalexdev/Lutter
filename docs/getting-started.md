# Getting started

Forge Engine turns a JSON document that describes a user interface into real Kotlin/Compose
code plus a runtime that can render the same document directly. This page takes you from a
fresh clone of this repository to a working command-line tool, a green test suite, and a
running preview app.

## Requirements

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17 or newer | The build targets JVM 17; continuous integration runs on JDK 21. |
| Gradle | 9.5.0 | This repository ships no wrapper — see below. |
| Network access | first build only | Dependency and version-catalog resolution happens online once, then stays in your Gradle cache. |

**Why there is no `gradlew`:** the project deliberately depends on a Gradle installation of
the required version rather than a wrapper jar, so the build never bypasses the version gate.
Install Gradle 9.5.0 your usual way (SDKMAN!, Homebrew, or a manual unpack), verify with
`gradle --version`, and every command below works as written.

## Building

From the repository root:

```
gradle build
```

That compiles every module and runs the full verification suite, including architecture
tests that fail the build on forbidden module edges. For a faster inner loop, scope the
build to a module:

```
gradle :engine:schema:test
```

## The modules

| Module | What it is |
|---|---|
| `engine:model` | Ids, tokens, and value types shared by everything else. |
| `engine:schema` | The component/modifier/action/function/type registry and its DSL. |
| `engine:serialization` | JSON encode/decode of documents and schema specs. |
| `engine:interpreter` | Resolves parsed documents into renderable trees. |
| `engine:analysis` | Diagnostics: unknown components, bad values, wrong slot usage. |
| `engine:editing` | Structural edits over resolved trees. |
| `engine:codegen` | Emits Kotlin source from a schema. |
| `engine:runtime` | Renders documents live; renderer and modifier-applier registries. |
| `engine:builtins` | The built-in schema pack (specs, modifiers, enums, functions, actions). |
| `engine:builtins-compose` | The built-in Compose renderers and modifier appliers. |
| `engine:test-support` | Test utilities, including the benchmark harness. |
| `tools:cli` | The `forge` command-line tool. |
| `tools:architecture-tests` | Module-graph and architecture enforcement. |
| `integration:generated-compile` | Compiles generated golden code as part of the suite. |
| `samples:desktop-preview` | Compose Desktop preview of any document. |

## Using the CLI in two minutes

Build the launcher:

```
gradle :tools:cli:installDist
```

Then check a document — every fixture in `integration/generated-compile/fixtures-navigating/`
is a real document you can use:

```
tools/cli/build/install/cli/bin/cli check integration/generated-compile/fixtures-navigating/button_navigate.json
```

and generate code from it:

```
tools/cli/build/install/cli/bin/cli generate integration/generated-compile/fixtures-navigating/button_navigate.json --out /tmp/out --package com.example.app
```

`check` prints one summary line (`forge check: N errors, N warnings, N info in '…'`) and exits
0 when the document is valid, 1 when it has errors. `generate` writes one Kotlin file per
generated artifact and says where it wrote them. The full command and exit-code reference is
[the CLI page](cli.md).

## Previewing a document

The Compose Desktop app renders any document and hot-reloads it while you edit:

```
gradle :samples:desktop-preview:run --args='integration/generated-compile/fixtures-navigating/button_navigate.json'
```

With no argument it loads a fixture that exercises navigation, so `gradle
:samples:desktop-preview:run` is already a working demo on the first try.

## What to read next

- [The format specification](format-spec.md) — the document format: envelope, canonical
  JSON rules, the component catalog, and real wire samples.
- [The CLI page](cli.md) — both commands, all flags, and the exit-code table.
- [The plugin guide](plugin-guide.md) — how to add your own components, modifiers, and
  renderers without touching engine modules.

## Troubleshooting

- `unsupported Gradle version` — the build pins 9.5.0; check `gradle --version`.
- Resolution failures on the first build — dependency and catalog resolution needs network
  access; retry once online and the Gradle cache covers later offline runs.
- `unknown command '…'` from the CLI — the tool currently ships exactly two commands,
  `generate` and `check`; anything else exits with a usage message.
- Stale launcher after changes — `installDist` re-runs cheaply; re-install before blaming
  the code.
