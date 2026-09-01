## 1. Direct HLTB page research and parsing

- [x] 1.1 Capture a current public HLTB game page and a not-found response, identify the least volatile embedded structured source for id, title, cover, and all four completion lengths, and document the finding in focused parser fixtures.
- [x] 1.2 Add a pure canonical HLTB route builder and strict pasted-link parser covering HTTPS, allowed hosts, positive ids, optional trailing slash, and rejection of credentials, ports, query data, fragments, and unsupported paths.
- [x] 1.3 Extend `HltbDataSource` with typed direct-id lookup outcomes and implement `HltbGamePageParser` against the captured structured fixtures rather than CSS classes or visible prose.
- [x] 1.4 Implement `ScrapingHltbDataSource.lookupById` using only the internally constructed canonical URL and preserving the distinction between not-found, transport failure, and parse failure.
- [ ] 1.5 Add parser/link/data-source tests for valid non-www and www links, malformed or hostile URLs, successful full and partial length payloads, missing covers, not-found pages, redirects, rotated payload failure, and empty responses.

## 2. Candidate provenance and external routes

- [x] 2.1 Add backward-compatible `PRIMARY`, `BROADER_SEARCH`, and `MANUAL_LINK` provenance to `HltbCandidate`, defaulting absent serialized values to `PRIMARY`.
- [x] 2.2 Centralize HLTB candidate-page and Steam Store link construction so UI surfaces derive external URLs only from validated positive ids.
- [ ] 2.3 Add regression tests proving old `candidatesJson` remains readable, new provenance round-trips, and link construction rejects non-positive identifiers.

## 3. Broader query generation and scoring

- [x] 3.1 Implement pure edition/storefront-noise removal for the bounded recognized term set while retaining meaningful base titles and sequel numbers.
- [x] 3.2 Implement safe subtitle reduction, leading-article normalization, and one terminal Arabic/Roman numeral alternative.
- [x] 3.3 Implement ordered normalized deduplication and a hard maximum of three non-empty variants that differ from the primary query.
- [x] 3.4 Extend pure candidate scoring with token overlap, core-title containment, low-value edition terms, and a strong conflicting-sequel-number penalty while continuing to score against the original Steam title.
- [x] 3.5 Implement cross-query HLTB-id deduplication that retains the richest candidate payload and marks every merged result as `BROADER_SEARCH`.
- [ ] 3.6 Add table-driven tests for Witcher-style edition/subtitle cases, trademark/bracket noise, Arabic/Roman numerals, conflicting sequels, title collisions, duplicate variants, and three-query/request ceilings.

## 4. Repository rescue operations

- [x] 4.1 Add a non-destructive broader-candidate operation that accepts only an existing `UNMATCHED` game, runs variants sequentially with the current inter-request delay, and reuses the HLTB data-source session.
- [x] 4.2 Persist successful broader candidates as `NEEDS_REVIEW` without changing the original fetch timestamp, and preserve `UNMATCHED` on exhausted search or failure.
- [x] 4.3 Add non-persisting manual-link preview that validates locally, performs direct-id lookup through the data-source seam, and returns a `MANUAL_LINK` candidate.
- [x] 4.4 Reuse `resolveMatch` only after explicit linked-candidate confirmation, ensuring dismissal and every validation/lookup failure leave resolved, needs-review, and unmatched rows untouched.
- [x] 4.5 Expand DAO/repository observation for the match center to include both `NEEDS_REVIEW` and `UNMATCHED`, while retaining a separate ambiguous-review count for the badge.
- [ ] 4.6 Add repository tests for eligibility, sequential throttling, partial-query success, exhausted versus failed search, timestamp preservation, manual-link previews, confirmation, dismissal, prior resolved-match protection, and queue/count semantics.

## 5. Match-center state and navigation

- [x] 5.1 Replace review-only view-model state with match-center state containing ambiguous and unmatched games joined to Steam name/icon/artwork, selected position, candidates, and per-game rescue operations.
- [x] 5.2 Add next/previous or equivalent selection navigation with stable behavior when resolving a game removes it from the actionable set.
- [x] 5.3 Add independent transient states for broader-search loading/failure/exhaustion and manual-link input/validation/loading/preview/failure, guarding duplicate operations per game.
- [x] 5.4 Keep the HLTB match-center menu item available even with no ambiguous rows, and show its badge only for `NEEDS_REVIEW` games.
- [ ] 5.5 Add view-model/navigation tests for unmatched-only access, empty state, badge count, selected-game removal, operation cancellation, and state preservation across candidate updates.

## 6. Candidate card and Steam header UI

- [x] 6.1 Add a Steam-game review header with icon or artwork, Steam title, current match state, current-versus-total position, and a non-mutating external Steam Store action.
- [x] 6.2 Add adaptive HLTB candidate cards with larger cover art, themed fixed-geometry fallback, name, available Main/Main + Extras/Completionist/All Styles lengths, provenance/confidence guidance, and an explicit `Use match` action.
- [x] 6.3 Add a separate external HLTB action to each candidate card and prove its click target cannot invoke match selection.
- [x] 6.4 Build the responsive candidate grid with one readable column on narrow widths and additional minimum-width columns on wider devices, preserving scrolling for every candidate.
- [x] 6.5 Share route builders, length formatting, image fallback, accessibility descriptions, and selection semantics with the existing inline picker without forcing the grid layout into its bottom sheet.
- [ ] 6.6 Add Compose tests for Steam-header separation, all available/absent length combinations, cover failure, adaptive column behavior, external-link isolation, candidate selection, game navigation, and accessibility labels.

## 7. Broader-search and manual-link UI

- [x] 7.1 Add `Try broader search` only to unmatched-game management and match-center states, with explanatory copy and distinct loading, failed, exhausted, and candidates-found presentations.
- [x] 7.2 Move a game from unmatched rescue into candidate review immediately after broader results are persisted, labeling the results as requiring manual verification.
- [x] 7.3 Add manual HLTB link entry to unmatched and needs-review match-center states and as a last-resort footer in the inline change-match picker.
- [x] 7.4 Add field-level invalid-link feedback, direct-lookup progress, distinct not-found/transport/parse failures, and retry/correction behavior without dismissing useful prior candidates.
- [x] 7.5 Add the linked-candidate preview comparing the original Steam title with HLTB cover/title/all available lengths and separate `Confirm match` and dismiss actions.
- [ ] 7.6 Add Compose tests for rescue-action eligibility, duplicate-trigger blocking, exhausted versus failed copy, manual input validation, preview confirmation, preview dismissal, prior-match preservation, and inline-picker access.

## 8. Integration and validation

- [ ] 8.1 Confirm ordinary per-game lookup, batch refresh, freshness, progress/logging, endpoint-session reuse, and automatic exact-match thresholds remain unchanged and never invoke broader search.
- [ ] 8.2 Confirm selecting candidates from primary, broader, and manual-link sources writes the same existing HLTB id and four length fields without a Room migration.
- [ ] 8.3 Run `:app:compileDebugKotlin`, app unit tests, focused HLTB parser/matcher/repository tests, and `:app:testDebugUnitTest` offline where dependencies are cached.
- [ ] 8.4 Run match-center and inline-picker instrumentation tests on an available emulator or device, including narrow and wide layouts, external browser handoff, process recreation, and image failure.
- [ ] 8.5 Manually reproduce an exact zero-result title such as a Witcher 2 edition, verify bounded broader candidates require selection, and verify a pasted correct HLTB link previews and resolves all available lengths.
- [ ] 8.6 Manually verify invalid/foreign links, wrong but valid HLTB links, missing pages, offline failures, and dismissal never overwrite last-good or unmatched data.
- [ ] 8.7 Run strict OpenSpec validation and `git diff --check`, then record automated and device-verification evidence task by task.
