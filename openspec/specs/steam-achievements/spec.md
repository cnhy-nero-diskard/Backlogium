## Purpose

Fetches every library game's per-player Steam achievement unlock data and global unlock
percentages, gating that fetch by data freshness so routine syncs stay cheap. Persists
achievements keyed by game and achievement id, capturing a first-unlock rarity snapshot
that never drifts on later syncs even as the live global percentage changes. Feeds
unlocked achievements — using each one's rarity snapshot — into the gamification engine's
XP recompute so they contribute tiered XP to the player's total.

## Requirements

### Requirement: Fetch Steam achievement data
The system SHALL fetch, from Steam, each library game's per-player achievement unlock
state and the global unlock percentage for each of that game's achievements, and MAY fetch
the game's achievement schema for display names and icons.

#### Scenario: Fetching a game's achievements
- **WHEN** a library game's achievement data is stale or missing
- **THEN** the system requests that game's per-player unlock state and global unlock
  percentages from Steam and stores the results

#### Scenario: Game has no achievements
- **WHEN** a fetched game exposes no achievements
- **THEN** the system records that the game has no achievements and does not treat it as an error

#### Scenario: Achievement fetch fails for a game
- **WHEN** a game's achievement request fails (private profile, no stats, or a transport error)
- **THEN** that game's achievement fetch is skipped without failing the overall sync, and any
  previously stored achievement data for that game is left intact

### Requirement: Freshness-gated achievement sync
The system SHALL fetch achievements for every game in the library, regardless of play
history or goal tagging, and SHALL refetch a game's achievements only when its stored data
is older than a freshness threshold or absent.

#### Scenario: Stale game is refreshed
- **WHEN** a library game's stored achievement data is older than the freshness threshold
- **THEN** its achievements are refetched on the next sync

#### Scenario: Fresh game is not refetched
- **WHEN** a library game's stored achievement data is within the freshness threshold
- **THEN** it is not refetched on that sync

### Requirement: Persist achievements with a first-unlock rarity snapshot
The system SHALL persist each achievement keyed by its game and achievement id, storing its
unlock state, unlock time when available, the current global unlock percentage, and a rarity
percentage snapshotted at the first sync that observes the achievement as unlocked with a
known global percentage. The snapshot SHALL NOT change on later syncs.

#### Scenario: Snapshot taken at first observed unlock
- **WHEN** a sync first observes an achievement as unlocked and a global unlock percentage is available
- **THEN** the system stores that percentage as the achievement's rarity snapshot

#### Scenario: Snapshot is stable against later drift
- **WHEN** a later sync reports a different global unlock percentage for an already-snapshotted achievement
- **THEN** the stored rarity snapshot is unchanged, while the current global percentage is updated for display

#### Scenario: Still-locked achievement has no snapshot
- **WHEN** an achievement has never been observed unlocked
- **THEN** it has no rarity snapshot

### Requirement: Retain achievement descriptions
The achievement fetch SHALL retain each achievement's description and its hidden flag from Steam's
achievement schema, so the UI can present them without an additional network call.

#### Scenario: Description stored
- **WHEN** a game's achievement schema is fetched
- **THEN** each achievement's description and hidden flag are stored alongside its display name and
  icon

#### Scenario: Description absent from the schema
- **WHEN** Steam supplies no description for an achievement
- **THEN** no description is stored for it and the fetch does not fail

#### Scenario: Existing achievements not force-refreshed
- **WHEN** achievement rows were stored before descriptions were retained
- **THEN** they are not eagerly re-fetched, and their descriptions populate on the game's next
  natural schema fetch

### Requirement: Feed achievement XP into gamification
The system SHALL build the gamification engine's achievement inputs from stored
achievements — using the rarity snapshot as each achievement's rarity input — and pass them
into the XP recompute so unlocked achievements contribute XP to the player's total.

#### Scenario: Unlocked achievements contribute XP
- **WHEN** the gamification values are recomputed and the player has unlocked achievements with a rarity snapshot
- **THEN** the recompute includes those achievements so their tiered XP is added to the player's total XP

#### Scenario: Locked or un-snapshotted achievements contribute nothing
- **WHEN** an achievement is locked, or unlocked but without a rarity snapshot
- **THEN** it contributes zero XP to the recompute

#### Scenario: XP uses the snapshot, not the live percentage
- **WHEN** an achievement's current global percentage differs from its rarity snapshot
- **THEN** its XP contribution is computed from the snapshot, so already-earned XP does not change
