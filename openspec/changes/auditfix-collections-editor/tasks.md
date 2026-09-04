## 1. Prerequisites

- [ ] 1.1 Confirm `auditfix-spec-truth` has landed, so this change's `app-ui` addition is sequenced after that change's spec sync rather than merging alongside it
- [ ] 1.2 Read `DatabaseTransactionScope` in `data/backup/` and how `SessionActionWriter` uses it — it is the house pattern for "the caller owns the transaction" and the transactional save should use it rather than a new mechanism. Verified by the plan naming it
- [ ] 1.3 Read `CLAUDE.md`'s note on the deferred `HomeViewModel`/`CollectionRepository` entity-boundary breach before adding to that repository, so the new method does not widen it

## 2. Blank-name guard (#123 — smallest, do first)

- [ ] 2.1 Give the save FAB in `CollectionScreen.kt:1429-1448` a real disabled state so `onClick` is not invoked while the name is blank, rather than only switching `containerColor`/`contentColor`. Verified by a UI test that tapping it with a blank name calls nothing
- [ ] 2.2 Add a name check to `CollectionViewModel.save()` alongside the existing `_saving` check, trimming whitespace first so a whitespace-only name is refused as blank. Verified by the test in 2.4
- [ ] 2.3 Keep both guards. The redundancy is deliberate — the audit asked for the ViewModel to enforce it so non-Compose callers and tests cannot bypass presentation state (design.md Decision 2). Verified by both present
- [ ] 2.4 Test: calling `save()` directly with a blank or whitespace-only name creates nothing, updates nothing, and does not set the done/navigate signal
- [ ] 2.5 Test: a non-blank name still saves normally, so the guard has not made the ordinary path unusable

## 3. Deadline sort removal (#110)

- [ ] 3.1 Remove `DAYS_REMAINING` from `CollectionSort` (`domain/CollectionSort.kt:17`). Verified by the enum no longer offering a metric the data model cannot distinguish
- [ ] 3.2 Change `CollectionMode.DEADLINE_GOAL`'s `defaultSort()` to `COMPLETION_FRACTION` — most complete first, which is what a player racing a deadline acts on (design.md Decision 3). Verified by a new deadline collection defaulting to it
- [ ] 3.3 Remove `CollectionSort.DAYS_REMAINING` from the deadline mode's picker options (`CollectionScreen.kt:1701`) and its "Deadline" label (`:1716`). Verified by the picker offering no option that produces a different order than it names
- [ ] 3.4 **Delete** the shared fallback branch in `CollectionSummary.order()` (`:107-113`) rather than repointing it, and let the `when` be exhaustive over the remaining keys. That branch is unreachable for `MANUAL_SEQUENCE` because `order()` returns early for `ORDERED_QUEUE` at `:98`; leaving a fallback is how the next unhandled key silently becomes alphabetical (design.md Decision 4). Verified by no catch-all branch remaining
- [ ] 3.5 Test: an existing deadline-goal collection with `DAYS_REMAINING` stored loads normally and is ordered by the mode default, via `collectionSortOrNull` returning null for an unparseable name. **No migration should be needed** — verify that rather than adding one
- [ ] 3.6 Test: a deadline-goal collection's members are ordered by completion fraction, not alphabetically — the regression test for #110
- [ ] 3.7 Confirm the deadline banner still presents days remaining until the target date (`custom-collections/spec.md:139`). That is a collection-level value and is correct; only the member *sort* is removed. Verified by the banner unchanged on a device
- [ ] 3.8 Confirm ordered-queue collections still order by sequence regardless of sort selection, and that done-mark semantics are untouched

## 4. Transactional save (#124 — largest, do last)

- [ ] 4.1 Add a repository method that takes the buffered end state — collection fields, desired member list in order, done marks — and commits it as one unit. Verified by a single call replacing the six-call sequence in `save()`
- [ ] 4.2 Back it with one DAO transaction covering the collection row (insert or update), member additions, sequence order, removals, and done marks, using `DatabaseTransactionScope` (design.md Decision 1). Verified by the test in 4.7
- [ ] 4.3 **Move the membership reconciliation into the transaction.** `save()` currently reads `getMembers(id)` outside any transaction and diffs in the ViewModel, so the diff can be computed from a membership that changed before the writes land — the same re-read-inside-the-commit property `auditfix-sync-write-integrity` established for sync. Verified by the read and the writes sharing one transaction
- [ ] 4.4 Make the new method take and return plain values, not `Collection`/`CollectionMember` entities, so it does not widen the boundary breach `CLAUDE.md` records for this repository. Verified by no entity type in its signature
- [ ] 4.5 Release `_saving` on failure via `try`/`finally` or equivalent, so a failed save leaves the editor usable instead of stuck until recreation. Verified by the test in 4.8
- [ ] 4.6 Remove the now-inaccurate parts of `save()`'s KDoc claim and let it describe what the code does — it currently says "Persist everything atomically" while doing the opposite
- [ ] 4.7 Test: inject a failure after the point where the collection row and member additions would previously have committed, then assert **nothing** was stored — no details update, no added members, no reorder, no done marks. This is the regression test for #124 and the most important test in this change
- [ ] 4.8 Test: a failed save clears the saving indication and the save action works again
- [ ] 4.9 Test: a successful save commits fields, membership, sequence order, and done marks together
- [ ] 4.10 Test: Cancel still discards everything in memory, so the two halves are symmetric

## 5. Close out

- [ ] 5.1 `openspec validate --strict auditfix-collections-editor` passes
- [ ] 5.2 `./gradlew :app:testDebugUnitTest` passes
- [ ] 5.3 Confirm the `CLAUDE.md` boundary grep is no worse than before this change: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`. The `HomeViewModel` breach remains reported (it is deferred, not fixed here); no new line may appear
- [ ] 5.4 On a device: create a deadline collection, add members with differing completion, and confirm they order by completion fraction with no "Deadline" sort offered
- [ ] 5.5 Sync the deltas into `openspec/specs/` via the archive workflow, not by hand
- [ ] 5.6 Close #110, #123, #124
