# live-status

## Purpose

Defines the app's live, transient "now playing" signal: determining the player's
current in-game state from Steam and refreshing it on a short foreground cadence,
with an optional user-started monitor for background idle checks, independent of the
periodic background playtime sync.

## Requirements

### Requirement: Current in-game state
The system SHALL determine the player's current in-game state from Steam, distinguishing
"in-game" (with the running game's identity) from "not in-game", using
`ISteamUser/GetPlayerSummaries` (`gameid`/`gameextrainfo`). The now-playing state is a
transient live signal and SHALL NOT be persisted.

#### Scenario: Player is in a game
- **WHEN** the live-status poll runs and Steam reports the player is in a game
- **THEN** the now-playing state resolves to in-game with that game's identity (name, and
  icon/app id when available)

#### Scenario: Player is not in a game
- **WHEN** the live-status poll runs and Steam reports no running game
- **THEN** the now-playing state resolves to not in-game

#### Scenario: Live fetch fails
- **WHEN** the live-status poll fails (network or API error)
- **THEN** the failure does not crash the app or clobber other synced data; the last
  known now-playing state is retained or treated as unknown until the next successful poll

### Requirement: Foreground live polling cadence
The system SHALL refresh the current in-game state on a short foreground cadence
(approximately every 30 seconds) that runs only while the app is foregrounded and the
consuming screen is active, and SHALL stop polling when it is not. This live poll SHALL be
independent of the periodic background playtime sync, whose cadence is unchanged.

#### Scenario: Polling while observed
- **WHEN** the Home screen is foregrounded and observing live status
- **THEN** the current in-game state is refreshed approximately every 30 seconds

#### Scenario: Polling stops when unobserved
- **WHEN** no screen is observing live status (e.g. the app is backgrounded)
- **THEN** the live poll stops issuing requests

#### Scenario: Background sync unaffected
- **WHEN** the live poll is running or stopped
- **THEN** the periodic background playtime sync continues on its own 15-minute schedule,
  unchanged

### Requirement: Opt-in idle presence monitoring
When the user has enabled Live monitor, the foreground presence service SHALL continue its
approximately 30-second Steam presence checks while Steam reports no running game. This service
MUST begin only from a user-visible app interaction or a subsequent app foreground.

#### Scenario: Game begins while the app is backgrounded
- **WHEN** Live monitor is enabled, the app is backgrounded, and Steam begins reporting a game
- **THEN** the service reflects the running game on its next presence check

#### Scenario: Game ends while monitoring remains enabled
- **WHEN** a monitored game ends and Steam reports no running game
- **THEN** the service remains active and returns to idle monitoring

#### Scenario: Monitor disabled during a session
- **WHEN** the user disables Live monitor while Steam still reports a running game
- **THEN** the existing tracked session continues until Steam reports that the game ended

#### Scenario: Service stopped by Android
- **WHEN** Android stops the monitor and the user later foregrounds the app while Live monitor is enabled
- **THEN** the monitor is restarted from that foreground interaction

### Requirement: Background presence tracking
The system SHALL continue observing the player's in-game presence while the app is not in the
foreground, for as long as the player remains in a game, and SHALL stop observing once the player
is no longer in a game.

#### Scenario: Presence tracked after leaving the app
- **WHEN** the player is in a game and the app is sent to the background
- **THEN** presence continues to be observed and reflected outside the app

#### Scenario: Observation stops when the game ends
- **WHEN** an observation reports that the player is no longer in a game
- **THEN** background presence observation stops

#### Scenario: Not observing while idle
- **WHEN** the player is not in a game
- **THEN** no background presence observation runs

### Requirement: Live session start time
The system SHALL record when the player was first observed in the current game and SHALL retain
that timestamp until the game ends, so elapsed session time can be presented and survives an app
restart.

#### Scenario: Start time recorded
- **WHEN** the player is first observed in a game
- **THEN** the start time for that game is recorded

#### Scenario: Elapsed time survives restart
- **WHEN** the app is restarted while the player is still in the same game
- **THEN** elapsed time continues from the recorded start time rather than restarting from zero

#### Scenario: Start time reset on game change
- **WHEN** the player is observed in a different game than the recorded one
- **THEN** the start time is replaced with the new game's first-observed time

#### Scenario: Start time cleared when the game ends
- **WHEN** the player is no longer in a game
- **THEN** the recorded start time is cleared

#### Scenario: Live session excluded from XP
- **WHEN** elapsed live session time is recorded
- **THEN** it is never used as a playtime input for XP, quests, or streaks, which continue to be
  derived solely from playtime-delta-synthesized sessions
