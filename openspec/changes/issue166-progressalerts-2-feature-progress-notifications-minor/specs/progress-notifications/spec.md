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
