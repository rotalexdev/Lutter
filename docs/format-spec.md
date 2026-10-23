# Lutter format specification — envelope, canonical JSON, and the `@SerialName` catalog

Status: the v1 wire contract as implemented. `PLAN.md` §18/§19 remains the design record;
where this document and the code disagree, fix the code first and then mirror — never the
reverse. Anchors are file names (line numbers drift); the serializer code lives in
`engine/serialization` and the annotated model lives in `engine/model`.

Applies to `formatVersion = 1` and `schemaVersion = 1` — both frozen at MVP release per
`PLAN.md` §19.2, so this is the complete v1 surface, not a moving snapshot.

## 1. Envelope

Every encoded document is one JSON object with exactly four required fields
(`Envelope.kt`):

```json
{
  "format": "forge.ui-document",
  "formatVersion": 1,
  "payload": { "…": "UiDocument" },
  "schemaVersion": 1
}
```

| Field | Type | Meaning |
|---|---|---|
| `format` | `String`, the literal `"forge.ui-document"` (`FORMAT_ID`) | Format family id. Anything else is a decode error; additional formats arriving later speak through their own `DocumentCodec.formatId` on the same interface. |
| `formatVersion` | `Int`, must equal `1` (`FORMAT_VERSION`) | Envelope-layout version. Strict equality — no acceptance of larger values and no migration on it. |
| `schemaVersion` | `Int`, current `1` (`SchemaVersion.kt`) | Payload-contract version. Above the supported value throws `UnsupportedFutureVersion` (a `SerializationException`); below it refuses — the format has never had a v0. |
| `payload` | `UiDocument`, required | The document; §2's rules and §4's discriminators apply inside it and nowhere else. |

Decode semantics (`Envelope.kt`, `JsonDocumentCodec.kt`):

- The header is validated before the payload. Unknown envelope keys, a wrong `format`,
  or a missing field is a decode error — unknown keys are never silently ignored.
- The one escape hatch is `DecodeOptions(lenient = true)`, tools-only: it strips unknown
  header keys and re-decodes strictly. The payload below the header stays strict either
  way, which is what §19.4 of the plan means by strict-with-a-tools-door.
- A successful decode returns `DecodeResult`; `migratedFrom` is null until a migration
  chain exists (§6).

## 2. Canonical JSON rules

Canonical bytes come from one writer (`CanonicalJsonWriter.kt`) and one serializer
configuration (`ForgeJson.kt`); nothing canonicalizes ad hoc at call sites.

| # | Rule |
|---|---|
| 1 | Object keys are sorted recursively, by UTF-16 code-unit order. Node ids in the `nodes` table are object keys, so the nodes table is sorted through this same rule. |
| 2 | Arrays keep model order — order is data and the writer never sorts it. |
| 3 | Pretty-printed with a two-space indent, LF line endings, one trailing newline; empty containers stay inline (`{}`, `[]`). |
| 4 | Scalars are written verbatim; the writer applies no second number formatter. Number spelling is owned by the serializers (§3). |
| 5 | `encodeDefaults = false`: a model default is not written. Changing a model's default value is therefore a persisted-shape change — the kind of change a schema migration must own (ADR-005). |
| 6 | `explicitNulls = false`: absent equals null on the wire; a null is never spelled. |
| 7 | Plan §18.2's set-ordering policy (sets emitted sorted by each element's canonical encoding) is *not* enforced by the writer today — only object keys are sorted. See §8. |

The absence and defaults rules together are the format's own contract: a key omitted
because of rule 5 reads as the model default of the property that declares one, and a
key omitted because of rule 6 reads as null only for a defaultless nullable, which is
why omitting a nullable was safe to make legal at all.

## 3. Canonical numbers

Floats and doubles serialize through `CanonicalFloat`/`CanonicalDouble` and
`CanonicalNumbers.kt` (engine/model), not through the platform's own number wording:

- Text is produced from a scaled integer (`value × 10⁴`), rounded to the *nearest with
  ties away from zero*; at most four fractional digits, trailing zeros trimmed.
- The text is written as an unquoted JSON literal — never via `Double.toString()` or
  locale printf — so the bytes are JVM-independent and carry no `E`/exponent spelling.
- Negative zero is written as positive `0`; a value that underflows to zero becomes `0`.
- NaN and ±Infinity are refused, and absolute magnitudes above `MAX_CANONICAL_MAGNITUDE`
  (2.0e11) are refused: encoding throws rather than emitting a lossy number.
- `num.format`'s companion spelling (`fixedDecimalText`) is a rendering helper, kept
  width-clamped to 9 digits; it is not the wire rule.
- Decoders re-canonicalize, so a document read back and re-encoded is byte-identical to
  the original canonical bytes — the round-trip property the format rests on.

## 4. Polymorphism on the wire

One discriminator spelling: `"type"`. Every polymorphic union in the model carries its
own `@JsonClassDiscriminator("type")`; the `ForgeJson` configuration's
`classDiscriminator = "k"` is overridden by each union, so `"k"` does not appear on the
v1 wire for any of them. Non-polymorphic classes serialize as flat members with no
discriminator at all. Tag values are §5's catalog.

## 5. The `@SerialName` catalog

90 tags across 13 type families, extracted mechanically from the annotation uses in
`engine/model` (comment mentions of `@SerialName` excluded). The union's declaration
file is named because a tag is only meaningful within its union's scope.

| Union (file in engine/model) | Tags |
|---|---|
| `Value` (value/Value.kt) | `null, bool, i32, i64, f32, f64, str, color, dp, sp, enum, url, ref, icon, token, list, obj, map` |
| `TypeRef` (type/TypeRef.kt) | `bool, i32, i64, f32, f64, str, color, dp, sp, url, nullable, list, map, enum, object, ref, token, icon, dimension` |
| `TokenKind` (type/TokenKind.kt) | `color, typography, shape, dimension` |
| `RefKind` (type/RefKind.kt) | `page, component, resource, dataModel` |
| `Expr` (expr/Expr.kt) | `const, ref, member, call, unary, binary, if, list, template` · scope-reference union: `state, param, event, item` |
| `Operators` (expr/Operators.kt) | `not, neg, add, sub, mul, eq, neq, lt, le, gt, ge, and, or` |
| `PropertyValue` (expr/PropertyValue.kt) | `const, expr` |
| `Persistence` (doc/StateDecl.kt) | `none, saveable` |
| Resources (doc/ResourceDecl.kt) | source: `text, bundled, remote, embedded` · kind: `string, image, font, icon, file` · qualifier: `locale, density, theme` |
| Theme (doc/ThemeDecl.kt) | `material3` |
| Node ownership (index/DocumentIndex.kt) | `page, component` |

Two facts a reader of the catalog must hold at once:

- Tags are deliberately *reused* across unions (`bool`, `list`, `icon`, `page`,
  `const`, …). That is safe: each union serializes through its own set of serializers,
  so no kotlinx conflict and no cross-union meaning bleed — but a tag is only
  interpretable together with its union.
- Within one union the set of tags is the discriminated alphabet: an unknown tag there
  is a decode error, and a renamed one is a data migration. Adding or renaming a tag
  never silently changes the wire — the catalog documents the change, and the schema
  version carries it. `@SerialName` names are the persistence contract (ADR-005):
  renaming a Kotlin class is safe, renaming its serial name is not.

## 6. Versions, migrations, and the future

The migration machinery (`Migrations.kt`, `Migration.kt`, engine/serialization):

- The registry today is an **empty chain**: `schemaVersion` is 1 and nothing below exists,
  so there is nothing to migrate yet — persisted v1 documents are the whole universe.
- The plan freezes the v1 surface at MVP release (`PLAN.md` §19.2), so any persisted-shape
  change after the freeze goes through a new schema version and a chain entry.
- A chain, once present, routes sequentially: versions sorted ascending, contiguous, no
  gaps; each entry is a pure `JsonElement → JsonElement` function; released entries are
  immutable forever after they ship.

The envelope gates this from the outside: `formatVersion` mismatches are envelope errors
(§1), and the payload only ever sees chains for its own `schemaVersion`.

## 7. A real wire sample

Reference fixtures, real bytes as committed:

- `engine/serialization/src/commonTest/resources/migrations/v1.json` — a canonical v1
  document (the sample the tests round-trip),
- `…/migrations/v1.migrated.json` — the golden the migration test asserts a rebuilt
  document's canonical text against (the chain is empty today; the fixture is what that
  test will compare against once schemas move),
- and the conformance bundle under `integration/generated-compile/fixtures/` — every one
  decodes and re-encodes canonically in CI.

The sample's envelope excerpt, verbatim from `v1.json`:

```json
{
  "format": "forge.ui-document",
  "formatVersion": 1,
  "payload": {
    "app": { "packageName": "com.example.counter", "startPage": "p_home" },
    "components": {},
    "meta": { "name": "Counter" }
```

The excerpt shows §2's rules working unaided: four header fields, object keys sorted
(`app` < `components` < `meta`), two-space indent, no nulls spelled.

## 8. Promised by the plan, not on the v1 wire

Recorded here so the spec never teaches a fiction (each traceable to §34's ADRs):

- A `PropertyValueSerializer` collapsing the `{"type":"const","value":…}` shape to a
  bare-value spelling is promised by `PLAN.md` §18.5 but is not implemented — the wire
  today carries the full shape, and `PropertyValueTest` asserts that spelling. Writing
  the collapsed shape would be writing bytes a v1 decoder refuses.
- Plan §18.2's set-ordering rule has no writer implementation (§2, rule 7) —
  `ResourceVariant.qualifiers` is the current field that notices the difference.
- `PLAN.md` §18.5's own wire example is stale: it spells `"k"` and collapsed consts,
  while the code writes `"type"` and the full const shape. Do not copy its samples;
  this document's sample and §5's catalog are the ones with code behind them.
- Version constants live in `engine/model`'s `SchemaVersion.kt`
  (`dev.rotalex.lutter.model.SchemaVersion`), though `PLAN.md` §19.1 says
  `schemaVersion` is owned by `:engine:serialization` — cosmetic drift, recorded
  rather than massaged.
