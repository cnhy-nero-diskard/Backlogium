# onboarding-credentials

## Purpose

Defines the in-app onboarding flow that captures, validates, encrypts, and persists the Steam Web
API key and SteamID64 without requiring edits to `local.properties` or an app rebuild, along with
the encrypted credential store, dual SteamID entry paths, repeatable editing from Home, and the
one-time first-run seed from `BuildConfig`.

## Requirements

### Requirement: In-app credential capture
The system SHALL provide an in-app onboarding flow that captures the Steam Web API key and a
SteamID64 from the user and persists them, WITHOUT requiring any edit to `local.properties` or an
app rebuild. The flow SHALL capture the API key before the SteamID so that vanity-URL resolution
has a key available.

#### Scenario: Completing onboarding
- **WHEN** the user enters a valid Steam Web API key and a valid SteamID64 (by either entry path)
  and confirms
- **THEN** the credentials are persisted to the encrypted store and the app transitions to its
  normal configured state

#### Scenario: Onboarding takeover when unconfigured
- **WHEN** the app has no stored credentials and no seed is available
- **THEN** the app presents the onboarding flow as a full-screen takeover instead of the main
  content

### Requirement: Dual SteamID entry paths
The system SHALL let the user supply the SteamID64 either by pasting a raw SteamID64 or by pasting
a Steam profile URL. For a `.../profiles/<id64>` URL the system SHALL extract the SteamID64
locally; for a `.../id/<vanity>` URL the system SHALL resolve it to a SteamID64 via the Steam Web
API. The resolved or entered value SHALL be validated as a 17-digit SteamID64 before it is
accepted.

#### Scenario: Raw SteamID64 pasted
- **WHEN** the user selects the raw-ID path and enters a 17-digit SteamID64 beginning `7656119`
- **THEN** the value is accepted as the SteamID64

#### Scenario: Profile URL with embedded SteamID64
- **WHEN** the user pastes a URL of the form `steamcommunity.com/profiles/<id64>`
- **THEN** the SteamID64 is extracted from the URL locally without an API call and accepted

#### Scenario: Vanity profile URL resolved
- **WHEN** the user pastes a URL of the form `steamcommunity.com/id/<vanity>` and a valid API key
  is present
- **THEN** the system calls `ResolveVanityURL` and, on `success`, accepts the returned SteamID64

#### Scenario: Vanity resolution finds no profile
- **WHEN** vanity resolution returns no match (`success` other than 1)
- **THEN** the flow shows an inline "no Steam profile found for that URL" error and does not store
  a SteamID

#### Scenario: Invalid SteamID64 rejected
- **WHEN** the entered or resolved value is not a 17-digit SteamID64 beginning `7656119`
- **THEN** the flow shows a validation error and does not persist the value

### Requirement: Credentials are verified against Steam before being accepted
The onboarding flow SHALL verify entered credentials with a single authenticated request to Steam
before persisting them, and SHALL distinguish a rejected API key, a SteamID with no matching
profile, and a failure to reach Steam. A verification that does not succeed SHALL NOT persist the
credentials.

Verification is a precondition of persisting credentials and SHALL NOT be presented as an optional
or declinable step of any later flow, since credentials that were not verified are never stored.

#### Scenario: Verification cannot be declined
- **WHEN** the user reaches the point at which credentials would be persisted
- **THEN** verification runs, and there is no path that persists credentials without it

#### Scenario: Verification precedes setup
- **WHEN** first-run setup is presented
- **THEN** the credentials it will use have already been verified, so no setup stage repeats or
  depends on verification

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

### Requirement: Encrypted credential storage
The system SHALL store the Steam Web API key and SteamID64 encrypted at rest using a key held in
the Android Keystore, and SHALL NOT store them in plaintext preferences or commit them to source.
The API key SHALL be masked wherever it is displayed and SHALL never be logged.

#### Scenario: Credentials stored encrypted
- **WHEN** credentials are saved
- **THEN** their persisted representation is ciphertext produced with an Android Keystore-backed
  key, not readable plaintext

#### Scenario: Keystore key unavailable
- **WHEN** stored credentials cannot be decrypted (e.g., the Keystore key was invalidated)
- **THEN** the app treats credentials as absent and re-presents onboarding instead of crashing

### Requirement: Repeatable credential editing from Home
The system SHALL, once credentials are configured, present a credentials surface on the Home
screen that shows the active SteamID and a masked API key and lets the user reopen the onboarding
flow to change credentials at any time.

#### Scenario: Reopening onboarding after configuration
- **WHEN** the user activates the "Edit" action on the Home credentials card
- **THEN** the onboarding flow reopens pre-reflecting the current state so credentials can be
  changed and re-saved

#### Scenario: Active credentials shown
- **WHEN** the Home credentials card is shown while configured
- **THEN** it displays the active SteamID and a masked form of the API key

### Requirement: First-run credential seed
The system SHALL, on first access when the encrypted store is empty and `BuildConfig` carries
non-blank credential values, import those values once into the encrypted store; thereafter the
encrypted store SHALL be the source of truth and `BuildConfig` SHALL NOT be consulted.

#### Scenario: Existing build seeds once
- **WHEN** the encrypted store is empty and `BuildConfig.STEAM_API_KEY`/`STEAM_ID` are non-blank
- **THEN** those values are imported into the encrypted store and the app starts configured

#### Scenario: In-app store wins after seeding
- **WHEN** the encrypted store already holds credentials
- **THEN** the app uses only the stored values and ignores `BuildConfig`, even if the user later
  clears the stored credentials
### Requirement: Changing the configured SteamID has a defined data consequence
Saving a SteamID different from the one currently configured SHALL be treated as an account
change with an explicit, confirmed consequence for stored data. The system SHALL NOT accept a
changed SteamID and leave data recorded under the previous account in place unlabelled, because
subsequent polls would then compare one account's playtime against another's baseline.

#### Scenario: SteamID changed
- **WHEN** the user saves credentials whose SteamID differs from the configured one
- **THEN** the change is not applied until the user confirms it, having been told what happens
  to data recorded under the previous account

#### Scenario: Confirmation declined
- **WHEN** the user declines the confirmation
- **THEN** the configured credentials and all stored data are left exactly as they were

#### Scenario: Export offered before data is discarded
- **WHEN** confirming the change would discard data recorded under the previous account
- **THEN** the user is offered an export of that data before it is discarded

#### Scenario: API key changed alone
- **WHEN** the user saves credentials whose API key differs but whose SteamID is unchanged
- **THEN** the API key is updated with no effect on stored data and no confirmation

#### Scenario: First configuration
- **WHEN** credentials are saved and no SteamID was previously configured
- **THEN** they are stored without confirmation, as there is no previous account

#### Scenario: Account change survives interruption
- **WHEN** a confirmed account change is interrupted at any point
- **THEN** the next start detects the incomplete change and completes it, rather than leaving
  credentials naming one account while data reflects another

#### Scenario: No sync runs against an incomplete account change
- **WHEN** an account change has not finished applying
- **THEN** no playtime poll is permitted to diff against the stored data until it has, so a
  half-applied change cannot produce a baseline from one account and a poll from another

#### Scenario: Account-independent data is retained
- **WHEN** a confirmed account change discards account-specific data
- **THEN** data that is a property of a game rather than of an account, such as external
  completion-time estimates, is retained and is not removed as a side effect of discarding the
  library it was linked to
