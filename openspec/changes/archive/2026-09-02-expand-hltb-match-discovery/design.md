## Context

Backlogium's normal HLTB lookup sends the Steam title as whitespace-separated search terms, maps
the first page of up to 20 results into `HltbCandidate`, and applies normalized Levenshtein scoring.
That scorer can rank weak candidates, but it cannot help when HLTB returns an empty candidate list.
The resulting `UNMATCHED` row is excluded from `observeNeedsReview()`, so the batch review surface
cannot rescue it; a per-game refresh merely repeats the same query.

Candidate data is already richer than the review UI: each candidate carries a numeric HLTB id,
cover URL, four completion lengths, and confidence. The existing review nests small candidate rows
inside one card per Steam game, while the inline picker reuses the same row in a bottom sheet. The
numeric HLTB id and Steam app id are also sufficient to construct external game links without
additional metadata requests.

HLTB is an undocumented, rotating web surface. Search already hides behind `HltbDataSource` and
discovers its endpoint/token dynamically. Direct game-page hydration must preserve that seam and be
built from captured public-page fixtures rather than coupling UI code to live HTML.

## Goals / Non-Goals

**Goals:**

- Make candidate comparison visually clear, with distinct cover-led cards, all available HLTB
  lengths, and independent external links.
- Make ambiguous and unmatched games discoverable through one match center without turning every
  unmatched game into an attention badge.
- Add a bounded, user-triggered broader-search escalation only after a genuine zero-result search.
- Add a strictly validated HLTB game-link fallback that previews authoritative linked data before
  the user confirms it.
- Preserve exact matching, existing cache protection, batch behavior, and old candidate JSON.

**Non-Goals:**

- Run fuzzy search automatically during ordinary single-game or batch lookups.
- Auto-resolve any fuzzy or link-derived result.
- Search the general web, accept arbitrary hosts, or follow arbitrary redirects supplied by users.
- Add a server-side HLTB proxy or manually entered completion lengths.
- Rebuild the Library's normal game grid; the match center only borrows its responsive card grammar.

## Decisions

### 1. Evolve review into a one-game-at-a-time HLTB match center

The HLTB options menu will always expose an `HLTB match center` destination. Its badge remains the
count of `NEEDS_REVIEW` rows, because those are actionable candidates waiting for a choice; unmatched
games appear in a separate `No match` group/count without creating a persistent warning badge.

The destination selects one Steam game at a time and shows `current / total` navigation. Its header
uses locally available Steam identity—a game icon or derived artwork, Steam name, match state, and a
single `Steam` external link. Below it, HLTB candidates render in an adaptive grid: one column on
narrow widths and two or more where card width remains readable. Each candidate is its own card
with a larger portrait cover, name, available Main Story/Main + Extras/Completionist/All Styles
lengths, compact confidence/source guidance, an `HLTB` external link, and an explicit `Use match`
action.

Opening an external link is a separate click target and never selects the candidate. Selecting a
candidate retains today's immediate resolution semantics; the explicit button is itself the manual
confirmation. The compact inline bottom-sheet picker may retain a list presentation, but it will
share link building, length formatting, and the manual-link footer rather than force desktop-like
cards into a constrained sheet.

Alternative considered: display all Steam games and all candidate grids in one long list. This
quickly becomes visually dense, loses context between candidates and their Steam source title, and
makes large batch reviews expensive to compose.

### 2. Query generation is fuzzy; result selection remains conservative

`HltbQueryGenerator` will create ordered, distinct variants only after the primary search succeeded
with zero candidates. It will produce at most three additional queries from the original Steam
title:

1. remove recognized storefront/edition noise such as `Enhanced Edition`, `Definitive Edition`,
   `Game of the Year`, `GOTY`, `Remastered`, platform markers, and balanced trademark/bracket noise;
2. reduce a subtitle after `:`, an em/en dash, or a spaced hyphen while retaining the numbered core;
3. remove a leading article and create one safe Arabic/Roman numeral alternative for a terminal
   sequel number.

Empty, duplicate, and unchanged variants are discarded. Queries run sequentially with the existing
inter-request delay and reuse the in-memory HLTB search session. Results are merged by positive HLTB
id; when the same id occurs more than once, the richest candidate payload wins.

`HltbMatcher` scores every merged candidate against the **original Steam title**, not the relaxed
query that found it. The fuzzy score combines normalized edit similarity with token-set overlap and
core-title containment. Conflicting sequel numbers apply a strong penalty so `The Witcher 2` cannot
rank `The Witcher 3` above a weaker but numerically compatible entry. Edition terms carry little
weight. All returned candidates receive `source = BROADER_SEARCH` and are persisted as
`NEEDS_REVIEW`; no confidence threshold can auto-resolve them.

`HltbCandidate.source` will be an optional/defaulted serialized field (`PRIMARY` by default), so
candidate JSON written before this change stays readable. The source is presentation/provenance,
not a second match-status system.

Alternative considered: lower `CONFIDENT_THRESHOLD` or make the existing Levenshtein matcher more
permissive. Neither causes HLTB to return candidates for a zero-result query, and both increase
silent false matches on normal searches.

### 3. Broader search is an explicit rescue operation with separate transient state

`HltbRepository.searchBroaderCandidates(appId, originalName)` will be non-destructive while requests
are in flight. It is enabled only when the current stored state is `UNMATCHED`, guards duplicate
requests per game in the view model, and distinguishes transport failure from an exhausted broader
search.

If candidates are found, the repository stores them as `NEEDS_REVIEW` and preserves the existing
row's original `fetchedAt`; this is resolution work on an already completed lookup, not a fresh
ordinary-cache result. If no broader candidates are found, or a request fails, the `UNMATCHED` row
remains intact. The UI shows `Searching broader titles…`, retryable failure, or `Still no matches`
without conflating these outcomes.

Alternative considered: automatically run broader queries inside `query()`. That multiplies batch
traffic for obscure titles, lengthens normal refreshes, and can convert a trustworthy `UNMATCHED`
answer into a pile of weak candidates the user never asked to inspect.

### 4. Strictly parse pasted links into an id before any network call

A pure `HltbGameLink` parser accepts only absolute HTTPS URLs whose normalized host is
`howlongtobeat.com` or `www.howlongtobeat.com`, with no user-info, custom port, query, or fragment.
The path must identify one positive numeric game id under the supported `/game/{id}` route; optional
trailing slash is normalized away. The application never sends the pasted string to OkHttp. It
extracts the id and constructs its own canonical `https://howlongtobeat.com/game/{id}` URL.

The same centralized route builder creates every candidate's external link. Steam links likewise
use the existing app-id-derived Store route. This keeps display and validation policy out of
composables and prevents link-opening from mutating match state.

Alternative considered: accept any URL containing a number or follow pasted redirects. That creates
an avoidable server-side-request-forgery-like boundary on-device and makes validation misleading.

### 5. Add direct game hydration to the existing data-source seam

`HltbDataSource.lookupById(hltbId)` will return one `HltbCandidate` or a typed not-found/parse
outcome. `ScrapingHltbDataSource` will request only the internally constructed canonical page and
delegate parsing to a pure `HltbGamePageParser`. Before implementation commits to fields, a focused
spike will capture one current game page and one missing-page response, identify the stable embedded
structured payload used for id, title, cover, and all four lengths, and add redacted fixtures.
Parsing must target structured JSON/page data rather than CSS classes or visible prose.

Transport/rotation/parse failure is not `not found`. Both leave stored HLTB data unchanged. This
method remains behind the seam so a future proxy can replace scraping without changing repository
or UI consumers.

### 6. Link resolution always previews before persistence

The manual-link form is available from unmatched/needs-review match-center states and as a
last-resort footer in the inline change-match picker. After local validation, the view model calls
`previewLinkedCandidate(url)`. The resulting `source = MANUAL_LINK` candidate appears in a preview
card with its cover, HLTB title, all available lengths, and the original Steam title for comparison.

Only `Confirm match` calls the existing `resolveMatch(appId, candidate)` path. Dismissal, invalid
input, not-found, transport failure, or parse failure writes nothing. A resolved match can therefore
also be corrected with a pasted link without losing the prior match until confirmation succeeds.

Alternative considered: extract the id and persist it immediately. The id alone supplies no lengths
and gives the user no chance to catch a valid but wrong HLTB page.

### 7. Reuse current storage; do not add a Room migration

Resolved `HltbData` already stores the HLTB id and four lengths. Fuzzy candidates fit the existing
`candidatesJson`, and link candidates use the same `resolveMatch` write. Only the serialized
candidate source field is added with a default, preserving old JSON. DAO queries expand the match
center's observed set to `NEEDS_REVIEW` plus `UNMATCHED`; `reviewCount` remains review-only.

## Risks / Trade-offs

- **HLTB page structure changes** → isolate parsing behind `HltbGamePageParser`, keep captured
  fixtures, distinguish parse failure from not-found, and preserve last-good data.
- **Broader queries surface wrong sequels or similarly named games** → cap queries, penalize numeric
  conflicts, show cover/length/link context, and never auto-resolve.
- **More HLTB requests can look abusive** → user-trigger only, maximum three variants, sequential
  spacing, session reuse, and id deduplication.
- **A permanently visible match-center item adds menu weight** → use compact wording and show a badge
  only for ambiguous games that already await selection.
- **Two candidate presentations can drift** → share candidate formatting, route builders, external
  link affordance, placeholders, and selection callbacks even if grid/card and sheet/row layouts
  remain intentionally different.
- **Confidence percentages can imply certainty** → present confidence as secondary match guidance,
  never as proof, and emphasize manual confirmation for broader results.

## Migration Plan

1. Add backward-compatible candidate provenance, pure query/link utilities, and direct-page parser
   fixtures before changing repository behavior.
2. Add broader-search and link-preview repository methods without modifying ordinary `query()`.
3. Expand DAO/view-model state to include unmatched games, then replace the review presentation and
   add inline rescue actions.
4. Existing installs require no database migration. Old `candidatesJson` decodes as `PRIMARY`, and
   existing resolved/unmatched rows enter the new UI unchanged.
5. Rollback removes only the new UI and rescue paths; stored matches remain compatible because
   resolution still writes the existing `HltbData` fields.

## Open Questions

- Which embedded structured payload is currently the least volatile source for a direct HLTB game
  page's title, cover, and four lengths? Task 1 is an implementation spike that must answer this
  with captured fixtures before the parser is built; it does not change the product contract.
