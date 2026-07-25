# Document the color palette in the README

## Why

The app's visual identity is deliberate and constrained — a "Steam-native dark" charcoal/navy
surface family with a single gold accent reserved for milestone moments — and every design decision
in the UI depends on knowing that rule. But it is documented only in two places a reader will not
look: comments in `ui/theme/Color.kt`, and a section of `docs/ui-screens-descriptor.md`.

The README, which is the entry point for anyone new to the project, describes the stack, the
architecture, and the gamification formula in detail, and says nothing about the visual system. So
the most easily violated convention in the codebase is also the least discoverable one — and "gold
means you accomplished something" is exactly the kind of rule that gets broken by someone who never
knew it existed.

## What Changes

- A **color palette section in the README**: the token families (accent, dark surfaces,
  secondary/tertiary, light scheme) with their hex values, roles, and on-color pairings.
- An explicit statement of the **accent rule** — gold is reserved for milestone moments (level-up,
  streak milestones, 100% completion) and is not a general-purpose highlight; steel-blue carries
  ordinary emphasis and the in-game lane; green, once added, means live presence only.
- A pointer to `docs/ui-screens-descriptor.md` as the fuller per-screen reference, and to
  `ui/theme/Color.kt` as the source of truth for values.

## Capabilities

No behavior changes. Documentation only.

## Impact

- **Affected files:** `README.md` (new section), and `docs/ui-screens-descriptor.md` if its palette
  section needs reconciling with the README's.
- **No code changes**, no migrations, no tests.

## Non-goals

- **Changing any color.** This documents the palette that exists.
- **A full design-system document** — typography, spacing, iconography, component inventory. The
  descriptor doc already covers screens; this adds the color reference the README lacks.
- **Duplicating the values as the source of truth.** `Color.kt` remains authoritative; the README
  documents and points at it.
