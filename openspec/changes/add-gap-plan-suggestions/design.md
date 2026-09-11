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

- Keep eligibility, capacity, per-tier target selection, and the presented facts in a pure domain
  engine with injected dates, an injected seed, and plain values.
- Produce three meaningfully different lengths of commitment without claiming statistical confidence
  the source data cannot establish, and without ranking games by a composite the player cannot see.
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
- Ranking suggestions. The engine selects among fitting candidates without preference and presents
  the facts; judging a game is the player's job.
- Reserving capacity against other collections, constructing a daily calendar, or guaranteeing a
  completion date.
- Persisting recommendation snapshots, seeds, or current-player counts.
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

### 4. Select uniformly among the candidates nearest a tier's target

Each tier targets a share of full capacity — 70%, 85%, 100% — and its pick is drawn **uniformly at
random** from the eligible candidates nearest that target, with the three picks constrained to be
distinct.

Targeting rather than capping is what makes the tiers mean anything. Under a cap alone, "fits within
70%" and "fits within 100%" are satisfied by the same two-hour game, so all three tiers could return
the same length and the choice between them would carry no information. Targeting makes Relaxed a
short commitment and Full a long one, which is the question the player is actually answering.

Selection is unweighted, and this is the significant reversal from the first design. Review quality,
genre affinity, and completion momentum no longer choose anything. They are shown on a pick so the
player can judge it. The reasoning is that a recommender which ranks by an opaque composite is
asking to be trusted, while one that offers a fitting game and states its Steam rating, its genres,
its player count, and how much of it is left is asking to be *checked* — and the second is the
honest posture for a signal set this thin.

**What this deletes.** The previous design carried a considerable machine for choosing: a weighted
`candidateQuality`, a `bundleValue` objective over mean quality, budget utilization and pairwise
genre diversity, and a fixed-width beam search with a 32-entry frontier, a declared processing
order, and a documented tie-break chain — all of it justified by the need to pick five games well
and reproducibly. None of that survives. One game per tier needs no combination search, and uniform
selection needs no ranking. This is a large amount of designed and tested behaviour being removed
deliberately, and it is recorded here rather than quietly dropped, because a future reader will
otherwise find its absence surprising.

Within-bundle genre diversity is dropped outright rather than reinterpreted as diversity *across*
the three tiers. Cross-tier diversity is defensible and was considered, but it constrains a
selection that is otherwise uniform, and reintroducing a preference immediately after removing all
of them would undo the point. If three shooters in a row proves annoying in practice, that is a
later change with its own evidence.

A missing rating or genre no longer needs a neutral constant, because nothing is being scored. It is
simply a fact the card does not show. That removes the whole neutral-versus-zero problem rather than
solving it.

**Ordering is imposed after the draw, not during it.** The three picks are drawn as above, then
assigned to tiers in ascending remaining time. Constraining each lower tier to draw only below the
tier above was rejected: it can empty a tier where a distinct eligible game existed, which
contradicts the distinctness scenario's "next-nearest eligible candidate". Reassignment cannot break
a ceiling, because shares increase with intensity — matching ascending lengths to ascending shares is
feasible whenever any assignment is, so the sorted assignment is safe exactly when the drawn one was.

This was found on a real library rather than in review, and the numbers are worth keeping because
they show how ordinary the case is: three eligible games at 91h 55m, 93h 44m and 142h 29m against a
302h 54m forecast, where a reroll put the 93h 44m game at Relaxed and the 91h 55m game at Balanced.
Both sat far below both the 212h and 257h shares, so the nearness band held both for both tiers and
the draw was free to invert them. Targeting alone guarantees ordering only when the pool spans the
request's capacity; a library with few resolved HLTB lengths does not, and that is the common case
rather than the exception.

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

### 6. Live counts decorate finalized picks and never choose them

The picks are a function of local state and the seed. All three finalize before any network request,
so an offline device, a timed-out enrichment, and a fully enriched run all offer the same games.

After they finalize, the ViewModel performs one current-player lookup per distinct multiplayer pick
— at most three, because the result holds one game per tier. Lookups run with a concurrency of 4
inside an 8-second overall window. A generation identity owns every lookup; a reroll, cancellation,
or navigation invalidates that identity so a predecessor cannot publish into its successor, and any
result arriving after the window is discarded.

An available count may state a fact on the pick that already exists. It may not change which game a
tier offers. With one pick per tier there is no row order left for a count to influence, which
removes the previous design's one remaining avenue for a network fact to affect what the player
sees — an improvement the simplification gets for free.

Persisting counts or reusing them on game detail was rejected separately: a stale live fact would be
misleading and would contradict the current player-count lifecycle.

### 7. Reroll by seed, and keep adoption stable

Each generation draws a seed and retains it with the result. Identical inputs and an identical seed
produce identical picks; rebuilding draws a new seed. This is what lets a shown result hold still
while it is being considered — the property that makes a suggestion something you can commit a month
to — while still letting the player ask for a different one.

It also replaces the previous design's normative claim that identical inputs produce identical
membership. That claim was correct and testable, and it was also precisely what made the rebuild
control a no-op: with nothing else varying, pressing it could only return what was already on
screen. Seeding preserves the useful half of the property and discards the half that made the
surface feel broken.

Per-slot editing — remove a member, choose a replacement from a filtered pool — is removed with the
bundles it served. With one game per tier, "swap this one" and "reroll" are the same request, and
offering both a browse-and-search replacement sheet and a reroll button would reintroduce exactly
the busywork this design is removing. A player who wants a different game presses rebuild.

Acceptance maps the accepted pick to the existing atomic `CollectionSaveDraft` path, unchanged, with
`memberAppIds` carrying the single accepted game. Every field is still set explicitly, `id` included,
because `CollectionRepository.save` branches on `id == 0L` to choose creation over update.

### 8. One pushed surface, three cards, and detail in place

Home and Collections link to the same route. Setup and results share one ViewModel-scoped flow so
the player can adjust inputs without writing preferences.

Results present capacity at two levels, because a per-tier figure alone would hide the policy. The
request's full forecast capacity is stated once; each tier then states its own share. A Relaxed card
showing only its own smaller number would conceal the time the 70% intensity deliberately withheld,
which is the opposite of what choosing an intensity is meant to offer.

Each tier renders one game card carrying the facts that judge it: Store genres, Steam review
description with volume, current players where the pick is multiplayer, and remaining time at the
selected basis. Family-shared picks are labelled. An unavailable fact is omitted rather than shown
as a zero.

Activating a card opens the **existing** collection game-detail overlay — partial height, leaving
the result visible above, dismissed by its own control or system back. Reusing that treatment rather
than building a second compact detail matters for more than consistency: the overlay already has
specified artwork fallbacks and dismissal behaviour, and a parallel implementation would drift from
them. Inspection never rerolls, so a player can open all three in turn and still accept the one they
started with.

Exactly one control produces a new set. The first implementation shipped two — a primary button and
a separate "Regenerate" — invoking the same action, which was worse than redundant given the picks
could not vary: it promised a reroll that did not exist. One control, and it genuinely rerolls.

## Risks / Trade-offs

- [Steam review and Store endpoints are undocumented or can change shape] -> Keep narrow DTOs, pin
  every field and category id from a captured fixture rather than from memory, distinguish definitive
  absence from transient failure and from a refused envelope, retain last-known cache values, and let
  plans work with neutral missing metadata.
- [Two independent Store chains double the app's worst-case request rate against one host] -> State
  the aggregate bound rather than the per-chain one, keep both chains stopping on the first transient
  failure, and rely on 429 already being classified as transient; revisit the shared spacing if Steam
  throttles in practice.
- [Uniform selection will sometimes offer a poorly reviewed or unappealing game] -> Accept it as the
  cost of not ranking, and answer it with presentation rather than filtering: the card states the
  rating, volume, genres, players, and remaining time, and rebuilding is one tap. A filter on
  quality would reintroduce the opaque judgement this design removes.
- [A library the enrichment has not reached offers picks with almost no facts attached] -> Accept and
  disclose it. Selection is unaffected, since nothing is ranked, but the cards will be sparse and the
  coverage disclosure says so rather than implying more was known.
- [Genre affinity can mistake repeated obligation for enjoyment] -> It no longer selects anything, so
  the risk is reduced to a possibly odd sentence on a card. Count active dates rather than hours, use
  recency decay, and do not persist a behavioral profile.
- [A small eligible pool makes rerolling produce the same picks] -> Detect it and say so, rather than
  letting the control appear ignored. This is the failure mode that made the previous rebuild button
  feel broken, and it is now an explicit state instead of an unexplained no-op.
- [A current-player snapshot varies by time zone and hour] -> Keep it out of selection entirely, use
  it only to state a fact, label it as current, and do not persist it.
- [A learning player can enter an unrealistic manual budget] -> Label manual provenance clearly and
  apply the same visible utilization levels rather than presenting a Personal Pace claim.
- [Independent gap plans and existing collections can overcommit the same future time] -> Preserve the
  existing non-reservation model and avoid language implying that capacity has been booked.
- [One game per tier can leave substantial time unused when nothing near a target qualifies] -> Show
  the tier's share and the pick's remaining time honestly, and let rebuilding look again. Three
  legible choices are the point; filling the window exactly is not.

## Migration Plan

1. Extend the Store category cache with a nullable participation-category field and add a separate
   review-cache table in one Room migration; existing rows remain valid with unknown categories. In
   the same step widen Store enrichment eligibility to treat a null category payload as work to do,
   so those migrated rows refresh on the next batch instead of waiting out their remaining genre
   freshness.
2. Extend Store parsing and family-shared seeding, then add review acquisition, repository mapping,
   bounded worker scheduling, and migration tests before exposing the cache to recommendations.
3. Add and test the pure engine: eligibility, capacity provenance, per-tier target selection, seeded
   uniform draw, distinctness across tiers, and the facts carried on a pick.
4. Add the aggregation feed, then the post-finalization live-count decoration for at most three app
   ids, keeping the local path complete on its own so the decoration is never load-bearing.
5. Add setup/results navigation, the three single-pick cards, and the in-place detail overlay, then
   map acceptance through the existing atomic collection save protocol.
6. Verify offline generation, cancellation ownership, process recreation before save, save failure,
   and both fresh-install and upgraded-database behavior — the upgraded case specifically confirming
   that a pre-existing enriched library acquires categories rather than waiting 30 days.

Rollback removes the route, worker, feed, and selection engine. The extra cache table and
nullable category column can remain inert under a downgraded feature build; no user-authored state
depends on them. Collections already accepted by the player remain ordinary deadline collections and
must not be deleted during rollback.
