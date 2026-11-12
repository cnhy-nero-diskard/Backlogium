# Backlogium — Screen Descriptor

Status snapshot: 2026-11-12 (`origin/master` at `ffc28ff2`).

Source: `app/src/main/java/com/example/backlogium/ui/**`. Android app, Material 3 (Compose),
using a custom "Steam-native dark" identity with dynamic (wallpaper-derived) color turned
off, so the look is identical across devices. Bottom navigation with 5 destinations (Home /
Library / History / Analytics / Settings), plus pushed sub-destinations reached from
them: **Onboarding + guided first-run setup** (from Settings, or as a first-run takeover
  that verifies credentials and offers an opt-in setup checklist), **Game detail** (from
Library), **HowLongToBeat review** (from Library), **Collection** (from Home), and
**Diagnostics** (from Settings). Every screen renders from local state only (offline-first)
and has an "empty" / "nothing to show" variant where the surface has no data.

## Design tokens

- **Theme:** Material 3, custom dark-first color scheme. Android dynamic color is **off**
  (`BacklogiumTheme(dynamicColor = false)`), so the palette below is the app's look on every
  device (not a wallpaper-derived fallback). A light scheme is retained for system light mode.
- **Palette — "Steam-native dark" (charcoal/navy surfaces + single gold accent):**
  - Dark scheme — background `#10141C`, surface `#171C26`, surface-variant `#232A38`,
    primary/accent (gold) `#E0A83A` on `#241A00`, secondary (steel-blue) `#7FA6C9`.
  - Light scheme — background/surface `#FBF8F1`, primary (gold) `#7A5A00`, secondary
    (steel-blue) `#2F5B7C`.
  - The single gold hue is enforced as **hue territory, not a milestone reservation**: gold is
    Material 3 `primary` and carries ordinary emphasis everywhere, but nothing else may be gold.
    The achievement rarity ramp (`enhance-game-detail`) reuses `Gold` at LEGENDARY and `SteelBlue`
    at RARE rather than minting new accents — `RarityCommon #8A93A3` and `RarityUncommon
    #6FAE7A` are the only new hues it adds.
  - **Tertiary container (enhance-now-playing)** — a hand-tuned steel-blue container/on-container
    pair for the Home now-playing card, since Material 3's auto-derived default would otherwise be
    an unrelated baseline purple: dark `#243B4C` on-container `#CFE4F0`; light `#D3E4F0`
    on-container `#12283A`.
  - **Live/active green (`playingIndicator`)** — the Library's "currently playing" row dot: dark
    `#4ADE80`, light `#15803D`. Distinct from both gold's territory and the steel-blue in-game
    lane, and vivid enough not to be mistaken for `RarityUncommon`'s muted sage.
- **Typography:** Material 3 type scale, with a bundled **display font (Orbitron, SIL OFL,
  `res/font/orbitron.ttf`)** applied to the large numeral moments only — `headlineMedium`
  (Home "Level N") and `headlineSmall` (Home streak count). All other styles stay on
  `FontFamily.Default`. Font is bundled (not a Downloadable Font) to preserve offline-first.
- **Shape:** Material 3 `Card` everywhere (rounded rect, default M3 elevation/shape); game-art
  thumbnails are clipped to an 8dp rounded square.
- **Icons:** a single icon library — **Tabler Icons** (Compose port,
  `br.com.devsrsouza.compose.icons:tabler-icons`). Nav bar: Home / DeviceGamepad / History / ChartBar / Settings;
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
plain surface color, 16dp horizontal / 10dp vertical padding, status-bar inset consumed.

Its own surface fill is **dropped (transparent) on Home, and on any screen painting a backdrop
wash** (game detail's header-art wash), so the shell background shows through it instead. On Home
that is what lets the header and the now-playing panel below it read as one continuous block: with
a `surface` fill the header ended in a hard color step against the panel's transparent top edge,
and that step read as a crease. Layout is identical either way.

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

Bottom navigation: 5 items, icon (Tabler) + label, one selected at a time:

| Destination | Icon (Tabler) | Label |
|---|---|---|
| Home | `Home` | Home |
| Library | `DeviceGamepad` | Library |
| History | `History` | History |
| Analytics | `ChartBar` | Analytics |
| Settings | `Settings` | Settings |

Content area is a `NavHost` that swaps between the 5 top-level screens; state is preserved when
switching tabs (standard save/restoreState nav behavior). Home is the start destination.

---

## Screen 1 — Home

**Purpose:** live play status, the next player action, level/XP, today's quest,
streak, and collection missions.

**Layout:** single scrollable column, 16dp outer padding, 16dp vertical gaps between cards,
full width.

1. **"Now playing" panel** (conditional, only while the player is in-game; enhance-now-playing) —
   Home's most visually prominent element while shown, and deliberately **not** a card: it is a
   full-bleed panel continuous with the profile header above it. Three things create that
   continuity, because an inset/shadowed/rounded card under a full-bleed header read as two
   unrelated stacked things:
   - **Full-bleed** — no side margins (the Home column is unpadded at this level; every *other*
     card keeps the 16dp inset via an inner column), no elevation, square top corners, generously
     rounded (24dp) bottom ones.
   - **Its top edge dissolves into the header** — a vertical gradient from the header's own
     `surface` color into the M3 **tertiary container** steel-blue, so there is no visible seam.
   - **A shell-wide wash** — while in-game Home reports its tint to the shell's `accentColor`
     backdrop (the same hoisted mechanism the game detail screen's header-art wash uses), which
     paints *behind the profile header too*; the header goes transparent whenever such a wash
     exists, so header and panel sit in one continuous tinted field.

   The steel-blue lane is deliberately not the gold "primary container" — gold stays reserved for
   milestone moments. Over that, a slow flowing sheen sweeps horizontally so the panel reads as
   *live* rather than merely colored, and the running game's **store header art** fills the space
   right of the text as a faint (20% alpha) backdrop — the Library row's `GameBackdrop` treatment,
   but alpha-masked on *both* axes (two multiplied `DstIn` ramps): horizontally so it dissolves
   before the game name, vertically so it stays clear of the panel's top edge, which a hard-starting
   image would otherwise turn back into a crease. Derived from the appId
   (`SteamIconMapper.headerUrl`), so nothing extra is fetched or stored; a game whose art 404s — or
   whose running-game id didn't parse — simply renders no backdrop and no placeholder.

   > **Not a Steam logo, deliberately.** A faint Steam brand mark was considered for this backdrop
   > and rejected: Valve's [branding guidelines](https://partner.steamgames.com/doc/marketing/branding)
   > require the logo to "stand alone" and not be combined with other graphics, words, or design
   > features, and the [Web API Terms of Use](https://steamcommunity.com/dev/apiterms) forbid
   > presenting Steam data so the app appears endorsed by or affiliated with Valve — which a large
   > decorative watermark would imply. The small `BrandSteam` glyph labelling the Settings "Steam
   > account" row is a different, functional use and stays. Contents: a 64dp game icon (themed controller fallback), the
   running game's name (bold, headline style), and a "Playing for {N}" caption ticking every second
   from a persisted session-start timestamp (client-side, no network) — formatted by
   `UiFormat.liveElapsed` (`"23s"` / `"5m 23s"` / `"2h 35m"`: seconds below the hour mark, since a
   visibly advancing number is what makes it read as live; dropped above it, where they are noise).
   Worded as time *since detection*, not an exact launch time, since detection can lag by up to the
   periodic sync's interval. There is no repeated "Now playing" label — the header directly above
   already reads "In game", so the panel continues that thought rather than restating it (the label
   survives as an accessibility description only). The sheen honors the system's reduced-motion
   setting via the shared `rememberReducedMotion()` (renders statically; the ticking timer and the
   panel's presence still carry the "live" meaning on their own). Adds no layout, and runs no
   animation, when not in-game. A foreground service keeps this state current in the background and
   posts a matching ongoing "Playing X · Nm" notification while in-game (cleared when the game ends).
2. **Error banner** (conditional, only when a sync error exists) — a `Card` tinted with the
   M3 "error container" color, containing the error message and Retry. Retry is
   disabled and shows a spinner during sync; a successful sync clears the error.
   Home also shows a retained-content updating indicator, conditional live-monitor
   availability guidance, and earned quest feedback. A next-action slot follows
   these notices, prioritizing an actionable Focus/collection mission while idle.
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
6. **Collections** — player-arranged mission cards with collection navigation,
   creation, gap planning, and reordering, below the progression cards.
7. **Smart collections** — derived lists below the custom collections, in a fixed
   order, when available.

Account credentials and routine manual sync are under Settings → Account & sync.
Steam history import/reset is under Settings → Data & privacy. Home's sync action
is the conditional error retry described above.

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
2. **HowLongToBeat entry points:** long-press/select games for a targeted lookup,
   or use a per-game lookup/review action in list and grid layouts. Library Tools
   contains display density, Select games, Refresh uncovered HLTB data, and Force
   refresh all HLTB data. The latter two explicitly build a selection from uncovered
   or all loaded games; they do not schedule a background library sweep. The Match
   Center entry is available for an actionable ambiguous or unmatched queue, even
   when the ambiguous-review count is zero.
3. **Selection lookup progress** (only while the explicit lookup runs): completed /
   total count, determinate progress, Stop, and a transient newest-first outcome log.
   Before totals are available it shows an indeterminate starting state. Stop keeps
   already fetched results; explicitly select remaining games to continue. Progress
   does not imply an automatically resumable freshness-based library sweep.
4. **Section header** "Focus" (bold, titleMedium) — only if the section has rows — with that list's
   own **sort control** on the right: an `ArrowsSort` icon + the active key's name, opening a
   `DropdownMenu` of Playtime / Name / Recently played / XP contributed / Added recently (the active one check-marked).
5. **Focus game row** (repeated), a full-width `Card` carrying `combinedClickable`, 12dp padding,
   horizontal layout over a **faint backdrop**: the game's Steam store header art
   (`.../steam/apps/{appId}/header.jpg`, derived from the appId — nothing stored), anchored to the
   right edge at 22% alpha and alpha-masked (`DstIn` horizontal gradient) so it dissolves before it
   reaches the text. Games whose header 404s render no backdrop and no placeholder.
   - 40dp square game icon (remote image, left; 8dp rounded, themed loading placeholder and
     controller fallback on error), with a small **live indicator dot** (`playingIndicator` green,
     top-end corner; enhance-now-playing) overlaid whenever Steam's live presence reports this
     exact game as the one currently running — in whichever section it appears in, without
     reordering the list. No dot at all when no game is running or the running game can't be
     matched to one in the library.
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
   control (same five keys; defaults differ: Focus defaults to Name, Your games to Playtime,
   matching the DAO ordering they replaced). Key and direction persist independently
   for each list. Added recently defaults to newest first using Backlogium's recorded
   first observation, not Steam purchase time; unknown dates stay last. Recently
   played uses Steam's rolling two-week activity, a separate measure.
7. **Your games row** (repeated): identical to a Focus row, except the HLTB status label appears
   only once there is something to report.
8. **No-matches row** (only when a filter matched nothing): `"No games match \"{query}\""` + a
   "Clear search" `TextButton`, rendered *inside* the list beneath the search field — never as a
   full-screen takeover, which would unmount the field that produced the query.

**Focus dialog** (Material 3 `AlertDialog`, from the 3-dot menu):
   - Title: "Add to Focus" (new) or "Remove from Focus" (existing)
   - Body: the game name in a sentence — no typed target is collected, completion lengths come from
     HowLongToBeat — plus this game's live HLTB status, a "Choose match" `TextButton` when the
     lookup needs review, or a "Change match" `TextButton` when it is resolved. Either opens a
     library-hosted `ModalBottomSheet`; Change match performs a fresh non-persisting lookup so an
     existing match is not overwritten before the user chooses. The sheet shows every candidate's
     themed cover-art placeholder, name, and Completionist length in an internally scrolling list;
     it shows progress while looking up and disables selection until the lookup completes. A
     "Refresh HowLongToBeat" `TextButton` forces a single-game lookup (disabled while one is in flight).
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

**Purpose:** the game itself — a summary of what the app knows about it — above its achievement
list, with unlock state, rarity tier, contributed XP, description, and unlock rate per
achievement. Reached by tapping a game in the Library.

**Layout:** LazyColumn, 16dp horizontal padding, 16dp vertical content padding.

**Summary section** (header `Card`, always present — a game with no achievement data still shows
its own information):
- 120dp store header art banner, cropped (absent rather than glyph-filled when it fails to load).
- 40dp game icon + bold `titleMedium` name, over `"{playtime} played"`. When history was
  imported, a second caption splits it: `"{tracked} tracked · {imported} imported"` (omitted
  otherwise, where it would only restate the total).
- Completion/XP caption: `"{unlocked}/{total} achievements · {xp} XP"` (the achievement half
  omitted when the game has none). The XP figure is the Library's own `LibraryXp` derivation,
  so the two screens cannot disagree.
- Current player count caption, `"{count} playing now"` (locale-grouped, e.g. "1,206,380"): polled
  from Steam's `GetNumberOfCurrentPlayers` every 30 seconds while this screen is open, no API key
  required; polling stops the moment the screen is left. Omitted entirely — no zero, no dash, no
  spinner — while the first fetch is in flight, if a poll fails, or if Steam has no count for the
  app; never persisted, so it's re-fetched from scratch on every visit.
- **HowLongToBeat** block when at least one length resolved: a bold `labelMedium` heading, then
  a label/value row per known length (Main Story, Main + Extra, Completionist, All Styles). Each
  is omitted individually when unknown and the whole block is gated on at least one being
  present — no zeros, no dashes, no placeholders.

**Rarity Standing section** (directly below the summary when achievement rows exist):
- A compact, dynamically tinted `Card` headed **"Rarity standing"** with a trophy icon and a
  `✦ {tier}` marker. The color tier follows the same percentile cut points as achievement rarity:
  COMMON at 50%+, UNCOMMON at 20%+, RARE at 5%+, EPIC at 1%+, and LEGENDARY below 1%. A useful
  provable ceiling is the accent-colored `"Top {ceiling}% or better"` headline; the completionist
  variant says `"At most {ceiling}% of owners have completed the game"`.
- Inline icon stats show `CircleCheck {unlocked}/{total} earned` and `User {average} avg`, with
  the values colored by the rarity accent and secondary steel-blue accent. When no bound can be
  derived or it is at least 50%, only these compact stats remain.
- Short caveat, always present: `"Steam owners • includes unplayed copies"`. The section is shared
  by the full game-detail destination and the collection game-detail overlay because both render
  the same `GameDetailList`.

When every achievement is unlocked, a striking **"GAME COMPLETED"** banner `Card` in the gold
accent color (`Trophy` icon + "Every achievement unlocked"), reading as a level-up-tier milestone.

**Sort control:** compact row above the list — an `ArrowsSort` icon plus two `FilterChip`s,
**Recent** (default, most recently unlocked first) and **Rarest**. Transient: a lens, not a
preference, so it resets to Recent on the next visit. Locked achievements group *after* unlocked
ones in both orders.

Then one `Card` per achievement:
- 40dp rounded achievement icon (remote, `Trophy` fallback / themed placeholder).
- Name (bodyLarge).
- Description (bodySmall, muted) beneath the name. When Steam withholds it for a hidden,
  not-yet-unlocked achievement → `"Hidden achievement"` instead; once unlocked and described, it
  renders normally. A row with neither (stored before descriptions were retained, not yet
  re-fetched) shows nothing rather than an empty bubble.
- Status caption: `"Locked"` or, when unlocked, `"{Tier} · +{xp} XP"` (accent-tinted), tier being
  COMMON/UNCOMMON/RARE/EPIC/LEGENDARY — plus the unlock rate, `"0.8% of players have this"`.
  The percent shown is the one that produced the tier beside it (the frozen snapshot), falling
  back to the live global percent only for locked rows, which have no snapshot; so a Legendary row
  can never read "6.0% of players". Omitted entirely when neither percent is known.
- Locked rows dim to 50% alpha as a whole row, keeping "locked" one signal rather than three
  competing muted greys now that the row also carries a description and a rate.

**Empty state:** a game with no stored achievements keeps its summary and replaces the list with
the caption "No achievements to show for this game yet." — no whole-screen takeover, since the
screen has the game's own information to show regardless.

---

## Screen 6 — Settings

**Purpose:** the app's administration surface: Steam account, manual sync, live monitor, data
import, backup/restore, and local rule configuration. Everything renders from locally stored state,
so the screen remains usable offline; when credentials are unconfigured, the account card becomes
the route into onboarding.

**Layout:** four summary groups open pushed detail destinations. Loading uses a
stable summary rather than an empty surface.

1. **Account & sync** — Steam account/onboarding, guided setup, manual sync,
   full achievement refresh, and app updates, with busy states and progress.
2. **Gameplay** — daily quest, live monitor, Focus-related rules, hidden/shared-game
   management, and the Match Center shortcut.
3. **Data & privacy** — Steam history import, offline assets, shared completion data
   and contribution export, authenticated cloud history acquisition and confirmed
   re-filing, backup export/import, and automatic snapshots.
4. **Advanced & diagnostics** — rule constants with confirmation and the pushed
   diagnostics surface with sync runs, request breakdowns, and presence decisions.

Cloud history acquisition exposes the selected range, bounded continuation,
completion state, and separate confirmation before applying it. Recovery activity
retains unknown-time or estimated-date disclosures rather than implying exact
session boundaries.

---

## Screen 7 — HowLongToBeat review (pushed from Library)

**Purpose:** resolve ambiguous (`NEEDS_REVIEW`) and unmatched (`UNMATCHED`)
HowLongToBeat entries. Candidate selection, broader title search, and validated
manual HLTB links allow the player to recover a match deliberately.

**Layout:** Match Center tracks the selected game through queue changes and
allows explicit candidate selection or Skip for this review session. Matching or
skipping advances to the next unprocessed game; completion does not wrap back to
deferred titles. Review skipped/reset actions restore access within the session.
Selection survives configuration recreation and temporary game-detail return.
Library list/grid lookup actions and Settings → Gameplay provide entry points.
Skipping leaves the stored match unresolved.

**Match states and recovery actions:**

- Candidate cards use an adaptive grid. Select a candidate to persist the match;
  broader-search candidates carry a reminder to verify the game.
- An `UNMATCHED` game with no candidates offers Try broader search, which relaxes
  edition, subtitle, and numeral variants with at most three additional queries.
  Searching shows progress; exhausted results suggest a manual link; failures
  show their failure class with Retry and Dismiss.
- Both ambiguous and unmatched entries offer manual HLTB-link input. Validation
  errors stay beside the input. Preview link fetches the entry with loading status;
  missing pages have a specific message, and request failures offer Retry/Clear.
  The preview compares the Steam title with the HLTB title and completion lengths.
  Only explicit confirmation saves it; Dismiss closes the preview without saving.

**Empty / loading states:** no selected game while loading shows a spinner. An
empty actionable queue shows Nothing to review with Done. A pass completed with
skipped games shows the skipped-session message and Review skipped as well as
Done. A game-scoped route finishes when that game actually leaves the repository
queue; skipping alone does not resolve its stored match.

---

## Screen 8 — Analytics (top-level)

**Purpose:** show how playtime, sessions, streaks, and achievements accumulate over a selected
period. The screen is derived from local Room history and remains usable without network access.

**Layout:** a vertically scrolling column with a play snapshot card, an analytics-window selector,
an active-days/all-days chart toggle, and cards for daily playtime, streak summary, session
insights, time-of-day pattern, achievement rarity, and most-played games.

Windows include rolling **2 weeks**, **30 days**, and **90 days**, plus calendar **1 month** and
**1 year** views. Earlier windows can be selected while tracked history still covers them. Tapping
a daily chart bar reveals its exact total and quest status.

**Empty state:** a configured account with no data in the selected window keeps the summary and
shows "No analytics in this window" with guidance to play and sync.

---

## Screen 9 — Collection overview and editor (pushed from Home)

**Purpose:** organize owned games into local collections and optionally plan a completion or
deadline goal without introducing a server dependency.

The overview shows the collection accent, mode, description, member count, game list, and the
derived pacing/deadline plan when applicable. Deadline collections use a selected HowLongToBeat
basis (Main Story, Main + Extra, Completionist, or All Styles), recent personal pace, remaining
work, and capacity/shortfall signals. A member can open the existing game-detail presentation
without leaving the collection flow.

The editor supports name/description, one of four modes (Basic, Completion goal, Deadline goal,
or Ordered queue), mode-appropriate sorting, accent, deadline, game add/remove, manual member
ordering, and done marks for ordered queues. Saving persists the collection and memberships as one
local edit; deleting requires confirmation.

---

## Screen 10 — Diagnostics (pushed from Settings)

**Purpose:** provide a developer-facing view of the local sync and live-presence decisions. It
does not read Firestore or replace the user-facing Home/Settings state.

The list shows recent sync outcomes, triggers, durations, request counts, and presence decisions.
Selecting a run opens its endpoint/request breakdown and game counters. An empty installation
shows "No sync runs recorded yet" and "No presence decisions recorded yet."

---

## Shared component — Empty State

Used by screens that expose "not configured" / "no data" variants.
Full-screen, centered both axes, 32dp padding:
- Title text (titleLarge, centered)
- 8dp gap
- Message/body text (bodyMedium, centered)

No icon/illustration in the current implementation — just two lines of centered text. This
is a natural place to add an illustration if regenerating with more visual polish.

---

## Notes for regenerating in Claude Design

- This is a **5-destination mobile app** (phone-sized canvas) with five pushed sub-screens
  (onboarding, game detail, HLTB review, collection, diagnostics), Material 3 look — rounded cards, a bottom tab bar,
  no top app bar currently (could be added).
- Card-heavy, single-column, vertically stacked info — no grids, no multi-column layouts.
- Analytics adds a hand-rolled Canvas bar chart and compact time-of-day bars; other progress
  indicators remain linear, with no rings or third-party chart dependency.
- Icons come from a single set (Tabler Icons, Compose port); there are no emoji glyphs.
- Every screen has a "sad path": not-configured and empty-data states are first-class, not
  afterthoughts — worth designing those explicitly rather than assuming happy path only.

## Discovery and recovery additions

Library search includes cached wishlist title matches with wishlist labels and
appropriate store/detail actions. Wishlist read failures remain distinct from an
empty result. Added recently uses Backlogium's first-observed arrival date, not a
Steam purchase date. Shared-game filters and hidden-game management keep playable
sources and visibility explicit. Library visit context survives temporary detail
navigation; a fresh visit starts with the default discovery state.

Home prioritizes active now-playing, then actionable Focus/collection missions.
Personal Pace and gap-plan suggestions support planning around a future title/date,
using a reliable pace profile or an explicit one-off budget.

History and Analytics include recovered activity with separate quantity and timing
certainty. Unknown-time activity has no synthetic start/end; multi-day estimates
retain their allocation explanation. Pending date-unknown credit remains distinct
from dated activity, and corrective placement does not award duplicate progress.
