## ADDED Requirements

### Requirement: Watch observations are attributable and bounded
Diagnostics SHALL identify achievement_watch observations with account-safe game attribution, outcome, request counts, and success/unchanged/deferred-rarity/offline/cancellation/throttling status. Existing credential redaction and retention/request-counter limits SHALL apply.

#### Scenario: Deferred required statistic
- **WHEN** a watch commit is deferred for missing global rarity
- **THEN** diagnostics distinguish it from a confirmed no-achievements result without exposing credentials

#### Scenario: Cancellation and backoff
- **WHEN** a generation is cancelled or throttled
- **THEN** the reason and bounded request counts are inspectable without logging secret request URLs

### Requirement: Detail hydration distinguishes catalogue and player outcomes
Diagnostics SHALL record bounded detail-hydration outcomes attributed to the requested game, distinguishing catalogue success/confirmed empty, usable player state, unusable private/no-stats state, transport failure, offline, and throttling. Missing player progress SHALL NOT be recorded as confirmed no achievements. Existing credential redaction and retention limits SHALL apply.

#### Scenario: Positive catalogue and unusable player state
- **WHEN** detail hydration obtains objectives but Steam supplies no usable personal progress
- **THEN** diagnostics distinguish catalogue success from the unusable player response without recording secret URLs or credentials

#### Scenario: Failed hydration
- **WHEN** a catalogue request fails or is throttled
- **THEN** diagnostics retain that distinct outcome rather than recording a successful empty catalogue
