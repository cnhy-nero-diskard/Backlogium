## ADDED Requirements

### Requirement: Achievement watching and unlock alerts are independently configurable
Settings SHALL expose watch enablement, a 60-second default/30-second alternative, and independent unlock-alert enablement. Watching SHALL require the separate opted-in live monitor. Disabling alerts SHALL retain achievement persistence; disabling watching SHALL cancel its traffic. Settings SHALL explain best-effort monitoring and slower failure backoff.

#### Scenario: Watch enabled without live monitor
- **WHEN** the watch preference is enabled but live monitoring is off
- **THEN** no watch requests are scheduled and the prerequisite is explained

#### Scenario: Alert opt-out
- **WHEN** the player disables unlock alerts while leaving watching enabled
- **THEN** new achievement observations persist without feedback

#### Scenario: Cadence selection
- **WHEN** the player selects thirty seconds
- **THEN** subsequent eligible ticks use that interval without immediate catch-up requests
