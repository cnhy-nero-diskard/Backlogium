# game-genres

## Purpose

Defines how the app acquires, caches, and serves each owned game's broad Steam Store
genres: a credential-free Store metadata lookup, run as bounded best-effort background
enrichment independent of the authenticated owned-games poll, cached locally so every
genre consumer renders offline.

## Requirements

### Requirement: Steam Store genre metadata
The system SHALL represent each owned game's broad Steam Store genres as ordered genre identifiers and display labels. The genre set SHALL come from the Store genre metadata for that app and SHALL NOT substitute community tags or infer genres from the game name.

#### Scenario: Store metadata contains genres
- **WHEN** Steam Store metadata for an owned game succeeds with one or more genres
- **THEN** the system retains each genre identifier and label in the order supplied by Steam

#### Scenario: Tags are not treated as genres
- **WHEN** a Store response exposes descriptive community tags that are not members of its genre list
- **THEN** those tags are not persisted or offered as game genres

### Requirement: Best-effort genre enrichment
The system SHALL acquire genre metadata through a background, bounded, and throttled enrichment path for owned games whose genre cache is missing or stale. Genre enrichment SHALL be independent of the authenticated owned-game and playtime poll, SHALL require no additional user credentials, and SHALL NOT delay or fail that normal sync path.

#### Scenario: Newly discovered game lacks genre metadata
- **WHEN** normal Steam sync stores an owned game with no genre-cache record
- **THEN** the game becomes eligible for background genre enrichment without delaying completion of normal sync

#### Scenario: Fresh genre cache exists
- **WHEN** background enrichment evaluates a game whose genre cache is still fresh
- **THEN** it performs no Store metadata request for that game

#### Scenario: Enrichment request fails transiently
- **WHEN** a Store metadata request fails because of a network, server, or throttling error
- **THEN** normal Steam sync remains successful and genre enrichment is eligible for bounded retry with backoff

#### Scenario: Large library requires multiple batches
- **WHEN** more games need enrichment than the configured request bound permits in one run
- **THEN** the system processes only the bounded subset and leaves the remaining games eligible for later continuation

### Requirement: Offline genre cache
The system SHALL persist the latest successfully checked genre result separately from the Steam-owned game row, including an empty or unavailable result, so all genre consumers render from local state. A transient refresh failure SHALL retain the last successful genre result, and a definitive empty or unavailable Store result SHALL be cached until stale to avoid repeated immediate requests.

#### Scenario: Cached genres are available offline
- **WHEN** the device is offline and a game has cached genres
- **THEN** game detail, Library search, and collection filtering can use those cached genres

#### Scenario: Refresh fails after prior success
- **WHEN** a refresh attempt fails transiently for a game that already has cached genres
- **THEN** the prior genres remain available and are not replaced by an empty result

#### Scenario: Store has no usable genre result
- **WHEN** the Store definitively reports no usable metadata or no genres for an app
- **THEN** the system records a checked empty result and does not immediately request it again

#### Scenario: Existing install migrates without genres
- **WHEN** an existing database is upgraded to the genre-capable schema
- **THEN** its games remain usable with unknown genres and become eligible for background enrichment
