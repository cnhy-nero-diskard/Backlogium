# Design

## Context

This change builds a net. It is worth being precise about what the net can and cannot
catch, because the temptation with test-infrastructure work is to declare victory once
the jobs are green rather than once the risks are covered.

## The uncomfortable constraint: v1–v13 schemas are unrecoverable

`exportSchema = false` has been set since the beginning, so no schema JSON exists for
any version. Enabling it now captures v14 and everything after. It does not and cannot
reconstruct the earlier ones.

`MigrationTestHelper` needs a schema JSON for the *starting* version to create a
database at it. Without v9's JSON, there is no supported way to ask Room for a v9
database.

Three options, and the choice determines what this change is actually worth:

| Option | Effort | What it verifies |
|---|---|---|
| **A.** Baseline at v14; test only v14→v15+ | low | every future migration, nothing historical |
| **B.** Reconstruct old schemas from git history of the entity classes | high | historical upgrades, at the cost of hand-authored JSON that may not match what Room actually emitted |
| **C.** Hand-write raw-SQL fixtures for selected old versions | medium | historical upgrades, without pretending the fixture is a Room export |

**Chosen: A, plus C for a single deep version.**

A alone leaves the existing installed base unverified forever. B is a trap — a
hand-authored "export" that differs from what Room really produced in v9 gives a test
that passes against fiction, which is worse than no test because it reads as coverage.

C is honest about what it is. Pick one meaningfully old version — the oldest whose
migration chain is still reachable and which plausibly exists on a real device — create
the database with raw `execSQL` from the entity definitions at that commit, seed it,
run the real migration chain to current, and assert the data survived. It verifies the
chain end to end without claiming to be a Room export.

**Which version to pick is a question for whoever implements this**, and it needs one
piece of information this change does not have: whether any installation older than a
given version plausibly exists. This is a personal-use app with a known install base of
roughly one device. If the implementer can establish that no device is running below,
say, v12, then C's scope shrinks accordingly and should. Do not build historical
coverage for versions that have no users — write down the finding instead.

### Historical target decision made during implementation

The repository's original app design records a single user, single Steam account, and
one device (`openspec/changes/archive/2026-07-24-add-android-steam-app/design.md`). The
latest recorded device observations identify `emulator-5554` on 2026-08-14
(`openspec/changes/archive/2026-08-14-optimize-steam-sync/tasks.md`). This checkout could
not query the live database: `adb` is not available to the process and the SDK path in
`local.properties` is inaccessible. Therefore this decision does not claim a live
database dump or prove that an older install still exists.

The current application schema is v14, and v13 is the immediately preceding schema for
the only documented installation. v13 is therefore the oldest version this evidence
justifies as an installed-base target. The deep fixture will be a hand-authored raw-SQL
v13 database that runs the real 13->14 migration. Versions v1->v12 are explicitly not
covered: no second or older installation is documented, and no Room exports exist from
which to reconstruct those shapes without testing against fiction. The v13 fixture is
an honest pre-export fixture, not a retroactive Room schema export.

### Migration chain inventory

The real chain under test is the following; the inventory also fixes the historical
fixture's target to an actual transition rather than a guessed version:

| Transition | Change |
|---|---|
| v1->v2 | Create `hltb_data`. |
| v2->v3 | Create `achievements`. |
| v3->v4 | Add `games.backfillMinutes` and `player_profile.playtimeBackfilled`. |
| v4->v5 | Add `player_profile.personaName` and `avatarUrl`. |
| v5->v6 | Add the sessions natural-key index. |
| v6->v7 | Add achievement `description` and `hidden`. |
| v7->v8 | Create diagnostics tables and their indexes. |
| v8->v9 | Create `collections`, `collection_members`, and the member index. |
| v9->v10 | Add collection `accent` and member `done`. |
| v10->v11 | Add collection `timeBasis`. |
| v11->v12 | Create `game_genre_cache`. |
| v12->v13 | Add collection `description` and `displayOrder`, then backfill order. |
| v13->v14 | Add sync tier counters, create `game_achievement_sync`, and translate/delete the old no-achievements sentinel. |

## Data survival, not just schema equality

`MigrationTestHelper.validateMigration` compares schemas. A migration that recreates a
table with the right columns and copies no rows passes it.

Every migration test in this change therefore follows:

```
  1. create DB at version N          (schema JSON or raw execSQL fixture)
  2. insert representative rows      ← the step that makes this a real test
       - one game with playtime, backfillMinutes, isGoal set
       - one session, one daily-progress row
       - one pre-v14 sync run with a related request-breakdown row
       - one achievement with a rarity snapshot
       - the singleton player profile with XP and longestStreak
  3. run the real migrations to current
  4. assert schema validated  AND  every seeded value is readable and unchanged
```

Step 2's row selection is deliberate: those are exactly the fields the audit's other
findings show are load-bearing and easy to lose — `backfillMinutes` and `isGoal` are
app-owned columns the sync must not clobber, `longestStreak` is a high-water mark that
must never regress, and the rarity snapshot has a documented first-unlock invariant.
A migration that silently drops one of them is the failure this test exists to catch.

## CI shape: independent jobs, not steps

The existing `ci.yml` already models the right instinct — `functions` is a separate job
from `test` with a comment explaining that the toolchains share nothing and should fail
independently. Add lint and instrumented as separate jobs; extend `functions` with its
test command while keeping its toolchain independent.

```
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │ test         │  │ lint         │  │ instrumented │  │ functions    │
  │ (existing)   │  │ (new)        │  │ (new)        │  │ typecheck +  │
  │ ./gradlew    │  │ ./gradlew    │  │ emulator:    │  │ test (new)   │
  │   test       │  │ lintDebug    │  │ migrations   │  │              │
  │              │  │              │  │ migrations   │  │              │
  │  ~fast       │  │  ~fast       │  │  ~slow       │  │  ~fast       │
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘
        │                 │                 │                 │
        └─────────────────┴─────────────────┴─────────────────┘
                     independent failures, parallel
```

**The instrumented job is the expensive one** and the one most likely to be disabled in
six months if it is flaky or slow. Two decisions to keep it alive:

- **Run it on pull requests and `master` pushes, same as the others.** An emulator job
  that only runs nightly is a job nobody looks at. If it turns out to cost too much,
  reduce its *scope* (migrations only, drop DAO tests) rather than its *frequency*.
- **Pin the emulator API level and AVD cache.** An unpinned emulator image is the usual
  source of "it failed again, just re-run it", and two re-runs is all it takes before a
  team starts ignoring a job.

The app's DAO tests are JVM tests (`:app:testDebugUnitTest`), so they remain in the
existing fast unit-test job. The instrumented job is intentionally filtered to
`MigrationTest`: the current Android-test tree also contains unrelated Compose UI
tests, and this change must not make migration coverage depend on their state.

The focused local `MigrationTest` on the API 35 `Medium_Phone_API_35` emulator
completed in approximately 65 seconds wall-clock, including Gradle startup,
installation, and test execution. That is acceptable for pull-request coverage;
if the complete instrumented suite proves materially slower, reduce its scope to
migrations before reducing its trigger frequency.

**Lint will almost certainly fail on first run.** A codebase that has never run lint
accumulates warnings. Establish a baseline (`lint.baseline`) so the job starts green and
new issues are visible, and record in the commit message that the baseline is a debt
marker rather than an approval. Do not fix the entire existing lint backlog inside this
change — that is unbounded scope wearing a small hat.

## Cloud function tests: fake the store, don't emulate it

The poller's logic worth testing is `isMaterialChange` and `recordObservation`'s
read-compare-write sequence. Neither needs a real Firestore.

**Chosen**: unit tests against an in-memory fake of the narrow Firestore surface the
poller uses (`collection().doc().get()`, `batch().set()`, `commit()`), plus direct tests
of `isMaterialChange` as a pure function.

**Rejected**: the Firestore emulator as the primary vehicle. It is slow to start, adds a
Java dependency to the Node job, and its value is in verifying rules and transaction
semantics — neither of which is what these tests are for. It becomes the right tool once
`auditfix-cloud-poller-consistency` introduces `runTransaction`, and that change should
add it. Here, a fake keeps the job fast enough that nobody deletes it.

Cases to cover, drawn directly from the audit:

- same-game poll → no write at all
- game-to-game transition → both documents written, `since` reset
- game-to-offline → transition recorded
- persona-state-only change → **no write**, protecting the load-bearing constraint in
  CLAUDE.md that idle-account persona churn must not fragment sessions
- Steam timeout / error → no write, no partial state
- duplicate delivery of the same logical observation → currently produces a second
  document; **assert the present behaviour and mark the test as documenting a known
  defect**, so `auditfix-cloud-poller-consistency` inverts a failing expectation rather
  than discovering the behaviour from scratch

That last one is the governance rule below, applied.

## Spec-governance rule

The audit's closing concern is real even though its example was wrong. The rule:

> **`openspec/specs/` wins.** When a test and a normative spec disagree, the spec is
> authoritative and the test is the defect — unless the spec is the thing that is wrong,
> in which case it changes through a proposal, not through a test being quietly edited.
>
> A test that intentionally encodes behaviour known to be incorrect SHALL say so in a
> comment naming the change that will fix it. An unannotated passing test is a claim
> that the behaviour is correct.

The second paragraph is the operative one. The reason the audit misread the streak test
is that nothing in the test says whether order-only folding is intended or accidental —
the *spec* says it is intended, but the reader has to find the spec to know. A one-line
comment would have cost nothing and prevented a confident wrong conclusion in a
comprehensive audit. That is a cheap lesson to take.

## What this change deliberately does not do

- Does not fix any correctness finding. Adding a test that documents a known defect is
  in scope; changing the behaviour is not.
- Does not add UI or Compose tests. No audit finding points there, and the maintenance
  cost is high.
- Does not resolve the existing lint backlog beyond baselining it.
- Does not reconstruct v1–v13 Room schema exports. Explicitly rejected above.
