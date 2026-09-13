## Context

The density ladder is not a style choice; it is a proved property. `GameListDensity` encodes visible
fields as data, and `isStrictSubsetOf` exists so the ladder can be asserted rather than assumed:

```kotlin
fun GameListDensity.isStrictSubsetOf(looser: GameListDensity): Boolean =
    this != looser && looser.visibleFields.containsAll(visibleFields)
```

The spec states the consequence directly: *"the information shown per game is a subset of what the
previous density showed, with nothing newly appearing."* So "add a bar to grid view" cannot be taken
literally — a bar present in `GRID` and absent from `LIST` is new information appearing as density
increases, and breaks the one property the ladder guarantees.

The field it would render already exists at both densities. `ACHIEVEMENT_COUNT` was deliberately
split out from `XP_CONTRIBUTION` for exactly this kind of reason, and its KDoc says so: *"the count
is what a completionist scans for and a grid cell has room for it."* The bar is that field drawn
better, at every density that already shows it.

The other half is simpler and stranger. Nothing in the app counts the library:

```
   Library     "Focus"        "Your games"      ← no counts
   Analytics   6 figures, none about library size
   Settings    Data section manages a database it never describes
   Home        progress content only — correctly excluded
```

## Goals / Non-Goals

**Goals:**

- Make the library's size answerable from inside the app.
- Make trophy progress as scannable as completion progress, at the densities that already show it.
- Preserve the density ladder's strict-subset property exactly.
- Keep missing data distinguishable from zero, everywhere it already is.

**Non-Goals:**

- Defining an authoritative "games owned". Steam's answer includes tools and playtests, and the app
  is not going to adjudicate that here.
- Filtering or hiding anything. `add-hidden-games` owns that.
- A new density, a new field, or a change to which fields any density shows.
- Trophy progress on surfaces that do not already show the achievement count — the densest grid keeps
  showing neither.
- Aggregate trophy progress on collection summaries. That already exists.

## Decisions

### 1. The bar is a rendering of `ACHIEVEMENT_COUNT`, not a new field

```
   LIST         identity · playtime · completion bar · trophy bar + count · XP badge
   GRID         identity · playtime · completion bar · trophy bar + count
   COMPACT_GRID identity                                                        (unchanged)
```

Nothing is added to `visibleFields`, `isStrictSubsetOf` still holds, and the assertion that proves
the ladder needs no amendment. Anywhere `AchievementCountLabel` renders today, the bar renders too;
anywhere it does not, nothing changes.

This also settles the collection overview without a separate argument: `TrophyLabel` on a member tile
is the same field on a surface that shares the ladder, so it gets the same treatment.

### 2. The count stays, and the bar is added beside it

Replacing `34/63` with a bar trades a precise answer for an approximate one. The two do different
jobs — the bar is for scanning a grid, the fraction is for the game you stopped on — and a completionist
wants both. The bar goes above the count on the same rung, so the cell gains a bar and not a line of
text.

### 3. Colour, against a palette with reservations

The palette documents its reservations explicitly, and every obvious candidate is taken:

| Hue | Already means |
|---|---|
| `Gold` | milestone — level-up, streak, the completed banner |
| `GoldOverrun` | the excess past a HowLongToBeat length — **in the bar directly above** |
| `PlayingIndicator` green | currently playing |
| `SteelBlue` | the now-playing lane, and the `RARE` rarity halo |
| `RarityUncommon` sage | deliberately muted so it does not compete, and near the live green |

`RarityEpic` violet is the strongest remaining candidate: it is already achievement-coded, sits far
from gold and from green in hue, and is saturated enough to read at bar scale. Reusing a rarity token
rather than minting a sixth colour also follows the precedent the rarity ramp set when `RARE` and
`LEGENDARY` reused existing tokens instead of adding two more.

The constraint that actually decides it is adjacency: the trophy bar sits four pixels under a bar
that is gold, or gold-and-rust when a game is past its length. Whatever hue is chosen must be
unmistakable against both states of the bar above it, and that has to be checked in the cell rather
than in a palette file.

### 4. Two stacked bars need separating, not just colouring

```
   ▓▓▓▓▓▓▓▓▒▒▒▒▒▒▒▒   completion   ← gold, or gold + rust past the length
   ▓▓▓▓▓░░░░░░░░░░░   trophies     ← distinct hue
   34/63
```

At grid width these are two thin full-width rules almost touching. Colour alone is not enough —
colour alone is never enough — so each bar is identified in the announced description, and the pair
is given enough separation that they do not read as one split control. A player with a colour-vision
deficiency must still be able to tell which bar is which.

### 5. A completed game keeps its pill and gets no bar

The existing treatment replaces the plain count with a gold `100% Completed` indicator, and it exists
because a full bar and a 62-of-63 bar are indistinguishable at a glance. Drawing a full trophy bar
next to the pill would restore exactly the ambiguity the pill removed, and would spend gold twice in
one cell.

So at 100%: the pill, no bar. Below 100%: the bar and the count.

### 6. Missing data draws nothing

An empty track reads as "none unlocked". The app is consistent that missing trophy data stays
distinguishable from zero trophies — `custom-collections` requires it of the completion-goal banner,
and the Library row already omits the count entirely rather than showing `0/0`. The bar follows: no
data, no bar, no placeholder.

### 7. The count is presented as the app's view, not as truth

`GetOwnedGames` is called with `include_appinfo=1` and `include_played_free_games=1`, and returns
SteamVR, Wallpaper Engine, benchmark suites, and playtests as games. A player who reads "912 games"
and knows they own about 800 will conclude the app is wrong, and they will be more right than the
app.

So the count is framed as the library as Backlogium holds it. And it is written to survive
`add-hidden-games`: once a hidden set exists, a surface showing a filtered list states both the
shown count and the total, so hiding nine tools does not silently shrink the library by nine.

### 8. Where the count goes, and where it does not

| Surface | Count | Why |
|---|---|---|
| Library section headers | **yes** | where scale is being scanned; per section, so the two answer separately |
| Analytics | **yes** | the all-time rarity breakdown already describes the whole library; scale is missing context |
| Settings → Data | **yes** | it manages a database and currently never says what is in it |
| Home | **no** | Home presents progress content only, by requirement |
| Collection overview | **no** | member count already exists there |

## Risks / Trade-offs

- **A cell with two bars, a count, a playtime, a name, and — after `add-library-recency-signals` — a
  corner badge, is close to full.** → The bar replaces no text but adds only a few pixels of height.
  If the cell becomes crowded, the honest correction is which rung to drop at `GRID`, decided by the
  ladder, not by squeezing.

- **Reusing `RarityEpic` gives one token two meanings** — an epic-tier halo and trophy progress. → The
  two never appear together: the halo is per achievement on game detail, the bar is per game in a
  list. Judged a smaller cost than a sixth palette entry, and reversible if it reads wrong.

- **A count invites disputes about what a game is.** → Deliberately not adjudicated. The count says
  what it counted and defers the definition to `add-hidden-games`, which is the change that will
  actually have to answer it.

- **Three surfaces showing a count can drift.** → All three read the same library flow; the risk is a
  fourth added later against a different source. Worth one shared derivation rather than three call
  sites each taking `.size`.

- **Bars are noise if the player does not care about achievements.** → Confined to the densities that
  already show the count, so the densest grid stays clean. A player who wants less detail already has
  the control for it.
