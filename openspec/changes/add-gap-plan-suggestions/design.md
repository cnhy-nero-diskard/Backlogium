## Context

See `proposal.md` for motivation. The current app already exposes visible domain-level library games
with truthful per-source playtime, all four HLTB lengths, broad Store genres, and recency. Personal
Pace can forecast expected minutes for an injected future date range, while collection summaries
already calculate remaining work by subtracting stored playtime from a selected HLTB estimate.

The missing pieces cross several boundaries. Steam review summaries are not represented anywhere.
The existing Store `appdetails` response contains participation categories, but the DTO and genre
cache deliberately retain only broad genres and app type. Current-player counts are live,
non-persisted facts whose normative lifecycle is currently tied to game detail. Collection creation
is already atomic through `CollectionRepository.save`, so adoption should reuse that transaction
rather than add a second persistence protocol.

The feature must remain useful offline, must not trigger implicit HLTB lookups, and must keep Room
entities out of new UI state. Recommendation output must also be stable enough to become a month-long
commitment rather than changing whenever a cache or live request emits.

## Goals / Non-Goals

**Goals:**

- Keep eligibility, capacity, candidate scoring, bundle composition, and explanations in a pure,
  deterministic domain engine with injected dates and plain values.
- Produce meaningfully different risk envelopes without claiming statistical confidence the source
  data cannot establish.
- Add review and multiplayer metadata through bounded, credential-free, independently failing
  enrichment paths.
- Finalize one immutable result snapshot per generation and convert the accepted membership through
  the existing atomic collection transaction.
- Make every recommendation traceable to facts visible to the player.

**Non-Goals:**

- Recommending games outside the locally tracked Steam library or purchasing wishlist games.
- Predicting or scraping release dates; the anticipated title and date are player-confirmed inputs.
- Automatically refreshing HLTB data, resolving HLTB matches, or treating missing lengths as zero.
- Learning a durable taste profile, collecting explicit likes/dislikes, or using cloud inference.
- Reserving capacity against other collections, constructing a daily calendar, or guaranteeing a
  completion date.
- Persisting recommendation snapshots, current-player counts, or derived scores.
- Backing up refreshable Steam review and participation-category caches.
- Letting any network fact decide which games a plan contains.

## Decisions

### 1. Use a pure gap-plan engine behind one repository-level feed

Add pure domain request, candidate, reason, variant, and result types. The engine accepts the current
date, a capacity source, visible game projections, recent session projections, cached suggestion
metadata, and optional live-count facts. It performs no Room, Retrofit, Android, or clock work.

A thin injected feed assembles one consistent input snapshot from `GameRepository.library`,
`PersonalPaceRepository.profile`, session aggregates, and suggestion metadata. The feed needs two
distinct session reads and they must not be conflated: `SessionRepository.trackedMinutesByGame`
(all-time, for truthful family-shared playtime) and `SessionRepository.closedSessionsSince`
(windowed `PlaySession` rows carrying `appId`, for genre affinity). The ViewModel owns the one-shot
generation lifecycle and edits to its transient result.

This follows `SmartCollections` plus `SmartCollectionFeed`: one shared derivation avoids Home and
Collections producing different answers from the same facts. A screen-local scoring implementation
was rejected because it would couple behavior to Compose state and be difficult to test exhaustively.

### 2. Treat capacity utilization as a transparent planning policy

Reliable Personal Pace supplies expected minutes from tomorrow through the target date inclusive.
A learning profile cannot support the existing definitive feasibility language, so setup requires a
positive one-off total-hours value and records `MANUAL` as the capacity provenance. No manual value is
stored as a new pace preference.

The target date is bounded to 1,095 days ahead, reusing `PersonalPace.DEFAULT_FIT_HORIZON_DAYS`.
`PersonalPaceProfile.forecast` walks the range one day at a time, so an unbounded target date is
both a meaningless plan and an unbounded loop. The bound is the horizon `earliestFitDate` already
applies, not a new policy.

The planning window's "today" must be read from the injected time source at generation time.
`PersonalPaceRepository` is a `@Singleton` that captures `today` and `cutoffMillis` once at
construction, so a long-lived process that crosses local midnight would otherwise compute "tomorrow"
from a stale date. The feed takes the profile from that repository but derives the forecast range
itself.

The three variant budgets are calculated from the same full-capacity value:

```text
fullCapacityMinutes = floor(expected gaming minutes for tomorrow..targetDate inclusive)
Relaxed  = floor(fullCapacityMinutes * 0.70)
Balanced = floor(fullCapacityMinutes * 0.85)
Full     = floor(fullCapacityMinutes * 1.00)
```

The percentages are planning intensities, not confidence intervals. Inflating every HLTB estimate
and also reducing capacity was rejected because two arbitrary safety adjustments would be difficult
to explain and excessively conservative. The three visible utilization levels give the player the
choice directly.

### 3. Reuse truthful playtime and require resolved selected-basis work

Story maps to `MAIN_STORY`; Completionist maps to `COMPLETIONIST`. The feed calculates displayed
playtime through the source-aware domain rule already used by player-facing summaries:
`GameSource.displayedPlaytimeMinutes` — Steam total for owned games, and manual estimate plus
tracked sessions for family-shared games. Remaining work is the wider-type-safe equivalent of
`max(estimate - playtime, 0)`, matching `CollectionSummary`'s existing derivation.

Only visible games with a positive selected estimate and positive remaining work enter composition.
Missing, unmatched, and needs-review HLTB states contribute to a reported coverage count but trigger
no request. This preserves the explicit-target HLTB contract. Both unplayed and unfinished games are
enabled initially, while setup can disable either category without changing stored library state.

Achievement completion is not used as a second eligibility definition. The request explicitly asks
how long the selected HLTB run remains, so mixing an achievement-first completion rule into this path
would produce contradictions such as calling a Story run complete only after every trophy.

### 4. Separate candidate quality from bundle composition

Candidate quality uses normalized components and retains the component values that generated its
reason codes:

```text
candidateQuality = 0.50 * reviewQuality
                 + 0.30 * genreAffinity
                 + 0.20 * completionMomentum
```

`reviewQuality` is the 95% Wilson lower bound of positive versus negative reviews. This prevents
tiny perfect samples from dominating established ratings.

A missing review summary contributes the explicit neutral constant `0.5` and carries no review
reason; a missing or empty genre signal does the same for `genreAffinity`. A fixed neutral is still
a chosen value rather than an absence, and its consequence is stated here rather than discovered
later: on a fresh install, an offline device, or any library the bounded enrichment has not yet
reached, every candidate shares both neutral constants, so 80% of `candidateQuality` is a constant
and ranking collapses onto `completionMomentum` plus the bundle-level terms. That is the intended
degradation — a plan built from duration and progress alone, disclosed as such. A neutral was still
preferred over a zero, which would rank an un-enriched game below a genuinely badly reviewed one.

`genreAffinity` is derived from the latest 56 completed local dates with the same 28-day recency
half-life as Personal Pace. Each game contributes at most once per active date to each of its broad
genres, regardless of minutes that day. Genre weights are normalized against the strongest observed
genre; a game uses the mean of its known genre weights. Date-level presence was chosen over raw
minutes because an endless game or one unattended marathon would otherwise dominate the player's
inferred taste.

`completionMomentum` is the clamped played fraction of the selected estimate for an unfinished
started game and zero for an unplayed game. It is deliberately the smallest component: existing
progress should help surface a finishable game without turning every plan into backlog cleanup.

Each variant independently searches combinations of one to five candidates under its minute budget.
Bundle value is:

```text
bundleValue       = 0.60 * mean(candidateQuality)
                  + 0.30 * budgetUtilization
                  + 0.10 * genreDiversity

budgetUtilization = plannedMinutes / thisVariantBudgetMinutes
```

Genre diversity is based on pairwise overlap among games with known genres; unknown genres are
neutral rather than maximally diverse. Mean candidate quality, rather than summed quality, avoids
giving five mediocre one-hour games an automatic advantage over two excellent games. Utilization
still rewards using the available window.

That objective is deliberately non-monotone: `mean(candidateQuality)` rewards small sets while
`budgetUtilization` rewards large ones, so a partial combination's value bounds nothing about the
value of any combination extending it. The pruning rule is therefore part of the contract rather
than an implementation detail, because it decides the answer:

```text
order      candidates processed by candidateQuality desc, remaining minutes asc, app id asc
frontier   at most 32 partial combinations retained after each candidate is considered
key        bundleValue of the partial combination, scored exactly as a final bundle
tie-break  planned minutes desc, member count asc, lexicographic app-id list
```

A fixed width and a fixed processing order are what make "identical inputs produce identical plans" a
testable property rather than an aspiration. Objective components, planned minutes, member count, and
the lexicographically sorted app-id list provide deterministic comparison of finished bundles in that
order.

A greedy next-best-game algorithm was rejected because an early medium-length selection can prevent a
later combination with better total quality, fit, and diversity. An exhaustive search was rejected
because `n choose 5` is unbounded for a large library.

### 5. Reuse Store appdetails for participation categories and cache reviews separately

Extend the existing Store appdetails DTO and definitive result to carry only the participation
category identifiers and labels needed by this feature. Genres and categories remain separately
decoded domain values. This costs no additional appdetails request and preserves existing genre
behavior.

Four details of that reuse are load-bearing, and each would produce a wrong result if left implicit:

**Categories are matched by stable numeric id, never by label.** `StoreGenreDto.id` is a `String`
because Store genre ids arrive as strings; the `categories` array's `id` arrives as a *number*, so
the category DTO needs its own numeric-id shape rather than a copy of the genre DTO. Labels are
localized — the existing call already sends `l=english` — so a label match would silently classify
nothing the moment that parameter changes. The multiplayer, online co-op, and massively-multiplayer
id set is defined once as a named constant table, and every id in it is pinned from a captured
`appdetails` fixture for a real app of that kind rather than from memory; task 2.1 captures those
fixtures first for exactly this reason.

**Unknown categories and no categories are different values.** The category column is nullable: null
means never retrieved, an empty decoded list means the Store answered and this app advertises none.
Only the second may be read as single-player. This is the distinction the existing `appType` column
already makes for app kind, for the same reason.

**An upgraded install must actually refetch.** `GameGenreCacheDao.eligibleAppIds` selects only rows
that are absent or older than the 30-day window, so a library enriched before this change would carry
fresh rows with a null category column and refetch nothing for up to 30 days — the entire multiplayer
path inert on exactly the installs that have the most data. Eligibility is widened to include a row
whose category payload is null, so the migration's unknown rows are picked up by the next enrichment
batch. Resetting `checkedAt` in the migration was rejected: it would also discard genre freshness that
is still valid.

**A refused envelope is not a definitive absence.** `SteamStoreGenreDataSource` currently records a
`success = false` envelope — delisted, region-locked, not a store item — as `Details(emptyList(),
appType = null)`, caching it as a checked negative for 30 days, while `SteamStoreAppDataSource` calls
the identical case `Unavailable`. Piggybacked categories inherit the first treatment, which would
classify every delisted multiplayer game as single-player. The category write therefore leaves the
payload null for a refused envelope rather than writing an empty list.

Add a separate review-cache table keyed by app id with positive, negative, total, Steam description,
availability state, and checked timestamp, and a foreign key to `games` with `ON DELETE CASCADE` as
`game_genre_cache` has. Reviews come from the credential-free Store reviews summary endpoint at
`appreviews/{appId}` on the existing `store.steampowered.com` Retrofit client, with `json=1`,
`language=all`, `purchase_type=all`, and the page-size parameter that suppresses review bodies —
whose exact name is confirmed from a captured response during task 2.2 rather than assumed, since only
the summary counts are wanted. A separate table is preferred because review and appdetails requests
can succeed, fail, and become stale independently.

Review refresh mirrors genre enrichment: missing before stale, at most 25 apps per worker run, at
least 500 ms between requests, a 30-day freshness window, a 15-minute continuation delay, hidden games
excluded from selection, and retry without overwriting last-known success after transient failure.
Refresh is scheduled independently of authenticated sync. A combined per-game Store worker was
rejected because one endpoint failure would unnecessarily stop durable progress from the other
endpoint.

That rejection has a cost worth stating plainly: two independent chains against one host through one
shared OkHttp client double the app's worst-case Store request rate, from 25 per 15 minutes to 50.
Both chains stop a batch on the first transient failure, and an HTTP 429 arrives as an `HttpException`
and is therefore already classified as transient, so a throttled client backs off rather than
hammering — but the aggregate figure is the one to check against Steam's behavior, not the per-chain
one.

### 6. Live counts decorate finalized plans and never choose their members

Plan membership is a function of local state only. The three variants finalize from cached and locally
derived facts before any network request is made, so an offline device, a timed-out enrichment, and a
fully enriched run all produce exactly the same games.

After the variants finalize, the ViewModel performs one current-player lookup per distinct multiplayer
member of those variants — at most fifteen app ids, because three variants of at most five games
cannot contain more, and usually fewer once shared members are deduplicated. Lookups run with a
concurrency of 4 inside an 8-second overall enrichment window. A generation identity owns every
lookup; cancellation, navigation, or regeneration invalidates that identity so a predecessor cannot
publish into its successor, and any result arriving after the window is discarded.

An available count may do exactly two things: order multiplayer rows within the variant that already
contains them, and supply a factual "playing now" reason chip. It may not add a game, remove a game,
or move a game between variants. Unavailable counts contribute no ordering preference and remain
different from a successful count of zero.

Letting counts swap otherwise-similar multiplayer alternatives was rejected. It would have required an
invented similarity threshold, and it would have made the same request produce different plans
depending on whether the network answered in time — contradicting the requirement that identical
inputs produce identical plans, and undermining a snapshot the player is being asked to commit a month
to. Persisting counts or reusing them on game detail was rejected separately: a stale live fact would
be misleading and would contradict the current player-count lifecycle.

### 7. Keep editing transient and adoption stable

The ViewModel retains the ranked eligible pool with the finalized result. Removing a member or
choosing a replacement reruns validation and bundle totals locally; replacement choices that exceed
the selected budget remain unavailable. Neither operation mutates library or collection state.

Acceptance maps the final preview to the existing atomic `CollectionSaveDraft` path. Every field of
that draft is set explicitly, `id` included — `CollectionRepository.save` branches on `id == 0L` to
choose creation over update, so it is the field that decides what the save means:

```text
id           = 0            // 0 selects creation; any other value would update an existing row
name         = "Before <anticipated title>"
mode         = DEADLINE_GOAL
sort         = CollectionMode.DEADLINE_GOAL.defaultSort()
targetDate   = confirmed target date, ISO-8601
accent       = null         // default neutral styling, as a manually created collection gets
timeBasis    = MAIN_STORY or COMPLETIONIST
description  = null
memberAppIds = accepted app ids
doneAppIds   = empty
```

This creates a normal collection with stable membership and all existing edit, backup, sync-survival,
and forecasting behavior. A new recommendation-specific collection table and an auto-updating smart
collection were rejected: both would duplicate collection behavior, and automatic membership drift
would undermine a plan the player had committed to follow.

### 8. Add one pushed adaptive surface, not another navigation destination

Home and Collections link to the same route. Setup and results share one ViewModel-scoped flow so the
player can go back to adjust inputs without writing preferences.

Results present the capacity figures at two levels, because a per-variant figure alone would hide the
policy. The request's full forecast capacity is stated once for all three variants; each variant then
states its own budget, its planned minutes, and its reserve as `variantBudget - planned`. A Relaxed
card that showed only "4,200 available, 300 reserve" would conceal the 1,800 minutes the 70% intensity
deliberately withheld, which is the opposite of what decision 2 claims to offer.

Each variant card also carries capacity provenance, up to five game cards, factual reason chips, HLTB
coverage disclosure, replacement, and save actions. Family-shared membership is always labeled.

The save confirmation repeats the generated name, target date, basis, and member count before the
atomic write. On failure it releases busy state and retains the preview. The surface follows existing
game-card artwork, density, reduced-motion, and collection-overlay patterns rather than introducing a
new visual system.

## Risks / Trade-offs

- [Steam review and Store endpoints are undocumented or can change shape] -> Keep narrow DTOs, pin
  every field and category id from a captured fixture rather than from memory, distinguish definitive
  absence from transient failure and from a refused envelope, retain last-known cache values, and let
  plans work with neutral missing metadata.
- [Two independent Store chains double the app's worst-case request rate against one host] -> State
  the aggregate bound rather than the per-chain one, keep both chains stopping on the first transient
  failure, and rely on 429 already being classified as transient; revisit the shared spacing if Steam
  throttles in practice.
- [Wilson quality still reduces taste to community sentiment] -> Keep review quality to half of
  candidate quality, expose the underlying review fact, and combine it with personal and plan-level
  signals.
- [A library the enrichment has not reached ranks almost entirely on progress] -> Accept and disclose
  it; the plan states the facts it used, and the coverage disclosure says what was missing rather than
  implying the ranking was better informed than it was.
- [Genre affinity can mistake repeated obligation for enjoyment] -> Count active dates rather than
  hours, use recency decay, keep the signal weak and explainable, and do not persist a behavioral
  profile.
- [Beam search can miss the mathematical global optimum] -> Fix the width, the processing order, and
  the frontier key so the result is at least reproducible, and test adversarial packing cases;
  recommendation usefulness matters more than claiming exact optimization.
- [A current-player snapshot varies by time zone and hour] -> Keep it out of membership entirely, use
  it only to order rows and state a fact, label it as current, and do not persist it.
- [A learning player can enter an unrealistic manual budget] -> Label manual provenance clearly and
  apply the same visible utilization levels rather than presenting a Personal Pace claim.
- [Independent gap plans and existing collections can overcommit the same future time] -> Preserve the
  existing non-reservation model and avoid language implying that capacity has been booked.
- [Five-game cap can leave substantial time unused when only very short games qualify] -> Show the
  reserve honestly; the cap protects result readability and the request for a focused set.

## Migration Plan

1. Extend the Store category cache with a nullable participation-category field and add a separate
   review-cache table in one Room migration; existing rows remain valid with unknown categories. In
   the same step widen Store enrichment eligibility to treat a null category payload as work to do,
   so those migrated rows refresh on the next batch instead of waiting out their remaining genre
   freshness.
2. Extend Store parsing and family-shared seeding, then add review acquisition, repository mapping,
   bounded worker scheduling, and migration tests before exposing the cache to recommendations.
3. Add and test the pure recommendation engine, including confidence-adjusted reviews, recency genre
   affinity, capacity provenance, eligibility, bundle search, explanations, and deterministic ties.
4. Add the aggregation feed, then the post-finalization live-count decoration, keeping the local path
   complete on its own so the decoration is never load-bearing.
5. Add setup/results navigation and UI, then map acceptance through the existing atomic collection
   save protocol.
6. Verify offline generation, cancellation ownership, process recreation before save, save failure,
   and both fresh-install and upgraded-database behavior — the upgraded case specifically confirming
   that a pre-existing enriched library acquires categories rather than waiting 30 days.

Rollback removes the route, worker, feed, and recommendation engine. The extra cache table and
nullable category column can remain inert under a downgraded feature build; no user-authored state
depends on them. Collections already accepted by the player remain ordinary deadline collections and
must not be deleted during rollback.
