## ADDED Requirements

### Requirement: History session facts are individually understandable
History SHALL identify each session's approximate local start, recorded minutes, and open/live state as separate facts with textual and accessible equivalents. It SHALL preserve canonical start-date attribution and SHALL NOT pair tracked minutes with an exact start-end range or present an approximate start as directly observed. A cloud contribution SHALL remain a separately qualified fact when stored evidence supports it; current ownership or reader configuration alone SHALL NOT establish the amount's historical source.

#### Scenario: Closed session
- **WHEN** a closed session is expanded
- **THEN** its approximate start and recorded minutes are explicitly labelled without an exact end-time-derived duration

#### Scenario: Open session
- **WHEN** an open session is shown
- **THEN** the row identifies the live state and recorded amount without treating elapsed wall-clock time as additional recorded play

#### Scenario: Overnight session
- **WHEN** a session starts before midnight and continues into the next day
- **THEN** it appears once on its start date with an understandable attribution explanation

#### Scenario: Cloud or legacy evidence
- **WHEN** a session has proven cloud assistance or incomplete historical source evidence
- **THEN** its contribution remains separately qualified or its source is described conservatively, without a blanket Steam-only claim

### Requirement: History daily evidence and quest credit have distinct scopes
History SHALL keep day and per-game recorded totals consistent with their visible session contents and use the daily-activity-breakdown capability to explain stored quest credit separately when it differs. Stored quest outcomes SHALL remain authoritative when available; missing outcomes SHALL be presented as unavailable. A progress-only date SHALL retain its recorded credit and unavailable game allocation. Missing rows or gaps SHALL be described as no recorded evidence rather than proof the player did not play.

#### Scenario: Visible sessions and credit disagree
- **WHEN** a day's visible recorded total differs from the stored amount that supplied its quest result
- **THEN** History identifies both scopes and their reconciliation while retaining the stored quest outcome

#### Scenario: Progress-only date
- **WHEN** a recorded daily-progress row exists without visible session detail
- **THEN** the day retains its credit/outcome and explains that a game/session allocation is unavailable

#### Scenario: No recorded quest result
- **WHEN** session evidence exists without a stored daily-progress outcome
- **THEN** History shows the sessions with unavailable quest state instead of an invented failure

#### Scenario: Gap between recorded sessions
- **WHEN** displayed records have a temporal gap
- **THEN** the presentation does not assert no play occurred or interpolate unsupported activity

### Requirement: History grouping never implies a measured time axis
The existing day/game/session presentation SHALL make its grouping and ordering understandable. Game rows SHALL be ordered by recorded minutes with deterministic ties, and a timeline-style gutter SHALL NOT imply that row position, line length, or spacing measures elapsed time. Sessions inside a game SHALL remain ordered by their approximate start. Its text and layout SHALL remain readable with larger fonts and in both themes.

#### Scenario: Games interleave in time
- **WHEN** one game's sessions occur before and after another game's sessions
- **THEN** History identifies the game grouping rather than implying the game rows form one chronological sequence

#### Scenario: Sparse day
- **WHEN** few records span a long time interval
- **THEN** gutter spacing is not presented as proportional duration or continuous observation

### Requirement: History clarity preserves bounded navigation and reveal state
History clarity changes SHALL preserve its bounded loaded date window, older-content action, explicit fully-loaded state, current-day expansion, valid expanded game/session state, and game-detail navigation. They SHALL preserve evidence-backed cloud marks, their explanations, and exact-session reveal with a clear unavailable fallback. Recomputations SHALL retain a coherent prior snapshot with an updating state; account replacement SHALL clear old content.

#### Scenario: Older history loaded
- **WHEN** the player loads another bounded window
- **THEN** valid expanded rows remain stable and the older/fully-loaded state reflects the actual evidence floor

#### Scenario: Cloud activity reveals a session
- **WHEN** a cloud activity item targets a session still present in the loaded window
- **THEN** its exact day, game, and session remain revealable after the presentation change

#### Scenario: Account changes during loading
- **WHEN** the active account changes while history recomputes
- **THEN** the previous account's records are cleared and late results cannot repopulate them
