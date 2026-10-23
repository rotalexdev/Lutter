# The forge CLI

`forge` is the command-line front door of the engine. It validates UI documents and turns
them into Kotlin source files. The tool is deliberately small: exactly two commands, no
configuration file, no state between runs.

This page describes the CLI as it exists in the code — command surface, flags, output, and
the exit-code contract that scripts can rely on. Behavior here is pinned by tests in
`tools/cli`, so if this page and the tool ever disagree, that is a reportable bug.

## Building the launcher

```
gradle :tools:cli:installDist
```

The install script lands at `tools/cli/build/install/cli/bin/<launcher-name>` (the launcher
shares the module's application name). Re-run the task after code changes — it is cheap and
the scripts do not hot-reload.

## The two commands

### `forge generate`

```
forge generate <document.json> [--out <dir>] [--package <package>]
```

Generates Kotlin source from a valid document.

| Flag | Meaning | Default |
|---|---|---|
| `--out` | Output directory for generated files | `generated` (relative to the working directory) |
| `--package` | Root package of the generated code | The document's own `app.packageName` |

Success output, on stdout, is one line that tells you exactly where everything went:

```
forge generate: wrote 14 files to '/tmp/out'
```

If the document fails validation, nothing is generated: the diagnostics are printed to
stderr and the process exits `1` (the rejected-exit code). `generate` never leaves a
half-written tree behind.

### `forge check`

```
forge check <document.json>
```

Validates a document without generating anything — the CI-friendly and editor-hook-friendly
command.

stdout is exactly one summary line:

```
forge check: 0 errors, 1 warning, 2 info in 'todo-list.json'
```

stderr carries one finding per line, in a stable, greppable shape:

```
Error component.unknown at nodes[3]: unknown component type 'TextCreativity'
```

Each finding line names its severity, machine-readable code, source location, and message —
the same catalog of codes the engine produces, [documented in the format specification](format-spec.md).

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success — the operation completed. |
| `1` | Rejected — the document is undecodable as a document or failed validation. |
| `2` | Unusable input — bad usage, unknown command, missing flags, or a file that cannot be read or decoded. |
| `3` | Engine failure — the engine itself failed; not your document's fault. |

The split that matters for scripting: `1` means "this document has problems, here they are";
`2` means "I could not run at all — fix the invocation or the file path"; `3` means "report
this as a bug".

Concrete shapes you will meet:

| Invocation | Result |
|---|---|
| `forge` with no arguments | Exit `2`; usage text on stderr. |
| `forge lint` | Exit `2`; `unknown command 'lint' … known: generate, check`. |
| `forge check missing.json` | Exit `2`; `forge check: no such file 'missing.json'`. |
| `forge check corrupt.json` | Exit `2`; `forge check: cannot decode 'corrupt.json': <reason>`. |

## Argument-parsing rules

The CLI uses a hand-rolled parser — no argument-parsing library — with deliberately strict
rules, all verified in tests:

- Flags are the documented ones only. An unknown flag exits `2`, and the error names the
  flags the command accepts (no silent swallowing of typos like `--ouptut`).
- A flag repeated twice exits `2` (duplicates are mistakes, not overrides).
- A flag's value may not look like a flag — `--out --package foo` exits `2`.
- `--` ends flag parsing, letting you pass values that start with `-`.
- A lone `-` is a legitimate value (for an actual file literally named `-`).
- `-h` / `--help` prints the signature and exits, and is allowed even where positional
  arguments are expected.
- Surplus or missing positional arguments quote the exact expected signature.

## Library equivalence

Everything the CLI does is also available programmatically: `tools/cli` is a thin shell over
the engine modules (`model`, `schema`, `serialization`, `analysis`, `codegen`, `builtins`),
and its command implementations (see `GenerateCommand.kt` and `CheckCommand.kt` next to
`Main.kt`) are about forty lines each. If you need check/generate semantics inside a Gradle
task or an editor extension, those two files are the reference for composing the same calls.
