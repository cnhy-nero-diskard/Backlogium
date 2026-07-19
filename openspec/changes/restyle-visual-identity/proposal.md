## Why

Backlogium currently runs on the unmodified Material 3 Compose starter template — the
generic purple palette from `Color.kt`, default type scale, dynamic (wallpaper-derived)
color left on, and plain emoji standing in for icons everywhere (🏠🎮📜🔥✅⏳, per
`docs/ui-screens-descriptor.md`). Nothing about the UI currently signals what the app
is for or who it's for. As a Steam-library/gamification tracker, it has genre-native
visual language sitting unused (level-ups, streaks, quests) and an audience — Steam
players — that would read a Steam-flavored dark aesthetic instantly. This change gives
the app a deliberate visual identity instead of an unstyled default, and closes a
related, already-flagged gap (undefined loading/error states for game art) while the
same components are being touched anyway.

## What Changes

- Replace the default Material 3 purple color scheme with a custom "Steam-native dark"
  scheme — charcoal/navy surfaces evocative of Steam's own client without literally
  cloning its palette, plus a single amber/gold accent (chosen instead of blue, since
  Steam already owns blue in this context, and chosen as a forward-compatible hook for
  a possible future LEGENDARY-tier rarity color without committing to the full rarity
  ramp now).
- Turn off Android dynamic color (`dynamicColor` defaults to `false` in
  `BacklogiumTheme`) so the custom scheme is the app's look on every device, not a
  wallpaper-dependent fallback.
- Introduce a distinct display font for large numeral moments only (Level N, streak
  count, XP totals) via `Type.kt`; body text stays on `FontFamily.Default`.
- Replace all emoji placeholders (nav bar icons and status glyphs: 🏠🎮📜🔥✅⏳) app-wide
  with a single icon set (Phosphor Icons, Compose port).
- Define loading / loaded / error states for the 40dp Steam CDN game-art thumbnails on
  the Library screen (currently undefined, per `docs/ui-screens-descriptor.md`).
- Add inline Lottie celebratory animations at two triggers, using free/community
  LottieFiles assets (no custom-commissioned art in this change):
  - Level-up: inline within the Home screen's Level card when the player's level
    increments.
  - Streak milestone: inline within the Home screen's Streak card, triggered every 7
    days of `currentStreak` (a named, documented constant in the gamification module's
    existing style, not a hardcoded literal).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `app-ui`: adds visual-identity requirements (custom dark color scheme with dynamic
  color disabled, display typography for numeral moments, app-wide icon set, game-art
  loading/error states, inline celebratory animations for level-up and streak
  milestones) to the capability defined by `add-android-steam-app`. No existing
  requirement's screen behavior changes — this is additive styling and presentation
  layered onto the same screens.

## Impact

- **Affected code:** `app/src/main/java/com/example/backlogium/ui/theme/` (`Color.kt`,
  `Theme.kt`, `Type.kt`), `ui/home/HomeScreen.kt` (Level/Streak cards + animation
  triggers), `ui/library/LibraryScreen.kt` (game row icons + new loading/error states),
  `ui/history/HistoryScreen.kt` and the bottom navigation (emoji → icon-set swap),
  `ui/components/EmptyState.kt` (candidate for an icon per the descriptor's note).
- **New dependency:** a Lottie-for-Compose library and a Compose Phosphor Icons port.
- **Sequencing:** builds on `add-android-steam-app` (in progress, 35/39 tasks) for the
  screens being restyled, and on `add-gamification-engine` (done) for the
  `currentStreak`/level data the new triggers read. Does not depend on
  `add-achievement-xp`.

## Non-goals

- **Rarity-driven theming beyond the single gold accent color** — the five rarity tiers
  (`COMMON`/`UNCOMMON`/`RARE`/`EPIC`/`LEGENDARY`) already defined by `add-achievement-xp`
  stay out of the general theme; reserved for a future achievement-badge UI change.
- **Achievement badge / rarity indicator UI** — `add-achievement-xp` already deferred
  this to `app-ui`; this change lays palette/type/icon groundwork only and does not
  build badges. Flagged as a future follow-on change to pick up.
- **Per-game accent color extraction from Steam cover art** (e.g. via a Palette API) —
  desirable later, not in scope here.
- **Custom-commissioned or bespoke Lottie animations** — free community assets only for
  this change.
