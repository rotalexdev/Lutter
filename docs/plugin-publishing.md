# Packaging and shipping a plugin

[Authoring plugins](plugin-guide.md) ends with a host that composes your contribution in two
lines. This page picks up where that leaves off: how those lines become a file someone else can
install, what that file promises, and what a host checks before it runs your code.

Nothing here changes the recipe. A plugin is still a Kotlin library that implements
`SchemaContribution`; the container is only how the library travels.

## The container

A `*.lutterplugin` is a ZIP file with a fixed shape:

| Entry | Required | What it is |
|---|---|---|
| `META-INF/lutter/plugin.json` | yes | The manifest: identity, API version, build, entry points |
| `classes/jvm/<name>.jar` | for a jar build | The payload a desktop/JVM host executes |
| `classes/android/<name>.dex` | for a dex build | The payload an Android host executes |

Anything else is ignored rather than rejected, so a packager can add signatures, licence files or
build metadata without a format change. The three paths live in one place in the model
(`PluginContainerLayout`), and both the writer and the reader read them from there.

## The manifest

```json
{
  "id": "forge.status",
  "version": "0.3.0",
  "apiVersion": 1,
  "build": "desktop-only",
  "dependsOn": [{"id": "forge.builtins", "version": "1.0.0"}],
  "entryPoints": {
    "schema": "com.forge.status.StatusBadgeContribution",
    "runtime": "com.forge.status.Runtime#registerStatusBadge"
  }
}
```

| Field | Meaning |
|---|---|
| `id` | The plugin's identity, namespaced like every id in the model. A document's `meta.plugins` names it |
| `version` | The plugin's own version. Compared by **exact string equality** — see below |
| `apiVersion` | The engine plugin API this build was compiled against. Today that is `1` |
| `build` | `full`, `android-only` or `desktop-only`: which payload kinds the container must contain |
| `dependsOn` | Plugins that must be installed before this one, each with the version it needs |
| `entryPoints.schema` | FQCN of your `SchemaContribution`. An `object` (or a class with a no-argument constructor) |
| `entryPoints.runtime` | Your runtime registration hook as `FQCN#method`, because the runtime half is a builder plus a function rather than an interface |

**Versions compare by exact string equality.** There is no range grammar and no semver library:
a requirement of `1.0.0` is satisfied by `1.0.0` and by nothing else. This is deliberate — a range
grammar is a place for subtle, unpinned behaviour, and the field is a record of what a document was
authored against rather than a dependency resolver. `dependsOn` follows the same rule.

## Building it

Add the packaging plugin to your build. It ships as part of this repository today, so a plugin
project adds it as an included build:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../lutter/plugins/lutter-plugin")
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("dev.rotalex.lutter.plugin")
}

lutterPlugin {
    id = "forge.status"
    version = "0.3.0"
    apiVersion = 1
    // Defaults to "desktop-only". The only value that can be packaged today — see below.
    pluginBuild = "desktop-only"
    schemaEntryPoint = "com.forge.status.StatusBadgeContribution"
    // runtimeEntryPoint = "com.forge.status.Runtime#registerStatusBadge"
    // dependsOn.put("forge.builtins", "1.0.0")
}
```

The plugin applies itself to a JVM module and registers one task, `lutterPluginContainer`, which
the `assemble` lifecycle already depends on. It takes the module's own jar as the payload and
writes:

```
build/lutter-plugin/<id>-<version>.lutterplugin
```

Two properties of the output matter more than they look:

- **It is byte-for-byte reproducible.** Entries are stored uncompressed (a payload is already
  compressed), in a fixed order, with a fixed timestamp. Packaging the same classes twice produces
  the same file, so a container can be compared, cached or signed like any other artifact.
- **It refuses to claim a build it cannot back.** `android-only` and `full` are refused by name:
  a dex needs D8/R8, which this repository does not carry, and a container with an empty
  `classes/android/` would declare a build its payloads do not support — the exact failure the
  load-time check exists to catch.

Publishing the container to Maven is not decided yet (it follows the T27 decision recorded in
ADR-018). Until then a container is distributed as a file, and a host installs it with a flag.

## What verifies at load, and in what order

A host reads a container and checks in the order whose consequences matter: can the file be read,
is the manifest there, does it decode, do its claims agree with its contents, and only then is any
code loaded.

| Code | Raised when |
|---|---|
| `plugin.container_unreadable` | The file is not a ZIP, or a payload cannot be read |
| `plugin.manifest_missing` | No `META-INF/lutter/plugin.json` |
| `plugin.manifest_invalid` | The manifest does not decode, or names neither entry point |
| `plugin.payload_missing` | The declared `build` needs a payload kind the container does not contain |
| `plugin.api_version_mismatch` | `apiVersion` is not the version this engine implements |

A container that fails any of these is refused **before a class is loaded**. That ordering is the
point of verifying rather than assuming: a malformed container cannot execute code, and a plugin
built against a different API version produces a fact about versions rather than a `LinkageError`
somewhere in the middle of rendering.

The boundary that makes this possible is ADR-018's: **no `:engine:*` module reads a container or
names a `ClassLoader`**. The reader lives in `:tools:plugin-container`, which depends only on the
model, and the host that owns a JVM opens the classloader. A schema, a document and a generator
stay plain data and plain functions.

## Hosting it

`forge check` and `forge generate` both accept `--plugin`, repeatable:

```bash
forge check todo-list.json --plugin forge-status-0.3.0.lutterplugin
forge generate todo-list.json --plugin charts.lutterplugin --plugin inline.lutterplugin
```

Order is preserved: plugins are installed in the order given, so `dependsOn` means what a caller
wrote. Each loaded container reports its identity before the command's own output:

```
forge check: loaded plugin forge.status 0.3.0 (api 1, desktop-only)
```

What the host does with the file, in order: read it, refuse a container it cannot execute (the CLI
is a JVM host, so it loads a jar and refuses an `android-only` container by naming the build it
declares), extract the payload, load the entry point, and call `install` on the schema. `install`
contributes the specs, records the descriptor in `Schema.contributors`, and refuses — before your
code runs — a duplicate `id` or a `dependsOn` no earlier plugin satisfies.

A document then names what it was authored against:

```json
"meta": { "plugins": [{"id": "forge.status", "version": "0.3.0"}] }
```

Analysis compares that against the contributors actually installed. A document naming a plugin the
host did not load is refused with `plugin.missing`, and one naming a version the loaded plugin does
not carry with `plugin.version_mismatch` — both as errors, so generation cannot proceed against a
schema that would silently render an unknown component.

## If you are writing the host

The three calls, in order:

```kotlin
val container = PluginContainerReader.read(file)          // :tools:plugin-container — no classloader
val loaded = PluginHost.loadOne(file, command)            // the host half: jar, classloader, INSTANCE
val schema = Engine.schema(listOf(loaded), command)       // builtins + install(...)
```

`PluginHost` is the CLI's own object rather than a library, because *hosting* means owning a
classloader. An Android host will load the dex payload through `DexClassLoader` instead; the
container already carries what that step needs, and no engine module changes to make room for it.

## Today's two boundaries

- **Dex is declared, verified, and not produced.** `android-only` and `full` manifest values exist,
  the reader enforces them, the CLI refuses them on the JVM, and no packager in this repository can
  produce a dex yet. A plugin that ships an Android payload needs a build step this repository does
  not have — D8/R8 — and inventing an empty payload to satisfy a manifest is the failure mode the
  verification step exists to catch.
- **Distribution is a file, not a coordinate.** Publishing containers to Maven waits on the T27
  decision (ADR-018). A plugin's *library* can be published however its author likes; the container
  is what a host installs today.
