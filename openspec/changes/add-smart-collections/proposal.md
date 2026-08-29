# Smart Collections

## Why

Custom collections answer "what do I want to group?" Nothing answers "what is my library actually
telling me?" The app holds every fact needed — per-session play history, HowLongToBeat lengths,
achievement counts, playtime — and asks the player to notice the patterns in them unaided.

Those patterns are the whole point of a backlog app. A game with three hours in it, untouched for
six weeks, is a different thing from one never launched, which is different again from one sitting
at 85% of its main story. Each implies a different action, and today all three look identical in a
sorted list.

The most useful of these is the one the player cannot see at all: **which games could realistically
be finished tonight.** A never-started game with a four-hour main story is the answer to backlog
paralysis, and the app has both numbers and joins them nowhere.

These lists are derived, not owned. Membership is a consequence of the facts, so it cannot be
edited — and it changes with the calendar rather than with any user action or sync. A game becomes
dropped on a Tuesday because a month elapsed, not because anything happened.

## What Changes

- **Five derived collections**, computed rather than stored, appearing alongside custom collections
  in the Collections screen:

  | | Rule |
  |---|---|
  | **Quick wins** | never started, main story at or under 6 hours |
  | **Never started** | no recorded playtime from any source |
  | **Almost done** | at or past 80% of main story *and* 80% of achievements, not completed |
  | **Dropped** | over 1.5 hours played, not completed, not played in 30 days |
  | **Completed** | every achievement unlocked, or playtime at or past main story where a game has no achievements |

- **Almost done requires achievements, not just hours.** Playtime alone reads a forty-hour roguelike
  as nearly finished against a thirty-two-hour length while under half its achievements are
  unlocked, which is not a claim any player would recognise. Achievements are evidence of what was
  accomplished, so eighty percent of them is a condition of the list rather than a decoration on it.
  A game confirmed to have no achievements is judged on playtime alone; one whose achievements have
  never been fetched is excluded, because an unmet condition and an unknown one must not look alike.
- **Dropped reads Steam's last-played time, not only observed sessions.** Requiring a session the app
  itself watched excluded exactly the games most likely to be abandoned — the ones played long
  before Backlogium was installed. Steam already reports when each owned game was last played, so
  that is the date the rule uses, with an observed session taking over when it is more recent.
- **Completion is achievements-first with a playtime fallback**, and each member states which rule
  placed it there. A game with neither achievements nor a HowLongToBeat length is excluded rather
  than guessed at.
- **Membership is immutable** — no adding, removing, reordering, or renaming. The rules are fixed
  and stated on each list, so a member is always explicable.
- **Each list can be hidden**, individually, from the Collections screen.
- **A list with no members does not appear**, so a new library is not greeted by five empty rows.
- **Lists overlap deliberately.** A game 85% through its main story and untouched for two months is
  both almost done and dropped, and that combination is the most actionable thing the app could
  say about it.
- **Home shows them last, and read-only.** The derived lists appear beneath the custom collections
  on Home, separated by a horizontal dashed rule, in their fixed order. They cannot be reordered,
  edited, or removed there — the rule above them is what marks the boundary between the collections
  the player arranged and the ones the app worked out.

## Capabilities

### New Capabilities
- `smart-collections`: the derived collections, their rules and fixed thresholds, how completion is
  determined and disclosed, immutability, per-list visibility, their fixed position on Home, and how
  each behaves when the data a rule depends on is missing.

### Modified Capabilities
- `app-ui`: the Collections screen presents derived collections as a group distinct from custom
  ones, with their rules visible and their visibility controllable; Home presents the same lists as
  a fixed, unmovable group below the custom cards.

## Impact

- **No new tables and no migration.** Membership is derived on read from state the app already
  observes. `HomeViewModel` and `CollectionViewModel` already combine `gameRepository.library`,
  `achievementRepository.counts`, and session data for exactly this kind of derivation.
- **Affected code (new):** a pure `SmartCollections` object in `domain/` taking library games,
  per-game session summaries, achievement counts, and today, returning each list's membership —
  following the same shape as `CollectionSummary.derive`, `SessionDiffer`, and `Gamification`; a
  visibility preference on `SettingsDataStore`; the Collections-screen group and its controls.
- **Affected code (modified):** the Collections screen and the Home collections section.
- **No new query at all.** Last play comes from `Game.lastPlayedAt`, which the sync worker already
  writes from Steam's `rtime_last_played`, joined with the existing per-game
  `latestSessionAtByGame` and `trackedMinutesByGame` aggregates.
- **One derivation, two surfaces.** `SmartCollectionFeed` performs the single pass that Home and the
  Collections screen both read, so a rule cannot mean one thing in a Home card and another in the
  list it opens.
- **Custom collections are untouched.** No change to the `collections` table, to display order, to
  modes, or to any existing behaviour — including Home's drag-to-reorder, which continues to act on
  the custom cards alone. Derived lists are a separate group, below them.
- **Derived lists carry no mode.** Deadline and ordered-queue modes require a target date and a
  manual sequence respectively, neither of which a derived list can have. They present a member
  count and their rule, not a banner.
- **Time-dependent without being event-driven.** Membership changes at a day boundary with no sync
  and no user action. Computing on read makes that correct for free; materializing it would need a
  daily recompute and a staleness window.
- **Thresholds are fixed in this change.** `app-settings` already carries editable gamification
  rules with retroactive-effect disclosure, and these thresholds are the same shape — but five
  lists' worth of knobs is a lot of configuration to add before any of it has been used. A visible
  fixed rule beats a hidden adjustable one; making them tunable is a later decision informed by
  which single number actually chafes.
- **No network, no cloud, no permission.** Every input is already local.
