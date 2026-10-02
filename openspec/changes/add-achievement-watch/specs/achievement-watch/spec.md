## Purpose
Provide bounded, retryable achievement observations for the active game without introducing another playtime tracker.

## ADDED Requirements

### Requirement: The watch follows confirmed active-game presence
The system SHALL watch only one visible running game for the configured account while the opted-in live monitor is active. Successful confirmed presence SHALL drive lifecycle changes; after state commit, old-watch cancellation SHALL precede existing session-end publication and shared-game observation, and current-watch activation SHALL follow them. Failures SHALL NOT be interpreted as a stop. Rehydrated state alone SHALL NOT start the watch.

#### Scenario: Same game continues
- **WHEN** another successful observation names the same game
- **THEN** the existing watch generation and baseline remain active

#### Scenario: A switches directly to B
- **WHEN** a successful observation changes the running game from A to B
- **THEN** A is cancelled before B starts and A's stale responses cannot commit

#### Scenario: Failed presence observation
- **WHEN** presence cannot be fetched
- **THEN** the prior watch target is retained, subject to offline/error backoff

#### Scenario: Monitoring stops or game is hidden
- **WHEN** the service ends, watch/monitor is disabled, the account changes, or the game becomes hidden
- **THEN** the watch is cancelled and in-flight results cannot write or alert

### Requirement: Normal achievement cadence is thirty to sixty seconds
The system SHALL fetch immediately on confirmed activation and then at the selected 60-second default or 30-second interval without overlaps or catch-up bursts. Unchanged successful observations SHALL retain that cadence. Offline work SHALL pause; failure/throttling backoff MAY exceed the selected interval and SHALL reset after success.

#### Scenario: Unchanged successful reads
- **WHEN** the game remains active and repeated achievement responses are unchanged
- **THEN** the selected cadence continues instead of backing off toward five minutes

#### Scenario: Throttled or offline
- **WHEN** the device is offline or a request is throttled
- **THEN** requests pause or back off without blocking UI or changing the playtime ledger

### Requirement: Only committed observations advance the watch baseline
The first usable committed observation of each watch generation SHALL seed a baseline without producing historic unlock alerts. Later unlock comparisons SHALL use the last successfully committed observation. Failed, deferred, cancelled, or stale observations SHALL NOT advance it.

#### Scenario: First committed observation
- **WHEN** a watch starts on a game with many existing unlocks
- **THEN** achievements are persisted but no unlock alert group is created

#### Scenario: Missing globals retry
- **WHEN** a locked baseline is followed by an unlocked response with missing globals and then the same unlocked response with usable globals
- **THEN** the failed observation changes no baseline and the successful retry records the snapshot and one logical unlock group

#### Scenario: Concurrent ordinary refresh
- **WHEN** ordinary sync stores an unlock between two successful watch observations
- **THEN** the watch compares its committed observations and neither loses nor duplicates the newly observed group

### Requirement: Watch writes preserve the existing playtime and progress engines
The watch SHALL store achievement observations and invoke the existing earned recompute when XP-bearing inputs change. It SHALL NOT create playtime polls, modify session attribution, or calculate an independent XP value. A committed observation needing recompute SHALL retain recoverable work until the recompute succeeds, including baseline/repair commits that have no unlock event. Unchanged XP inputs SHALL not trigger a redundant recompute.

#### Scenario: Unlock affects XP
- **WHEN** a watch commit stores a new unlocked achievement
- **THEN** the existing engine computes XP from the normal inputs

#### Scenario: Crash after achievement commit
- **WHEN** the process stops after a Room commit before recompute
- **THEN** restart can complete the recorded recompute work without duplicating the group
