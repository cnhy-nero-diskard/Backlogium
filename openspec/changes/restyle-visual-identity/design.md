## Context

Backlogium's UI (`app/src/main/java/com/example/backlogium/ui/`) is the stock
Compose/Material3 template output: `Color.kt` still has the generator's default
purple values (`Purple80`/`PurpleGrey80`/`Pink80` etc.), `Theme.kt` defaults
`dynamicColor = true` so Android 12+ devices ignore that palette entirely in favor of
wallpaper-derived color, and `Type.kt` only overrides `bodyLarge` — everything else is
Material3's default type scale. Icons are literal emoji characters embedded in `Text`
composables (nav bar labels and status glyphs), not a real icon component. Game art
(`LibraryScreen.kt`'s `GameIcon`) is a bare Coil `AsyncImage` with no `placeholder`,
`error`, or loading-state handling — a broken/slow Steam CDN URL currently renders
nothing.

The app is a single Gradle module (`app/`) plus a pure-Kotlin `gamification` module
with no Android dependencies, kept intentionally free of Android/Retrofit/Room imports
(see `add-achievement-xp`'s stated boundary). The app is explicitly offline-first —
every screen renders from local Room state and must work with no network — which
constrains how fonts and animation assets get sourced (a runtime-downloaded Google
Font or a fetched-on-first-use Lottie file would both violate that offline guarantee
on first launch).

Stakeholder: solo developer, no design team; decisions here favor "one clear choice
now" over "configurable design system," consistent with how the gamification module
already prefers named constants over runtime-tunable theming.

## Goals / Non-Goals

**Goals:**
- A single, custom, dark-first color scheme that reads as "Steam-adjacent" without
  reusing Steam's actual hex values, with dynamic color turned off so the look is
  identical across devices.
- One display font for level/streak/XP numerals; body text unchanged.
- One icon library replacing every emoji glyph app-wide.
- Defined loading/loaded/error states for the Library screen's game-art thumbnails.
- Two inline, Lottie-driven celebratory moments (level-up, weekly streak milestone)
  using bundled community assets — no network fetch for the animation itself.
- All new assets (font, icon set, Lottie files) ship inside the APK so the app's
  offline guarantee is unaffected.

**Non-Goals:**
- Rarity-tier-driven theming beyond the one gold accent color choice.
- Achievement badge / rarity indicator UI.
- Per-game accent color extracted from cover art.
- Custom-commissioned Lottie art, or any Lottie asset fetched over network at runtime.
- A themeable/configurable design-token system — one fixed scheme, not a framework for
  building more later.

## Decisions

### 1. Color scheme: hand-authored `ColorScheme`, dynamic color defaulted off
Replace `Color.kt`'s purple constants with a small set of named tokens (e.g. a
charcoal/near-navy surface family and a single gold/amber accent) passed into
`darkColorScheme(...)` / `lightColorScheme(...)` in `Theme.kt`, same pattern already
used, just with new values. `BacklogiumTheme`'s `dynamicColor` parameter default
changes from `true` to `false`.
- **Alternative considered:** keep dynamic color as the default and only supply the
  custom scheme as the "static fallback" (today's framing in
  `docs/ui-screens-descriptor.md`). Rejected — the whole point of this change is a
  deliberate, consistent identity; leaving dynamic color on means the actual
  day-to-day look is still whatever the user's wallpaper produces on Android 12+,
  which is the exact problem being fixed.
- **Alternative considered:** a full Material Theme Builder-generated tonal palette
  from a seed color. Rejected for v1 — hand-picking the handful of colors actually used
  by this app's small surface area (few screens, card-heavy, no complex elevation
  stack) is simpler than adopting a generated 13-tone system this app won't exercise.

### 2. Display typography: bundled font file via `res/font`, not a downloadable font
For the Level/streak/XP numeral moments, bundle a single display font as a font
resource (`res/font/*.ttf` + a `FontFamily`) referenced from specific `TextStyle`s in
`Type.kt` (e.g. `headlineMedium`/`headlineSmall`), rather than Android's Downloadable
Fonts API (Google Fonts provider).
- **Why bundled over downloadable:** Downloadable Fonts fetch from Google Play
  Services on first use and can fall back to the system font if that fetch fails or
  Play Services is unavailable — inconsistent with an app whose other screens are
  explicitly designed to guarantee identical behavior offline.
- **Scope discipline:** only the large numeral-bearing text styles get the display
  font; body/caption styles keep `FontFamily.Default` so the font choice can't hurt
  body-text legibility or add unnecessary glyph-rendering cost to dense list screens
  (Library, History).

### 3. Icon set: one Compose-native icon library, not per-icon SVG imports
Adopt a single community Compose icon library (Phosphor Icons) as a Gradle dependency
and replace every emoji usage (nav bar: 🏠🎮📜; status glyphs: 🔥✅⏳) with icon
composables from it.
- **Alternative considered:** hand-import individual SVGs as vector drawables.
  Rejected — a packaged library gives a consistent stroke weight/visual family across
  all six replacements for less effort than curating six one-off assets, and is easy
  to swap wholesale later if the aesthetic direction changes.

### 4. Game-art states: Coil's built-in placeholder/error slots, no new state machine
`LibraryScreen.kt`'s `GameIcon` moves from a bare `AsyncImage(model = iconUrl, ...)` to
supplying `placeholder` (a themed empty-icon shape) and `error` (a distinct fallback,
e.g. a generic game-controller glyph from the new icon set) painters/composables.
Coil already tracks loading vs success vs error internally — this is wiring existing
Coil capability, not building new state tracking.

### 5. Streak-milestone cadence: a named constant in the app module, not the pure `:gamification` module
The "every 7 days" check becomes a documented constant (e.g.
`STREAK_MILESTONE_INTERVAL_DAYS = 7`) and a small pure function
`isStreakMilestone(streakDays: Int): Boolean`, placed in the **app module**
(`com.example.backlogium.domain`, alongside `GamificationUpdater`) rather than inside
the pure `:gamification` Gradle module. `HomeScreen.kt` calls that function to decide
whether to play the Lottie clip; it does not reimplement the modulo check inline.
- **Why not inside `:gamification`:** that module's current `Gamification.kt` is an
  explicitly self-declared stub ("This is the stub surface consumed by the Android
  app... [`add-gamification-engine`] owns the full, exhaustively unit-tested
  implementation") — `add-gamification-engine` (0/14 implementation tasks done as of
  this writing) is expected to replace that file's contents wholesale with the real
  per-game engine, not patch it incrementally. Adding new, unrelated code to a file
  slated for wholesale replacement risks it being silently dropped when that other
  change lands, since whoever implements it has no reason to know about a milestone
  helper bolted onto the stub. The app module (`GamificationUpdater` and its
  surrounding `domain` package) is stable and untouched by that work, so it's the
  safe home.
- **Why not UI-local (inline in `HomeScreen.kt`):** the app module already unit-tests
  this kind of small pure rule the same way (`GamificationUpdaterTest.kt`,
  `SessionDifferTest.kt` in `app/src/test/.../domain/`), so placing it there keeps it
  covered by that same test discipline instead of being an untested inline check.
- **Why not XP-bearing:** a milestone here is purely a celebratory UI trigger — it
  does not award XP or change `XpState`, so it does not touch the additive
  playtime+achievement XP combination `add-achievement-xp` established, and has no
  reason to live inside the XP engine's boundary regardless of the module question
  above.

### 6. Level-up detection: track "previous level" in Compose state, not persisted
`HomeUiState` only carries the current level — there is no "previous level" anywhere
today. Detecting an increment is done entirely on the UI side: `HomeScreen.kt` seeds
`remember { mutableStateOf(state.level) }` on first composition and compares against
it inside a `LaunchedEffect(state.level)`, updating the remembered value after each
check.
- **Why not persisted (Room/DataStore):** this is a celebratory-only signal with no
  gameplay consequence; persisting "last acknowledged level" would add a schema field
  for a purely decorative trigger and contradicts this change's "no schema change"
  migration plan (below). The trade-off is that a level-up occurring while the app is
  fully killed (not just backgrounded) won't animate on next launch — accepted, since
  `remember` seeding from the *current* value on first composition (rather than
  defaulting to e.g. 0) means it never fires a false increment on cold start either.
- **Why this doesn't depend on the real engine:** the comparison only needs two
  `Int`s and is agnostic to how `state.level` was computed — it works identically
  whether `state.level` comes from today's stub math or the real per-game engine
  once `add-gamification-engine` lands.

### 7. Lottie: `lottie-compose`, assets bundled as raw resources
Add `com.airbnb.android:lottie-compose` and bundle two chosen free/community
LottieFiles JSON animations under `res/raw/`, played via `LottieAnimation` inline
inside the existing Level card (level-up) and Streak card (milestone) — not a
full-screen `Dialog`/overlay.
- **Why bundled, not `LottieCompositionSpec.Url`:** fetching the animation JSON from a
  URL at playback time would make a celebratory moment silently fail to render (or
  block) when offline, which this app's whole architecture is built to avoid.
- **Why inline, not overlay:** keeps the moment scoped to the card that already shows
  the relevant stat, avoids adding a new modal/dismissal interaction pattern for what
  is a decorative confirmation, not new information.

## Risks / Trade-offs

- **[Risk] Community Lottie assets may not visually match the "Steam-native dark +
  gold accent" mood** (generic confetti/burst assets are often bright/multicolor) →
  **Mitigation:** filter candidate assets for ones whose default colors can be
  recolored/tinted toward the gold accent (Lottie supports dynamic color properties
  for solid-fill layers), and treat "close enough, tintable" as the v1 bar rather than
  blocking on a perfect match — revisit with custom art in a later change if it reads
  as mismatched in practice.
- **[Risk] Bundled font file adds APK size** for a change whose primary goal is
  visual, not functional → **Mitigation:** pick a single-weight (not variable) font
  file and subset if the library offers it; this is a small, one-time cost consistent
  with icon-library and Lottie-asset additions already accepted in this change.
- **[Risk] Six emoji-to-icon swaps touch four different screens/files in one change**,
  raising the chance of an inconsistent icon size/weight slipping through in one
  spot → **Mitigation:** tasks.md should include a final pass that reviews all six
  replacements together, not just per-file diffs.
- **[Trade-off] Fixing game-art loading/error states here (rather than as its own
  change) slightly widens this change's diff beyond pure styling** → accepted per
  earlier scoping decision: it touches the exact component (`GameIcon`) already being
  visually revisited, and splitting it out would mean editing that composable twice.

## Migration Plan

Purely additive/visual — no data migration, no schema change, no API change. Rollout
is a normal app update: on next build, all screens render with the new theme,
typography, and icons immediately (no feature flag; per Non-Goals, this isn't building
a themeable system). Rollback is a plain revert of this change's commits/PR if the new
look needs to be pulled.

## Open Questions

- Exact hex values for the charcoal/navy surface family and the gold accent are not
  pinned down here — left for implementation (tasks.md) to choose and check against
  Material3 contrast/accessibility guidance (e.g. `onSurface`/`onPrimary` contrast
  ratios), rather than freezing specific hex codes in planning.
- Which two specific LottieFiles community assets to use is an implementation-time
  choice (tasks.md), constrained by the tintability consideration in Risks above.
- Exact display font choice (which bundled typeface) is left to implementation;
  constraint is "one weight, bundled, licensed for app use."
