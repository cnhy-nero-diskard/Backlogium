## ADDED Requirements

### Requirement: Re-file history action

The cloud presence section SHALL offer a one-time action to re-file already-recorded play against
the presence record, and SHALL offer its reversal once completed.

The action SHALL state plainly what it changes and what it does not: that play may move to the
dates it actually happened, and therefore that daily quests and streaks may change; and that
experience, levels, and each game's total playtime will not change at all.

Stating the limit is load-bearing rather than polite. The natural expectation of an action that
recovers a month of more accurate history is that progression improves, and it will not — only its
placement in time does. An action that lets that expectation stand would read as broken when it
worked correctly.

#### Scenario: Offered while configured

- **WHEN** the cloud section is shown while configured and the action has not been run
- **THEN** the action is offered, with its effect and its limit both stated

#### Scenario: Not offered while unconfigured

- **WHEN** no cloud endpoint is configured
- **THEN** the action is not offered

#### Scenario: Completed state

- **WHEN** the action has completed
- **THEN** the section reflects that it has run and offers its reversal instead

#### Scenario: The limit is stated before it runs

- **WHEN** the action is presented
- **THEN** it states that experience, levels and total playtime are unchanged by it

#### Scenario: Outcome reported

- **WHEN** the action completes
- **THEN** the section reports what changed — how many sessions were re-filed and how many dates
  were affected — rather than only that it finished

#### Scenario: Reversal returns the previous attribution

- **WHEN** the user reverses a completed re-filing
- **THEN** attribution returns to what it was and the action is offered again
