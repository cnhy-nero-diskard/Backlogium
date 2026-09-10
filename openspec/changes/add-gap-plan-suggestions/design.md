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

## Decisions

### 1. Use a pure gap-plan engine behind one repository-level feed

Add pure domain request, candidate, reason, variant, and result types. The engine accepts the current
date, a capacity source, visible game projections, recent session projections, cached suggestion
metadata, and optional live-count facts. It performs no Room, Retrofit, Android, or clock work.

A thin injected feed assembles one consistent input snapshot from `GameRepository.library`,
`PersonalPaceRepository.profile`, session aggregates, and suggestion metadata. The ViewModel owns the
one-shot generation lifecycle and edits to its transient result.

This follows `SmartCollections` plus `SmartCollectionFeed`: one shared derivation avoids Home and
Collections producing different answers from the same facts. A screen-local scoring implementation
was rejected because it would couple behavior to Compose state and be difficult to test exhaustively.

### 2. Treat capacity utilization as a transparent planning policy

Reliable Personal Pace supplies expected minutes from tomorrow through the target date inclusive.
A learning profile cannot support the existing definitive feasibility language, so setup requires a
positive one-off total-hours value and records `MANUAL` as the capacity provenance. No manual value is
stored as a new pace preference.

The three variant budgets are calculated from the same full-capacity value:

```text
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
playtime through the source-aware domain rule already used by player-facing summaries: Steam total
for owned games, and manual estimate plus tracked sessions for family-shared games. Remaining work is
the wider-type-safe equivalent of `max(estimate - playtime, 0)`.

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

`reviewQuality` is the 95% Wilson lower bound of positive versus negative reviews. A missing review
summary uses a neutral component and carries no review reason. This keeps an offline cache gap from
becoming a fabricated poor rating while preventing tiny perfect samples from dominating established
ratings.

`genreAffinity` is derived from the latest 56 completed local dates with the same 28-day recency
half-life as Personal Pace. Each game contributes at most once per active date to each of its broad
genres, regardless of minutes that day. Genre weights are normalized against the strongest observed
genre; a game uses the mean of its known genre weights. No history or no known genres is neutral.
Date-level presence was chosen over raw minutes because an endless game or one unattended marathon
would otherwise dominate the player's inferred taste.

`completionMomentum` is the clamped played fraction of the selected estimate for an unfinished
started game and zero for an unplayed game. It is deliberately the smallest component: existing
progress should help surface a finishable game without turning every plan into backlog cleanup.

Each variant independently searches combinations of one to five candidates under its minute budget.
Bundle value is:

```text
bundleValue = 0.60 * mean(candidateQuality)
            + 0.30 * budgetUtilization
            + 0.10 * genreDiversity
```

Genre diversity is based on pairwise overlap among games with known genres; unknown genres are
neutral rather than maximally diverse. Mean candidate quality, rather than summed quality, avoids
giving five mediocre one-hour games an automatic advantage over two excellent games. Utilization
still rewards using the available window. A bounded deterministic beam search retains only the best
partial combinations while processing candidates, which avoids an unbounded `n choose 5` search for
large libraries. Objective components, planned minutes, member count, and the lexicographically
sorted app-id list provide deterministic comparison in that order.

A greedy next-best-game algorithm was rejected because an early medium-length selection can prevent
a later combination with better total quality, fit, and diversity.

### 5. Reuse Store appdetails for participation categories and cache reviews separately

Extend the existing Store appdetails DTO and definitive result to carry only the participation
category identifiers and labels needed by this feature. Add a nullable/defaulted category JSON field
to `game_genre_cache`, and populate it in the existing genre enrichment and family-shared admission
paths. Genres and categories remain separately decoded domain values. This costs no additional
appdetails request and preserves existing genre behavior.

Add a separate review-cache table keyed by app id with positive, negative, total, Steam description,
availability state, and checked timestamp. Reviews come from the credential-free Store reviews
summary endpoint using `language=all`, `purchase_type=all`, and no review bodies. A separate table is
preferred because review and appdetails requests can succeed, fail, and become stale independently.

Review refresh mirrors genre enrichment: missing before stale, at most 25 apps per worker run, at
least 500 ms between requests, a 30-day freshness window, continuation for remaining work, and retry
without overwriting last-known success after transient failure. Refresh is scheduled independently
of authenticated sync. A combined per-game Store worker was rejected because one endpoint failure
would unnecessarily stop durable progress from the other endpoint.

### 6. Live counts refine only multiplayer alternatives before finalization

The engine first derives a local candidate order and identifies up to twelve multiplayer candidates
most likely to participate across the three variants. The ViewModel performs one current-player
lookup per distinct app id with bounded concurrency and a bounded overall enrichment window. A
generation identity owns every lookup; cancellation, navigation, or regeneration invalidates that
identity so a predecessor cannot publish into its successor.

Available counts use `ln(1 + count)` ordering only when comparing otherwise similar candidates that
share multiplayer participation semantics. They do not produce a universal score that lets a large
live-service population outrank a single-player game, and they never form an eligibility threshold.
Unavailable counts contribute no preference and remain different from a successful count of zero.

The result snapshot is finalized once enrichment completes or its bounded window ends. Late results
are ignored, so membership does not move after the player starts reviewing it. If there are no
eligible multiplayer candidates, the local result finalizes immediately. Persisting counts or
reusing them on game detail was rejected because a stale live fact would be misleading and would
contradict the current player-count lifecycle.

### 7. Keep editing transient and adoption stable

The ViewModel retains the ranked eligible pool with the finalized result. Removing a member or
choosing a replacement reruns validation and bundle totals locally; replacement choices that exceed
the selected budget remain unavailable. Neither operation mutates library or collection state.

Acceptance maps the final preview to the existing atomic `CollectionSaveDraft` path:

```text
name        = "Before <anticipated title>"
mode        = DEADLINE_GOAL
sort        = deadline mode default
targetDate  = confirmed target date
timeBasis   = MAIN_STORY or COMPLETIONIST
members     = accepted app ids
done marks  = empty
```

This creates a normal collection with stable membership and all existing edit, backup, sync-survival,
and forecasting behavior. A new recommendation-specific collection table and an auto-updating smart
collection were rejected: both would duplicate collection behavior, and automatic membership drift
would undermine a plan the player had committed to follow.

### 8. Add one pushed adaptive surface, not another navigation destination

Home and Collections link to the same route. Setup and results share one ViewModel-scoped flow so the
player can go back to adjust inputs without writing preferences. Results use three distinct plan
cards, each with capacity provenance, available/planned/reserve time, up to five game cards, factual
reason chips, HLTB coverage disclosure, replacement, and save actions. Family-shared membership is
always labeled.

The save confirmation repeats the generated name, target date, basis, and member count before the
atomic write. On failure it releases busy state and retains the preview. The surface follows existing
game-card artwork, density, reduced-motion, and collection-overlay patterns rather than introducing a
new visual system.

## Risks / Trade-offs

- [Steam review and Store endpoints are undocumented or can change shape] -> Keep narrow DTOs,
  distinguish definitive absence from transient failure, retain last-known cache values, and let
  plans work with neutral missing metadata.
- [Wilson quality still reduces taste to community sentiment] -> Keep review quality to half of
  candidate quality, expose the underlying review fact, and combine it with personal and plan-level
  signals.
- [Genre affinity can mistake repeated obligation for enjoyment] -> Count active dates rather than
  hours, use recency decay, keep the signal weak and explainable, and do not persist a behavioral
  profile.
- [Beam search can miss the mathematical global optimum] -> Keep the search deterministic, use a
  generous bounded frontier, and test adversarial packing cases; recommendation usefulness matters
  more than claiming exact optimization.
- [A current-player snapshot varies by time zone and hour] -> Use it only among multiplayer
  alternatives, never as an eligibility threshold, label it as current, and do not persist it.
- [A learning player can enter an unrealistic manual budget] -> Label manual provenance clearly and
  apply the same visible utilization levels rather than presenting a Personal Pace claim.
- [Independent gap plans and existing collections can overcommit the same future time] -> Preserve
  the existing non-reservation model and avoid language implying that capacity has been booked.
- [Five-game cap can leave substantial time unused when only very short games qualify] -> Show the
  reserve honestly; the cap protects result readability and the request for a focused set.

## Migration Plan

1. Extend the Store category cache with a nullable/defaulted participation-category field and add a
   separate review-cache table in one Room migration; existing rows remain valid with unknown data.
2. Extend Store parsing and family-shared seeding, then add review acquisition, repository mapping,
   bounded worker scheduling, and migration tests before exposing the cache to recommendations.
3. Add and test the pure recommendation engine, including confidence-adjusted reviews, recency genre
   affinity, capacity provenance, eligibility, bundle search, explanations, and deterministic ties.
4. Add the aggregation feed and bounded live-enrichment lifecycle, retaining a fully local path when
   data or network is unavailable.
5. Add setup/results navigation and UI, then map acceptance through the existing atomic collection
   save protocol.
6. Verify offline generation, cancellation ownership, process recreation before save, save failure,
   and both fresh-install and upgraded-database behavior.

Rollback removes the route, worker, feed, and recommendation engine. The extra cache table and
nullable category column can remain inert under a downgraded feature build; no user-authored state
depends on them. Collections already accepted by the player remain ordinary deadline collections and
must not be deleted during rollback.
