# steam-sync

## ADDED Requirements

### Requirement: A game has at most one open session
The system SHALL ensure that no game can have two open sessions stored at the same time,
whichever mechanism synthesized them. This SHALL hold when two observations for the same game
are derived concurrently and commit in either order, and SHALL NOT depend on process-scoped
mutual exclusion for its correctness, because observations reach the session ledger from paths
that do not share a lock.

Where an observation would open a session for a game that already has one open, the system
SHALL treat that observation as an extension of the existing session rather than as a new
session, or SHALL reject it — never append a second open row.

The consequence this prevents is not a transient duplicate but a permanent one. Once two open
rows exist for one game, a query for "the" open session returns an arbitrary one of them: one
row can remain orphaned open indefinitely, session counts are doubled in history and
analytics, and subsequent extension and close actions land on whichever row the unordered
query happened to return. There is no external source of session boundaries to reconcile
against afterwards, so the duplicate cannot be resolved after the fact.

#### Scenario: Two concurrent observations for the same game
- **WHEN** two observations for a game with no open session are derived concurrently, and both
  reach the write boundary before either has committed
- **THEN** exactly one open session exists for that game afterwards, and the other observation
  either extends it or is rejected

#### Scenario: Commit order does not matter
- **WHEN** two concurrent observations for the same game commit in either order
- **THEN** the resulting stored state is the same, and contains one open session

#### Scenario: Correctness does not rest on a process lock
- **WHEN** the guarantee is exercised with any process-scoped sync coordination disabled
- **THEN** it still holds, because the paths that write sessions do not all take the same lock

#### Scenario: Different games are unaffected
- **WHEN** two different games each have an open session at the same time
- **THEN** both are valid, because the constraint is per game and not global

#### Scenario: Natural-key lookups stay tolerant
- **WHEN** the backup/restore merge engine looks up a session by its game, start, and end
  timestamps
- **THEN** that lookup remains tolerant of a real-world collision among closed sessions, and a
  collision there does not fail a sync or an import

### Requirement: A synthesized session interval is never inverted
A stored session SHALL NOT have an end timestamp earlier than its start timestamp. When an
observation's timestamp precedes the interval it would open or extend — as happens when the
device wall clock moves backwards between polls — the system SHALL NOT store the inverted
interval. It SHALL either refuse the action or clamp the boundary so the interval remains
non-inverted, and the refusal or clamp SHALL be recorded rather than discarded silently.

The guarantee SHALL apply to every emission site on the playtime-diffing path, both the action
that opens a session and the action that extends one, since both derive a boundary from the
current clock reading.

Tracked minutes are unaffected by this requirement: they come from Steam's reported delta and
remain correct across a clock change. What a rollback corrupts is session history and recency,
for which the device clock is the only source — so an impossible interval that reaches storage
cannot be repaired from anywhere.

#### Scenario: Clock moves backwards while a session is open
- **WHEN** a game has an open session and the next observation reporting a playtime increase
  arrives with a timestamp earlier than that session's start
- **THEN** no stored session has an end earlier than its start

#### Scenario: Rollback while opening a session
- **WHEN** an observation would open a session whose derived start is later than its derived
  end, because the clock moved backwards between the previous poll and this one
- **THEN** the inverted interval is not stored

#### Scenario: Rewound boundary does not survive into a close
- **WHEN** a session's boundary was subject to a backwards clock movement and a later
  observation reporting no increase closes it
- **THEN** the closed session's interval is still non-inverted

#### Scenario: Tracked minutes are preserved
- **WHEN** a backwards clock movement causes an action to be refused or clamped
- **THEN** the playtime delta Steam reported is still accounted for, because it does not
  depend on the device clock

#### Scenario: Forward clock jumps remain ordinary
- **WHEN** the device clock moves forward between polls
- **THEN** sessions extend normally, because a forward jump produces no inverted interval
