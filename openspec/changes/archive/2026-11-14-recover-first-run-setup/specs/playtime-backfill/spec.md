# Spec Delta

## ADDED Requirements

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
