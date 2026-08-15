## ADDED Requirements

### Requirement: Credentials are verified against Steam before being accepted
The onboarding flow SHALL verify entered credentials with a single authenticated request to Steam
before persisting them, and SHALL distinguish a rejected API key, a SteamID with no matching
profile, and a failure to reach Steam. A verification that does not succeed SHALL NOT persist the
credentials.

#### Scenario: Credentials verified
- **WHEN** the entered API key and SteamID are accepted by Steam and identify an existing profile
- **THEN** verification succeeds and the credentials are persisted

#### Scenario: API key rejected
- **WHEN** Steam refuses the request because the API key is not valid
- **THEN** the flow reports that the key was not accepted, does not persist anything, and returns
  the user to the key entry

#### Scenario: No profile for the SteamID
- **WHEN** Steam accepts the key but reports no profile for the entered SteamID
- **THEN** the flow reports that no profile was found, does not persist anything, and returns the
  user to the SteamID entry

#### Scenario: Steam unreachable
- **WHEN** the verification request fails for network reasons
- **THEN** the flow reports that Steam could not be reached, offers to try again, and does not
  present the credentials as invalid

#### Scenario: Retry after a network failure
- **WHEN** the user retries after a network failure and the retry succeeds
- **THEN** the credentials are persisted without needing to be re-entered

#### Scenario: Verification is not repeated on a re-run
- **WHEN** already-verified credentials are read after onboarding
- **THEN** no further verification request is made on their account

#### Scenario: Private profile
- **WHEN** the entered credentials identify an existing profile whose library is not publicly
  readable
- **THEN** verification succeeds, since the profile exists, and the consequence surfaces where the
  library is fetched

### Requirement: Onboarding leads into setup
Completing the credential flow SHALL lead into first-run setup rather than directly into the app, so
that a newly configured install is populated rather than empty. Declining setup SHALL still leave
the app fully usable.

#### Scenario: Setup offered after credentials are saved
- **WHEN** credentials are verified and persisted for the first time
- **THEN** first-run setup is presented

#### Scenario: Declining setup
- **WHEN** the user declines setup
- **THEN** the app transitions to its normal configured state and every part of it is usable

#### Scenario: Editing credentials later
- **WHEN** an already-configured user reopens the credential flow to change credentials
- **THEN** the new credentials are verified, and setup is not presented again unprompted

#### Scenario: Step count reflects the flow
- **WHEN** the credential flow presents its progress through its steps
- **THEN** the count reflects the steps the flow actually has, rather than a fixed number
