# Document the color palette in the README

## Why

The app's visual identity is deliberate and constrained — a "Steam-native dark" charcoal/navy
surface family with a gold accent that owns its hue territory exclusively — and every design
decision in the UI depends on knowing that rule. But it is documented only in two places a reader
will not look: comments in `ui/theme/Color.kt`, and a section of `docs/ui-screens-descriptor.md`.

The README, which is the entry point for anyone new to the project, describes the stack, the
architecture, and the gamification formula in detail, and says nothing about the visual system. So
the most easily violated convention in the codebase is also the least discoverable one — and "don't
introduce a second gold" is exactly the kind of rule that gets broken by someone who never knew it
existed.

## What Changes

- A **"Visual identity" section in the README**, organized by *semantic family* rather than by
  token: what each family means, its anchor hex, and where the full values live.
- An explicit statement of the **hue-territory rule** (see below), replacing the weaker and
  factually wrong framing that gold is "reserved for milestones only".
- **Reconciling `docs/ui-screens-descriptor.md`**, whose token section still claims the achievement
  rarity ramp is out of scope. It shipped.
- A pointer to `docs/ui-screens-descriptor.md` as the fuller per-screen reference, and to
  `ui/theme/Color.kt` as the source of truth for values.

### The rule being documented

`Theme.kt` maps `primary = Gold`, so gold is *not* reserved for milestone moments — it carries
ordinary emphasis on every FAB, progress bar, and selected state in the app. Stating otherwise
would contradict the first screen a reader looks at. What is actually reserved is **hue
territory**:

- **Gold** is the primary accent, and nothing else may be gold. The achievement rarity ramp
  deliberately reuses `Gold` at LEGENDARY rather than minting a second gold, so gold reads as one
  meaning rather than two.
- **Steel-blue** (secondary/tertiary) owns the "in game right now" lane, including the hand-tuned
  `tertiaryContainer` for the now-playing card.
- **Green** (`PlayingIndicator`) means live presence only, and is kept vivid so it is not confused
  with the muted sage of `RarityUncommon`.
- Derived accents (overrun, deadline warning, rarity halo, collection tints) stay muted or stay
  inside an existing hue family precisely to avoid claiming new territory.

### Scope of the values

The README documents **families, not the full token table**. `Color.kt` holds roughly forty tokens
across nine families; transcribing all of them into the README would duplicate the source of truth
this change explicitly declines to duplicate, and would drift on the next color added. The
exhaustive per-screen values stay in `docs/ui-screens-descriptor.md`.

## Capabilities

No behavior changes. Documentation only.

## Impact

- **Affected files:** `README.md` (new section), `docs/ui-screens-descriptor.md` (correct the
  stale "rarity ramp is out of scope" claim and reconcile with the README's framing).
- **No code changes**, no migrations, no tests.

## Non-goals

- **Changing any color.** This documents the palette that exists.
- **A full design-system document** — typography, spacing, iconography, component inventory. The
  descriptor doc already covers screens; this adds the color reference the README lacks.
- **Duplicating the values as the source of truth.** `Color.kt` remains authoritative; the README
  documents the rules and points at it.
