## ADDED Requirements

### Requirement: Installed state is reported from the host
The system SHALL determine which owned games are installed by reading the host's own Steam
installation records, covering every configured library location rather than a single default one,
and SHALL report each installed game's identifier and its size on disk.

#### Scenario: Games across several library folders
- **WHEN** the host has Steam libraries on more than one drive
- **THEN** games installed in every configured library are reported

#### Scenario: Size reported alongside identity
- **WHEN** an installed game is reported
- **THEN** the report carries the space it occupies on disk

#### Scenario: A game removed since the last report
- **WHEN** a game has been uninstalled on the host and a new report is received
- **THEN** it is no longer reported as installed

### Requirement: An unreadable report is not an empty one
Where the host's installation records cannot be read or understood, the system SHALL treat the
result as no report rather than as a report that nothing is installed, and SHALL retain the
previously known state.

#### Scenario: Records in an unrecognised format
- **WHEN** the host's installation records cannot be parsed
- **THEN** no installed state is overwritten, and the condition is distinguishable from a genuinely
  empty library

#### Scenario: Records unreadable
- **WHEN** the records exist but cannot be read
- **THEN** the previously reported state is retained with its original date, and is not replaced by
  an empty set

#### Scenario: A genuinely empty library
- **WHEN** the host has Steam installed with no games installed
- **THEN** an empty installed set is reported, and it is distinguishable from a failure to read

### Requirement: Installed state is always dated
The app SHALL retain the most recent report so that installed state remains available while the
host is unreachable, and SHALL present when that state was last confirmed wherever it is shown.
The app SHALL NOT present retained state as a current fact.

#### Scenario: Host asleep
- **WHEN** the host is unreachable and the player views their library
- **THEN** the last reported installed state is shown, together with when it was reported

#### Scenario: Freshly reported
- **WHEN** a report has just been received
- **THEN** installed state is shown as current

#### Scenario: State never reported
- **WHEN** no report has ever been received for a paired agent
- **THEN** no installed state is claimed for any game

### Requirement: Ownership and installation are independent facts
The system SHALL treat Steam ownership and local installation as separate observations and SHALL
NOT infer either from the other.

#### Scenario: Owned but not installed
- **WHEN** a game is owned and absent from the installed report
- **THEN** it is shown as owned and not installed

#### Scenario: Installed but not owned
- **WHEN** the host reports a game installed that the player's Steam library does not include
- **THEN** the app disregards it rather than adding it to the library

#### Scenario: Ownership unaffected by an unreachable host
- **WHEN** the host has never been reachable
- **THEN** every owned game is presented exactly as it is today, with no installation claim either
  way
