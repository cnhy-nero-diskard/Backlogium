# Backlogium — Screen Descriptor

Source: `app/src/main/java/com/example/backlogium/ui/**`. Android app, Material 3 (Compose),
using a custom "Steam-native dark" identity with dynamic (wallpaper-derived) color turned
off, so the look is identical across devices. Bottom navigation with 3 destinations (Home /
Library / History), plus three pushed sub-destinations reached from them: **Onboarding**
(from Home, or as a first-run takeover), **Game detail** (from Library), and **HowLongToBeat
review** (from Library). Every screen renders from local state only (offline-first) and has an
"empty" / "nothing to show" variant.

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
  CircleCheck / CircleMinus (History daily quest met / not met), Trophy (achievements /
  game-completed), BrandSteam + Pencil (Steam-account card), Download (history import),
  ExternalLink / AlertCircle (onboarding). No emoji are used for icons.
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

Material 3 `Scaffold` with a **profile header** in `topBar` and a bottom `NavigationBar`.

**Profile header** — a slim, always-present identity strip above every top-level screen, on the
plain surface color, 16dp horizontal / 10dp vertical padding, status-bar inset consumed:

- 36dp circular Steam avatar; a themed `User` glyph on `surfaceVariant` stands in while loading,
  on a load failure, or before any avatar has been synced.
- Persona name (title, bold, single line, ellipsized). Falls back to the neutral "Steam player"
  when nothing has been synced yet — never the raw SteamID.
- Presence label beneath it (label style, muted): "In game" / "Online" / "Offline". Omitted
  entirely until the first live poll returns, so no state is claimed before it is known.
- Carries **no level number** — the app's XP level belongs to Home's Level/XP card, and a second
  unrelated number here would read as a contradiction.
- Renders **nothing** while credentials are unconfigured or still loading, so the onboarding
  takeover keeps the full screen.

Bottom navigation: 3 items, icon (Tabler) + label, one selected at a time:

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

1. **"Now playing" banner** (conditional, only while the player is in-game) — a `Card` tinted
   with the M3 "primary container" color: a 32dp game icon (themed controller fallback) + a
   "Now playing" label over the running game's name (bold). Adds no layout when not in-game.
2. **Error banner** (conditional, only when a sync error exists) — a `Card` tinted with the
   M3 "error container" color, containing just the error message text, 16dp padding.
3. **Level / XP card** — surface card, 16dp padding:
   - Large bold headline in the display font: `"Level {N}"`
   - Horizontal progress bar (linear, full width) showing XP progress within the level
   - Small caption below: `"{xpIntoLevel} / {xpForNext} XP to next level · {totalXp} total"`
   - On a level increment, the `levelup` Lottie plays once, inline (top-end of the card).
4. **Today's quest card**:
   - Title: "Today's quest"
   - Status line: a Tabler icon + label — `CircleCheck` "Complete" (met, accent-tinted) or
     `Clock` "In progress" (not met)
   - Caption: `"{minutes played} of {quest threshold} played today"` (durations formatted as
     "1h 20m" / "45m")
5. **Streak card**:
   - Title: "Streak"
   - Large line: a `Flame` icon + `"{N} day(s)"` count in the display font
   - Caption: `"Longest: {N}"`
   - When the streak reaches a multiple of 7, the `streak_milestone` Lottie plays once, inline.
6. **Steam account card** — surface card, 16dp padding, horizontal: a `BrandSteam` icon (accent),
   a column with title "Steam account" over `"SteamID {id}"` and a masked `"API key {•••}"`
   caption, and a trailing "Edit" `TextButton` (`Pencil` icon) that opens the Onboarding screen
   to change credentials. The raw API key never reaches this card — it is masked upstream.
7. **Steam history card** — surface card, 16dp padding, title "Steam history":
   - Before import: caption "Count your pre-install Steam playtime toward XP. One-time only." +
     an outlined "Import Steam history" button (`Download` icon). Tapping opens a confirmation
     dialog explaining the one-time effect (matched games capped by the taper, unmatched counted
     in full).
   - After import: a `CircleCheck` + "History imported" line, a caption, and a "Reset import"
     `TextButton` (with its own confirmation dialog) that undoes the import.
8. **Sync row** (bottom, full width, space-between) — left: `"Last sync: {date/time}"` (or
   "Syncing…") caption text; right: a filled "Sync now" button (shows a spinner while syncing).

**Empty / alt state:** if Steam isn't configured, the whole screen is replaced by the
full-screen **Onboarding** flow (see Screen 4) as a takeover — completing it flips the app to
the configured state automatically, no explicit navigation. (This replaced the old dead-end
"Steam not configured" text screen.)

---

## Screen 2 — Library

**Purpose:** a searchable, independently sortable list of every owned game, with the curated
**Focus** set (playtime accounted separately, and what `Focus games only` quests scope to) split
above **Your games** — the rest of the library. Every game with a known HowLongToBeat length shows
its completion progress and its XP contribution; a 3-dot menu adds/removes Focus, and long-press
starts a multi-select for a targeted HowLongToBeat lookup.

> **Label vs. identifier:** the UI says Focus / Your games while the schema and engine say
> `isGoal`, `goalMinutesPlayed`, `observeBacklog()`, and `QuestMode.GOAL_ONLY`. Deliberate — a
> relabel needs no migration, renaming columns would (see the `enhance-library` design). Expect the
> two vocabularies side by side in this screen's code.

**Layout:** an optional selection bar above a single scrollable list (LazyColumn), 16dp outer
padding, sections rendered only if they have rows:

0. **Selection bar** (only while at least one game is selected), a `secondaryContainer` strip:
   `"{n} selected"` + a "HowLongToBeat lookup (n)" `TextButton` (disabled while a refresh runs) +
   an X `IconButton` that clears the selection.
1. **Search field** (`OutlinedTextField`, first item): label "Search games", leading `Search` icon,
   trailing X `IconButton` once non-empty. Filters both lists by case-insensitive name substring.
2. **HowLongToBeat controls:** "Refresh HLTB library" `FilledTonalButton` (becomes a spinner +
   "Refreshing…" while running) + "Force all" `OutlinedButton`, both disabled while a refresh runs,
   over a "Review HLTB matches ({n})" `TextButton`.
3. **Batch progress card** (only while a refresh runs): `"{done} / {total}"` (bold) beside a
   "Stop" `TextButton` (`PlayerStop` icon), over a determinate `LinearProgressIndicator`, then a
   fixed-height (96dp) scrolling log, newest first, of
   `"{game} — matched | needs review | no match | lookup failed"`. **Stop** cancels the sweep;
   there is no separate pause because a plain "Refresh HLTB library" afterwards resumes by itself —
   everything already fetched now falls inside the freshness window. ("Force all" restarts from
   scratch by design.) Before the first game reports —
   and when the sweep's target set turns out empty — it reads "Starting HowLongToBeat refresh…"
   with an indeterminate bar rather than a stalled `0 / 0`. The log covers only the period the
   screen has been observed: returning mid-run shows correct progress with a log resuming from that
   point.
4. **Section header** "Focus" (bold, titleMedium) — only if the section has rows — with that list's
   own **sort control** on the right: an `ArrowsSort` icon + the active key's name, opening a
   `DropdownMenu` of Playtime / Name / Recently played / XP contributed (the active one check-marked).
5. **Focus game row** (repeated), a full-width `Card` carrying `combinedClickable`, 12dp padding,
   horizontal layout over a **faint backdrop**: the game's Steam store header art
   (`.../steam/apps/{appId}/header.jpg`, derived from the appId — nothing stored), anchored to the
   right edge at 22% alpha and alpha-masked (`DstIn` horizontal gradient) so it dissolves before it
   reaches the text. Games whose header 404s render no backdrop and no placeholder.
   - 40dp square game icon (remote image, left; 8dp rounded, themed loading placeholder and
     controller fallback on error)
   - 12dp gap
   - Column: game name (bodyLarge) → caption `"{playtime} played"` → **completion progress** when a
     HowLongToBeat Completionist length exists (nothing at all when no length is known):
     - *under* the completionist length — `LinearProgressIndicator` filled to
       `playtime / completionist` in the gold accent over the default track ("still to play"),
       caption `"{playtime} / {completionist} to 100%"`
     - *past* it — the bar **rescales to the playtime**: the gold fill shrinks to
       `completionist / playtime` and the remainder — the excess hours — fills the track in
       `GoldOverrun` (`#8A431C`, the accent pushed darker and redder; `GoldOverrunLight`
       `#B4571F` in light mode). The bar is never empty, and the gold segment shrinking *is* the
       "how far past" signal. Caption `"{completionist} to 100% · played {n}%"`.
     - → HLTB status label → a **single-line** badge row: the achievement badge
       (`"{unlocked} / {total} achievements"`, ellipsized first if space runs out, or a gold
       "100% COMPLETED" pill) next to a bare `"{n} XP"` (never wrapped; the full "XP contributed"
       wording is the accessibility label)
   - Trailing: a `DotsVertical` `IconButton` ("Manage focus") → the Focus dialog. While selecting,
     it is replaced by a `Check` / `Checkbox` state icon.
   - Tap → **Game detail** (or toggles selection while selecting); long-press → toggles selection.
   - A selected row takes a `secondary` outline and `secondaryContainer` fill. Completion is marked
     by the gold pill alone — the row-level gold outline was dropped as too loud in a long list.
6. **Section header** "Your games" — only if the section has rows, with its own independent sort
   control (same four keys; defaults differ: Focus defaults to Name, Your games to Playtime,
   matching the DAO ordering they replaced). Each selection persists across visits.
7. **Your games row** (repeated): identical to a Focus row, except the HLTB status label appears
   only once there is something to report.
8. **No-matches row** (only when a filter matched nothing): `"No games match \"{query}\""` + a
   "Clear search" `TextButton`, rendered *inside* the list beneath the search field — never as a
   full-screen takeover, which would unmount the field that produced the query.

**Focus dialog** (Material 3 `AlertDialog`, from the 3-dot menu):
   - Title: "Add to Focus" (new) or "Remove from Focus" (existing)
   - Body: the game name in a sentence — no typed target is collected, completion lengths come from
     HowLongToBeat — plus this game's live HLTB status and a "Refresh HowLongToBeat" `TextButton`
     that forces a single-game lookup (disabled while one is in flight)
   - Confirm button: "Add" / "Remove"; dismiss button: "Cancel"

**Empty / alt states:**
- Not configured → centered Empty State, title "Steam not configured".
- No games at all yet → centered Empty State, title "No games yet", explaining sync is
  pending or the profile may be private. Keyed to the **unfiltered** library, so a search that
  matches nothing never reaches it.

**Note on the XP badge:** the row shows a bare `"{n} XP"`, but it reports the same inputs the
gamification engine uses — frozen
backfill + tracked session minutes, tapered against the game's completion length, plus its unlocked
achievements' rarity XP — so every row's badge sums to the player's real total XP. It is therefore
*not* proportional to the "{playtime} played" text above it, and `0 XP contributed` on a
long-owned game is correct for anyone who never imported their Steam history.

---

## Screen 3 — History

**Purpose:** play history as a day → game → session breakdown, replacing the old flat
"recent sessions" list and separate "daily stats" list with one structure (regroup-history).

**Layout:** single flat `LazyColumn` (no nested lazy lists — Compose can't nest one), 20dp horizontal /
16dp vertical outer padding, day groups sorted most-recent-first:

1. **Today's day group**, expanded by default, sits above everything else.
2. **Section header** "Daily stats" divider.
3. **Past day groups** (repeated), collapsed by default; at most 30 day-groups load initially.
4. **"Load older"** row at the end, widening the window by another 30 days.

**Day header row**, full-width `Card`, 10dp padding:
- Left: date (bodyLarge) over caption `"{minutes played} played"`, appending
  `" · {focus minutes} on Focus games"` only when those minutes > 0. This total is **the sum of
  the sessions listed beneath it**, not the stored `DailyProgress` total (the two can differ by a
  midnight-crossing session's post-midnight portion).
- Right: a `ChevronUp`/`ChevronDown` expand affordance (omitted for a day with nothing to
  expand), and a `CircleCheck` icon (accent-tinted) if that day's quest was met, otherwise a muted
  `CircleMinus` icon — the quest indicator is always the stored `DailyProgress` value, never the
  presented sum.
- Below, when the day has any: a row of achievement thumbnails (20dp, rounded) for every
  achievement unlocked that day across every game, up to 5, followed by a `"+N"` badge for any
  remainder. Omitted entirely on a day with no unlocks.

**Game row** (shown when its day is expanded), full-width `Card`, indented, 10dp padding:
- Left: game icon (shared `GameIcon` composable, also used by the Library) + name, weighted and
  ellipsized (a long title must not squeeze the trailing minutes text into an unreadable column).
- Right: that game's total minutes for the day, and an expand affordance.
- Rounded on every corner while collapsed; flat on the bottom while expanded, so the sessions panel
  below reads as this row's own dropdown content rather than a disconnected block.

**Sessions panel** (shown when a game is expanded): one `Card` directly beneath that game's row —
same horizontal margin, flat top meeting the game row's flat bottom, a distinct tonal
(`surfaceVariant`) background — containing one line per session:
- `"~3:00 PM · 2h 35m played"`, or `"· live"` appended while still open. Deliberately an
  approximate *start*, not a start–end range: showing two clock times invites subtracting them into
  a duration, which can disagree with the tracked minutes once Steam's own counter lags and reads as
  an arithmetic error rather than two honest, different measurements. The tilde and "played" wording
  are still deliberate — they mark the start as poll-quantized and the minutes as Steam's tracked
  count.

**Empty / alt states:**
- Not configured → centered Empty State, title "Steam not configured".
- No day groups at all yet → centered Empty State, title "No history yet".

---

## Screen 4 — Onboarding (pushed / first-run takeover)

**Purpose:** capture, validate, and encrypt the Steam Web API key + SteamID64 in-app — no
`local.properties` edit or rebuild. Reached as a full-screen takeover when unconfigured, or
pushed from the Home "Steam account" card's "Edit" action.

**Layout:** single scrollable column, 24dp padding, 16dp gaps. Header "Connect your Steam
account" + a "Step {1|2} of 2" label (accent). Two steps:

1. **Step 1 — API key:** explanatory body, an `OutlinedTextField` behind a password
   transformation with a Show/Hide toggle (label switches to "New API key (leave blank to keep
   current)" when editing existing creds), a "Where do I get a key?" `TextButton` with an
   `ExternalLink` icon (opens the Steam key page), and a full-width "Continue" button.
2. **Step 2 — SteamID:** two `FilterChip`s toggle the entry mode — "SteamID64" (raw) vs.
   "Profile URL". An `OutlinedTextField` (numeric keyboard for raw, URI keyboard for URL) with
   mode-appropriate label/placeholder. Inline **resolve feedback**: a `CircleCheck` (accent) +
   "SteamID {id}" on success, or an `AlertCircle` (error) + message on failure. A "Back"
   `TextButton` plus a full-width primary button that is "Verify"/"Resolve" until resolved, then
   "Finish" (shows a spinner while saving).

---

## Screen 5 — Game detail (pushed from Library)

**Purpose:** per-game achievement list — unlock state, rarity tier, and contributed XP per
achievement. Reached by tapping a game in the Library.

**Layout:** LazyColumn, 16dp padding. Bold `headlineSmall` game name header. When every
achievement is unlocked, a striking **"GAME COMPLETED"** banner `Card` in the gold accent
color (`Trophy` icon + "Every achievement unlocked"), reading as a level-up-tier milestone.
Then one `Card` per achievement:
- 40dp rounded achievement icon (remote, `Trophy` fallback / themed placeholder).
- Name (bodyLarge) + status caption: `"Locked"` (row dimmed to 50% alpha) or, when unlocked,
  `"{Tier} · +{xp} XP"` (accent-tinted), tier being COMMON/UNCOMMON/RARE/EPIC/LEGENDARY.

**Empty state:** if the game has no stored achievement data → centered Empty State titled with
the game name, body "No achievements to show for this game yet."

---

## Screen 6 — HowLongToBeat review (pushed from Library)

**Purpose:** resolve ambiguous HowLongToBeat matches. Games whose match was uncertain after a
refresh are listed with their candidate entries so the user can pick the right one.

**Layout:** LazyColumn, 16dp padding, 12dp gaps. One `Card` per game needing review: bold
game name (titleMedium), a "Choose the correct HowLongToBeat entry:" caption, then a list of
tappable candidate rows separated by `HorizontalDivider`. Each candidate row shows the
candidate name (bodyLarge) over its Completionist length (`"Completionist: 1h 20m"` or "No
Completionist length"). Tapping a candidate resolves the match and drops that game from the
list.

**Empty state:** nothing to review → centered Empty State titled "Nothing to review", body
"Games with an ambiguous HowLongToBeat match appear here after a refresh."

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

- This is a **3-tab mobile app** (phone-sized canvas) with three pushed sub-screens
  (onboarding, game detail, HLTB review), Material 3 look — rounded cards, a bottom tab bar,
  no top app bar currently (could be added).
- Card-heavy, single-column, vertically stacked info — no grids, no multi-column layouts.
- Only 2 data-viz elements: linear (horizontal) progress bars — no charts, rings, or graphs.
- Icons come from a single set (Tabler Icons, Compose port); there are no emoji glyphs.
- Every screen has a "sad path": not-configured and empty-data states are first-class, not
  afterthoughts — worth designing those explicitly rather than assuming happy path only.
