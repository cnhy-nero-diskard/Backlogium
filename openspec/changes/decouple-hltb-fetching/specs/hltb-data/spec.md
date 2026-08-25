## MODIFIED Requirements

### Requirement: Client-side HLTB lookup
The system SHALL query HowLongToBeat directly from the device with no application backend and no
API key, resolving the site's current search endpoint and any required request token at call time
rather than relying on hard-coded values. A lookup SHALL be issued only for games the user named —
a single game, or an explicit selection of games. The system SHALL NOT issue a lookup for a game
the user did not name, and SHALL NOT derive the set of games to look up from the library, from a
freshness threshold, or from any other implicit criterion.

#### Scenario: Endpoint or token has rotated
- **WHEN** a lookup is attempted and HowLongToBeat has changed its search endpoint path or request token since the last call
- **THEN** the system re-resolves the current endpoint and token before searching, and does not permanently cache a stale endpoint or token

#### Scenario: Lookup fails
- **WHEN** an HLTB lookup fails (network error, unresolvable endpoint, or empty response)
- **THEN** the failure is surfaced to the caller and no cached HLTB data for the affected game is overwritten or cleared

#### Scenario: No lookup without an explicit target
- **WHEN** no game has been explicitly named for lookup
- **THEN** no request is issued to HowLongToBeat

#### Scenario: Library size does not imply lookups
- **WHEN** the library grows, whether by sync, import, or restore
- **THEN** no lookup is issued as a consequence

#### Scenario: Age does not imply lookups
- **WHEN** stored HowLongToBeat data becomes old
- **THEN** no lookup is issued as a consequence, and the data remains in use until the user
  looks the game up or a dataset supersedes it

### Requirement: Per-game HLTB cache with freshness
The system SHALL store HowLongToBeat data per game keyed by the Steam app id, retaining the
resolved HLTB id, the Main Story, Main+Extras, Completionist, and All-Styles completion lengths,
the time the data was gathered, and whether it was gathered by a lookup on this device or supplied
by an applied dataset.

#### Scenario: Storing a resolved game
- **WHEN** a game is successfully matched and its HLTB data fetched
- **THEN** the system stores its HLTB id, all four completion lengths, and the fetch time keyed by the game's Steam app id

#### Scenario: All metrics retained
- **WHEN** HLTB data is stored for a game
- **THEN** all four completion-length metrics are retained so a consumer can later select a different metric without re-fetching

#### Scenario: Origin retained
- **WHEN** HLTB data is stored for a game
- **THEN** whether it came from a device lookup or from an applied dataset is retained alongside it

#### Scenario: Rows stored before origin was recorded
- **WHEN** a row was stored before origin was retained
- **THEN** it remains readable and usable, and is treated as having come from a device lookup

### Requirement: Per-game fetch on goal tagging
The system SHALL resolve a game's HowLongToBeat data when it is tagged as a goal, using cached data
when present, then data supplied by an applied dataset, and only querying HowLongToBeat when
neither has an answer for that game.

#### Scenario: Goal tagged with cached data present
- **WHEN** a game that already has cached HLTB data is tagged as a goal
- **THEN** the system uses the cached data and does not issue a new HowLongToBeat query

#### Scenario: Goal tagged with dataset coverage
- **WHEN** a game with no cached HLTB data but covered by an applied dataset is tagged as a goal
- **THEN** the dataset's values are used and no HowLongToBeat query is issued

#### Scenario: Goal tagged with no cached data
- **WHEN** a game with no cached HLTB data and no dataset coverage is tagged as a goal
- **THEN** the system queries HowLongToBeat for that game and stores the result

### Requirement: Refresh an explicit subset of games
The HowLongToBeat lookup SHALL accept an explicit set of games, and SHALL look up exactly those
games regardless of how recently their data was gathered. An explicit set is the only way to look
up more than one game.

#### Scenario: Refreshing a subset
- **WHEN** a lookup is requested for an explicit set of games
- **THEN** only those games are queried

#### Scenario: Freshness window bypassed for an explicit selection
- **WHEN** a game in an explicit selection has data gathered very recently
- **THEN** it is still looked up, because the explicit selection expresses intent — there is no
  longer any age threshold that could exempt it

#### Scenario: Dataset coverage bypassed for an explicit selection
- **WHEN** a game in an explicit selection is already covered by an applied dataset
- **THEN** it is still looked up, and the result supersedes the dataset's values for that game

#### Scenario: Whole-library refresh unchanged
- **WHEN** a lookup is requested without an explicit set of games
- **THEN** the request is not satisfiable, because no whole-library variant remains — the set of
  games to look up can only be named by the user

#### Scenario: Request volume limited across a selection
- **WHEN** a lookup runs over an explicit selection of several games
- **THEN** the system reuses a single resolved endpoint and token for the run and spaces the
  queries so it does not issue them as an unthrottled burst

### Requirement: Per-game batch outcomes reported
A lookup over an explicit selection SHALL report, as it proceeds, which game was just processed and
what its outcome was, so a caller can present a live log.

#### Scenario: Outcome reported per game
- **WHEN** a game in the selection has been processed
- **THEN** the run reports that game along with whether it resolved, needs review, had no match,
  or failed to look up

#### Scenario: Failed lookup distinguished from no match
- **WHEN** a game's lookup fails for transport reasons
- **THEN** it is reported as a failed lookup, distinct from a successful search that found no
  candidates, and its cached data is left intact

### Requirement: Batch outcomes reported reflect what actually happened
A lookup over an explicit selection SHALL distinguish games that were resolved, games for which no
match exists, and games whose lookup failed. Progress reported to the user on completion SHALL
state the number actually resolved, and SHALL NOT report attempted work as completed work.

#### Scenario: Every lookup in a batch fails
- **WHEN** all lookups in a selection fail
- **THEN** the completion report states that none were resolved

#### Scenario: Genuine absence of a match
- **WHEN** a lookup succeeds and establishes that no match exists
- **THEN** that game is reported as having no match rather than as a failure

#### Scenario: Progress still advances on failure
- **WHEN** a lookup fails partway through a selection
- **THEN** the attempted-progress count still advances, so a progress indicator continues and
  terminates

#### Scenario: Mixed batch
- **WHEN** a selection contains resolved games, no-match games, and failures
- **THEN** the completion report states the resolved count, not the selection size

#### Scenario: Cancellation is not a failure
- **WHEN** a run over a selection is cancelled
- **THEN** cancellation propagates rather than being classified as a lookup failure

## REMOVED Requirements

### Requirement: Batch library refresh with freshness gate
**Reason**: This requirement is the source of the request volume this change exists to remove. A
library-wide sweep issues one HowLongToBeat search per owned game per freshness window, per device,
solving on every install a matching problem whose answer is identical for every user. The shared
dataset supplies that answer in a single download, so the sweep has no remaining purpose — and
keeping it would leave the impolite path available by default, which defeats the change.

**Migration**: Completion lengths across the library now arrive by applying the shared dataset,
specified in the `hltb-dataset` capability. Games the dataset does not cover are looked up
deliberately, one game or one explicit selection at a time, under "Refresh an explicit subset of
games". The freshness window and its force option have no successor: a stored row is superseded by
a newer dataset or by a lookup the user asked for, never by age alone.

### Requirement: A batch that accomplished nothing is not reported as successful work
**Reason**: This requirement governs the scheduler outcome of the background batch worker — whether
a wholesale transient failure becomes eligible for retry and backoff. With the library-wide sweep
removed, no HowLongToBeat work is scheduled in the background: a lookup runs only while the user has
asked for it and is watching it. There is no scheduler outcome left to report and nothing to retry
on a schedule.

**Migration**: Failure of a user-initiated lookup is surfaced to the user directly, per "Batch
outcomes reported reflect what actually happened", and retrying is the user's choice rather than a
scheduled one. Dataset download failures are handled by the `hltb-dataset` capability, which leaves
the previously applied dataset in effect.
