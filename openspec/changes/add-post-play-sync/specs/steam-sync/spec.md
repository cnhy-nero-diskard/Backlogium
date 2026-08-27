## ADDED Requirements

### Requirement: Play-triggered targeted playtime fetch
When an observed play session ends, the system SHALL fetch the stopped game's playtime from Steam
without waiting for the next periodic poll. The fetch SHALL be scoped to that one game, so its cost
is independent of library size, and SHALL request no data other than playtime.

#### Scenario: Session ends
- **WHEN** presence observation reports that a game which was running is no longer running
- **THEN** a targeted playtime fetch is scheduled for that game

#### Scenario: Fetch is scoped to one game
- **WHEN** a targeted playtime fetch runs
- **THEN** it requests a bounded recent-game window, selects only the stopped game's observation, and
  its request count does not grow with the size of the library

#### Scenario: Periodic cadence unchanged
- **WHEN** a targeted playtime fetch is scheduled, running, or exhausted
- **THEN** the periodic poll continues on its existing schedule, unchanged

#### Scenario: Session start is not a trigger
- **WHEN** presence observation reports that a game has started running
- **THEN** no targeted playtime fetch is scheduled

#### Scenario: Presence change that leaves the game running
- **WHEN** the player's presence state changes while the same game is still running
- **THEN** no targeted playtime fetch is scheduled

#### Scenario: Response for an unexpected game
- **WHEN** a targeted playtime fetch returns playtime for a game other than the one that stopped
- **THEN** the observation is discarded and no playtime is attributed

#### Scenario: Stopped game is not the first recent game
- **WHEN** the bounded response contains another recent game before the one that stopped
- **THEN** the stopped game's observation is selected and only its playtime can be attributed

### Requirement: Targeted fetch retries on a bounded schedule
Because Steam does not publish a game's updated playtime at the instant that game exits, the system
SHALL retry a targeted playtime fetch on a bounded schedule. The schedule SHALL stop at the first
observation showing an increase over the stored baseline, SHALL be exhausted after a bounded number
of attempts, and SHALL treat exhaustion as an ordinary outcome rather than a failure.

#### Scenario: Increase observed on a later attempt
- **WHEN** an attempt observes playtime greater than the stored baseline for that game
- **THEN** the observation is applied and no further attempts are made

#### Scenario: Increase observed immediately
- **WHEN** the first attempt already observes an increase
- **THEN** the observation is applied and exactly one request was issued for that session

#### Scenario: Schedule exhausted
- **WHEN** every attempt in the schedule observes no increase
- **THEN** the schedule ends without being retried or extended, and the periodic poll remains
  responsible for observing the increase whenever Steam publishes it

#### Scenario: Session too short to register
- **WHEN** a game runs for less time than Steam records as playtime
- **THEN** the schedule is exhausted, nothing is recorded, and this is not surfaced as a failure

#### Scenario: Request cost is bounded per session
- **WHEN** one play session ends
- **THEN** the targeted fetch issues no more than its bounded attempt count of requests for that
  session

#### Scenario: Same game stopped twice within the window
- **WHEN** a game is started and stopped again while an earlier schedule for it is still pending
- **THEN** the later schedule replaces the earlier one rather than both running

#### Scenario: A different game stopped while a schedule is pending
- **WHEN** a second game stops while a schedule for a first game is still pending
- **THEN** both games' schedules proceed independently

#### Scenario: Fetch fails
- **WHEN** a targeted playtime fetch fails for network or API reasons
- **THEN** previously stored data is unchanged and the schedule continues or ends without affecting
  the periodic poll

### Requirement: Schedule generations own targeted work
Each play-triggered schedule SHALL have a monotonically increasing generation persisted per app id.
The generation, app id, attempt index, and triggering session end SHALL be carried in every attempt's
WorkManager input. Starting a new schedule SHALL advance the generation before enqueueing its first
attempt. Generation advancement, active-generation checks, observation commits, and successor
enqueues SHALL be serialized per app id by one coordinator; WorkManager cancellation SHALL be
treated as cleanup, not as the correctness guard.

Before committing an observation or enqueueing a successor, an attempt SHALL re-read the persisted
generation while holding that coordinator's critical section. If its input generation is not active,
the attempt SHALL succeed as a no-op: it SHALL neither commit playtime nor enqueue a successor.

#### Scenario: A newer session supersedes a running attempt
- **WHEN** generation A is mid-flight, a new session end advances the app to generation B and
  replaces the WorkManager chain, then generation A resumes
- **THEN** generation A records no playtime and enqueues no successor, while generation B remains the
  only active schedule

#### Scenario: A stale attempt observes an increase
- **WHEN** an attempt from a superseded generation returns an increase after generation B became
  active
- **THEN** the increase is discarded, including its old session-end event time, and the ordinary
  commit path is not called

#### Scenario: A stale attempt observes no increase
- **WHEN** an attempt from a superseded generation reaches its no-increase path after generation B
  became active
- **THEN** it does not append a successor to either chain

### Requirement: Targeted fetches commit through the ordinary poll path
A targeted playtime fetch SHALL apply its observation through the same session synthesis and
persistence path as a periodic poll, deriving its committed delta from baselines read within the
committing transaction. It SHALL NOT synthesize sessions, credit daily progress, or author derived
values by any separate route.

#### Scenario: Same increase observed by both sources
- **WHEN** a targeted fetch and a periodic poll both observe the same playtime increase
- **THEN** the increase is recorded exactly once, and the second to commit records no additional
  session and no additional minutes

#### Scenario: Records are indistinguishable by source
- **WHEN** a session is recorded from a targeted fetch
- **THEN** the stored session, playtime baseline, and daily progress are identical to what a
  periodic poll observing the same increase would have produced

#### Scenario: The observation carries the time the play ended
- **WHEN** a targeted fetch commits an observation
- **THEN** it supplies the session end that triggered its schedule as the time the play occurred,
  rather than the time the attempt ran

#### Scenario: A later attempt does not report a later play
- **WHEN** the increase is observed by a late attempt of the schedule rather than the first
- **THEN** the time the play occurred is the same session end, so the record does not drift later
  with the attempt that happened to see it

#### Scenario: Steam-owned fields are not locally authored
- **WHEN** a targeted fetch commits an observation and its response carries no last-played time
- **THEN** it leaves the stored last-played time unchanged for the next periodic poll to set,
  rather than writing a locally-derived value into a Steam-owned field

#### Scenario: Derived values keep one author
- **WHEN** a targeted fetch commits an observation
- **THEN** derived values are written by the existing on-device path, and no second derivation is
  performed

#### Scenario: Playtime decrease
- **WHEN** a targeted fetch observes playtime lower than the stored baseline
- **THEN** no session is emitted and no negative playtime is produced

#### Scenario: Concurrency with a running poll
- **WHEN** a targeted fetch runs while a periodic poll is already in flight
- **THEN** exactly-once crediting still holds, and the targeted fetch neither fails nor is skipped
  on account of the overlap

### Requirement: A targeted fetch survives the app being closed
The schedule for a targeted playtime fetch SHALL be durable, so that a session ending immediately
before the app is backgrounded or its process ends is still collected.

#### Scenario: App closed after quitting a game
- **WHEN** the player quits a game and then leaves the app, and the app's process ends
- **THEN** the remaining scheduled attempts still run and the session is recorded

#### Scenario: Device restarted mid-schedule
- **WHEN** the device restarts while a schedule is pending
- **THEN** either the remaining attempts run or the schedule ends cleanly, and in both cases the
  periodic poll still observes the increase
