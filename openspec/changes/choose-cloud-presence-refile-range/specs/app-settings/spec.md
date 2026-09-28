## MODIFIED Requirements

### Requirement: Re-file history action

The cloud presence section SHALL offer a one-time action to re-file already-recorded play against a user-confirmed historical range and, separately within that confirmation, offer to date eligible pre-Backlogium owned-game play already counted by the opt-in Steam-history import. It SHALL offer exact reversal once completed. Before starting, it SHALL show the earliest retained observation or clearly say none is available, let the user explicitly choose a valid start date up to the present, and show the effective range that will be acquired. A recent-31-days default SHALL match the existing re-file's start when the user accepts it without choosing a different date. A custom start date SHALL be in the past, SHALL not precede the earliest retained observation's calendar date in the user's timezone, and SHALL not be after the current date. An unavailable or stale bound SHALL be refreshed or rejected before acquisition rather than silently clamped to an undisclosed date.

When the player opts to date pre-Backlogium imported play, Settings SHALL ask them to explicitly confirm a pre-data cutoff date/time, distinct from the selected cloud start date, with timezone and exact effective instant visible. The cutoff SHALL be after the effective start and no later than the fixed end; Settings SHALL not infer a first-sync timestamp from the first recorded session. The player MAY decline the imported-play transfer while still re-dating existing sessions. If the separate Steam-history import was never performed, Settings SHALL not claim that cloud presence can create owned-game minutes. The action SHALL state plainly what it changes and what it does not: dates, daily quests and streaks may change; an opted-in imported-play transfer moves *already-counted* minutes from imported to tracked/dated sessions, but experience, levels, the combined credited minutes, and each game's Steam total do not change. It SHALL say that the poller does not prove every minute of old Steam history or precise Steam accounting at each time. During acquisition, Settings SHALL show the selected range/cutoff, fixed end, completed pages/transitions, and whether a bounded batch needs explicit continuation. Completion SHALL show both the actual covered range and the counts of sessions/dates affected, minutes newly dated and minutes left imported; a completed zero-change re-file SHALL still show its range. While a completed re-file is applied, the section SHALL show its saved range/cutoff and require reversal before applying another or resetting the separate Steam-history import.

Stating the limit is load-bearing rather than polite. The natural expectation of an action that recovers a month of more accurate history is that progression improves, and it will not — only its placement in time and its tracked/imported classification do. An action that lets that expectation stand would read as broken when it worked correctly.

#### Scenario: Offered while configured
- **WHEN** the cloud section is shown while configured and the action has not been run
- **THEN** the action is offered, with its effect, limit, available beginning, selected range and optional imported-play transfer explained

#### Scenario: Not offered while unconfigured
- **WHEN** no cloud endpoint is configured
- **THEN** the action is not offered

#### Scenario: Default and custom start choices
- **WHEN** a user confirms the untouched recent-31-days choice
- **THEN** acquisition uses the same rolling recent-window beginning as today's re-file
- **WHEN** the user instead selects a valid earlier calendar date
- **THEN** the confirmation shows that date and the effective time boundary, including the earliest retained observation if it truncates the selected date

#### Scenario: Invalid or unavailable start
- **WHEN** the selected start is in the future, is not a past date for a custom choice, precedes the earliest retained observation's local date, or the available beginning is unknown
- **THEN** the action does not start and Settings explains the constraint or lookup failure

#### Scenario: Pre-data cutoff is explicitly chosen
- **WHEN** an imported Steam-history balance exists and the player chooses to date part of it
- **THEN** Settings requires confirmation of a cutoff within the chosen range and shows the exact time/zone before starting
- **AND** it explains that the cutoff is the player's estimate of when Backlogium began recording, not a detected first-sync timestamp

#### Scenario: Player only wants date correction
- **WHEN** the player does not choose pre-data dating, or has no Steam-history import
- **THEN** the selected range may still re-date existing sessions without creating new owned-game minutes from the poller

#### Scenario: Completed state
- **WHEN** the action has completed
- **THEN** the section reflects that it has run, shows its effective applied range and optional pre-data cutoff, and offers its reversal instead

#### Scenario: The limit is stated before it runs
- **WHEN** the action is presented
- **THEN** it states that experience, levels and total playtime are unchanged by it

#### Scenario: Outcome reported
- **WHEN** the action completes, even if no session changed
- **THEN** the section reports the selected and actually covered range, how many existing sessions were re-filed, how many imported minutes became dated tracked sessions, how many remain imported, and how many dates were affected

#### Scenario: Partial batch is not completion
- **WHEN** a batch reaches its page or time limit, goes offline, or fails before reaching the fixed end
- **THEN** Settings shows progress and offers an explicit retry or continue action without claiming that the re-file was applied

#### Scenario: Reversal returns the previous attribution
- **WHEN** the user reverses a completed re-filing
- **THEN** original session attribution and transferred imported balances return to their pre-action classification without erasing later ordinary play, and the action is offered again with a new start-date/cutoff choice

#### Scenario: Resetting the Steam-history import while a transfer is applied
- **WHEN** the player requests the separate Steam-history import reset before reversing a cloud pre-data transfer
- **THEN** Settings explains that they must undo the cloud re-file first instead of silently losing or double-counting transferred minutes
