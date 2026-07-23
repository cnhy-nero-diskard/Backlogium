# Backlogium — Screen Descriptor

Source: `app/src/main/java/com/example/backlogium/ui/**`. Android app, Material 3 (Compose),
using a custom "Steam-native dark" identity with dynamic (wallpaper-derived) color turned
off, so the look is identical across devices. Bottom navigation with 3 destinations. Every
screen renders from local state only (offline-first) and has a "not configured" / "empty"
variant.

## Design tokens

- **Theme:** Material 3, custom dark-first color scheme. Android dynamic color is **off**
  (`BacklogiumTheme(dynamicColor = false)`), so the palette below is the app's look on every
  device (not a wallpaper-derived fallback). A light scheme is retained for system light mode.
- **Palette — "Steam-native dark" (charcoal/navy surfaces + single gold accent):**
  - Dark scheme — background `#10141C`, surface `#171C26`, surface-variant `#232A38`,
    primary/accent (gold) `#E0A83A` on `#241A00`, secondary (steel-blue) `#7FA6C9`.
  - Light scheme — background/surface `#FBF8F1`, primary (gold) `#7A5A00`, secondary
    (steel-blue) `#2F5B7C`.
  - The single gold accent is a deliberate, forward-compatible hook (a possible future
    LEGENDARY rarity color) — the full rarity ramp is intentionally out of scope.
- **Typography:** Material 3 type scale, with a bundled **display font (Orbitron, SIL OFL,
  `res/font/orbitron.ttf`)** applied to the large numeral moments only — `headlineMedium`
  (Home "Level N") and `headlineSmall` (Home streak count). All other styles stay on
  `FontFamily.Default`. Font is bundled (not a Downloadable Font) to preserve offline-first.
- **Shape:** Material 3 `Card` everywhere (rounded rect, default M3 elevation/shape); game-art
  thumbnails are clipped to an 8dp rounded square.
- **Icons:** a single icon library — **Tabler Icons** (Compose port,
  `br.com.devsrsouza.compose.icons:tabler-icons`). Nav bar: Home / DeviceGamepad / History;
  status glyphs: Flame (streak), CircleCheck / Clock (quest complete / in progress),
  CircleCheck / CircleMinus (History daily quest met / not met). No emoji are used for icons.
  (Tabler substitutes for Phosphor, which has no Compose port on Maven Central; the choice is
  a single consistent stroke-based family, swappable wholesale later.)
- **Game art states:** the 40dp Steam CDN thumbnails (`GameIcon`, via Coil
  `SubcomposeAsyncImage`) have defined states — a themed `surfaceVariant` placeholder while
  loading and a themed generic-controller (`DeviceGamepad`) fallback on load failure.
- **Celebratory animations:** two inline Lottie clips (bundled under `res/raw/`, community
  LottieFiles assets) — `levelup.json` plays in the Home Level card when the level increments,
  `streak_milestone.json` plays in the Home Streak card when the streak hits a multiple of
  `STREAK_MILESTONE_INTERVAL_DAYS` (7). Both play once per triggering event, inline (not modal).
- **Spacing rhythm:** 16dp screen padding, 8–12dp internal card padding, 4–12dp gaps between
  stacked elements.

## App shell

Material 3 `Scaffold` with a bottom `NavigationBar`, 3 items, icon (Tabler) + label, one
selected at a time:

| Destination | Icon (Tabler) | Label |
|---|---|---|
| Home | `Home` | Home |
| Library | `DeviceGamepad` | Library |
| History | `History` | History |

Content area is a `NavHost` that swaps between the 3 screens below; state is preserved when
switching tabs (standard save/restoreState nav behavior). Home is the start destination.

---

## Screen 1 — Home

**Purpose:** at-a-glance status — level/XP, today's quest, streak, last sync.

**Layout:** single scrollable column, 16dp outer padding, 16dp vertical gaps between cards,
full width.

1. **Error banner** (conditional, only when a sync error exists) — a `Card` tinted with the
   M3 "error container" color, containing just the error message text, 16dp padding.
2. **Level / XP card** — surface card, 16dp padding:
   - Large bold headline in the display font: `"Level {N}"`
   - Horizontal progress bar (linear, full width) showing XP progress within the level
   - Small caption below: `"{xpIntoLevel} / {xpForNext} XP to next level · {totalXp} total"`
   - On a level increment, the `levelup` Lottie plays once, inline (top-end of the card).
3. **Today's quest card**:
   - Title: "Today's quest"
   - Status line: a Tabler icon + label — `CircleCheck` "Complete" (met, accent-tinted) or
     `Clock` "In progress" (not met)
   - Caption: `"{minutes played} of {quest threshold} played today"` (durations formatted as
     "1h 20m" / "45m")
4. **Streak card**:
   - Title: "Streak"
   - Large line: a `Flame` icon + `"{N} day(s)"` count in the display font
   - Caption: `"Longest: {N}"`
   - When the streak reaches a multiple of 7, the `streak_milestone` Lottie plays once, inline.
5. **Sync row** (bottom, full width, space-between) — left: `"Last sync: {date/time}"`
   caption text; right: a filled "Sync now" button.

**Empty / alt state:** if Steam isn't configured, the whole screen is replaced by a single
centered **Empty State** (see component below) — title "Steam not configured", body
explaining to set `steam.apiKey`/`steam.steamId` and that the Steam profile must be public.

---

## Screen 2 — Library

**Purpose:** goal games (with progress) separated from the rest of the owned-games backlog;
tap any game to tag/edit/untag it as a goal.

**Layout:** single scrollable list (LazyColumn), 16dp outer padding, sections rendered only
if non-empty:

1. **Section header** "Goal games" (bold, titleMedium) — shown only if goal games exist.
2. **Goal game row** (repeated, one per goal game), each a full-width `Card`, 12dp padding,
   horizontal layout:
   - 40dp square game icon (remote image, left; 8dp rounded, themed loading placeholder and
     controller fallback on error)
   - 12dp gap
   - Column: game name (bodyLarge) → linear progress bar (full width) → caption
     `"{playtime} / {target} ({percent}%)"`
   - Entire row is tappable → opens the Goal dialog (see below) pre-filled for editing/untag.
3. **Section header** "Backlog" (always shown).
4. **Backlog game row** (repeated, one per non-goal game), full-width `Card`, 12dp padding:
   - 40dp square game icon (left, same loading/error states as above) + 12dp gap
   - Column: game name (bodyLarge), caption `"{playtime} played"`
   - Tappable → opens Goal dialog to tag it as a new goal.

**Goal dialog** (Material 3 `AlertDialog`, shown on row tap):
   - Title: "Set as goal" (new) or "Edit goal" (existing goal)
   - Body: game name (bodyMedium) + an outlined numeric text field labeled
     "Target (minutes)", digits only
   - Confirm button: "Save" (disabled until a valid positive number is entered)
   - Dismiss button: "Untag" (if editing an existing goal) or "Cancel" (if new)

**Empty / alt states:**
- Not configured → centered Empty State, title "Steam not configured".
- No games at all yet → centered Empty State, title "No games yet", explaining sync is
  pending or the profile may be private.

---

## Screen 3 — History

**Purpose:** recent synthesized play sessions + per-day play totals and quest results.

**Layout:** single scrollable list (LazyColumn), 16dp outer padding, two sections:

1. **Section header** "Recent sessions" (shown if any sessions exist).
2. **Session row** (repeated), full-width `Card`, 12dp padding, horizontal, space-between:
   - Left column: game name (bodyLarge) over a caption with formatted date/time of session
     start
   - Right: duration text, e.g. `"1h 20m"`, or `"{duration} · live"` if the session is still
     open/ongoing
3. **Section header** "Daily stats" (shown if any daily rows exist).
4. **Day stat row** (repeated), full-width `Card`, 12dp padding, horizontal, space-between:
   - Left column: date (bodyLarge) over caption `"{minutes played} played"`, appending
     `" · {goal minutes} on goals"` only when goal minutes > 0
   - Right: a `CircleCheck` icon (accent-tinted) if that day's quest was met, otherwise a
     muted `CircleMinus` icon

**Empty / alt states:**
- Not configured → centered Empty State, title "Steam not configured".
- No sessions AND no daily rows yet → centered Empty State, title "No history yet".

---

## Shared component — Empty State

Used identically across all 3 screens for the "not configured" / "no data" variants.
Full-screen, centered both axes, 32dp padding:
- Title text (titleLarge, centered)
- 8dp gap
- Message/body text (bodyMedium, centered)

No icon/illustration in the current implementation — just two lines of centered text. This
is a natural place to add an illustration if regenerating with more visual polish.

---

## Notes for regenerating in Claude Design

- This is a **3-tab mobile app** (phone-sized canvas), Material 3 look — rounded cards,
  a bottom tab bar, no top app bar currently (could be added).
- Card-heavy, single-column, vertically stacked info — no grids, no multi-column layouts.
- Only 2 data-viz elements: linear (horizontal) progress bars — no charts, rings, or graphs.
- Icons come from a single set (Tabler Icons, Compose port); there are no emoji glyphs.
- Every screen has a "sad path": not-configured and empty-data states are first-class, not
  afterthoughts — worth designing those explicitly rather than assuming happy path only.
