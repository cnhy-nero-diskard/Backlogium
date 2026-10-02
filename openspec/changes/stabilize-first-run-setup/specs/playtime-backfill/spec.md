## MODIFIED Requirements

### Requirement: Opt-in historical playtime import
The system SHALL provide a user-initiated action that imports each game's pre-existing Steam playtime so it counts toward XP, and SHALL NOT import historical playtime unless the user invokes this action. The same operation SHALL serve onboarding and Settings and SHALL require a confirmed baseline for the active account before consuming the one-time import. It SHALL explain that lifetime counters do not supply historical session timestamps and SHALL NOT create dated sessions, daily quest credit, or streak history from those counters. Imported historical playtime SHALL remain separate from newly tracked playtime and use the existing XP rules.

#### Scenario: Importing history
- **WHEN** the user invokes the import action with a confirmed baseline
- **THEN** each game's historical Steam playtime is captured and included in that game's XP input, and the player's XP is recomputed to reflect it

#### Scenario: History not counted by default
- **WHEN** the user has never invoked the import action
- **THEN** XP reflects only playtime tracked after install, unchanged from current behavior

#### Scenario: Baseline is unavailable
- **WHEN** import is requested without a confirmed baseline for the active account
- **THEN** the operation reports that library sync is needed and writes neither imported offsets nor the completed-import flag

#### Scenario: Confirmed empty library
- **WHEN** the user explicitly imports a confirmed zero-game baseline
- **THEN** a zero-change import may complete, with that result explained rather than confused with failed initial sync

#### Scenario: Steam counters have no dates
- **WHEN** historical lifetime playtime is imported
- **THEN** no historical dated sessions, quest credit, or streaks are fabricated from those counters

### Requirement: One-time idempotent import
The system SHALL treat the import as a one-time event: once history has been imported, the system SHALL record that state and SHALL NOT re-import or double-count historical playtime on subsequent syncs or repeated invocations, while continuing to track new playtime normally. Imported offsets and the one-time flag SHALL be committed as one account-scoped unit. Any unfinished derived recomputation SHALL be durably distinguishable from a fully completed import and SHALL be recoverable without capturing new offsets. Pending import recomputation SHALL retain its administrative presentation semantics regardless of which operation performs it: imported historical XP SHALL NOT be announced as newly earned play, and stale or incomplete derived writes SHALL NOT clear pending recovery.

#### Scenario: Repeated invocation does nothing
- **WHEN** the user invokes the import action after history has already been fully imported
- **THEN** no additional historical playtime is added and XP is unchanged by the repeat

#### Scenario: New playtime still accrues after import
- **WHEN** the player plays more after importing history
- **THEN** the new tracked playtime is added on top of the imported history without re-importing it

#### Scenario: Growing Steam total is not re-imported
- **WHEN** a later sync observes a higher lifetime Steam total for an already-imported game
- **THEN** the increase is counted only as newly tracked playtime, not as additional imported history

#### Scenario: Imported history survives syncs
- **WHEN** a sync refreshes each game's Steam data after history has been imported
- **THEN** the captured historical portion is preserved on each game, so recomputed XP still reflects the imported history and does not drop back to tracked-only

#### Scenario: Import overlaps a sync commit
- **WHEN** import and a sync commit overlap
- **THEN** the import uses one coherent snapshot of lifetime totals and tracked minutes, and each credited minute is counted once in the combined imported and tracked total

#### Scenario: Interrupted raw import
- **WHEN** the raw import commit is interrupted
- **THEN** either offsets, the one-time flag, and the need for recomputation are committed together or none of them are

#### Scenario: Interrupted recomputation
- **WHEN** raw import has committed and the process ends before derived recomputation completes
- **THEN** recovery recomputes from the frozen offsets without re-importing or duplicating progress events

#### Scenario: Repeated request while recomputation is pending
- **WHEN** the user retries an already-committed import whose recomputation is unfinished
- **THEN** the unfinished recomputation is resumed rather than treating the request as a fresh import or claiming a completed result

#### Scenario: Another operation recomputes while import recovery is pending
- **WHEN** a periodic sync, rule change, or another derived writer recomputes while an import's administrative recomputation remains pending
- **THEN** it resolves the committed import with administrative presentation semantics before clearing its recovery state, does not announce imported historical XP as newly earned play, and does not clear that state with a stale or incomplete derived write

## ADDED Requirements

### Requirement: History import requests remain attributable to the consenting account
The system SHALL bind an explicit import request and its recovery to the account for which consent was given. It SHALL revalidate that account and baseline eligibility at the commit boundary. An account change SHALL prevent an old request from capturing or recomputing the replacement account's imported history. Existing completed imports and tracked sessions SHALL remain compatible with the new recovery model.

#### Scenario: Account changes after confirmation
- **WHEN** consent is recorded for one account and another account becomes active before the import commits
- **THEN** the old request imports nothing for the replacement account and reports that it was superseded

#### Scenario: Existing completed import upgrades
- **WHEN** a previously completed import is loaded after upgrade
- **THEN** its frozen offsets and one-time state remain intact and no automatic re-import is performed
