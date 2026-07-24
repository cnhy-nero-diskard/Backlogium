# steam-sync

## Purpose

Defines Steam integration for the Android app: polling the Steam Web API on a
periodic background schedule using configured credentials, synthesizing play
sessions by diffing reported playtime across polls, establishing a baseline on
first sync so historical playtime is not misread as new sessions, and surfacing
sync failures without discarding the last good data.

## Requirements

### Requirement: Steam credential configuration
The system SHALL read the Steam Web API key and SteamID64 from an encrypted in-app credential
store (populated by the in-app onboarding flow), expose them through a credentials repository, and
SHALL NOT hardcode or commit these values. On first access, when the store is empty and
`BuildConfig` carries non-blank values, the system SHALL seed the store from `BuildConfig` once;
after seeding the encrypted store is the source of truth. All Steam requests SHALL obtain the key
and SteamID from the credentials repository rather than reading `BuildConfig` directly.

#### Scenario: Credentials present
- **WHEN** the encrypted store holds a Steam API key and SteamID64
- **THEN** the values are exposed through the credentials repository and used for all Steam requests

#### Scenario: Credentials seeded from build config
- **WHEN** the store is empty and `steam.apiKey`/`steam.steamId` were provided at build time
- **THEN** the values are imported into the encrypted store once and used thereafter as the stored
  credentials

#### Scenario: Credentials missing
- **WHEN** no credentials are stored and none can be seeded
- **THEN** the app treats itself as not configured and presents the onboarding flow instead of
  crashing

### Requirement: Periodic Steam poll
The system SHALL poll the Steam Web API on a periodic background schedule using
WorkManager with a minimum interval of 15 minutes, requiring network connectivity,
and SHALL reschedule itself across app restarts and device reboots.

#### Scenario: Scheduled poll succeeds
- **WHEN** the periodic worker runs with network available and valid credentials
- **THEN** it fetches `GetOwnedGames` (with app info and played free games) and `GetSteamLevel`, and persists the results to the local database

#### Scenario: No network
- **WHEN** the worker runs without connectivity
- **THEN** the run is deferred by WorkManager and the last synced data remains intact

#### Scenario: Manual sync
- **WHEN** the user triggers "Sync now"
- **THEN** a one-time expedited poll is enqueued and executes independently of the periodic schedule

### Requirement: Session synthesis by playtime diffing
The system SHALL synthesize play sessions by comparing each game's `playtime_forever`
against the previously stored value, since the Steam Web API does not expose session
or "currently playing" data.

#### Scenario: Playtime increases
- **WHEN** a game's `playtime_forever` is greater than its stored value
- **THEN** an open session for that game is created if none exists, extended by the delta minutes, and its last-increase timestamp updated

#### Scenario: Playtime unchanged
- **WHEN** a game with an open session shows no increase on the next poll
- **THEN** the session is closed with its end time set to the last-increase timestamp

#### Scenario: Playtime decreases
- **WHEN** a game's `playtime_forever` is less than its stored value (e.g. family sharing or refund)
- **THEN** no session is emitted and the decrease does not produce negative playtime

### Requirement: First-sync baselining
The system SHALL treat the first successful poll as a baseline, recording current
playtime totals without creating any historical sessions.

#### Scenario: Initial install poll
- **WHEN** the first successful poll completes and no prior playtime is stored
- **THEN** each game's `playtime_forever` is stored as the baseline and zero sessions are created

#### Scenario: Deltas after baseline
- **WHEN** subsequent polls observe playtime increases beyond the baseline
- **THEN** only those post-baseline deltas are turned into sessions

### Requirement: Sync failure surfacing
The system SHALL detect and surface sync failures without discarding the last good
data.

#### Scenario: Private profile or empty response
- **WHEN** a poll returns no games or an authorization/privacy error
- **THEN** the app retains the last synced data and exposes a recoverable error state indicating the profile may be private
