# What is Lutter? A plain-language guide

*This page explains Lutter for someone who has never programmed. No jargon without a
translation. One real example, from this repository, end to end.*

---

## The one-paragraph answer

Lutter is a machine that turns **descriptions of screens** into **real apps**.

A screen — a card with a picture, a slider, a button — can be written down as a plain text
file, like a recipe. Lutter reads that recipe, checks it for mistakes, and then builds the
actual screen on a phone or a computer, exactly as described. The app that comes out is a
normal, fast, native app; Lutter just builds it from the description instead of a person
typing every screen by hand.

## The furniture analogy

Think of a furniture company.

- The **recipe** is the assembly instruction sheet: "one panel, four screws, one drawer,
  attach in this order."
- Lutter is **both** of these:
  - a **workshop worker** who can follow the sheet on the spot and assemble the piece in
    front of you (this part of Lutter is called the *runtime*), and
  - a **factory stamping machine** that reads the same sheet once and produces a finished,
    pre-built piece that no longer needs the sheet at all (this part is called the
    *codegen* — short for "code generator").

Same sheet, two ways to get furniture. That is the whole idea.

## Why anyone wants this

If screens are recipes instead of hand-built objects, then:

- **Checking replaces hoping.** A machine can read the recipe and say "this recipe
  mentions a screw that doesn't exist" *before* anything is built. Lutter does this
  (its *analysis* step), and every mistake it finds is reported with the exact place in
  the recipe that caused it.
- **One recipe, many kitchens.** The same description builds the screen on a phone, on a
  desktop computer, and on other devices, because Lutter builds everything on the same
  industry-standard foundations (a technology called Compose, made by Google).
- **The recipe is the truth.** What the screen looks like is written in one readable
  file — not scattered across thousands of lines of hand-written programming.

## A real example, straight from this repository

This repository contains a test recipe called `image_basic`. Here is what it says,
translated from its file (`integration/generated-compile/fixtures/image_basic.json`)
into plain words:

> "Make a column (things stacked top to bottom) containing:
> an **image** named *hero* — a picture that lives inside the app —
> with a description for accessibility, 'Hero image',
> and below it a **text label** that says 'Hero'."

Written as an actual recipe, the interesting part looks like this (shortened):

```json
{
  "type": "m3.Image",
  "id": "n_image",
  "props": {
    "source": { "type": "ref", "kind": "resource", "id": "r_hero" },
    "contentDescription": "Hero image"
  }
}
```

You do not need to read that. The point is what it *does*:

- `m3.Image` names a building block from Lutter's vocabulary — "show a picture", in the
  visual style of Material Design 3 (Google's design system, the same one used by
  thousands of Android apps).
- `"ref", "kind": "resource", "id": "r_hero"` means "the picture is not drawn here; it is
  a **named resource** that travels with the app" — like a recipe saying "the photo lives
  in the back of the book, labeled r_hero". The actual picture file (a small blue square,
  `hero.png`) sits in the app's luggage separately from the recipe.

## What Lutter does with that recipe

1. **It checks it.** The analysis step confirms: the building block `m3.Image` exists, the
   resource `r_hero` exists, the resource really *is* a picture (not, say, a font), and
   the description text is a proper text. If any of that were wrong, Lutter reports the
   exact problem — for example, asking for a resource that was never declared.
2. **The workshop builds it now.** The runtime reads the recipe and composes the live
   screen — picture, label, everything — on the spot.
3. **The factory stamps it out.** The codegen step writes real, ordinary Kotlin (the
   programming language for such apps) from the same recipe. The stamped result for the
   picture line above looks like this, once compiled:

   ```kotlin
   Image(
       painter = painterResource(Res.drawable.hero),
       contentDescription = "Hero image",
   )
   ```

   That is a sentence in a real app language saying "draw the picture named hero, and read
   it aloud as 'Hero image' for people using accessibility tools."

4. **It proves the two agree.** The repository's test harness builds the screen *both*
   ways — workshop and factory — and compares them, element by element and pixel by
   pixel. They must match, or the build fails. "The two ways of building agree" is not a
   hope here; it is a checked promise.

## What was built most recently

The project grows by adding words to the recipe vocabulary. The latest batch added:

- **`m3.Slider`** — the draggable control for picking a value, like a volume dial.
- **`FlowRow` / `FlowColumn`** — layouts that wrap items to the next line when they run
  out of room, like text wrapping at the edge of a page.
- **`m3.Image` plus resources** — showing pictures (and later sounds of other kinds:
  fonts, files) that travel *with* the app and are declared in the recipe, checked by the
  analyzer, and resolved per platform (a phone may want a different picture size than a
  desktop; the recipe says which variant answers where).

Every one of those additions shipped the same way: the recipe vocabulary gained a word,
the checker learned what that word may and may not do, the workshop learned to build it,
the factory learned to stamp it, and the tests proved all three agree.

## Where the pieces live

| Plain-language name | Where it is      | What it is                          |
|---------------------|------------------|-------------------------------------|
| The recipe format   | `engine/model`   | What a description may say          |
| The vocabulary      | `engine/schema`  | Every building block and its rules  |
| The checker         | `engine/analysis`| Finds mistakes before building      |
| The workshop        | `engine/runtime` | Builds screens live, from the recipe|
| The factory         | `engine/codegen` | Writes real app code from the recipe|
| The toolkit         | `tools/cli`      | The command that does it all (`forge`)|

If you read nothing else: **Lutter means a screen you can describe, check, and build —
the same description, checked once, running everywhere.**
