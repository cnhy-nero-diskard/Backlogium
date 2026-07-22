## 1. Dependencies

- [x] 1.1 Add `lottie-compose` dependency to `app/build.gradle.kts`.
- [x] 1.2 Add the icon-set Compose port dependency to `app/build.gradle.kts` — **Tabler Icons**
      (`br.com.devsrsouza.compose.icons:tabler-icons`), substituted for Phosphor which has no
      Compose port on Maven Central (see design.md decision 3).
- [x] 1.3 Source and add one bundled display font file under `app/src/main/res/font/`
      (single weight, confirm its license permits app bundling). — Orbitron (variable font,
      SIL OFL) at `res/font/orbitron.ttf`; license copied to `docs/licenses/Orbitron-OFL.txt`.

## 2. Color scheme

- [x] 2.1 Replace the default purple constants in `ui/theme/Color.kt` with named
      charcoal/navy surface tokens and a single gold/amber accent token, chosen against
      Material3 contrast guidance for `onSurface`/`onPrimary` pairs.
- [x] 2.2 Update `LightColorScheme`/`DarkColorScheme` in `ui/theme/Theme.kt` to use the
      new tokens (dark-first; keep a light variant for system light-mode users).
- [x] 2.3 Change `BacklogiumTheme`'s `dynamicColor` parameter default from `true` to
      `false`.
- [ ] 2.4 Manually verify on an Android 12+ emulator/device that the app no longer
      shifts color with wallpaper changes. *(manual on-device check)*

## 3. Typography

- [x] 3.1 Define a `FontFamily` in `ui/theme/Type.kt` referencing the bundled font
      resource from task 1.3.
- [x] 3.2 Apply that `FontFamily` to the `headlineMedium`/`headlineSmall` (or
      equivalent numeral-bearing) `TextStyle`s used for Level, streak count, and XP
      total text; leave all other `TextStyle`s on `FontFamily.Default`. *(Level uses
      headlineMedium, streak count uses headlineSmall; the XP total appears only in a
      bodySmall caption, which per design decision 2 stays on FontFamily.Default.)*
- [x] 3.3 Verify in `HomeScreen.kt` that only the Level/streak/XP numerals render in
      the new font and captions/body text are unaffected. *(verified by code inspection;
      only headlineMedium/headlineSmall carry the display font.)*

## 4. Icon set swap

- [x] 4.1 Replace the bottom navigation bar's Home/Library/History emoji (🏠🎮📜) with
      icons from the chosen library (`Destination.kt` now carries an `ImageVector`;
      `BacklogiumAppRoot.kt` renders it via `Icon`).
- [x] 4.2 Replace the streak flame emoji (🔥) in `HomeScreen.kt` with an icon (`Flame`).
- [x] 4.3 Replace the quest status emoji (✅/⏳) in `HomeScreen.kt` with icons
      (`CircleCheck`/`Clock`).
- [x] 4.4 Replace the quest-met glyph (✅ / em dash) in `HistoryScreen.kt`'s daily stat
      rows with an icon (`CircleCheck` met / `CircleMinus` not met — distinguishable).
- [x] 4.5 Do a single pass reviewing all icon replacements together for consistent
      size/weight/color before moving on. *(All from one Tabler family; nav icons default
      size, 20–28dp for status glyphs, tinted via theme `primary`/`onSurfaceVariant`.)*

## 5. Game art loading/error states

- [x] 5.1 In `LibraryScreen.kt`'s `GameIcon`, add a themed placeholder for the loading
      state (switched to Coil `SubcomposeAsyncImage` with a themed `loading` slot).
- [x] 5.2 Add a themed `error` fallback (generic `DeviceGamepad` controller icon) for the
      failed-load state.
- [ ] 5.3 Manually verify both states: temporarily point a game's icon URL at an
      invalid/unreachable URL and confirm the error state renders instead of a blank
      space; throttle network to confirm the placeholder shows during load. *(manual check)*

## 6. Streak milestone rule (app module, not the pure `:gamification` module)

- [x] 6.1 Add a documented `STREAK_MILESTONE_INTERVAL_DAYS` constant (default `7`) in
      the app module's `domain` package (`domain/StreakMilestone.kt`) — deliberately NOT in
      the pure `:gamification` module (see design.md decision 5).
- [x] 6.2 Add a pure `isStreakMilestone(streakDays: Int): Boolean` function in that
      same file.
- [x] 6.3 Add unit tests alongside the app module's existing domain tests
      (`StreakMilestoneTest.kt`) covering: non-multiple-of-7 returns false, exact multiples
      (7, 14) return true, 0/negative streak returns false.

## 7. Celebratory Lottie animations

- [x] 7.1 Choose one free/community LottieFiles JSON asset for level-up and one for
      streak milestone. — level-up: trophy/cup burst (`lf20_touohxv0`); streak: confetti
      cannon (`lf20_rovf9gzu`), both LottieFiles free community assets.
- [x] 7.2 Bundle both chosen JSON files under `app/src/main/res/raw/` (`levelup.json`,
      `streak_milestone.json`).
- [x] 7.3 In `HomeScreen.kt`'s Level card, seed `remember { mutableStateOf(state.level) }`
      on first composition and, in a `LaunchedEffect(state.level)`, compare against it to
      detect an increment; play the level-up `LottieAnimation` inline when detected. Tracked
      in Compose state, not persisted (design decision 6).
- [x] 7.4 In `HomeScreen.kt`'s Streak card, call `isStreakMilestone` (task 6.2) on the
      current streak and play the milestone `LottieAnimation` inline when true (guarded so it
      fires only when the streak *changes* to a milestone, not on every recomposition).
- [ ] 7.5 Manually verify both animations play once per triggering event (not on every
      recomposition) and do not replay on unrelated state changes. *(manual on-device check)*

## 8. Documentation

- [x] 8.1 Update `docs/ui-screens-descriptor.md`'s "Design tokens" section to reflect
      the new color scheme, typography, icon set, and the game-art/animation additions.
