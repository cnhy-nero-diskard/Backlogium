## ADDED Requirements

### Requirement: Installed state on game detail
Where a desktop agent is paired and has reported, game detail SHALL state whether the game is
installed on that desktop, together with when that was last confirmed, and SHALL state nothing
about installation where no report exists.

#### Scenario: Installed
- **WHEN** the game appears in the most recent installed report
- **THEN** game detail states that it is installed on the paired desktop, and when that was
  confirmed

#### Scenario: Not installed
- **WHEN** the game is absent from the most recent installed report
- **THEN** game detail states that it is not installed on the paired desktop, and when that was
  confirmed

#### Scenario: No agent paired
- **WHEN** no agent is paired
- **THEN** game detail makes no claim about installation, and is presented exactly as it is today

#### Scenario: Paired but never reported
- **WHEN** an agent is paired but no report has been received
- **THEN** game detail makes no claim about installation

### Requirement: Ready-to-play filter in the Library
Where installed state is known, the Library SHALL offer a filter limiting it to games installed on
the paired desktop. The filter SHALL be absent rather than empty when no installed state is known.

#### Scenario: Filtering to installed games
- **WHEN** the player applies the ready-to-play filter
- **THEN** the Library shows only games in the most recent installed report

#### Scenario: Filter reflects dated state
- **WHEN** the filter is applied while the desktop is unreachable
- **THEN** it filters on the last reported state and the Library indicates when that was reported

#### Scenario: Filter unavailable without a report
- **WHEN** no agent is paired, or none has reported
- **THEN** the filter is not offered

#### Scenario: Existing sorting and grouping unaffected
- **WHEN** the ready-to-play filter is applied
- **THEN** the Library's existing sort, grouping, and density behaviour are unchanged
