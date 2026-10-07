## Context

See proposal.md for scope. PresenceService already runs a 30-second presence loop subject to opt-in and Android service limits. LiveStatusRepository.checkNow returns early on failed fetches; successful observations update the recorded session and visible state, publish PlaySessionEnd, then invoke the family-shared PresenceObserver. Post-play sync and cloud reading already exist.

AchievementRepository coalesces same-user/game requests and coordinates writes. AchievementMerge preserves non-null snapshotPercent but can fill an absent snapshot later. ProgressMarksStore has a durable transition protocol for existing progress events, not a general unlock-event queue.

## Goals / Non-Goals

**Goals:** bounded active-game traffic; retryable observations; account-safe writes; durable logical events; clear delivery limits.

**Non-Goals:** changing SessionDiffer, session attribution, playtime totals, existing cloud/background schedules, or the ordinary sync snapshot policy.

## Decisions

### Presence integration and generation ordering

Introduce one watch coordinator through the existing successful-presence dispatch seam. Define its sequence explicitly: (1) commit recorded session and visible state, (2) invalidate/cancel any previous watch generation on stop/account/game change, (3) publish the existing PlaySessionEnd, (4) invoke the existing family-shared PresenceObserver with its failure isolation, (5) activate/retain the eligible current watch and schedule its nonblocking fetch. A→B therefore stops A before post-play A dispatch and starts B after the shared-game observation/admission step. Same-game observations retain their generation. Failed fetches return before all five steps. Do not add a second nowPlaying collector that guesses whether a retained failed-fetch state is a fresh observation.

The coordinator serializes its own lifecycle: same account/game retains the generation and baseline; a confirmed stop cancels the generation; A→B cancels A before starting B. Failed presence fetches do not stop or switch the watch. stopPolling/service shutdown, opt-out, account reset, and hiding the game cancel it. Rehydrated state alone does not start requests. A hidden raw game remains part of the existing session lifecycle but cannot become a watch target or alert.

Fetch outside the coordinator lock; revalidate account, visible game, generation, settings, and service lifecycle immediately before a coordinated write. Discard cancelled/stale results. Cancellation never records a playtime end. No new final fetch after a confirmed stop; existing post-play behavior stays independent.

### A steady bounded cadence

Fetch immediately on confirmed activation, then no faster than the selected 60/30-second interval. No overlapping ticks or catch-up burst. Unchanged successful reads keep this cadence rather than drifting to the old five-minute interval. Offline pauses network work; transient errors use bounded exponential backoff and Retry-After where available, reset after success. Failure backoff can exceed one minute and is described as such. Normal schedules are best effort while the monitor can run.

### Prepare, validate, commit, then advance

Use the existing keyed fetch/write coordination; extend a prepared observation API rather than adding an independent DAO writer. Compare with the watch's last successfully committed unlocked set, not an uncommitted response and not only today's stored rows (another refresh may already have stored an unlock).

For unlocked rows with no existing non-null snapshot, require a usable current global percentage for that specific row. Validate zero as a real percentage and reject absent/invalid values. Fetch globals only if required; a missing statistic defers the whole watch observation. A deferred/failed write changes neither rows, pending events, nor baseline. Locked→unlocked/player success/globals failure followed by the same unlocked/player success/globals success must recover the snapshot and exactly one logical group.

The first usable commit seeds the watch-session baseline and produces no unlock event; it may hydrate historic rows. Later commits atomically merge achievements and insert a unique pending group. Retired rows, descriptions, freshness/timestamp guards, and non-null snapshot immutability follow the existing merger. A later watch read may repair an existing null snapshot; repair alone is not a newly observed unlock.

Invoke the existing RecomputeSource.SYNC recompute when a successful write changes XP-bearing achievement inputs; never calculate XP here or recompute merely for unchanged freshness. Each watch commit stores an internal work record with a recoverable recompute-needed marker where applicable and an optional unlock-group payload. Baseline or repair-only commits have no delivery event but can still retain recompute work. Clear the marker after coordinated recompute, so a crash after the Room commit cannot leave the profile stale. Do not nest the non-reentrant progress transition coordinator inside its own protocol.

### Durable groups and delivery

Add Room watch-work/outbox records keyed by stable observation ID with account, game, observation time, recompute-needed state, and optional delivery payload/state. A pending unlock payload carries its event ID and sorted achievement identities/names/icons/unlock dates and snapshot values; internal recompute-only records never become alerts. Use a stable canonical identity from account/game/watch epoch and sorted unlock identities; retries reuse it. Multiple groups remain separate and ordered. Surface payloads never re-read a changing game/achievement list.

A dispatcher durably chooses foreground or background once. Foreground claims are terminal before presenting, ensuring at-most-once presentation; a crash between claim and render can suppress that one alert, an explicit trade-off. Unclaimed groups survive restart. Background posts use an event-derived stable notification ID and onlyAlertOnce; uncertain posting retries replace the same notification before acknowledging dispatch. Android notification delivery and the Room write cannot be one atomic transaction, so this promises one logical event and idempotent posting, not universally exactly-once physical sound/vibration.

Denied notification permission/disabled alerts produce a terminal suppressed state; they never become a surprise backlog later. Independent alert controls do not stop achievement persistence. Re-check account/hidden status before dispatch and suppress stale payloads. Account replacement/reset clears pending events; backups do not export them and restores do not replay them. Retain terminal identities for retry deduplication with bounded retention after their producing epoch has closed.

Existing level/quest/streak transition recovery and acknowledgement stay unchanged. The stable order becomes level-up, streak milestone, unlock group, quest met, streak broken. Only the chosen in-app group receives an unlock haptic; background vibration is governed by the notification channel/system preference.

## Risks / Trade-offs

- Frequent calls use battery/API quota → one active game, shared request coalescing, no bursts, offline pause, error backoff.
- Process death or Android monitoring budget ends the cadence → retain normal sync as catch-up, expose monitor state honestly.
- A missing global statistic delays otherwise valid data → scope the stronger rule to watch commits needing a snapshot; ordinary sync remains unchanged.
- External notification posting has a crash window → stable IDs, durable dispatch state, honest physical-delivery limits.
- Schema evolution/export leakage → migration tests plus explicit outbox exclusion from backup and account-reset tests.

## Migration Plan

Implement a versioned Room migration for the outbox; upgrade creates it empty and emits no historical alerts. Add settings with watch enabled only when the separate live monitor is opted in; use 60 seconds and alerts enabled by default subject to notification permission. Roll back operationally by disabling watch; do not downgrade the database. Validate migration and older backup restore paths before enabling the watcher in release.
