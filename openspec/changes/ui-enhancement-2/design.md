## Context

Five independent items. The only shared property is size, so this document decides each one
separately rather than looking for a unifying abstraction that does not exist.

Relevant facts:

- `GameListDensityControl.kt:29` renders `Text(density.label)` inside a `TextButton`. The Library
  header (`LibraryScreen.kt:208-229`) is a `Row` of `SearchField(weight(1f))`, the density control,
  and `HltbMenuButton`. Only the search field has a weight, so it is the only thing that shrinks.
- The same control is used by the collection overview (`CollectionScreen.kt:382`), so a change to
  it lands on two surfaces.
- `LibrarySortKey`'s KDoc states the current design explicitly: *"Each key has one sensible
  direction rather than a togglable one."* This change overturns that, so the reason has to be
  recorded here rather than only in a diff.
- `GameListDensity` encodes its ladder as `visibleFields: Set<GameListField>`, and
  `isStrictSubsetOf` exists so the monotonicity property is mechanically checkable. Any change to
  what a density shows has to keep that test passing.
- `AnalyticsScreen.kt:840-858`: the rarity header `Row` is `Icon`, `Text("Achievement rarity")`,
  `Spacer(weight(1f))`, an optional `TextButton`, then the count `Text`. Three unweighted children
  compete for whatever the spacer does not take.
- `SettingsScreen.kt:329-357`: the Sync card is one `Row` with `SpaceBetween`, a left `Column` of
  two status lines and a right `Column` of a filled `Button` over a `TextButton`.

## Goals / Non-Goals

**Goals:**

- Every item is independently revertible. Nothing here depends on anything else here.
- No Room migration, so the change cannot break an upgrade or a restore.
- The density ladder stays mechanically provable as a strict subset chain.
- Older backups keep importing with unchanged behaviour.

**Non-Goals:**

- A shared "sortable list" abstraction spanning Library, collections, History, and Analytics.
- Touching collections' sort model.
- Any new information on any surface. Item 3 moves an existing figure into a density that was
  already rendering the underlying data.

## Decisions

### 1. The density control becomes an icon, and that is what fixes the search field

The obvious fix for the squeeze is to give the search field a minimum width, or to give the density
control a maximum. Both are wrong: they treat the symptom, and both leave a control whose width
depends on which option happens to be selected — so the header still reflows every time the user
changes density, just within tighter bounds.

An icon has a fixed footprint by construction. "Compact grid" and "List" become glyphs of the same
size, the header stops reflowing entirely, and the search field gets its full remaining width back
at every density. The squeeze is not fixed so much as made impossible.

The dropdown keeps its text labels — that is where the vocabulary is taught, and it has room. Each
menu item gains its icon alongside the existing label and check mark, so the glyph on the button is
learnable rather than guessable.

The three glyphs map to what they do rather than to what they are called: a stacked-rows glyph for
`LIST`, a 2×2 grid for `GRID`, a denser grid for `COMPACT_GRID`. The button carries the active
density's name as its `contentDescription`, so nothing is lost for screen readers or for anyone who
long-presses it.

*Alternative considered:* a segmented button showing all three icons at once, with the active one
selected. Rejected — it is wider than the current text button at its widest, which makes the search
field permanently smaller instead of only sometimes smaller. It also does not scale if a fourth
density is ever added.

### 2. Sort direction is a separate persisted axis, not new sort keys

Two shapes were available. Double the enum — `PLAYTIME_DESC`, `PLAYTIME_ASC`, and so on — or add a
direction alongside the existing key.

Doubling the enum is worse in a specific way that matters here: `LibrarySortKey` constants are
persisted *by name*, and its KDoc warns that renaming one silently resets that list. Eight
constants means eight persisted names and a migration path for the four that already exist in
users' DataStore. A separate `LibrarySortDirection` leaves every existing stored key valid and
adds a key whose absence means "the direction this app has always used."

That absence-is-the-old-behaviour property is what makes this migration-free. A user upgrading has
no stored direction, reads the per-key default, and sees the Library exactly as before.

**The default direction is per key, not global.** `NAME` defaults ascending; the other three default
descending. This is precisely the current fixed behaviour, re-expressed as a default rather than as
a law, so the change is invisible until the user touches the toggle.

*Why overturn the original decision at all:* the KDoc's reasoning — that each key has one sensible
direction — is true of the *default* and false of the *only*. "Which games have I barely touched?"
is a real question about a backlog app, and playtime-ascending is the only way to ask it. The
original decision was right to pick one direction; it was wrong to make it the only one.

**The toggle is a distinct control, not a fifth menu item.** The sort menu answers "by what"; the
direction answers "which end first". Folding the second into the first produces a menu where
picking "Name" and picking "Name (Z→A)" are peers, which they are not. The `SortControl` button
gains a direction chevron next to the existing `ArrowsSort` icon, and tapping the chevron flips
without opening the menu.

**Direction applies after search relevance, not before.** `sortedFor` already composes relevance
tier then sort comparator. Reversing must reverse only the comparator half — a search that put the
strongest match first must keep doing so, or inverting the sort would silently rank the *worst*
match first. This is the one place in this change where getting it wrong produces a wrong answer
rather than an ugly one, so it gets its own test.

### 3. Achievement counts extend to `GRID`; XP stays list-only

The density ladder currently drops "achievement and XP badges" together at the same rung. Splitting
them is what makes this item possible without breaking the strict-subset property:

```
              IDENTITY  PLAYTIME  COMPLETION  ACHIEVEMENTS  XP   PLAYING
LIST             *         *          *            *        *       *
GRID             *         *          *            *        -       *
COMPACT_GRID     *         -          -            -        -       *
```

Each row remains a subset of the row above it, so `isStrictSubsetOf` still holds and its test still
passes. `GameListField.BADGES` splits into `ACHIEVEMENT_COUNT` and `XP_CONTRIBUTION`.

XP does not follow. It is the quietest signal in the app by deliberate design — the existing KDoc
calls it "the quietest thing here" — and a grid cell has one line of body text under the name.
Spending it on achievements is the better trade: the count is what a completionist scans for, and
the "100% Completed" pill that replaces it at full completion is the single most striking element
the row has.

`COMPACT_GRID` gets nothing new. Its whole purpose is maximum games per screen, and it currently
shows identity plus the live signal only.

*Alternative considered:* show achievements at every grid density including compact. Rejected — it
would break the subset chain at the bottom rung and put a two-number badge under a name already
truncated to one line in a three-column layout.

### 4. The rarity count is pinned, and the button yields

The header's real problem is that the count — the shortest and least compressible child — is the
one with no protection, while the "Show rarest" button, which is both longer and optional, is
unbounded.

Invert it: the count declares `maxLines = 1` and `softWrap = false` so it can never break, and the
optional button takes `weight(1f, fill = false)` so it ellipsizes first. This is the same technique
`GameBadges` already uses to keep achievements and XP on one line — the codebase has an answer for
this shape of problem and it should be reused rather than reinvented.

Deliberately *not* chosen: abbreviating the count (`1.2k unlocked`). The figure is a lifetime total
a user might screenshot; rounding it to fix a layout bug is a presentation change smuggled in as a
fix.

### 5. The Sync card pairs each action with its status

The card presents two independent operations — a Steam sync and a full achievement reconciliation —
plus a genre-enrichment status that belongs to neither, laid out as two columns that force the
reader to match left to right by position. There is no position that makes that correct, because
"Genres: idle" describes a third thing that has no button at all.

Restructure as one row per operation, each with its own label, its own status line, and its own
trailing action:

```
┌──────────────────────────────────────────────┐
│  Steam library                                │
│  Last sync: 16 Aug, 14:20        [ Sync now ] │
├──────────────────────────────────────────────┤
│  Achievements                                 │
│  Full refresh of every game        [ Refresh ]│
├──────────────────────────────────────────────┤
│  Genres            fetching…                  │
└──────────────────────────────────────────────┘
```

Genre enrichment becomes a status row with no action, which is honest — it has no user-triggerable
control today, and presenting its status in the same visual slot as the two that do was the source
of the scatter.

**Both actions keep their current enable/disable semantics unchanged.** The existing card comments
record that `Sync now` is disabled while syncing because both paths enqueue under one unique work
name with `KEEP`, so a second tap silently does nothing. That is behaviour, not layout, and this
change must not touch it.

This section is sequenced last (see the proposal) because two other in-flight changes add controls
to this same section.

## Risks / Trade-offs

- **The density icon is less immediately legible than the word.** Mitigated by keeping labels in the
  dropdown and by the `contentDescription`. Accepted: the control is used repeatedly by the same
  person, so the one-time learning cost is paid once and the width is won every visit.
- **Sort direction doubles the states each Library list can be in**, from four to eight. The
  per-key defaults mean seven of the eight are reachable only deliberately, and the reversal is
  applied at one place in `comparatorFor`'s caller rather than spread across four comparators.
- **Item 5 collides with two in-flight changes.** Handled by sequencing rather than by coordination;
  if it is applied out of order, the result is a correct layout of the wrong set of controls, which
  is visible immediately and cheap to redo.

## Migration Plan

No Room migration. Two additive Preferences DataStore keys whose absence reproduces current
behaviour exactly. Backup gains two optional fields; an import without them applies the per-key
defaults, which is what an older export meant.

## Open Questions

None. All five items are decided above.
