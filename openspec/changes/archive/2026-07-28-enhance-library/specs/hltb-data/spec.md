## ADDED Requirements

### Requirement: Refresh an explicit subset of games
The batch HowLongToBeat refresh SHALL accept an explicit set of games to refresh, and SHALL refresh
exactly those games regardless of how recently their data was fetched.

#### Scenario: Refreshing a subset
- **WHEN** a refresh is requested for an explicit set of games
- **THEN** only those games are queried

#### Scenario: Freshness window bypassed for an explicit selection
- **WHEN** a game in an explicit selection has data fetched more recently than the freshness window
- **THEN** it is still refreshed, because the explicit selection expresses intent

#### Scenario: Whole-library refresh unchanged
- **WHEN** a refresh is requested without an explicit subset
- **THEN** it behaves as today: stale and missing games only, unless a force refresh was requested

### Requirement: Per-game batch outcomes reported
The batch refresh SHALL report, as it proceeds, which game was just processed and what its outcome
was, so a caller can present a live log.

#### Scenario: Outcome reported per game
- **WHEN** a game in the batch has been processed
- **THEN** the batch reports that game along with whether it resolved, needs review, had no match,
  or failed to look up

#### Scenario: Failed lookup distinguished from no match
- **WHEN** a game's lookup fails for transport reasons
- **THEN** it is reported as a failed lookup, distinct from a successful search that found no
  candidates, and its cached data is left intact
