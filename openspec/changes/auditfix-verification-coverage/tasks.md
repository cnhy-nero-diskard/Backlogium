## 1. Scope the historical coverage question first

- [ ] 1.1 Establish the oldest database version plausibly present on a real device; if the install base is effectively one device, record that finding and narrow the historical target accordingly (design.md, "The uncomfortable constraint")
- [ ] 1.2 Read the migration chain in `data/local/BacklogiumDatabase.kt` and list each version transition with what it changes, so the tests below target real transitions rather than guesses
- [ ] 1.3 Write the conclusion into design.md: which version is the deep fixture target, and which versions are explicitly not covered and why

## 2. Schema export baseline

- [ ] 2.1 Set `exportSchema = true` in `BacklogiumDatabase.kt:49`
- [ ] 2.2 Configure the schema output location in `app/build.gradle.kts` so exports land in `app/schemas/`
- [ ] 2.3 Build to generate the v14 schema JSON and commit it as the baseline
- [ ] 2.4 Add `room-testing` as an `androidTestImplementation` dependency

## 3. Migration tests

- [ ] 3.1 Create `app/src/androidTest/java/.../data/local/MigrationTest.kt` with a `MigrationTestHelper` configured against the committed schema directory
- [ ] 3.2 Write a reusable seeding helper that inserts the representative record set from design.md: a game with playtime plus `backfillMinutes` and `isGoal`, a session, a daily-progress row, an achievement with a rarity snapshot, and the singleton profile with XP and `longestStreak`
- [ ] 3.3 Write a reusable assertion helper that reads every seeded value back and compares it to what was inserted — schema validation alone must never be the only assertion
- [ ] 3.4 Add the deep-history test using a raw `execSQL` fixture for the version chosen in task 1.3, running the full chain to current
- [ ] 3.5 Confirm the tests fail when deliberately broken — temporarily alter a migration to drop a column's data and verify the assertion catches it, then revert
- [ ] 3.6 Document at the top of the test file that any new database version requires a corresponding test here, so the requirement is discoverable from the code

## 4. Cloud function tests

- [ ] 4.1 Add a test runner to `functions/` as a dev dependency and a `test` script to `functions/package.json`
- [ ] 4.2 Write an in-memory fake of the narrow Firestore surface the poller uses — `collection().doc().get()`, `batch().set()`, `commit()` — recording writes for assertion
- [ ] 4.3 Test `isMaterialChange` directly as a pure function across its input space
- [ ] 4.4 Test `recordObservation` for: same-game poll writes nothing; game-to-game transition writes both documents and resets `since`; game-to-offline records a transition; Steam error or timeout writes nothing
- [ ] 4.5 Test that a persona-state-only change writes nothing, protecting the constraint in CLAUDE.md that idle-account persona churn must not fragment sessions
- [ ] 4.6 Add a duplicate-delivery test asserting the *current* behaviour, with a comment naming `auditfix-cloud-poller-consistency` as the change that will invert it — an unannotated passing test would read as an endorsement of a known defect

## 5. CI jobs

- [ ] 5.1 Add a `lint` job running `./gradlew lintDebug` as a separate job, following the existing file's pattern of independent per-toolchain jobs
- [ ] 5.2 Generate a `lint.baseline` so the job starts green, and note in the commit message that the baseline is a debt marker and not an approval
- [ ] 5.3 Add an `instrumented` job running the migration and DAO tests on an emulator, with the API level and AVD cache pinned
- [ ] 5.4 Add a `test` step to the existing `functions` job invoking the new `npm test`
- [ ] 5.5 Verify all jobs run on both pull requests and `master` pushes, and that each fails independently of the others
- [ ] 5.6 Record the instrumented job's wall-clock time; if it is long enough to invite deletion, reduce its scope to migrations only rather than reducing its frequency

## 6. Spec-governance rule

- [ ] 6.1 Add the "specs win, and a test encoding known-incorrect behaviour must say so" rule to the project's agent orientation in `CLAUDE.md`
- [ ] 6.2 Add the clarifying comment to `streak_ignoresGapsBetweenDatesUsesOrderOnly` stating that order-only folding is the engine's intended pure contract per the `gamification` spec, and that calendar densification is the caller's responsibility — this is what would have prevented the audit's misreading
- [ ] 6.3 Do **not** change `Gamification.streak()` or its expectations; the calendar-gap defect is at `GamificationUpdater.kt:125` and belongs to `auditfix-day-attribution`

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm still green
- [ ] 7.2 Run the instrumented tests locally against a device or emulator and confirm green
- [ ] 7.3 Run `npm --prefix functions run build` and `npm --prefix functions test` and confirm both pass
- [ ] 7.4 Run `openspec validate --change auditfix-verification-coverage`
- [ ] 7.5 Confirm no product behaviour changed in this change — the diff should contain tests, CI configuration, one Room flag, committed schema JSON, and comments
