## Why

The only grouping mechanism today is a single binary `Game.isGoal` tag, which splits the Library into
"Focus" and "Your games" and nothing more. It cannot express *intent*: a deadline, a play order, a
completion goal, or an achievement-cleanup priority. Backlogium already pulls every signal a meaningful
group would need — playtime, achievement counts and rarity, HowLongToBeat lengths, and completion
progress — but offers no way for the player to say "these games matter to me right now, in this order,
against this deadline." Custom collections let the player turn their Steam library into self-authored
missions: Steam provides the source data, Backlogium provides the meaning.

## What Changes

- **Custom collections**: user-defined named game groups with a collection mode and manual game
  membership, surfaced on the **Home tab** as mission cards rather than as a new Library section.
- **Four collection modes**, each a coherent preset rather than a raw config matrix:
  - **Basic list** — a simple named group of games.
  - **Completion goal** — emphasizes completion progress and remaining achievements across members.
  - **Deadline goal** — adds a target completion date and a countdown banner.
  - **Ordered queue** — members are sequenced; the collection surfaces the next game to act on.
- **Mode-specific banners**, all derived from signals already stored per game (no gamification engine
  change): completion progress (from cached HowLongToBeat lengths and playtime), achievements remaining
  (from stored achievement rows), deadline countdown (target date minus today), and next-in-queue
  (the head of the ordered members).
- **New persisted state**: a `collections` table (id, name, mode, sort, optional target date) and a
  `collection_members` join table (collection id, app id, order index). Manual membership only.
- **Collection management surface**: a pushed sub-destination to create and edit a collection, add and
  remove games, and reorder members in ordered-queue mode.
- **A pure, JVM-testable derivation layer** for collection summaries, mirroring the stance of the
  `:gamification` module — no clocks, no I/O, no persistence; callers feed stored rows and render the
  returned banner values.

## Capabilities

### New Capabilities
- `custom-collections`: the collections data model (tables, membership, modes), the pure banner/progress
  derivation, and the persistence and repository behavior for creating, editing, and resolving
  collections and their members.

### Modified Capabilities
- `app-ui`: the Home screen gains a collections section rendering mission cards with mode-specific
  banners, and a new pushed sub-destination for collection management (create, edit, add/remove games,
  reorder).

## Impact

- **New code:** a `Collection` entity and `CollectionMember` entity, their DAOs, a `CollectionRepository`,
  a `CollectionViewModel`, the Home collections cards, and a collection-management screen; a pure
  banner-derivation component with plain-JVM unit tests.
- **Modified code:** `HomeScreen` / `HomeViewModel` gain a collections section; `BacklogiumDatabase`
  gains two tables (schema version bump + `Migration`); `AppModule` / `DatabaseModule` wiring; the
  `Destination` nav graph gains a collection route.
- **No gamification engine change.** Banners consume outputs the engine already produces
  (`Gamification.goalProgress`, stored achievement counts, cached `HltbData`) — exactly the read-side
  derivation posture the XP badge used.
- **No change to the existing Focus tag.** `Game.isGoal`, `QuestMode.GOAL_ONLY`,
  `DailyProgress.goalMinutesPlayed`, `observeGoalGames()` / `observeBacklog()`, and the Library's
  Focus / Your-games split are untouched. Collections are an additive organizational layer on Home, not
  a replacement for the tag; a game may be Focus and in a collection independently.
- **No Steam Web API boundary change.** Collections are pure local organization over data already
  synced; they issue no network calls and obey the existing offline-first rule.

## Non-goals

- **Derived / automatic membership rules** (e.g., "games with fewer than 5 achievements left"). These
  re-open the "bulk tagging" rejection from the `enhance-library` change and add sync-time membership
  drift; deferred to a later proposal once the manual foundation and its data model are proven.
- **Migrating the existing Focus tag into a default collection.** Considered and deferred: keeping
  `isGoal` as the Focus collection's membership flag would preserve `QuestMode.GOAL_ONLY` and the
  per-day goal-minute accounting, but it is a separate scoping decision best made after collections
  exist. This slice is purely additive.
- **Gamification engine integration beyond read-side derivation.** Scoping quests to a collection,
  "clear one game before the next" as an enforced quest rule, and collections as daily-quest authors
  are deeper integrations that re-open `QuestMode` and `DailyProgress` accounting; deferred.
- **Templates beyond the four modes** (Rotation, Focus Sprint, Achievement Cleanup as its own mode).
  The four modes cover the distinct banner kinds; more templates are additive later.
- **A separate Collections tab.** Collections surface on Home as mission cards, not as a fifth
  navigation destination, so the four-tab `app-ui` navigation contract is unchanged.
