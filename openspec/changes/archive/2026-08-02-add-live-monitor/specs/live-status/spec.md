## ADDED Requirements

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
