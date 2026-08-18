# steam-sync

## ADDED Requirements

### Requirement: A playtime baseline is only diffed against the same account
The system SHALL NOT compare a stored playtime baseline against a poll for a different Steam
account. Session synthesis SHALL be possible only where the baseline and the observed playtime
originate from the same account, so that neither a fabricated session nor a suppressed one can
result from an account change.

#### Scenario: Poll following an account change
- **WHEN** the first poll after a confirmed account change occurs
- **THEN** it is treated as a first sync and establishes new baselines, synthesizing no sessions

#### Scenario: Higher totals on the new account
- **WHEN** the new account's total playtime for a game exceeds the stored baseline from the
  previous account
- **THEN** no session is synthesized from that difference

#### Scenario: Lower totals on the new account
- **WHEN** the new account's total playtime for a game is below the stored baseline from the
  previous account
- **THEN** the baseline is re-established from the new account's value, so subsequent genuine
  playtime is recorded rather than suppressed

#### Scenario: Same account across polls
- **WHEN** consecutive polls are for the same account
- **THEN** session synthesis by playtime diffing behaves exactly as specified for normal
  operation
