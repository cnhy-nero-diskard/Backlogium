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
  | **Almost done** | at or past 80% of main story, not completed |
  | **Dropped** | over 2 hours played, not completed, no meaningful session in 30 days |
  | **Completed** | every achievement unlocked, or playtime at or past main story where a game has no achievements |

- **A meaningful-session threshold of 15 minutes.** A launch shorter than that does not count as
  playing, so checking a setting or bouncing off a loading screen neither starts a game nor
  resumes a dropped one. This is the concept behind the wrinkle that motivated it — a game briefly
  relaunched after months is still dropped.
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
- **Nothing appears on Home.** Home's collection banners exist to surface intent the player chose;
  a derived observation has none.

## Capabilities

### New Capabilities
- `smart-collections`: the derived collections, their rules and fixed thresholds, the
  meaningful-session concept, how completion is determined and disclosed, immutability, per-list
  visibility, and how each behaves when the data a rule depends on is missing.

### Modified Capabilities
- `app-ui`: the Collections screen presents derived collections as a group distinct from custom
  ones, with their rules visible and their visibility controllable.

## Impact

- **No new tables and no migration.** Membership is derived on read from state the app already
  observes. `HomeViewModel` and `CollectionViewModel` already combine `gameRepository.library`,
  `achievementRepository.counts`, and session data for exactly this kind of derivation.
- **Affected code (new):** a pure `SmartCollections` object in `domain/` taking library games,
  per-game session summaries, achievement counts, and today, returning each list's membership —
  following the same shape as `CollectionSummary.derive`, `SessionDiffer`, and `Gamification`; a
  visibility preference on `SettingsDataStore`; the Collections-screen group and its controls.
- **Affected code (modified):** the Collections screen, and a session-summary query exposing each
  game's last meaningful session and meaningful session count.
- **A new query, not a new store.** Deriving "last meaningful session" per game needs one indexed
  aggregate over `sessions`, which already carries `minutes` per session and an `appId` index.
- **Custom collections are untouched.** No change to the `collections` table, to display order, to
  modes, or to any existing behaviour. Derived lists are a separate group in the same screen.
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
