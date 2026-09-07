# hltb-data

## ADDED Requirements

### Requirement: A superseded candidate search cannot publish over its replacement
A candidate search started for a game SHALL publish its outcome only while it is still the
search that owns that game's picker state. When a search is cancelled or superseded by a
newer search for the same game, it SHALL publish nothing — neither candidates, nor a loading
change, nor a failure.

Cancellation SHALL NOT be reported as a failure. A cancelled search has no outcome, and
presenting one as failed misinforms the player about a request that was never allowed to
finish. Where cancellation surfaces as an exception, it SHALL be propagated rather than
absorbed by a general failure path.

Ownership SHALL be established by identity — whether this search still owns the game's picker
entry — and not by the entry merely existing. A replacement search recreates the entry, so
presence alone cannot distinguish "my state" from "my successor's state".

#### Scenario: Dismissed and reopened for the same game
- **WHEN** the player dismisses a picker whose search is in flight and quickly reopens it for
  the same game, starting a new search
- **THEN** the first search publishes nothing, and the picker shows the second search's
  progress and result

#### Scenario: Cancellation is not shown as failure
- **WHEN** a candidate search is cancelled before completing
- **THEN** the picker does not present a failed state for it

#### Scenario: A live replacement is not interrupted
- **WHEN** a superseded search would complete while its replacement is still running
- **THEN** the replacement's loading state remains, and dismissing anything the player sees
  does not cancel the replacement

#### Scenario: The owning search publishes normally
- **WHEN** a candidate search completes while it still owns that game's picker state
- **THEN** its candidates, or its failure, are presented as before

#### Scenario: A genuine failure is still reported
- **WHEN** the owning search fails for a real reason, such as an unreachable data source
- **THEN** the picker presents the failure, so the guard does not hide actual errors

#### Scenario: Searches for different games are independent
- **WHEN** searches are in flight for two different games
- **THEN** neither can publish into the other's picker state
