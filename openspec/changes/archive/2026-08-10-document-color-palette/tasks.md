# Tasks — Document the color palette in the README

> Documentation only. `ui/theme/Color.kt` stays the source of truth for values; the README
> documents the *rules* and points at it. Families, not the full token table — see the proposal's
> "Scope of the values".

## 1. README "Visual identity" section
- [x] 1.1 Add the section after **Architecture**, before **App Surfaces**
- [x] 1.2 Open with the theme facts: Material 3, dark-first, `BacklogiumTheme(dynamicColor = false)`
  so this palette is the look on every device, and a light scheme retained for system light mode
- [x] 1.3 Document the families — anchor hex (dark scheme) and what each one *means*, not a full
  token dump:
  - **Surfaces (charcoal/navy)** — background `#10141C`, surface `#171C26`, surface-variant
    `#232A38`, text `#E4E8F0` / `#AEB6C4`
  - **Gold accent** — `Gold #E0A83A` on `#241A00`; mapped to M3 `primary`
  - **Steel-blue** — `SteelBlue #7FA6C9` (secondary), `SteelBlueLight` (tertiary),
    `SteelBlueContainer #243B4C` / `OnSteelBlueContainer #CFE4F0` for the now-playing card
  - **Live presence** — `PlayingIndicator #4ADE80` (light `#15803D`)
  - **Derived accents** — `GoldOverrun #8A431C` (completion overshoot),
    `DeadlineWarning #FFB454` (plans due soon)
  - **Rarity halo** — `RarityCommon #8A93A3`, `RarityUncommon #6FAE7A`, `RarityEpic #A579D6`;
    RARE and LEGENDARY reuse `SteelBlue` and `Gold`
  - **Collection tints** — `CollectionTeal`, `CollectionRose`, `CollectionCoral`, deliberately muted
  - **Light scheme** — one line: same families re-anchored (gold `#7A5A00`, steel-blue `#2F5B7C`,
    surfaces `#FBF8F1` / `#EDE6D6`), every dark token has a `*Light` counterpart in `Color.kt`
- [x] 1.4 State the **hue-territory rule** as the section's point, in the proposal's framing:
  gold is `primary` and carries ordinary emphasis, but nothing else may be gold — LEGENDARY rarity
  reuses `Gold` rather than minting a second one; steel-blue owns the "in game right now" lane;
  green means live presence only and stays vivid so it can't be read as `RarityUncommon`'s sage;
  derived accents stay inside an existing hue family rather than claiming new territory
- [x] 1.5 Link `docs/ui-screens-descriptor.md` (per-screen reference) and `ui/theme/Color.kt`
  (source of truth for all ~40 tokens)

## 2. Reconcile `docs/ui-screens-descriptor.md`
- [x] 2.1 Fix lines 21–22: the claim that the rarity ramp is "intentionally out of scope" is stale —
  `RarityCommon`/`RarityUncommon`/`RarityEpic` shipped, with RARE and LEGENDARY reusing existing
  tokens. Rewrite to describe the ramp that exists and why it reuses two of its five tiers
- [x] 2.2 Check the rest of the token section (lines 11–29) for framing that contradicts the
  README's hue-territory rule; reconcile rather than duplicate

## 3. Verify
- [x] 3.1 Every hex in the README matches `ui/theme/Color.kt` exactly
- [x] 3.2 Every M3 role claim (`primary = Gold`, secondary/tertiary steel-blue) matches
  `ui/theme/Theme.kt`
- [x] 3.3 No family in `Color.kt` is silently absent from the README's list
