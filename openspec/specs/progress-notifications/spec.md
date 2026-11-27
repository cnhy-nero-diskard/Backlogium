# Progress Notifications

## Purpose

Delivers configurable Android notifications for committed library and progress events and a previous-day summary of local evidence.

## Requirements

### Requirement: Progress notices reflect committed events
The app SHALL offer Android alerts for newly added games, broken streaks, daily quota and newly completed games. Alerts SHALL follow committed eligible transitions for the current account.

#### Scenario: Earned quest and streak events
- **WHEN** the authoritative quest or streak evaluator commits an eligible quota-met or streak-broken event
- **THEN** the corresponding enabled category receives at most one Android delivery per committed event

#### Scenario: Acquisition or shared admission
- **WHEN** a confirmed post-baseline poll commits a genuinely new owned game, or an automatic shared admission inserts a genuinely new visible game
- **THEN** an enabled notice names the game and opens its detail through the shared notification route, with one notice for the admission

#### Scenario: Administrative changes or replay
- **WHEN** a first-sync baseline, restore, import, refile, replay, or shared-to-owned conversion updates stored state
- **THEN** historical or already delivered events do not generate new notices

### Requirement: Every progress notice has an independent durable receipt
Every committed progress-alert event SHALL have an account-scoped durable notification receipt or crash-recoverable handoff. A foreground acknowledgement SHALL NOT remove or acknowledge it. Workers SHALL recover pending receipts without relying on the foreground pending-event list or a notification enqueue having already happened.

#### Scenario: Foreground acknowledgement leaves notification delivery pending
- **WHEN** a committed quest or streak event is acknowledged in the foreground before its notification worker runs
- **THEN** the in-app claim is acknowledged and the independent notification receipt remains pending for one notification delivery

### Requirement: Notification receipts are committed at source-event boundaries
Each receipt SHALL be keyed by category and stable source-event identity. It SHALL be persisted with the source transition, or an idempotent handoff SHALL be committed in the same transaction and retained until the receipt exists. If controls are stored separately, the handoff SHALL preserve the event-time eligibility decision.

#### Scenario: Quest and streak receipts follow earned finalization
- **WHEN** an earned quest-met or streak-broken event is finalized
- **THEN** its account-scoped receipt or durable handoff is committed at that boundary

#### Scenario: Owned acquisition reuses admission identity
- **WHEN** a confirmed post-baseline poll commits a genuinely new owned game
- **THEN** its receipt or handoff uses the durable account/game admission identity from #159, not a later best-effort settings write

#### Scenario: Automatic shared admission is recorded with insertion
- **WHEN** an automatic shared-game admission inserts a new game row
- **THEN** its receipt or handoff commits with that insert, and an already tracked game creates no new admission receipt

#### Scenario: Completion is recorded with confirmed merge
- **WHEN** the coordinated achievement merge changes a known-incomplete game to a non-empty confirmed fully unlocked set
- **THEN** its receipt or handoff commits with that merge

#### Scenario: Initial complete baseline is not an event
- **WHEN** the first confirmed achievement set for a game is already fully unlocked
- **THEN** the merge seeds the baseline without creating a completion receipt or handoff

#### Scenario: Process dies after an owned admission commit
- **WHEN** a post-baseline owned poll commits a new game and its durable receipt or handoff, then the process dies before notification work is enqueued
- **THEN** restart recovers one pending account-scoped receipt and can deliver it without relying on the in-app acquisition banner

#### Scenario: Process dies after an automatic shared admission
- **WHEN** an automatic shared-game insert commits with its durable receipt or handoff, then the process dies before notification work is enqueued
- **THEN** restart recovers one pending account-scoped receipt for that admission

#### Scenario: Process dies after verified completion merge
- **WHEN** the achievement merge commits a confirmed incomplete-to-complete transition with its durable receipt or handoff, then the process dies before notification work is enqueued
- **THEN** restart recovers one pending account-scoped completion receipt

#### Scenario: Process dies after an earned event finalizes
- **WHEN** an earned quest or streak event and its notification receipt or handoff are committed, then the process dies before dispatch
- **THEN** a later worker recovers the pending receipt without reading the in-app pending-event list

### Requirement: Posting uses a durable at-most-once claim
Before invoking Android for a progress-alert receipt, a worker SHALL durably compare-and-set its state from `PENDING` to `POST_CLAIMED`. Only the worker that changes the state SHALL invoke `NotificationManager.notify()`; a normal return SHALL advance it to `DELIVERED`. `POST_CLAIMED` SHALL be terminal and SHALL NOT be retried after restart or an ambiguous result, even if the notification is absent.

#### Scenario: Crash before claim leaves the receipt retryable
- **WHEN** a worker crashes before changing a receipt from `PENDING` to `POST_CLAIMED`
- **THEN** the receipt remains pending, and a later worker may claim and post it once

#### Scenario: Crash after claim but before Android posting may lose the alert
- **WHEN** a worker durably claims a receipt and the process dies before calling `NotificationManager.notify()`
- **THEN** recovery treats `POST_CLAIMED` as terminal and does not post; losing this alert is allowed

#### Scenario: Crash after posting does not re-alert after dismissal
- **WHEN** Android accepts a notification, the process dies before saving `DELIVERED`, and the user dismisses the notification before restart
- **THEN** recovery sees `POST_CLAIMED` and does not call `notify()` again, even though the original notification is no longer present

#### Scenario: Concurrent workers race to claim one receipt
- **WHEN** multiple workers concurrently compare-and-set the same pending receipt
- **THEN** exactly one changes `PENDING` to `POST_CLAIMED` and may call `notify()`; all others observe the claimed state and do not post

### Requirement: Definitive pre-post failures remain retryable
Failures known to occur before the durable post claim SHALL leave a receipt pending and retryable. Ineligible receipts SHALL remain terminally suppressed.

#### Scenario: Transient failure before claim
- **WHEN** a transient failure occurs during eligibility or notification preparation before the durable claim
- **THEN** the receipt remains `PENDING` and can be retried without risking a duplicate post

### Requirement: Stable notification identity only supports replacement
Workers SHALL use stable account/event notification identity with repeat-alert suppression to update a notification that remains present, but SHALL NOT rely on it as the deduplication guarantee because reposting after dismissal can create a new alert.

#### Scenario: A present notification is replaced quietly
- **WHEN** a notification with the same account/event identity is posted while the original is still present
- **THEN** Android updates the existing notification without repeating its alert; this does not guarantee deduplication after dismissal

### Requirement: Notification suppression is terminal for every category
At each source event's eligibility boundary, a receipt SHALL be pending only when the category preference and Android permission/channel state allow delivery; otherwise it SHALL be terminally suppressed. Before posting, the worker SHALL recheck the active account and current controls, terminally suppressing a receipt that is no longer eligible.

#### Scenario: Permission or preference suppresses any category
- **WHEN** the category is disabled or Android permission/channel state disallows posting at the source event's eligibility boundary
- **THEN** its receipt is terminally suppressed and any in-app progress claim or committed admission remains available

#### Scenario: Notification permission is denied
- **WHEN** an enabled category with an enabled channel reaches its source event boundary while Android notification permission is denied
- **THEN** its receipt is terminally suppressed and the in-app claim or committed admission remains available

### Requirement: Progress channels are registered before eligibility checks
At app initialization, the app SHALL register all stable progress-alert channels, including those for default-off categories, before any source event checks channel eligibility. Registration SHALL be idempotent and preserve existing Android settings.

#### Scenario: First enabled event after installation
- **WHEN** the app initializes with a new install and default-off categories, then the player enables a category with notification permission granted before its first eligible earned event
- **THEN** its stable channel is already registered before finalization, the receipt remains pending, and the worker posts one notification rather than suppressing it for a missing channel

### Requirement: Missing channels are not user-disabled
A missing channel SHALL indicate incomplete registration, not a user-disabled channel. The app SHALL retain the durable handoff and retry registration rather than terminally suppressing the receipt. It SHALL NOT replace a channel ID to bypass a user's settings.

#### Scenario: Channel registration is incomplete
- **WHEN** a source event reaches eligibility checking before its required channel is available
- **THEN** the durable handoff remains available for retry and is not terminally suppressed as though the user had disabled the channel

#### Scenario: Existing channel is disabled
- **WHEN** an eligible event is finalized while its already-registered Android channel is disabled
- **THEN** its receipt is terminally suppressed, the in-app claim remains available, and no replacement channel ID is created

### Requirement: Enabling or restoring does not replay suppressed alerts
A terminally suppressed receipt SHALL NOT be reopened by later enabling a preference or permission. Restore SHALL NOT import receipts or reconstruct them from historical progress or in-app claims.

#### Scenario: Enable after an event or restore
- **WHEN** a category or permission is enabled after its event was terminally suppressed, or progress is restored
- **THEN** no notification receipt is reopened or reconstructed for that historical event

### Requirement: Completion alerts require confirmed achievement evidence
A completion alert SHALL require a reliable transition from known incomplete to a non-empty confirmed player achievement set with all achievements unlocked. Its receipt or durable handoff SHALL be committed with that achievement merge. A first complete baseline SHALL seed the state without alerting.

#### Scenario: Estimate or partial progress
- **WHEN** a game reaches its HLTB estimate, or Steam returns only incomplete or unavailable achievement evidence
- **THEN** no verified completion alert is produced

#### Scenario: Canonical completion
- **WHEN** a normal eligible unlock commit completes a previously incomplete confirmed set
- **THEN** the current account can receive one enabled completion alert for that game

#### Scenario: Completion merge retries
- **WHEN** a verified completion merge is retried after its receipt was committed
- **THEN** the same account/game event identity is reused and no duplicate receipt or alert is created

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
