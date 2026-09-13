## Why

Backlogium can estimate a player's future gaming capacity and the remaining time for a manually
chosen deadline collection, but it cannot answer the earlier planning question: which worthwhile
games from the library form a realistic plan for the time before an anticipated release. A gap-plan
builder can turn the existing Personal Pace, HLTB, playtime, genre, and collection foundations into
an explainable recommendation without giving up the app's offline-first behavior.

## What Changes

- Add a gap-plan builder where the player names an anticipated game or upgrade, chooses its target
  date, selects Story or Completionist intent, and chooses whether suggestions may include unplayed
  and already-started games.
- Offer exactly three suggestions from visible Steam-owned and family-shared games — one game each
  for Relaxed, Balanced, and Full — where a tier *targets* 70%, 85%, or 100% of available time
  rather than merely capping it, so the three are three different lengths of commitment, always
  ordered shortest to longest. The three are distinct games.
- Select uniformly at random among the candidates nearest a tier's target, and present the facts
  that judge a pick rather than ranking by them. The app surfaces Store genres, Steam review
  description and volume, current players, and remaining time; the player decides. No composite
  score chooses, and none is shown.
- Make rebuilding a real reroll. Each generation draws a seed, so a shown result holds still while
  it is being considered, and rebuilding produces a visibly different set.
- Let a suggestion be inspected in place through a compact game-detail overlay, so judging one never
  costs leaving the surface.
- Use reliable Personal Pace capacity when available; when Personal Pace is learning, require a
  one-off total-hours budget and identify the resulting suggestions as manually budgeted.
- Treat missing or non-positive HLTB estimates as unknown rather than zero, calculate unfinished
  games from their remaining selected-basis time, and exclude completed or non-fitting candidates.
- Add bounded, credential-free acquisition and offline caching of Steam review summaries and Store
  participation categories needed by recommendation ranking and multiplayer classification.
- Finalize the picks from local state alone, then optionally decorate only the multiplayer games
  among them with fresh, non-persisted current-player counts. A count states a fact; it never
  decides which games are offered, so an offline run and an enriched run produce the same picks.
- Keep suggestions transient until the player confirms one. Confirmation atomically creates a stable
  deadline-goal collection carrying the target date, selected HLTB basis, generated name, and the
  accepted game; later metadata changes do not silently replace it.

## Capabilities

### New Capabilities
- `gap-plan-suggestions`: Defines gap-plan input, eligibility, per-tier capacity targets, unweighted
  rerollable selection, the facts presented with a pick, uncertainty handling, optional live
  enrichment, and adoption behavior.
- `steam-suggestion-metadata`: Defines credential-free Steam review and participation-category
  acquisition, bounded refresh, local caching, freshness, and unavailable-data semantics.

### Modified Capabilities
- `steam-player-count`: Permits bounded on-demand current-player lookups for the multiplayer games a
  finalized gap plan already contains, while preserving non-persistence and failure-as-unavailable
  behavior. Also scopes the existing 30-second repetition to the game-detail lifecycle, which today
  reads as an obligation on every lookup and would otherwise contradict the new one-shot use.
- `custom-collections`: Defines atomic creation of a deadline-goal collection from an accepted gap
  plan.
- `app-ui`: Adds the gap-plan setup, three single-pick results, in-place game-detail inspection,
  reroll, live-enrichment, and save-confirmation surfaces as a pushed Home/Collections experience.

## Impact

- Adds pure domain types and derivation for candidate eligibility, per-tier target selection,
  seeded rerolling, and the presented facts. No ranking engine: review quality, genre affinity, and
  completion momentum are displayed, never used to choose.
- Extends repository aggregation across the visible library, Personal Pace, sessions, HLTB, genres,
  achievements, and cached Steam suggestion metadata without exposing Room entities to product UI.
- Adds Store response models, a local metadata cache, Room migration, repository policy, and bounded
  background enrichment independent of authenticated Steam sync.
- Widens Store enrichment eligibility so records already cached without participation-category data
  refresh on the next run rather than waiting out their existing freshness window, which is what lets
  an upgraded install use the feature on day one instead of a month later.
- Extends the current-player lookup contract with a bounded post-finalization request lifecycle of
  at most three app ids, without making live counts a persisted, required, or selection-deciding
  input.
- Adds Home/Collections navigation and Compose/ViewModel state for setup, three single-pick results,
  the in-place detail overlay, rerolling, and atomic adoption into collections.
- Does not change the gamification engine, cloud poller, authenticated Steam sync contract, or the
  requirement that the app remain useful offline and without cloud access.
