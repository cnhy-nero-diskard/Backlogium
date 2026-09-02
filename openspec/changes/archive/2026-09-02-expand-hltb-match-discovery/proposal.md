## Why

The current HLTB review surface compresses candidates into small rows, and a successful zero-result
search leaves a game unmatched with no path beyond repeating the same exact query. Users need
clearer visual comparison plus deliberately escalating recovery tools for Steam titles whose naming
does not line up with HowLongToBeat, such as edition-heavy or subtitled games.

## What Changes

- Reorganize HLTB review around one Steam game at a time, presenting its original Steam identity
  separately from an adaptive grid of distinct HLTB candidate cards.
- Expand each candidate card to show its HLTB cover, name, available Main Story, Main + Extras,
  Completionist, and All Styles lengths, match guidance, and a non-selecting external HLTB link.
- Provide the original game's Steam Store link once in the review header rather than repeating it
  on every candidate.
- Make the HLTB match center discoverable even when only unmatched games exist, while keeping its
  attention badge scoped to ambiguous matches that already have candidates.
- Add an explicit "Try broader search" action only for games whose successful primary search
  returned no candidates.
- Generate a small, deterministic set of relaxed title queries, merge and deduplicate their results,
  score them against the original Steam title with sequel-number safeguards, and always require
  manual selection rather than auto-resolving a fuzzy result.
- Add a last-resort pasted HLTB game-link flow. Validate and canonicalize the link, load the linked
  HLTB entry through the existing transport seam, preview its identity and lengths, and require
  confirmation before replacing or creating a match.
- Preserve existing exact lookup, refresh freshness, batch progress, cached-data protection,
  on-demand image loading, and backward compatibility with retained candidate JSON.
- Navigate directly to the match center, scoped to the game just looked up, when a single-game
  Library lookup persists as needing review or unmatched — rather than requiring the user to
  separately open the match center and locate the game. Resolving that same game's match then
  returns directly to the Library, closing the loop without leaving the user in a multi-game
  review surface they never asked to browse.

## Capabilities

### New Capabilities

None. This change expands the existing HLTB data and application UI capabilities.

### Modified Capabilities

- `hltb-data`: Adds bounded broader-query generation and scoring, unmatched-game rescue, strict
  HLTB game-link resolution through the data-source abstraction, preview-before-persist behavior,
  and manual-only resolution for fuzzy or link-sourced results.
- `app-ui`: Reworks HLTB match review into a visually organized Steam-game/candidate-card
  experience with cover art, all available lengths, external Steam and HLTB links, match-center
  access for unmatched games, broader-search states, and manual-link entry and validation feedback.

## Impact

- **Affected code:** `HltbDataSource`, `ScrapingHltbDataSource`, HLTB DTO/parser fixtures,
  `HltbMatcher`, `HltbRepository`, `HltbDataDao`, the review and Library view models, navigation,
  the inline picker, and shared HLTB candidate presentation.
- **Persistence:** Existing `HltbData` and serialized `HltbCandidate` shapes already retain the HLTB
  id, image reference, and four lengths. No Room schema migration is expected; any new serialized
  candidate metadata must have backward-compatible defaults.
- **Network:** Broader search is user-triggered, capped, sequentially throttled, and reuses the
  current in-memory HLTB session. Direct game-link lookup adds one isolated, fixture-tested reader
  behind the existing swappable transport seam.
- **External navigation:** Steam links derive from the Steam app id and HLTB links derive from a
  validated HLTB id through centralized route builders. Opening a link never selects a match.
- **Risk:** Relaxed queries and pasted links can point at the wrong title, so neither path is allowed
  to auto-resolve; the user sees a complete preview and explicitly confirms the choice.

## Non-goals

- Automatically running fuzzy queries during ordinary per-game or batch refreshes.
- Auto-selecting fuzzy results based solely on their score.
- Accepting arbitrary URLs or fetching the pasted URL verbatim.
- Replacing the existing HLTB scraper with a backend service.
- Adding manual completion-length entry when an HLTB page cannot be resolved.
