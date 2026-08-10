## 1. Personal Pace domain model

- [x] 1.1 Add pure domain types for dated play totals, profile confidence, weekday habits, and date-range capacity forecasts with unformatted numeric outputs.
- [x] 1.2 Expose the closed synthesized-session history needed for a 56-completed-day profile through `SessionDao`/`SessionRepository`, reusing the existing session table and injected cutoff rather than adding persistence.
- [x] 1.3 Bucket closed sessions by injected local date, sum multiple sessions per day, generate covered zero-minute dates, and exclude the current date and open sessions from profile training.
- [x] 1.4 Implement recency weighting, robust global active-day duration, weekday frequency/duration estimates, and sparse-weekday blending as described in `design.md`.
- [x] 1.5 Implement the `LEARNING`/`RELIABLE` confidence gate using completed-date coverage and active-date thresholds.
- [x] 1.6 Implement inclusive future-range projection for expected active days, expected gaming minutes, and required minutes per projected active day without division-by-zero behavior.

## 2. Personal Pace unit coverage

- [x] 2.1 Test local-date bucketing, multiple sessions on one date, zero-minute coverage, current-date exclusion, and open-session exclusion across a non-UTC zone.
- [x] 2.2 Test that recent sustained behavior outweighs older behavior and that a marathon outlier does not define typical active-day duration.
- [x] 2.3 Test sparse-weekday blending and stable weekday-specific projections across an inclusive target range.
- [x] 2.4 Test all confidence boundaries, including 27 versus 28 covered dates and five versus six active dates.
- [x] 2.5 Test required-pace calculation, no-projected-active-day handling, deterministic injected-time behavior, and empty history.

## 3. Collection pacing derivation

- [x] 3.1 Extend collection summary inputs/outputs with Personal Pace confidence, projected capacity, projected active days, required active-day pace, capacity margin, and explicit `ON_TRACK`, `AT_RISK`, `LEARNING`, and `INCOMPLETE_DATA` states.
- [x] 3.2 Replace the deadline `days * 1,440` capacity calculation with Personal Pace capacity while preserving per-member selected-HLTB remaining-work subtraction and unknown-estimate counts.
- [x] 3.3 Implement the shared deadline-intervention eligibility rule for future at-risk plans and arrived/passed unfinished plans, excluding empty and completed collections.
- [x] 3.4 Derive an earliest estimated fit date for reliable complete at-risk plans by accumulating future Personal Pace capacity without automatically changing the stored target.
- [x] 3.5 Add Completionist-based completion-goal horizons and ordered-queue next-game/whole-queue horizons, while keeping basic-list summaries free of pacing output.
- [x] 3.6 Test on-track, at-risk, learning, incomplete-data, today, passed, empty, and completed deadline combinations, including exact action eligibility.
- [x] 3.7 Test completion-goal and ordered-queue projections, missing Completionist estimates, done-member skipping, and the absence of basic-list projections.

## 4. View-model and repository integration

- [x] 4.1 Build one reusable Personal Pace flow from session history and injected time so Home and Collection screens consume the same profile semantics.
- [x] 4.2 Feed Personal Pace into `HomeViewModel` collection-card derivation and carry only the concise mode/status/progress values needed by Home.
- [x] 4.3 Join live now-playing app id to Home collection memberships so every matching card receives an active-play state.
- [x] 4.4 Feed Personal Pace and member header-art URLs into `CollectionViewModel` overview/management models without adding network calls.
- [x] 4.5 Update every `SessionDao` test fake and affected view-model/domain fixture for any session-history contract change.

## 5. Shared game-card artwork

- [x] 5.1 Extract the Library row's right-aligned header image, opacity, offscreen alpha mask, and horizontal fade into a shared Compose backdrop component.
- [x] 5.2 Switch Library game rows to the shared backdrop without changing selection, click, or long-press behavior.
- [x] 5.3 Add the shared backdrop behind horizontal collection overview member cards and use Steam `hero_capsule.jpg` artwork in Library and collection overview grid tiles while preserving accent and metric contrast.
- [x] 5.4 Add the shared backdrop behind management member/add-game cards while keeping done, reorder, add, and remove controls legible and interactive.
- [ ] 5.5 Verify missing and failed header art falls back to the normal themed surface on horizontal Library/Collection cards, while missing `hero_capsule.jpg` assets use the grid fallback.

## 6. Home collection-card refinement

- [x] 6.1 Remove the uppercase mode label and restructure each Home card around the collection name, accessible mode icon, one concise status line, and compact progress surface where applicable.
- [x] 6.2 Keep healthy deadline copy quiet, show a concise required-pace/attention state only for definitive risk, and avoid enumerating missing HLTB details on Home.
- [x] 6.3 Change the ordered thumbnail preview from five to three while preserving member order, missing-icon fallback, and the remaining-count `N+` convention.
- [x] 6.4 Add a low-alpha accent/live-color border glow for every card containing the active game, using a slow draw-only pulse that does not change measurement.
- [x] 6.5 Fade the glow after play ends and provide a static faint outline when reduced motion is requested.
- [x] 6.6 Add or update Home presentation tests for concise mode states, three-thumbnail overflow arithmetic, and multiple matching active collections.

## 7. Collection overview pacing UI

- [x] 7.1 Replace the current deadline-plan calendar-minute/differential copy with approximate required pace, recent tracked pace, projected capacity, and explicit reliable/learning/incomplete states.
- [x] 7.2 Render `Change deadline` only from the domain eligibility flag and initialize its picker from the estimated fit date when one is available.
- [x] 7.3 Add mode-appropriate Personal Pace sections for completion goals and ordered queues while rendering no pacing section for basic lists.
- [x] 7.4 Keep missing-estimate counts and confidence explanations in the overview, with no definitive fit language when either gate is incomplete.
- [x] 7.5 Add presentation tests for conditional deadline-action visibility and each pacing state/mode.

## 8. Validation and visual verification

- [x] 8.1 Run `./gradlew.bat testDebugUnitTest --offline` and fix all affected JVM tests and fakes.
- [x] 8.2 Run `git diff --check` and validate `refine-collection-pacing-ui` with OpenSpec in non-interactive JSON mode.
- [ ] 8.3 On a device or emulator, verify Home hierarchy and three-thumbnail overflow with narrow and wide cards, long collection names, missing icons, and several collections; verify portrait hero-capsule artwork keeps Library and collection grids balanced.
- [ ] 8.4 On a device or emulator, verify one active game illuminates every matching collection, the glow fades after play, and reduced motion uses a non-animated cue.
- [ ] 8.5 On a device or emulator, verify bright/missing header art leaves horizontal collection metrics and management controls readable, and missing hero-capsule assets retain the grid fallback.
- [ ] 8.6 Manually verify reliable, learning, incomplete, on-track, at-risk, today, passed, and completed pacing states, including exact `Change deadline` visibility.
