## ADDED Requirements

### Requirement: Streak-broken acknowledgement on Home
When a streak the player had built ends, Home SHALL present a one-time acknowledgement naming the
length that was lost, shown once and not again for that break. The acknowledgement SHALL be
dismissible, SHALL NOT block the rest of Home, and SHALL NOT appear for a player who has never held
a streak.

#### Scenario: Break acknowledged once
- **WHEN** a streak of 14 days ends and the player next opens Home
- **THEN** an overlay states that the streak was broken and names its length

#### Scenario: Not shown again for the same break
- **WHEN** the player has dismissed the streak-broken overlay and returns to Home, including after
  the app process has been killed and relaunched
- **THEN** the overlay is not shown again for that break

#### Scenario: A later break is acknowledged separately
- **WHEN** the player rebuilds a streak and it later breaks again
- **THEN** the overlay is shown once for the new break, naming the new length

#### Scenario: Never held a streak
- **WHEN** a player who has never reached a streak of at least one day opens Home
- **THEN** no streak-broken overlay is shown

#### Scenario: Not shown for a rule change
- **WHEN** the player raises the daily quest goal so that the current streak recomputes to zero
- **THEN** no streak-broken overlay is shown, because the streak was not lost through play

#### Scenario: Home remains usable
- **WHEN** the streak-broken overlay is shown
- **THEN** the rest of Home remains reachable, and dismissing the overlay requires no confirmation

#### Scenario: Reduced motion honored
- **WHEN** the system indicates that animations should be reduced or disabled
- **THEN** the overlay appears without motion and its message remains fully legible
