## MODIFIED Requirements

### Requirement: Import entry points share readiness and effects
Onboarding and Settings SHALL use one import operation and expose its recorded state. New imports SHALL require durable confirmation of a successful owned-library snapshot for the active account. Both entry points SHALL explain that lifetime totals do not reconstruct dated play sessions.

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

#### Scenario: Completed import predates confirmation records
- **WHEN** the active account already has a completed import when the app upgrades
- **THEN** the import stays complete without requiring a new confirming poll or changing stored offsets

### Requirement: Interrupted and concurrent import preserves one-time credit
The system SHALL atomically capture canonical credited minutes, frozen historical offsets, completion, and pending recomputation for the admitted account. Recovery SHALL reuse the captured offsets. Repeated requests and overlapping writes SHALL preserve the effect of one import and all newly tracked credit.

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

#### Scenario: Import after unknown-time play was credited
- **WHEN** an owned game has 120 lifetime minutes, including 60 credited session minutes and 20 already-credited unknown-time minutes
- **THEN** import freezes 40 historical minutes, so credited activity plus historical offset totals 120 minutes
- **AND** the same result holds when the unknown-time credit is still pending date placement

#### Scenario: A tracked update races the import
- **WHEN** a sync commits new tracked credit during an import
- **THEN** import captures a coherent committed snapshot and no minute is counted in both the historical offset and the credited activity

## ADDED Requirements

### Requirement: History import requests remain attributable to the consenting account
The system SHALL durably associate each explicit import request with its consenting account before starting it. Recovery SHALL reconcile that request. A replaced account or stale request SHALL NOT authorize or complete an import for the active account.

#### Scenario: Account changes after confirmation
- **WHEN** the account changes between consent and raw commit
- **THEN** the old request is superseded and cannot write offsets for the active account

#### Scenario: Process ends before work starts
- **WHEN** the process ends after durable consent but before import starts
- **THEN** startup can resume that same request for the active account without creating another request

### Requirement: History import recomputation retains administrative provenance
Pending history-import recomputation SHALL retain its account, request, and administrative source until derived state is finalized. Any path that finishes it SHALL preserve those semantics. The imported XP SHALL NOT be announced as newly earned progress.

#### Scenario: Another writer recomputes during import recovery
- **WHEN** sync or a rule change runs while history-import recomputation is pending
- **THEN** recovery uses the committed offsets and administrative provenance, and only clears the marker after valid derived state is finalized

#### Scenario: Backup and history recomputation overlap
- **WHEN** backup restoration and history import both leave pending derived work
- **THEN** recovery recomputes the committed data with administrative semantics, preserves the history request's provenance, and never captures the historical offsets again
