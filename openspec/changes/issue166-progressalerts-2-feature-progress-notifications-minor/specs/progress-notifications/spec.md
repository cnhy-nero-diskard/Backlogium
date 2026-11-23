# Progress Notifications Delta

## Purpose

Delivers configurable Android notifications for committed library and progress events and a previous-day summary of local evidence.

## ADDED Requirements

### Requirement: Progress notices reflect committed events
The app SHALL offer Android alerts for newly added games, broken streaks, daily quota and newly completed games. Alerts SHALL follow committed eligible transitions for the current account.

#### Scenario: Earned quest and streak events
- **WHEN** the authoritative quest or streak evaluator commits an eligible quota-met or streak-broken event
- **THEN** the corresponding enabled category receives at most one Android delivery per committed event

#### Scenario: Acquisition or shared admission
- **WHEN** a newer confirmed library poll or automatic shared admission commits a genuinely new visible game after baselining
- **THEN** an enabled notice names the game and opens its detail through the shared notification route, with one notice for the admission

#### Scenario: Administrative changes or replay
- **WHEN** a first-sync baseline, restore, import, refile, replay, or shared-to-owned conversion updates stored state
- **THEN** historical or already delivered events do not generate new notices

### Requirement: Progress notification receipts are independent of in-app claims
Every committed earned quest-met or streak-broken event SHALL have a durable notification receipt keyed by account, category, and stable event identity (quest date or streak-break transition/date). The receipt SHALL be persisted at earned-event finalization or through a crash-recoverable handoff that survives until receipt commit. A foreground acknowledgement SHALL NOT remove or acknowledge it. Workers SHALL recover pending receipts without relying on the foreground pending-event list.

#### Scenario: Foreground acknowledgement leaves notification delivery pending
- **WHEN** a committed quest or streak event is acknowledged in the foreground before its notification worker runs
- **THEN** the in-app claim is acknowledged and the independent notification receipt remains pending for one notification delivery

#### Scenario: Process death before dispatch
- **WHEN** an earned quest or streak event and its notification handoff are committed, then the process dies before dispatch
- **THEN** a later worker recovers the pending receipt and can deliver it without reading the in-app pending-event list

### Requirement: Notification retries retain stable event identity
Workers SHALL use stable account/event notification identity and repeat-alert suppression so retries or duplicate execution cannot create a second logical or visibly repeated notification.

#### Scenario: Worker retry or duplicate execution
- **WHEN** a worker retries or receives the same receipt more than once, including after an ambiguous post
- **THEN** the stable account/event notification identity and repeat-alert suppression prevent a second logical or visibly repeated alert

### Requirement: Notification suppression is terminal
At earned-event finalization, a receipt SHALL be pending only when the category preference and Android permission/channel state allow delivery; otherwise it SHALL be terminally suppressed. Before posting, the worker SHALL recheck the active account and current controls, terminally suppressing a receipt that is no longer eligible.

#### Scenario: Permission or preference suppresses an event
- **WHEN** the category is disabled or Android permission/channel state disallows posting at earned-event finalization
- **THEN** the receipt is terminally suppressed and the in-app progress claim remains available

### Requirement: Enabling or restoring does not replay suppressed alerts
A terminally suppressed receipt SHALL NOT be reopened by later enabling a preference or permission. Restore SHALL NOT import receipts or reconstruct them from historical progress or in-app claims.

#### Scenario: Enable after an event or restore
- **WHEN** a category or permission is enabled after its event was terminally suppressed, or progress is restored
- **THEN** no notification receipt is reopened or reconstructed for that historical event

### Requirement: Completion alerts require confirmed achievement evidence
A completion alert SHALL require a reliable transition from known incomplete to a non-empty confirmed player achievement set with all achievements unlocked. A first complete baseline SHALL seed the state without alerting.

#### Scenario: Estimate or partial progress
- **WHEN** a game reaches its HLTB estimate, or Steam returns only incomplete or unavailable achievement evidence
- **THEN** no verified completion alert is produced

#### Scenario: Canonical completion
- **WHEN** a normal eligible unlock commit completes a previously incomplete confirmed set
- **THEN** the current account can receive one enabled completion alert for that game

### Requirement: Daily recap summarizes the previous local day
An enabled recap SHALL be scheduled on a best-effort basis and deliver at most once per account and previous local date. It SHALL summarize recorded play time, distinct eligible games, and recorded unlocks with supported dates, disclosing gaps and estimates.

#### Scenario: Day or zone changes
- **WHEN** the recap worker runs after a local date or time-zone change
- **THEN** it resolves and labels yesterday using the current zone and does not replay older dates

#### Scenario: Incomplete coverage
- **WHEN** playtime is estimated or an unlock has only an observation date
- **THEN** the recap labels the supported scope and does not assign an unsupported unlock date

### Requirement: Android delivery respects user and account state
Alerts SHALL respect per-category preferences, notification permission and Android channel settings. Delivery SHALL be restart-safe and account-scoped, and in-app progress SHALL remain available when system alerts are suppressed.

#### Scenario: System settings turn a channel off
- **WHEN** a corresponding eligible event occurs
- **THEN** it does not vibrate or post through a substitute channel

#### Scenario: Account changes before dispatch
- **WHEN** an older account still has queued notices
- **THEN** those notices are not posted for the new account
