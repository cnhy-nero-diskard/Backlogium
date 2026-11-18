## Context

See proposal.md for scope. PresenceService already runs a 30-second presence loop subject to opt-in and Android service limits. LiveStatusRepository.checkNow returns early on failed fetches; successful observations update the recorded session and visible state, publish PlaySessionEnd, then invoke the family-shared PresenceObserver. Post-play sync and cloud reading already exist.

AchievementRepository coalesces same-user/game requests and coordinates writes. AchievementMerge preserves non-null snapshotPercent but can fill an absent snapshot later. ProgressMarksStore has a durable transition protocol for existing progress events, not a general unlock-event queue.

A read-only device investigation on 2026-11-14 found app IDs 1462040, 2909400, and 552520 stored as owned with zero lifetime/recent minutes, no achievement rows or sync metadata, and no target requests in retained diagnostics. The user confirmed these games are unplayed. All 247 zero-play owned games in the snapshot lacked metadata. This is the deliberate NEVER-tier exclusion, not evidence of private/no-stats responses or absent Steam achievements. Public global endpoints returned 63, 61, and 72 entries respectively; these are investigation observations, not hardcoded totals. Automatic shared admission intentionally becomes eligible for the next ordinary sync; manual import already persists through refreshOne.

## Goals / Non-Goals

**Goals:** browse achievements before play with bounded on-demand traffic; distinguish catalogue availability from player progress; bounded active-game traffic; retryable observations; account-safe writes; durable logical events; clear delivery limits.

**Non-Goals:** changing SessionDiffer, session attribution, playtime totals, existing cloud/background schedules, or the ordinary sync snapshot policy.

## Decisions

### On-demand browsing before play

Opening either game-detail presentation requests a repository-owned hydration for that one visible tracked game, regardless of owned/shared source or zero playtime. Cached local content renders immediately. No library-wide job, live-monitor opt-in, or recurring detail-screen achievement timer is introduced. Recomposition and repeat observation do not schedule more requests. Unplayed owned games remain excluded from routine background tier selection and reconciliation; opening detail or confirmed active play is the explicit exception.

Reuse AchievementRepository and its account/game fetch/write coordination, with prepared results that distinguish catalogue and player outcomes. Extend coalescing so a detail caller joining an existing player fetch can obtain a missing catalogue without duplicating the same network work or accepting a player-only response as catalogue success. Detail hydration, watch, manual refresh, sync, reconciliation, and post-play refresh share timestamp/account guards and last-good merge rules. Revalidate configured account, tracked membership, and hidden status before commit; a late response cannot repopulate a removed/hidden game or a replaced account. Closing detail cancels its subscription and any work owned only by it without cancelling a shared caller's fetch.

Cache successful catalogue loads, including confirmed empty catalogues, for the existing 30-day schema window. A fresh catalogue with existing player data renders from cache; a fresh catalogue with unknown player progress remains browsable without a player request on each visit. Once the catalogue is missing/stale, issue one hydration attempt per opening, serially fetching schema, player state, and globals where applicable. Failures do not advance the failed data-kind timestamp. Automatic retries require usable connectivity and use bounded exponential backoff starting at 60 seconds and capped at 15 minutes, honouring a longer Retry-After; no retry survives the last interested caller. An explicit retry can request another attempt while respecting rate-limit deadlines and coalescing. Routine sync/watch remains responsible for later player changes, so browsing never becomes a second fast poll.

Alternatives: fetching every unplayed game on discovery would scale with hundreds of untouched titles; retaining the NEVER gate for detail would preserve the reported empty sections. On-demand caching bounds work by games the user actually inspects.

### Catalogue availability is separate from personal progress

Persist schema entries and successful catalogue freshness independently of account-specific Achievement rows and GameAchievementSync. The catalogue stores objective names, icons, descriptions, hidden flags, and available global percentages. It must survive a player response of private/no-stats/failed without requiring synthetic locked Achievement rows. Schema success, confirmed empty schema, player success, unusable player response, and transport/throttling failure are separate outcomes. Empty global percentages alone cannot establish that a game has no achievements.

Extend repository domain output with catalogue entries and explicit progress availability. Overlay last-good confirmed player rows by achievement identity; catalogue-only objectives have unknown personal progress, no unlock time, no rarity snapshot, and no earned XP. A positive catalogue with empty/unusable player data stays visible with progress unavailable. It must not set hasAchievements=false or advance player freshness. Only authoritative successful player observations flow through the existing achievement merge and completion/XP inputs; catalogue-only rows do not enter completion badges, rarity standing, smart completion collections, watch baselines, or unlock events. Retained player rows are not deleted or retired by an incomplete catalogue or failed detail observation. A complete authoritative player refresh retains existing reconciliation retirement rules.

A successful empty schema with no contradictory stored player rows can establish a confirmed empty catalogue; contradictions retain last-good rows and an incomplete outcome. Cache empty catalogues to prevent repeated requests; an absent/malformed schema response is not authoritative emptiness. A later successful player observation can populate real unlock state through the usual merge, with an ordinary corrective hydration recompute only when XP inputs change; it creates no unlock group and does not move a running watch's committed baseline. Reuse the recoverable recompute-work mechanism for hydration writes that change XP inputs; do not introduce another XP calculator or emit historical earned-progress alerts.

Game detail distinguishes loading, cached/stale content, confirmed no achievements, and unavailable data with retry. Catalogue-only content explains that personal progress is unavailable instead of claiming every objective is locked. The summary stays usable throughout, and both Library and collection entry points behave alike. Full-refresh settings copy explains that reconciliation covers eligible played/shared games; unplayed owned lists load when opened. Its selection and background schedule remain unchanged.

### Presence integration and generation ordering

Introduce one watch coordinator through the existing successful-presence dispatch seam. Define its sequence explicitly: (1) commit recorded session and visible state, (2) invalidate/cancel any previous watch generation on stop/account/game change, (3) publish the existing PlaySessionEnd, (4) invoke the existing family-shared PresenceObserver with its failure isolation, (5) activate/retain the eligible current watch and schedule its nonblocking fetch. A→B therefore stops A before post-play A dispatch and starts B after the shared-game observation/admission step. Same-game observations retain their generation. Failed fetches return before all five steps. Do not add a second nowPlaying collector that guesses whether a retained failed-fetch state is a fresh observation.

The coordinator serializes its own lifecycle: same account/game retains the generation and baseline; a confirmed stop cancels the generation; A→B cancels A before starting B. Failed presence fetches do not stop or switch the watch. stopPolling/service shutdown, opt-out, account reset, and hiding the game cancel it. Rehydrated state alone does not start requests. A hidden raw game remains part of the existing session lifecycle but cannot become a watch target or alert.

Fetch outside the coordinator lock; revalidate account, visible game, generation, settings, and service lifecycle immediately before a coordinated write. Discard cancelled/stale results. Cancellation never records a playtime end. No new final fetch after a confirmed stop; existing post-play behavior stays independent.

### A steady bounded cadence

Fetch immediately on confirmed activation, then no faster than the selected 60/30-second interval. No overlapping ticks or catch-up burst. Unchanged successful reads keep this cadence rather than drifting to the old five-minute interval. Offline pauses network work; transient errors use bounded exponential backoff and Retry-After where available, reset after success. Failure backoff can exceed one minute and is described as such. Normal schedules are best effort while the monitor can run.

### Prepare, validate, commit, then advance

Use the existing keyed fetch/write coordination; extend a prepared observation API rather than adding an independent DAO writer. Compare with the watch's last successfully committed unlocked set, not an uncommitted response and not only today's stored rows (another refresh may already have stored an unlock).

For unlocked rows with no existing non-null snapshot, require a usable current global percentage for that specific row. Validate zero as a real percentage and reject absent/invalid values. Attempt fresh globals alongside player-state refreshes, preserving the existing per-data-kind policy; a missing statistic required for a new snapshot defers the whole watch observation. A globals failure with no required new snapshot may retain last-good percentages and commit. A deferred/failed write changes neither rows, pending events, nor baseline. Locked→unlocked/player success/globals failure followed by the same unlocked/player success/globals success must recover the snapshot and exactly one logical group.

The first usable commit seeds the watch-session baseline and produces no unlock event; it may hydrate historic rows. Later commits atomically merge achievements and insert a unique pending group. Retired rows, descriptions, freshness/timestamp guards, and non-null snapshot immutability follow the existing merger. A later watch read may repair an existing null snapshot; repair alone is not a newly observed unlock.

Invoke the existing RecomputeSource.SYNC recompute when a successful write changes XP-bearing achievement inputs; never calculate XP here or recompute merely for unchanged freshness. Each watch commit stores an internal work record with a recoverable recompute-needed marker where applicable and an optional unlock-group payload. Baseline or repair-only commits have no delivery event but can still retain recompute work. Clear the marker after coordinated recompute, so a crash after the Room commit cannot leave the profile stale. Do not nest the non-reentrant progress transition coordinator inside its own protocol.

### Durable groups and delivery

Add Room watch-work/outbox records keyed by stable observation ID with account, game, observation time, recompute-needed state, and optional delivery payload/state. A pending unlock payload carries its event ID and sorted achievement identities/names/icons/unlock dates and snapshot values; internal recompute-only records never become alerts. Use a stable canonical identity from account/game/watch epoch and sorted unlock identities; retries reuse it. Multiple groups remain separate and ordered. Surface payloads never re-read a changing game/achievement list.

A dispatcher durably chooses foreground or background once. Foreground claims are terminal before presenting, ensuring at-most-once presentation; a crash between claim and render can suppress that one alert, an explicit trade-off. Unclaimed groups survive restart. Background posts use an event-derived stable notification ID and onlyAlertOnce; uncertain posting retries replace the same notification before acknowledging dispatch. Android notification delivery and the Room write cannot be one atomic transaction, so this promises one logical event and idempotent posting, not universally exactly-once physical sound/vibration.

Denied notification permission/disabled alerts produce a terminal suppressed state; they never become a surprise backlog later. Independent alert controls do not stop achievement persistence. Re-check account/hidden status before dispatch and suppress stale payloads. Account replacement/reset clears pending events; backups do not export them and restores do not replay them. Retain terminal identities for retry deduplication with bounded retention after their producing epoch has closed.

Existing level/quest/streak transition recovery and acknowledgement stay unchanged. The stable order becomes level-up, streak milestone, unlock group, quest met, streak broken. Only the chosen in-app group receives an unlock haptic; background vibration is governed by the notification channel/system preference.

## Risks / Trade-offs

- A large untouched backlog multiplies requests → hydrate one inspected game, cache positive/empty catalogues, and leave background NEVER selection unchanged.
- Schema-only data could imply false completion/XP → keep catalogue storage and unknown player progress outside earned inputs.
- Frequent calls use battery/API quota → one active game, shared request coalescing, no bursts, offline pause, error backoff.
- Process death or Android monitoring budget ends the cadence → retain normal sync as catch-up, expose monitor state honestly.
- A missing global statistic delays otherwise valid data → scope the stronger rule to watch commits needing a snapshot; ordinary sync remains unchanged.
- External notification posting has a crash window → stable IDs, durable dispatch state, honest physical-delivery limits.
- Schema evolution/export leakage → migration tests plus explicit outbox exclusion from backup and account-reset tests.

## Migration Plan

Implement versioned Room migrations for the catalogue/cache metadata and outbox; upgrade preserves all player rows/snapshots and creates new tables empty without historical alerts. Catalogue data is a refetchable cache excluded from backup export; old backups remain supported and refill on demand. Reset/account replacement clears catalogue cache with the existing local library, while hidden-game guards suppress reads and requests. No eager whole-library catalogue backfill runs on upgrade. Add settings with watch enabled only when the separate live monitor is opted in; use 60 seconds and alerts enabled by default subject to notification permission. Roll back operationally by disabling watch; do not downgrade the database. Validate migration and older backup restore paths before enabling the watcher in release.
