## Why

Backlogium can estimate a player's future gaming capacity and the remaining time for a manually
chosen deadline collection, but it cannot answer the earlier planning question: which worthwhile
games from the library form a realistic plan for the time before an anticipated release. A gap-plan
builder can turn the existing Personal Pace, HLTB, playtime, genre, and collection foundations into
an explainable recommendation without giving up the app's offline-first behavior.

## What Changes

- Add a gap-plan builder where the player names an anticipated game or upgrade, chooses its target
  date, selects Story or Completionist intent, and chooses whether recommendations may include
  unplayed and already-started games.
- Generate three deterministic bundles from visible Steam-owned and family-shared games: Relaxed,
  Balanced, and Full plans using respectively 70%, 85%, and 100% of the available time budget.
- Use reliable Personal Pace capacity when available; when Personal Pace is learning, require a
  one-off total-hours budget and identify the resulting plans as manually budgeted.
- Treat missing or non-positive HLTB estimates as unknown rather than zero, calculate unfinished
  games from their remaining selected-basis time, and exclude completed or non-fitting candidates.
- Rank and compose bundles from explainable fit, confidence-adjusted Steam review quality, inferred
  recent genre affinity, completion momentum, and plan-level genre variety rather than exposing an
  opaque recommendation score.
- Add bounded, credential-free acquisition and offline caching of Steam review summaries and Store
  participation categories needed by recommendation ranking and multiplayer classification.
- Optionally enrich only multiplayer-dependent games in the small generated shortlist with fresh,
  non-persisted current-player counts. Live-data failure does not block, remove, or invalidate an
  otherwise valid local plan.
- Keep generated plans transient until the player confirms one. Confirmation atomically creates a
  stable deadline-goal collection carrying the target date, selected HLTB basis, generated name, and
  accepted membership; later metadata changes do not silently replace its members.

## Capabilities

### New Capabilities
- `gap-plan-suggestions`: Defines gap-plan input, eligibility, capacity budgets, explainable bundle
  generation, uncertainty handling, optional live enrichment, and adoption behavior.
- `steam-suggestion-metadata`: Defines credential-free Steam review and participation-category
  acquisition, bounded refresh, local caching, freshness, and unavailable-data semantics.

### Modified Capabilities
- `steam-player-count`: Permits bounded on-demand current-player lookups for multiplayer games in a
  generated shortlist while preserving non-persistence and failure-as-unavailable behavior.
- `custom-collections`: Defines atomic creation of a deadline-goal collection from an accepted gap
  plan.
- `app-ui`: Adds the gap-plan setup, results, explanation, live-enrichment, and save-confirmation
  surfaces as a pushed Home/Collections experience.

## Impact

- Adds pure domain types and derivation for candidate eligibility, scoring explanations, bundle
  composition, budget utilization, and deterministic tie-breaking.
- Extends repository aggregation across the visible library, Personal Pace, sessions, HLTB, genres,
  achievements, and cached Steam suggestion metadata without exposing Room entities to product UI.
- Adds Store response models, a local metadata cache, Room migration, repository policy, and bounded
  background enrichment independent of authenticated Steam sync.
- Extends the current-player lookup contract with a bounded shortlist request lifecycle, without
  making live counts a persisted or required input.
- Adds Home/Collections navigation and Compose/ViewModel state for setup, three plan variants,
  explanations, candidate replacement, and atomic adoption into collections.
- Does not change the gamification engine, cloud poller, authenticated Steam sync contract, or the
  requirement that the app remain useful offline and without cloud access.
