## Why

The adversarial audit's highest-severity findings — write races in the Steam sync path,
non-atomic persistence, Firestore transition duplication, day-attribution
inconsistency — all require schema changes, migrations, or concurrency changes to fix.
The repository currently has no way to catch a mistake in any of those categories.

Specifically: the database is at version 14 with a long sequence of hand-written
migrations, `exportSchema = false`, and **zero** migration tests. CI runs
`./gradlew test` and a TypeScript compile, which means it verifies JVM unit behaviour
on freshly-created databases and that `functions/` parses. It does not verify that an
existing user upgrading from v9 keeps their data, and it does not execute a single line
of the cloud poller's state machine.

This change is sequenced *before* the correctness fixes on purpose. Three of the
remaining `auditfix-*` proposals will add migration 15 and beyond. Writing those
migrations into a database with no schema history and no upgrade tests is how a
correctness fix becomes a data-loss incident — and this app's data is unrecoverable by
design, because Steam exposes no history to re-derive it from.

## What Changes

- **Room schema export is enabled and schemas are committed.** `exportSchema = false`
  in `BacklogiumDatabase.kt:49` means there is no recorded shape for any version,
  including the current one. Turning it on captures v14 as the baseline going forward.
  It cannot retroactively produce v1–v13 — see design.md for how far back
  verification can realistically reach.
- **Migration verification tests are added.** `MigrationTestHelper` tests that create a
  database at an older version, insert representative rows, run the real migrations,
  and assert both that the schema matches and that the seeded data survived. Schema
  equality alone is insufficient: a migration that produces the correct columns while
  dropping every row passes a schema check and destroys a library.
- **CI grows the jobs that can catch the audit's remaining findings.** Android lint, an
  instrumented job to run the migration and DAO tests on an emulator, and a real test
  job for `functions/`. The current two jobs are structurally unable to see any of it.
- **`functions/` gets a test suite and a `test` script.** The presence poller
  implements read–compare–write against a shared store with at-least-once delivery.
  That is distributed-systems behaviour currently verified only by running in
  production. Cases: same-game poll, game-to-game transition, game-to-offline,
  Steam timeout, and duplicate scheduler delivery.
- **A spec-governance rule is recorded.** The audit's closing finding is that a green
  test suite can actively preserve a product bug when a test encodes an
  implementation-era assumption that a higher-level spec contradicts. The audit
  identified the wrong instance of this — see Impact — but the risk is real and worth a
  written rule about which artifact wins.

## Capabilities

### New Capabilities

- `schema-migration`: what the app guarantees about upgrading an existing installation
  — that a database created by any previously released version can be upgraded to the
  current version with its data intact, and that this is mechanically verified rather
  than asserted.

### Modified Capabilities

None. CI configuration and test coverage are not behaviour, and the governance rule in
design.md describes how specs and tests relate rather than changing any requirement.

## Impact

**New and changed paths**

| Path | Change |
|---|---|
| `app/src/main/java/.../data/local/BacklogiumDatabase.kt` | `exportSchema = true` |
| `app/schemas/` | new — committed schema JSON, starting at v14 |
| `app/build.gradle.kts` | schema location arg; `androidTestImplementation` for `room-testing` |
| `app/src/androidTest/java/.../MigrationTest.kt` | new |
| `.github/workflows/ci.yml` | lint job, instrumented job, functions test job |
| `functions/package.json` | `test` script and a test-runner dev dependency |
| `functions/src/**/*.test.ts` | new — poller state-machine cases |

**Correction carried from the audit, and it matters here**

The audit claims a unit test (`streak_ignoresGapsBetweenDatesUsesOrderOnly`) contradicts
the `gamification` spec and therefore preserves a bug. It does not. The spec defines the
engine as pure with callers supplying inputs, and requires streaks be computed "from an
ordered set of per-day quest results" — *ordered*, describing the list, not the calendar.
The engine and that test agree with the spec. The real defect is at
`GamificationUpdater.kt:125`, which passes a sparse `getAllOrdered()` row set without
densifying it to a calendar sequence, and it belongs to `auditfix-day-attribution`.

This is recorded here rather than silently dropped because the audit used that finding
as its evidence for the governance concern. The concern survives; its example does not.
Anyone implementing this change should not "fix" the streak test.

**Scope boundary**

This change adds no product behaviour and fixes none of the audit's correctness
findings. Its only output is the ability to detect them. Instrumented tests will
lengthen CI; design.md covers keeping that cost bounded.
