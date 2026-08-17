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

### Requirement: Live polling cadence and ownership
The system SHALL refresh the current in-game state on a short cadence (approximately every 30
seconds) while the player is in a game, owned by the background presence observer rather than by
screen observation. Screens SHALL observe shared live state without being responsible for keeping
the refresh alive. This live refresh SHALL be independent of the periodic background playtime
sync, whose cadence is unchanged.

#### Scenario: Refreshing while in a game
- **WHEN** the player is in a game and presence observation is active
- **THEN** the current in-game state is refreshed approximately every 30 seconds

#### Scenario: Screens do not own the cadence
- **WHEN** a screen stops observing live status but the player is still in a game
- **THEN** the refresh continues, and live state remains available to any other observer

#### Scenario: Background sync unaffected
- **WHEN** the live refresh is running or stopped
- **THEN** the periodic background playtime sync continues on its own 15-minute schedule,
  unchanged

### Requirement: Presence resolved before library work
A sync SHALL resolve and act on the player's current in-game state before performing any
library-scale work in that run, so presence latency is bounded by a single request rather than by
the duration of the surrounding sync.

#### Scenario: Presence acted on before achievement fetching
- **WHEN** a sync retrieves a player summary reporting a running game
- **THEN** presence observation begins before that run fetches achievement data for library games

#### Scenario: Presence unaffected by library-side conditions
- **WHEN** a sync cannot proceed with library work — for example the owned-games list is empty
  because it is private
- **THEN** the player's current in-game state is still resolved and acted on in that run

#### Scenario: Presence unaffected by later sync failure
- **WHEN** presence has been resolved and a later stage of the same sync run fails
- **THEN** the resolved presence state is retained and observation continues

### Requirement: Presence re-checked on app foreground
The system SHALL re-check the player's current in-game state each time the app enters the
foreground. If the first result does not report a running game, the system SHALL retry for a short,
bounded window while the app remains foregrounded, so Steam presence propagation does not require
an app restart or completion of an unrelated library sync.

#### Scenario: Game started while the app is backgrounded
- **WHEN** the player launches a game while the app is backgrounded, then returns to the app
- **THEN** the current in-game state is re-checked and the running game is reflected

#### Scenario: Game started while the app is open
- **WHEN** the player launches a game while the app is open and later returns to it
- **THEN** the running game is reflected without the app being restarted

#### Scenario: Repeated foregrounding
- **WHEN** the app is foregrounded again while already observing a running game
- **THEN** the re-check is harmless and does not restart the recorded session start time

#### Scenario: Presence propagation is delayed
- **WHEN** the first foreground check reports no running game but a retry within the bounded window
  reports one
- **THEN** the running game is reflected and presence observation begins immediately

#### Scenario: App backgrounds during the retry window
- **WHEN** the app leaves the foreground before the bounded retry window completes
- **THEN** the remaining foreground detection attempts are cancelled

### Requirement: Session state outlives the observer
Stopping presence observation for lifecycle reasons SHALL NOT clear the recorded live session.
Recorded session state SHALL be cleared only when an observation reports that the player is no
longer in a game.

#### Scenario: Observer stopped by the system
- **WHEN** presence observation stops because its host was killed, timed out, or the process died
- **THEN** the recorded session start time is retained and elapsed time continues from it once
  observation resumes

#### Scenario: Game genuinely ended
- **WHEN** an observation reports that the player is no longer in a game
- **THEN** the recorded session state is cleared

#### Scenario: Failed observation does not clear state
- **WHEN** an observation attempt fails with a network or API error
- **THEN** the recorded session state is retained and is not treated as the game having ended

### Requirement: Presence rehydrated on cold start
When the app starts and a live session is already recorded, the system SHALL present the
in-game state from that recorded session immediately, before any network result arrives, and
SHALL reconcile it with the first successful observation.

#### Scenario: Recorded session present at start
- **WHEN** the app starts and a live session for a game is recorded
- **THEN** the in-game state reflects that game immediately, with elapsed time measured from the
  recorded start

#### Scenario: Reconciled with the first observation
- **WHEN** the first successful observation after start reports the player is no longer in a game,
  or is in a different game
- **THEN** the rehydrated state is replaced by the observed state and the recorded session is
  updated accordingly

#### Scenario: No recorded session
- **WHEN** the app starts with no recorded live session
- **THEN** no in-game state is presented until an observation reports one

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

### Requirement: A refused monitoring start never fails a sync
Starting live monitoring SHALL be best-effort with respect to the Steam poll that requests it.
If the platform refuses or fails the start, the poll SHALL continue and report its own outcome,
and the refusal SHALL be recorded rather than discarded.

#### Scenario: Platform refuses the start during a background poll
- **WHEN** a scheduled poll requests monitoring while the app is backgrounded and the platform
  refuses to start it
- **THEN** the poll continues, fetches its data, and reports success if that data was retrieved

#### Scenario: Refusal is recorded
- **WHEN** a monitoring start is refused or fails
- **THEN** a record identifying the condition that caused it is stored, so the failure is
  observable afterwards

#### Scenario: Successful start is unaffected
- **WHEN** the platform permits the start
- **THEN** monitoring begins as before and the poll proceeds

### Requirement: Monitoring start uses a mechanism the platform permits
The system SHALL request live monitoring only through a mechanism permitted from the context
making the request, rather than issuing a request the platform is expected to reject.

#### Scenario: Request from a background scheduled poll
- **WHEN** monitoring is requested from a scheduled background execution
- **THEN** the request is made through a mechanism valid from that context, or is not made at
  all and is recorded as not attempted

#### Scenario: Request from the foreground
- **WHEN** monitoring is requested while the app is in the foreground
- **THEN** monitoring begins

### Requirement: Behaviour after a platform runtime budget is reached is stated and honest
When the platform ends monitoring because a cumulative runtime budget was reached, the system
SHALL stop cleanly, record that the budget was reached, and behave as the specification states
for resumption. The system SHALL NOT document or rely on a resumption path the platform will
refuse.

#### Scenario: Budget reached
- **WHEN** the platform signals that the monitoring runtime budget is exhausted
- **THEN** monitoring stops cleanly and the reason is recorded

#### Scenario: Resumption is described accurately
- **WHEN** the conditions for monitoring to resume are documented
- **THEN** they describe what the platform permits, including the case where monitoring cannot
  resume until the user brings the app to the foreground

#### Scenario: User is informed when unattended monitoring is unavailable
- **WHEN** monitoring has stopped and cannot resume without the user opening the app
- **THEN** the live-status surface distinguishes that state from active monitoring, rather than
  appearing to monitor

#### Scenario: Tracked playtime is unaffected
- **WHEN** monitoring is unavailable for any period
- **THEN** playtime continues to be tracked by the periodic poll, and only the finer-grained
  live resolution is lost

