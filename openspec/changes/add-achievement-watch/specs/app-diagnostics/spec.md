## ADDED Requirements

### Requirement: Watch observations are attributable and bounded
Diagnostics SHALL identify achievement_watch observations with account-safe game attribution, outcome, request counts, and success/unchanged/deferred-rarity/offline/cancellation/throttling status. Existing credential redaction and retention/request-counter limits SHALL apply.

#### Scenario: Deferred required statistic
- **WHEN** a watch commit is deferred for missing global rarity
- **THEN** diagnostics distinguish it from a confirmed no-achievements result without exposing credentials

#### Scenario: Cancellation and backoff
- **WHEN** a generation is cancelled or throttled
- **THEN** the reason and bounded request counts are inspectable without logging secret request URLs
