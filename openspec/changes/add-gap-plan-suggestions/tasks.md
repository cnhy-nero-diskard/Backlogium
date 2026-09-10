## 1. Steam Suggestion Metadata Storage

- [ ] 1.1 Add a nullable/defaulted participation-category payload to the Store metadata cache and a separate Steam review-summary cache entity/DAO, register both in Room, export the new schema, and verify fresh-install schema tests pass.
- [ ] 1.2 Add the forward Room migration that preserves existing genre/app-type rows with unknown categories and creates the review cache, and verify `MigrationTest` covers retained old rows, new defaults, and writable upgraded tables.
- [ ] 1.3 Extend backup/restore account-reset handling so refreshable suggestion caches are intentionally excluded and safely cleared where other account-derived caches are cleared, and verify backup round-trip and account-change tests remain green.

## 2. Steam Metadata Acquisition

- [ ] 2.1 Extend the narrow Store appdetails DTO, parser result, category codec, genre enrichment, and family-shared admission seeding to retain participation categories separately from genres, and verify fixtures cover multiplayer, online co-op, single-player-only, malformed, and absent categories.
- [ ] 2.2 Add a credential-free Steam review-summary endpoint and narrow DTO/parser for description and positive, negative, and total counts without review bodies, and verify data-source tests distinguish valid, definitive unavailable, malformed, HTTP, and transport outcomes.
- [ ] 2.3 Add repository domain models and observable cache mappings that preserve missing, unavailable, and available review/category states without leaking Room types, and verify repository tests cover all states and transient-failure retention.
- [ ] 2.4 Implement missing-first 30-day review enrichment in batches of at most 25 with at least 500 ms request spacing, continuation, network constraints, and backoff independent of authenticated sync, and verify worker/scheduler tests cover bounds, freshness, hidden-game exclusion, retry, and continuation.

## 3. Pure Gap-Plan Engine

- [ ] 3.1 Add plain domain request, capacity provenance, candidate, reason, intensity, variant, and snapshot models with Story/Completionist mapping, and verify JVM tests cover input validation and 70/85/100 percent budget rounding.
- [ ] 3.2 Implement source-aware remaining-work eligibility for visible owned and family-shared games, request category toggles, selected-basis HLTB coverage reporting, and Full-budget fit, and verify tests cover hidden-by-input games, partial progress, overflow safety, complete games, and missing estimates without any lookup dependency.
- [ ] 3.3 Implement the 95% Wilson review-quality component with neutral missing data and fact-bearing reason output, and verify tests show established high-volume ratings outrank tiny perfect samples while absent ratings remain neutral and undisclosed.
- [ ] 3.4 Implement 56-completed-day, 28-day-half-life genre affinity using at most one contribution per game/date/genre plus selected-basis completion momentum, and verify tests cover recency weighting, marathon resistance, no history, unknown genres, unplayed games, and partial progress.
- [ ] 3.5 Implement deterministic one-to-five-game bundle scoring and bounded beam composition for each intensity, enforcing budget, uniqueness, utilization, neutral unknown genres, and pairwise genre diversity, and verify tests cover adversarial packing, the five-game cap, empty/one-game plans, diversity preference, and app-id tie-breaking.
- [ ] 3.6 Add pure remove-and-replace validation against the retained eligible pool, recalculating planned/reserve minutes without mutating the other choices, and verify tests reject duplicate or over-budget replacements and preserve stable accepted membership.

## 4. Aggregation And Live Enrichment

- [ ] 4.1 Add one repository-level gap-plan feed that joins visible `LibraryGame` values, Personal Pace, recent completed-session projections, and suggestion metadata into domain inputs, and verify feed tests cover reliable pace, required manual hours while learning, truthful family-shared playtime, and offline cache gaps.
- [ ] 4.2 Add bounded current-player enrichment for at most twelve distinct shortlisted multiplayer app ids with deduplication, bounded concurrency/time, and no lookup for single-player candidates, and verify tests cover successful zero, unavailable counts, shared candidates across variants, and the twelve-id ceiling.
- [ ] 4.3 Guard every generation and live lookup with identity-based ownership so cancellation, navigation, regeneration, and late timeout results cannot publish over a successor, and verify coroutine tests reproduce superseded same-request and independent-request races.
- [ ] 4.4 Apply `ln(1 + count)` only as a comparison among otherwise similar multiplayer alternatives, finalize once after enrichment or timeout, and verify tests show live counts can refine multiplayer choices without excluding unavailable games or outranking unrelated single-player games.

## 5. Collection Adoption

- [ ] 5.1 Map an accepted snapshot to a new `CollectionSaveDraft` named `Before <anticipated title>` with deadline mode, default deadline sort, ISO target date, selected basis, exact distinct membership, and no done marks, and verify mapping tests cover Story, Completionist, edited previews, and family-shared members.
- [ ] 5.2 Save the mapped draft through the existing database transaction and return the created collection id while preserving the preview on failure, and verify integration tests prove collection-plus-membership atomicity, retry behavior, and stable membership after recommendation inputs change.

## 6. Gap-Plan UI

- [ ] 6.1 Add ViewModel state and actions for setup validation, Personal Pace/manual capacity provenance, generation ownership, three finalized variants, transient edits, save confirmation, busy/error recovery, and created-collection navigation, and verify ViewModel tests cover reliable, learning, offline, empty, regenerate, edit, save-success, and save-failure flows.
- [ ] 6.2 Build an adaptive setup surface for anticipated title, future date, Story/Completionist intent, unplayed/started inclusion, and learning-only total hours, and verify Compose behavior tests cover validation, defaults, manual-hours visibility, narrow layouts, and state restoration within the route.
- [ ] 6.3 Build distinct Relaxed, Balanced, and Full result cards with capacity source, available/planned/reserve time, up to five game cards, family-shared labels, factual reason chips, and missing-data disclosures, and verify Compose tests cover complete, sparse, offline, empty, and incomplete-HLTB states.
- [ ] 6.4 Add remove, budget-valid replacement, regenerate, review-save, retry, and open-created-collection interactions without haptics outside the shared vocabulary, and verify UI tests cover budget rejection, stable snapshots, superseded enrichment, confirmation contents, and recoverable save failure.
- [ ] 6.5 Register one pushed gap-plan route and add entry actions on Home and Collections without changing bottom navigation, and verify navigation tests open the same builder from both surfaces and return/open the created collection correctly.

## 7. Verification

- [ ] 7.1 Run `./gradlew :app:testDebugUnitTest` and fix all planner, repository, worker, ViewModel, migration, and regression test failures.
- [ ] 7.2 Run `./gradlew assembleDebug` and verify the debug APK compiles with Room schema export, Hilt bindings, WorkManager registration, navigation, and Compose resources.
- [ ] 7.3 Run the repository UI-to-storage import boundary grep and haptic-authority grep documented in `CLAUDE.md`, and verify the new product UI imports no Room/DAO/DataStore types and introduces no platform haptic call.
- [ ] 7.4 Exercise on a device or emulator with reliable pace, learning/manual budget, offline mode, missing HLTB/reviews, family sharing, multiplayer enrichment, regeneration, and accepted collection save, and record that all three variants remain readable and stable on narrow and wide layouts.
