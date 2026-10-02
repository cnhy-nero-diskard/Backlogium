## Context

See proposal.md for motivation. `SessionRepository` exposes bounded reads, finalized-session access, and hidden filtering. Analytics already compares period totals but has no per-game momentum model. Stored starts are approximate and use canonical local start-date attribution; their earliest visible record establishes only a record boundary, not continuous observation. Steam player counts are looked up fresh, polled every 30 seconds on detail, and explicitly not persisted by `steam-player-count`. They supply no historical growth series.

The new algorithm, bounded reads, and account/date invalidation require a design. Personal momentum follows `3c`'s scope convention and remains independent of `1c`/`2c`. Community feasibility is a research deliverable within this plan; its unresolved sampling choices do not alter the personal feature's fixed contract.

## Goals / Non-Goals

**Goals:** deterministic personal comparisons that stay useful offline; explicit evidence/eligibility; bounded computation; a documented community go/no-go decision.

**Non-Goals:** continuous tracking, current-player sampling/storage, a cloud service, a composite personal/community score, first-ever-play claims, a second recent-play collection, or user-configurable scoring in this version.

## Decisions

### Fixed completed weeks avoid partial-day comparisons

For local date `D`, compare current dates `[D-7, D-1]` and baseline dates `[D-14, D-8]`. Convert their bounds using the existing zone/time providers and exclusive local-midnight upper boundaries. Use finalized positive recorded session minutes, assigned wholly to each session's start date. This excludes today's partial data and unsettled open sessions. Date spans are equal in local calendar days; daylight-saving changes do not imply equal elapsed hours.

The card explicitly says it compares recent completed weeks and prints both ranges. It remains fixed when the main Analytics period selector changes. This is a documented first-version choice, not an implicit reuse of an unrelated year/month selection.

Alternative: follow every Analytics window length. Deferred because sparse annual/month-to-date comparisons introduce additional equal-duration/partial-period rules and make the initial feature harder to explain. The fixed dates provide one bounded, testable personal signal.

### Eligibility and ranking are transparent

For each eligible visible library title, compute current minutes `C`, baseline minutes `P`, and distinct positive-activity dates in the current week. Require `C >= 60` and at least two active dates. Growth also requires `P >= 30`, `delta = C-P >= 30`, and `4 * delta >= P`; use wide integer arithmetic so eligibility does not depend on rounded percentages. `P == 0` produces a separate newly-recorded-activity candidate, not a percentage. `0 < P < 30` is too weak a baseline for a growth claim.

Require the earliest eligible finalized visible library record to be on/before the baseline start; unknown or later boundaries produce an insufficient-history state. This is a conservative span gate, not a claim that recording was continuous. Every result is labelled recorded activity with an incomplete-tracking caveat. Old unlocks/imports cannot establish a session-record boundary.

Sort growth by delta descending, current minutes descending, app ID ascending. Sort newly recorded activity by current minutes descending, app ID ascending; place it in a distinct group after growth and take at most five combined rows. Display both amounts and the qualifying reason. Round a displayed percentage only after qualification and disclose the underlying amounts. Distinguish learning, eligible-but-no-increase, and available candidates.

Alternative: a weighted opaque score or raw percentage ranking. Rejected because low baselines can dominate, first-time recorded play has no finite growth percentage, and the criteria should be explainable from the row's data.

### Read one bounded snapshot and invalidate completely

Add a pure domain derivation fed by a repository snapshot containing the two periods' finalized per-game/per-date summaries, eligible library identities, visibility, and minimal earliest-record metadata. Aggregate in bounded storage reads or off-main-thread mapping; do not bring lifetime session detail into Analytics merely to compute a fourteen-day card. No new dependency or schema migration is expected.

Read committed inputs coherently. Cache/coalesce by account identity, dates, zone, eligible-library/visibility revision, and session revision, including finalization and re-filing. Clear old content on account changes. Keep prior same-account dated results explicitly updating while recomputing, without relabelling them as new dates. Reject obsolete completions after date/zone/account transitions.

Alternative: cache only by last session ID or selected Analytics range. Rejected because corrections/visibility/finalization can change an old row without inserting a new one, and this card has its own fixed dates.

### Small Analytics surface with clear evidence

Add a compact personal momentum card without changing the existing main headline priority. Use `3c`'s visible scope/semantics convention, resource-backed periods and amounts, named game-detail actions, and action-time visibility checks. A reachable explanation states comparison criteria and recording limits; essential personal/date scope remains inline. Preserve the main selector on detail return. A recent-play collection answers membership, while this card answers comparison and exposes its qualifying reason.

### Community feasibility precedes any sampling feature

Document the outcome in this design during the research task. Assess official API/usage constraints, consistent observation slots, cap/shortlist choice, opt-in, caching, retention, retry/backoff, low-count exclusions, missing samples, and local-versus-shared collection. Verify current constraints from primary sources then; no unverified quota or price is asserted by this proposal.

Use an explicit cost worksheet: scheduled requests/day = titles × slots/day; rows retained = titles × slots/day × retention days; add measured retry, payload, indexing, and multi-device costs. An illustrative candidate of 20 titles, four aligned slots/day, and 14-day retention implies 80 scheduled requests/day and 1,120 retained samples before retries, per independent sampler. These are evaluation assumptions, not implemented or approved sampling defaults. Compare a recently active shortlist with whole-library expansion and account for identical-game requests duplicated across devices/users.

Record a go/no-go with concrete request/storage/network budgets and verified comparability rules. Lack of affordable reliable comparable evidence is a valid no-go, leaving personal momentum as the outcome. A go result only makes a later `5c-add-community-play-momentum` proposal eligible for user-requested planning; it does not authorize sampling, a worker, persistence, or cloud-poller changes here. The current detail poll remains unchanged.

## Risks / Trade-offs

- Delayed or incomplete tracking can distort apparent growth → compare recorded facts only and retain explicit caveats; never promise continuous coverage.
- Closed-only comparison omits a long-running unfinalized session → explain finalized evidence and refresh when it settles.
- Fixed thresholds may omit small increases → document them, test exact boundaries, and revise the spec before changing the policy.
- Corrections or hidden-game changes can stale a result → invalidate the full contributing snapshot rather than rely on insertion watermarks.
- A fixed card near a selected-period dashboard can confuse scope → print both dates and state that it covers recent completed weeks.
- Community feasibility can broaden scope → separate its research output and preserve a local-only implementation regardless of its result.

## Migration Plan

Apply after `3c`: build bounded snapshot and pure derivation, then wire the card and state transitions, then verify offline/device behavior. No activity write or database migration is expected. Rollback removes the read model/card without altering history. Complete community research as a documented decision; keep #173's community portion open unless separately implemented. `5c` remains conditional future work and is not scaffolded by this series.

## Community feasibility record — 2026-10-03

### Verified constraints

`GetNumberOfCurrentPlayers` accepts one app ID and returns a current connected-player count, excluding offline players. Its documented parameters provide neither historical observations nor a batch list. One unkeyed public HTTPS research probe for app 570 returned HTTP 200/result 1 and a 47-byte JSON body at 2026-10-02T20:20:28.984Z. This verifies one successful lookup, not historical coverage or an availability guarantee. [Valve method documentation](https://partner.steamgames.com/doc/webapi/ISteamUserStats)

Valve distinguishes public HTTPS methods from publisher-key partner-server access. Array parameters require explicit method support. Public traffic uses an edge cache; the overview gives no measurement-freshness SLA for this count. Receipt time therefore cannot establish when Valve measured it. A 403 must stop requests rather than trigger repeated retries. [Valve Web API overview](https://partner.steamgames.com/doc/webapi_overview)

The general API terms permit free use with a 100,000-call daily ceiling and disclaim availability/accuracy guarantees. Treat that ceiling as a shared usage constraint, not a reserved throughput allowance or permanent contract; any deployment needs its own current terms review. Keep keys confidential and disclose collected data and destinations. [Valve API terms](https://steamcommunity.com/dev/apiterms)

Android periodic work accepts timing inexactness from battery optimization and Doze. A local sampler cannot promise four aligned daily observations while the app is idle. [Android PeriodicWorkRequest documentation](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)

### Evaluation candidate, not implemented defaults

- Explicit opt-in only. Select at most 20 currently visible library titles with positive finalized activity in the last 30 local dates; freeze the shortlist for a 14-day comparison. Visibility/eligibility changes invalidate that title's comparable panel. Stop collection and purge retained samples on opt-out. Do not collect Steam IDs. A shared service would still receive the chosen app IDs, revealing library interest, and needs a separate privacy/security design.
- Four UTC slots daily (00:00, 06:00, 12:00, 18:00), accepting receipts within ten minutes. Retain actual receipt time, slot identity and lateness; do not call receipt time a server measurement timestamp. Compare matched weekday/slot pairs across the two weeks, requiring at least 20 of 28 pairs and five distinct dates in each week. Disclose coverage. No zero filling, carrying forward, or historical catch-up: a current lookup cannot reconstruct missed observations.
- Exclude titles whose paired baseline median is below 100 players; low denominators are unstable. These experimental community criteria require a pilot and have no effect on shipped personal thresholds. Even matching slots cannot eliminate unknown upstream cache age.
- Cache one successful row per app/slot and deduplicate retry receipts. Retain only 14 days: at most 1,120 successful rows. A request has a ten-second deadline and at most one retry for 429/5xx with 30–120-second jitter, honoring Retry-After only within the slot window. Otherwise record missing evidence; stop on authentication/403 failures. Cap requests and transport bytes; do not retry beyond the daily budget.
- Independent devices duplicate API work and local storage; those duplicates are not extra evidence. A shared sampler can deduplicate by the union of titles, but adds server operations, client reads, credentials, privacy controls and infrastructure costs. None are authorized here.

### Bounded cost worksheet

All figures below are estimates, not measured operational results. Four slots/day, 14-day retention, 5% retry expectation and at most one retry/request are assumptions. Allow 128 bytes per stored observation plus an equal indexing allowance, and 4 KiB transport per request including protocol overhead. The single measured 47-byte body omits headers/TLS/DNS and failed-response sizes. SQLite/WAL/metadata and actual transport must be measured before shipping a sampler.

| Scenario | Scheduled / expected / maximum requests per day | Retained rows | Raw / indexed storage | Expected / maximum transport per day |
| --- | ---: | ---: | ---: | ---: |
| One device, 20 titles | 80 / 84 / 160 | 1,120 | 140 / 280 KiB | 336 / 640 KiB |
| One device, 1,000-title library | 4,000 / 4,200 / 8,000 | 56,000 | 6.84 / 13.67 MiB | 16.41 / 31.25 MiB |
| Two independent devices, same 20 titles | 160 / 168 / 320 | 2,240 across devices | 280 / 560 KiB | 672 KiB / 1.25 MiB |
| Shared 100 users, all sharing 20 titles | 80 / 84 / 160 | 1,120 server rows | 140 / 280 KiB | 336 / 640 KiB, server API traffic only |
| Shared 100 users, disjoint 20-title lists | 8,000 / 8,400 / 16,000 | 112,000 server rows | 13.67 / 27.34 MiB | 32.81 / 62.5 MiB, server API traffic only |

Formulae: scheduled calls = titles × 4; expected calls = scheduled × 1.05; retry maximum = scheduled × 2; rows = titles × 4 × 14; indexed storage = rows × 128 × 2 bytes; transport = requests × 4 KiB. At 47 body bytes, 84 expected requests total only 3,948 body bytes, illustrating why body-only budgeting is insufficient. Whole-library sampling multiplies the 20-title budget by 50. Shared client delivery, compute and database prices remain unpriced because no service/provider is selected; API free use does not imply free infrastructure.

### Decision and concrete gates

**NO-GO for production community momentum now.** The shortlist estimate is small, but there is no measured 14-day paired coverage, upstream freshness evidence, retry/storage/transport distribution or low-count robustness. Local background scheduling cannot guarantee alignment, and shared infrastructure/privacy/operating costs are undecided.

A future user-requested proposal would require an opt-in pilot demonstrating the paired-coverage rule and measured per-device budgets: target at most 100 requests/day, hard cap 160 including retries, hard cap 1 MiB/day transport and 2 MiB retained SQLite data including indexes/metadata/WAL. Over-budget or incomplete days remain missing, never fabricated. Shared sampling needs separate explicit operating and privacy budgets before approval. These are conditional pilot gates, not deployed settings or authorization to implement collection.

This change ships offline personal momentum only. It adds no sampler, worker, schema, persistence or cloud poller and leaves the existing detail lookup unchanged. Do not scaffold `5c`. #173's community implementation remains open; #147's broader roadmap and #168's membership/collection behavior are not completed by this comparison card.
