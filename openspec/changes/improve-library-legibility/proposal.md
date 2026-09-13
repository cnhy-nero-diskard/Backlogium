# Library Legibility

## Why

Two things the Library should say at a glance, and does not.

**It never says how big it is.** Searching the app's UI sources for a library count returns nothing:

```
$ grep -rn "library.size\|games\.size" app/src/main/java/com/example/backlogium/ui/
(no library count on any surface)
```

The Library's two section headers read `Focus` and `Your games`, bare. Analytics summarizes play
across a window and never mentions how many games that window is drawn from. Settings' Data section
manages the database without stating what is in it. "How many games do I actually own?" is the most
basic question a library app can be asked, and Backlogium is the wrong place to ask it.

**It draws one kind of progress and writes the other.** A grid cell currently carries a full-width
progress bar for HowLongToBeat completion and a line of text for achievements:

```
   ┌──────────────────┐
   │   [ game art ]   │
   │ Hollow Knight    │
   │ 41h              │
   │ ▓▓▓▓▓▓▓▒▒▒▒▒▒    │  ← completion vs HLTB, a bar
   │ 34/63            │  ← trophies, text
   └──────────────────┘
```

A grid exists to be scanned, and a bar is scannable in a way a fraction is not. For a completionist
— the player the achievement figure is *for* — the app renders the less relevant of the two numbers
in the more legible form. `34/63` also demands arithmetic the bar does for free.

Both are the same complaint: the Library makes you read to find out what it already knows.

## What Changes

- **A library count wherever the library's scale is context**: on each Library section header, on the
  Analytics screen alongside its all-time figures, and in Settings' Data section. Not on Home, which
  holds progress content only.
- **The count says what it counted.** Steam's `GetOwnedGames` returns tools, utilities, and playtests
  alongside games, and the app currently treats every one as a game. The count is presented as the
  library as Backlogium sees it, not as an authoritative "games owned", and where a set of entries is
  excluded from a list the count states both figures rather than silently reporting the smaller.
- **Trophy progress becomes a bar wherever the achievement count is already shown** — the Library
  list row, the least dense grid, and the collection overview's member tiles — in a colour distinct
  from the completion bar directly above it.
- **The bar is a new *rendering* of an existing field, not a new field.** The display-density ladder
  requires that a denser view be a strict subset of a looser one, with nothing newly appearing. Adding
  a bar only to the grid would break that. It renders wherever `ACHIEVEMENT_COUNT` renders, and
  nowhere else, so the ladder is preserved exactly.
- **The count stays beside the bar.** `34/63` is the precise answer and the bar is the scannable one;
  the bar replaces nothing.
- **Missing achievement data draws nothing at all** — no empty bar, no zero-width fill. The app is
  consistent that missing trophy data must stay distinguishable from zero trophies, and an empty
  track reads as "none unlocked".
- **A fully-completed game keeps its existing treatment.** The gold `100% Completed` pill continues to
  replace the plain count, and no full bar is drawn beside it — a full trophy bar and a nearly-full
  one are indistinguishable at grid scale, which is exactly what the pill exists to fix.

## Capabilities

### Modified Capabilities
- `app-ui`: game lists and the collection overview render trophy progress as a bar wherever the
  achievement count is shown; the Library's section headers, the Analytics screen, and Settings' Data
  section state the library's size, with the basis of the count disclosed.

## Impact

- **Presentation only.** No entity, no DAO, no migration, no request, no preference. The counts are
  the length of lists already in hand, and the bar renders `achievementUnlocked` and
  `achievementTotal`, which `LibraryGameUi` and `CollectionMemberUi` already carry.
- **Affected code (modified):** `ui/library/LibraryScreen.kt` (section headers, the row and grid-cell
  badge lines beside the existing `CompletionProgress`), `ui/collections/CollectionScreen.kt`
  (`TrophyLabel` on member tiles), `ui/analytics/AnalyticsScreen.kt`, `ui/settings/`.
- **A colour decision, made against a palette with reserved meanings.** Gold is milestone and
  completion, its overrun shade sits in the bar directly above, vivid green is live presence, and
  steel blue is both the now-playing lane and the `RARE` rarity halo. The trophy bar needs a hue that
  collides with none of them, and the palette's documented reservations are the constraint, not a
  suggestion.
- **Two stacked bars in a small cell is the real risk.** A grid cell is narrow, and two full-width
  bars four pixels apart can read as one control or as a single split bar. Separation and labelling
  are part of the requirement rather than left to taste.
- **Touches renderers `add-library-recency-signals` also touches.** That change adds a corner recency
  badge to the same rows and cells. Neither blocks the other; whichever lands second inherits a
  slightly busier cell and should check the result rather than assume it composes.
- **Composes with `add-hidden-games` rather than pre-empting it.** That change introduces a hidden
  set, at which point a raw count would understate the library. The disclosure rule here is written so
  the count keeps meaning something once a hidden set exists.
