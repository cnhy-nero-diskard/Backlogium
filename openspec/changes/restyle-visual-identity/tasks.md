## 1. Dependencies

- [ ] 1.1 Add `lottie-compose` dependency to `app/build.gradle.kts`.
- [ ] 1.2 Add the Phosphor Icons Compose port dependency to `app/build.gradle.kts`.
- [ ] 1.3 Source and add one bundled display font file under `app/src/main/res/font/`
      (single weight, confirm its license permits app bundling).

## 2. Color scheme

- [ ] 2.1 Replace the default purple constants in `ui/theme/Color.kt` with named
      charcoal/navy surface tokens and a single gold/amber accent token, chosen against
      Material3 contrast guidance for `onSurface`/`onPrimary` pairs.
- [ ] 2.2 Update `LightColorScheme`/`DarkColorScheme` in `ui/theme/Theme.kt` to use the
      new tokens (dark-first; keep a light variant for system light-mode users).
- [ ] 2.3 Change `BacklogiumTheme`'s `dynamicColor` parameter default from `true` to
      `false`.
- [ ] 2.4 Manually verify on an Android 12+ emulator/device that the app no longer
      shifts color with wallpaper changes.

## 3. Typography

- [ ] 3.1 Define a `FontFamily` in `ui/theme/Type.kt` referencing the bundled font
      resource from task 1.3.
- [ ] 3.2 Apply that `FontFamily` to the `headlineMedium`/`headlineSmall` (or
      equivalent numeral-bearing) `TextStyle`s used for Level, streak count, and XP
      total text; leave all other `TextStyle`s on `FontFamily.Default`.
- [ ] 3.3 Verify in `HomeScreen.kt` that only the Level/streak/XP numerals render in
      the new font and captions/body text are unaffected.

## 4. Icon set swap

- [ ] 4.1 Replace the bottom navigation bar's Home/Library/History emoji (🏠🎮📜) with
      icons from the chosen library (locate the nav bar composable via
      `ui/navigation/Destination.kt` / app shell).
- [ ] 4.2 Replace the streak flame emoji (🔥) in `HomeScreen.kt` with an icon from the
      chosen library.
- [ ] 4.3 Replace the quest status emoji (✅/⏳) in `HomeScreen.kt` with icons from the
      chosen library.
- [ ] 4.4 Replace the quest-met glyph (✅ / em dash) in `HistoryScreen.kt`'s daily stat
      rows with an icon from the chosen library (keep the em-dash-equivalent "not met"
      state distinguishable).
- [ ] 4.5 Do a single pass reviewing all icon replacements together for consistent
      size/weight/color before moving on (per design.md risk on inconsistent icons
      slipping through per-file).

## 5. Game art loading/error states

- [ ] 5.1 In `LibraryScreen.kt`'s `GameIcon`, add a themed `placeholder` painter/
      composable to the Coil `AsyncImage` call for the loading state.
- [ ] 5.2 Add a themed `error` painter/composable (e.g. a generic controller icon from
      the new icon set) for the failed-load state.
- [ ] 5.3 Manually verify both states: temporarily point a game's icon URL at an
      invalid/unreachable URL and confirm the error state renders instead of a blank
      space; throttle network to confirm the placeholder shows during load.

## 6. Streak milestone rule (gamification module)

- [ ] 6.1 Add a documented `STREAK_MILESTONE_INTERVAL_DAYS` constant (default `7`) to
      the `gamification` module alongside its existing tunables.
- [ ] 6.2 Add a pure `isStreakMilestone(streakDays: Int): Boolean` function next to
      `GamificationUpdater`.
- [ ] 6.3 Add unit tests in the `gamification` module's test suite covering: non-
      multiple-of-7 returns false, exact multiples (7, 14) return true, 0/negative
      streak returns false.

## 7. Celebratory Lottie animations

- [ ] 7.1 Choose one free/community LottieFiles JSON asset for level-up and one for
      streak milestone, screening for tintability toward the gold accent (per
      design.md risk on color mismatch).
- [ ] 7.2 Bundle both chosen JSON files under `app/src/main/res/raw/`.
- [ ] 7.3 In `HomeScreen.kt`'s Level card, detect a level increment (compare current
      vs previous level from `HomeViewModel`'s state) and play the level-up
      `LottieAnimation` inline when it occurs.
- [ ] 7.4 In `HomeScreen.kt`'s Streak card, call `isStreakMilestone` (task 6.2) on the
      current streak and play the milestone `LottieAnimation` inline when true.
- [ ] 7.5 Manually verify both animations play once per triggering event (not on every
      recomposition) and do not replay on unrelated state changes.

## 8. Documentation

- [ ] 8.1 Update `docs/ui-screens-descriptor.md`'s "Design tokens" section to reflect
      the new color scheme, typography, icon set, and the game-art/animation
      additions (it currently documents the pre-restyle stock Material3 state).
