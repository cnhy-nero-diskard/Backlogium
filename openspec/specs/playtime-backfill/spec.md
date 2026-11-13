# playtime-backfill

## Purpose

Defines the opt-in, one-time import of each game's pre-existing Steam playtime so that
historical hours count toward XP, bounded by the gamification engine's existing per-game
diminishing-returns taper, idempotent across syncs, and reversible.

## Requirements

### Requirement: Opt-in historical playtime import
The system SHALL provide a user-initiated action that imports each game's pre-existing Steam
playtime so it counts toward XP, and SHALL NOT import historical playtime unless the user
invokes this action.

#### Scenario: Importing history
- **WHEN** the user invokes the import action
- **THEN** each game's historical Steam playtime is captured and included in that game's XP
  input, and the player's XP is recomputed to reflect it

#### Scenario: History not counted by default
- **WHEN** the user has never invoked the import action
- **THEN** XP reflects only playtime tracked after install, unchanged from current behavior

### Requirement: Bounded historical XP via the existing taper
The system SHALL compute XP from imported historical playtime using the engine's existing
per-game diminishing-returns rules, so a game with a known completion length cannot earn more
than its capped amount regardless of how many historical hours it holds.

#### Scenario: Matched game caps despite large history
- **WHEN** a game with a HowLongToBeat completion length is imported with historical playtime far exceeding twice that length
- **THEN** its contributed XP is capped by the taper, not proportional to the raw historical hours

#### Scenario: Combined historical and tracked playtime
- **WHEN** a game has both imported historical playtime and playtime tracked after install
- **THEN** its XP is computed from the combined total, tapered as a single cumulative amount

### Requirement: One-time idempotent import
The system SHALL treat the import as a one-time event: once history has been imported, the
system SHALL record that state and SHALL NOT re-import or double-count historical playtime on
subsequent syncs or repeated invocations, while continuing to track new playtime normally.

#### Scenario: Repeated invocation does nothing
- **WHEN** the user invokes the import action after history has already been imported
- **THEN** no additional historical playtime is added and XP is unchanged by the repeat

#### Scenario: New playtime still accrues after import
- **WHEN** the player plays more after importing history
- **THEN** the new tracked playtime is added on top of the imported history without re-importing it

#### Scenario: Growing Steam total is not re-imported
- **WHEN** a later sync observes a higher lifetime Steam total for an already-imported game
- **THEN** the increase is counted only as newly tracked playtime, not as additional imported history

#### Scenario: Imported history survives syncs
- **WHEN** a sync refreshes each game's Steam data after history has been imported
- **THEN** the captured historical portion is preserved on each game, so recomputed XP still
  reflects the imported history and does not drop back to tracked-only

### Requirement: Reset an import
The system SHALL let the user undo a completed import, clearing the captured historical
playtime and returning to the not-imported state, and SHALL recompute XP so it reflects only
tracked playtime afterward. Resetting SHALL NOT discard tracked playtime.

#### Scenario: Undoing an import
- **WHEN** the user resets a completed import
- **THEN** the captured historical playtime no longer counts toward XP, XP is recomputed from
  tracked playtime only, and the import is offered again

#### Scenario: Reset preserves tracked playtime
- **WHEN** the user resets a completed import
- **THEN** playtime tracked after install is unchanged and still counts toward XP

### Requirement: Import entry points share readiness and effects
Onboarding and Settings SHALL invoke the same history-import operation and expose its recorded completion state. Import SHALL require a successful library snapshot for the configured account; an unreadable library or an unfinished first sync SHALL NOT be accepted as a completed empty import. Both entry points SHALL explain that Steam lifetime totals do not reconstruct dated play sessions.

#### Scenario: Import from onboarding
- **WHEN** a user explicitly imports history from onboarding after a successful library sync
- **THEN** its stored historical offsets and recomputed XP match importing the same library from Settings
- **AND** no historical sessions or dated daily progress are fabricated from lifetime totals

#### Scenario: Import attempted before the baseline
- **WHEN** import is requested before a successful library snapshot exists
- **THEN** it reports that library sync is required and leaves the import incomplete and available later

#### Scenario: A confirmed empty library
- **WHEN** a successful library snapshot confirms that the configured account owns no games
- **THEN** an explicitly requested import can complete as an empty import
- **AND** an unreadable or unconfirmed library response is not treated as that successful snapshot

#### Scenario: Import already completed elsewhere
- **WHEN** an entry point observes that history was imported through another entry point
- **THEN** it reflects completion instead of offering another progress-crediting import

### Requirement: Interrupted and concurrent import preserves one-time credit
An interrupted import SHALL be recoverable without losing captured historical credit or adding it twice. Repeated or concurrent requests from any entry point SHALL have the same final effect as one successful import. A concurrent sync or monitoring update SHALL retain new tracked credit and imported offsets without counting the same minutes as both imported and tracked play.

#### Scenario: Two import requests overlap
- **WHEN** onboarding and Settings request import before either request finishes
- **THEN** the final historical offsets and XP equal one import rather than two

#### Scenario: Interruption during capture
- **WHEN** the process ends after some historical offsets are written but before import completion
- **THEN** recovery completes or safely retries the same import without double credit or a falsely completed partial import

#### Scenario: Interruption before recomputation completes
- **WHEN** historical offsets have been captured and the process ends before derived values finish updating
- **THEN** a subsequent recovery reconciles XP with the captured offsets rather than treating the completion flag alone as proof that derived values are current

#### Scenario: Import overlaps tracked activity
- **WHEN** sync or monitoring commits tracked play while import captures pre-existing playtime
- **THEN** the resulting tracked credit and imported contribution preserve the same authorized quantity without duplicating any minutes
- **AND** later syncs preserve the captured historical offsets
