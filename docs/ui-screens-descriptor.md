# Backlogium — Screen Descriptor

Source: `app/src/main/java/com/example/backlogium/ui/**`. Android app, Material 3 (Compose),
dynamic color enabled on API 31+, falls back to the static palette below. Bottom navigation
with 3 destinations. Every screen renders from local state only (offline-first) and has a
"not configured" / "empty" variant.

## Design tokens

- **Theme:** Material 3, light + dark schemes, dynamic color when available (Android 12+).
- **Static fallback palette** (used pre-Android-12 or dynamic color off):
  - Dark scheme — primary `#D0BCFF`, secondary `#CCC2DC`, tertiary `#EFB8C8`
  - Light scheme — primary `#6650A4`, secondary `#625B71`, tertiary `#7D5260`
- **Typography:** Material 3 default type scale (headlineMedium, headlineSmall, titleLarge,
  titleMedium, bodyLarge, bodyMedium, bodySmall).
- **Shape:** Material 3 `Card` everywhere (rounded rect, default M3 elevation/shape) — no
  custom shapes defined.
- **Icons:** none from an icon library; nav bar and status glyphs are plain emoji (🏠 🎮 📜
  🔥 ✅ ⏳). Game art comes from remote Steam CDN thumbnails (40dp square, no placeholder/
  loading/error state defined yet).
- **Spacing rhythm:** 16dp screen padding, 8–12dp internal card padding, 4–12dp gaps between
  stacked elements.

## App shell

Material 3 `Scaffold` with a bottom `NavigationBar`, 3 items, icon (emoji) + label, one
selected at a time:

| Destination | Icon | Label |
|---|---|---|
| Home | 🏠 | Home |
| Library | 🎮 | Library |
| History | 📜 | History |

Content area is a `NavHost` that swaps between the 3 screens below; state is preserved when
switching tabs (standard save/restoreState nav behavior). Home is the start destination.

---

## Screen 1 — Home

**Purpose:** at-a-glance status — level/XP, today's quest, streak, last sync.

**Layout:** single scrollable column, 16dp outer padding, 16dp vertical gaps between cards,
full width.

1. **Error banner** (conditional, only when a sync error exists) — a `Card` tinted with the
   M3 "error container" color, containing just the error message text, 16dp padding.
2. **Level / XP card** — white/surface card, 16dp padding:
   - Large bold headline: `"Level {N}"`
   - Horizontal progress bar (linear, full width) showing XP progress within the level
   - Small caption below: `"{xpIntoLevel} / {xpForNext} XP to next level · {totalXp} total"`
3. **Today's quest card**:
   - Title: "Today's quest"
   - Status line: "✅ Complete" or "⏳ In progress"
   - Caption: `"{minutes played} of {quest threshold} played today"` (durations formatted as
     "1h 20m" / "45m")
4. **Streak card**:
   - Title: "Streak"
   - Large line: `"🔥 {N} day(s)"`
   - Caption: `"Longest: {N}"`
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
   - 40dp square game icon (remote image, left)
   - 12dp gap
   - Column: game name (bodyLarge) → linear progress bar (full width) → caption
     `"{playtime} / {target} ({percent}%)"`
   - Entire row is tappable → opens the Goal dialog (see below) pre-filled for editing/untag.
3. **Section header** "Backlog" (always shown).
4. **Backlog game row** (repeated, one per non-goal game), full-width `Card`, 12dp padding:
   - 40dp square game icon (left) + 12dp gap
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
   - Right: a large "✅" if that day's quest was met, otherwise an em dash "—"

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
- Emoji stand in for icons everywhere (🏠 🎮 📜 🔥 ✅ ⏳) — a good opportunity to swap in a
  real icon set when redesigning.
- Every screen has a "sad path": not-configured and empty-data states are first-class, not
  afterthoughts — worth designing those explicitly rather than assuming happy path only.
