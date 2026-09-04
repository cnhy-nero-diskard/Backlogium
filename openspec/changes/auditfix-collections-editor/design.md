## Context

See `proposal.md` — Why. Three findings in the collections editor, sharing two files.

What makes #110 different from the other two, and the reason this design exists: it is the
one audit finding where the *specification* is unimplementable rather than unimplemented.
`custom-collections/spec.md:232-235` requires a deadline-goal collection to default to
"days remaining", and:

```
Collection ──┬── targetDate  ◀── ONE date, on the collection
             │
             ├── member (appId)   days remaining = f(targetDate, today)
             ├── member (appId)   days remaining = f(targetDate, today)   ← identical
             └── member (appId)   days remaining = f(targetDate, today)   ← identical
```

Every member of one deadline collection has the same days-remaining value. There is no
faithful implementation to write. So #110 is a decision about the spec, and Decision 3 makes
it rather than deferring it into an open question.

## Goals / Non-Goals

**Goals:**

- Save is a transaction, matching what Cancel already is and what `save()`'s own KDoc claims.
- A blank name cannot reach storage by any route.
- No offered sort reports an ordering the members are not in.

**Non-Goals:**

- Per-member target dates for deadline collections. That is a feature (see Decision 3), and
  the audit's job here is to stop the current behaviour lying.
- Fixing the documented `HomeViewModel` entity-boundary breach. `CLAUDE.md` records it as
  deferred because the collections UI surface is broad; this change adds a repository method
  and must not widen it.
- Any change to ordered-queue sequencing, done-mark semantics, or the banner.

## Decisions

### Decision 1: One DAO transaction, with the reconciliation inside it

`save()` currently issues six kinds of call in sequence, each committing on its own. The fix
is a single repository method backed by one DAO transaction covering: the collection row
(insert or update), member additions, the new sequence order, member removals, and done
marks.

**The reconciliation moves down with it.** Today `save()` reads
`collectionRepository.getMembers(id)` and diffs it against the desired set *in the ViewModel*,
then issues per-item calls. That read is outside any transaction, so the diff can be computed
from a membership that changed before the writes land. Moving the read-diff-write inside the
transaction makes the reconciliation consistent as well as atomic — the same
"re-read your baseline inside the commit" property the archived
`auditfix-sync-write-integrity` established for the sync path.

**Alternative considered**: keep the per-item calls and wrap the whole `save()` body in a
transaction at the repository level. Rejected — it would work, but it keeps a UI-layer loop
driving N database calls inside a transaction it does not own, and the ViewModel would still
be the thing that knows how membership reconciliation works. Passing the desired end state
down and letting the data layer reconcile is both atomic and better-placed.

**The busy flag.** `_saving` is set before the launch and cleared only via `_done`. Any throw
leaves it true forever. It needs a `try`/`finally` (or equivalent) so a failure releases the
editor — which the spec now requires as its own scenario, because a stuck screen is how the
user experiences this bug even when no data was lost.

### Decision 2: Guard the blank name in both layers, and disable rather than restyle

Two changes, deliberately redundant:

- `CollectionScreen.kt:1429-1448` — the FAB currently switches only `containerColor` and
  `contentColor`. It gains a real disabled state so `onClick` is not invoked at all.
- `CollectionViewModel.save()` — checks the name alongside `_saving`, and refuses.

The redundancy is the point and the audit asked for it explicitly: "The ViewModel should also
enforce the invariant so non-Compose callers/tests cannot bypass presentation state." A
constraint that lives only in a composable's colour choice is not a constraint. Trimming
whitespace before the check is required, since a whitespace-only name is blank for every
purpose the user cares about.

### Decision 3: Remove `DAYS_REMAINING` as a member sort; deadline mode defaults to completion fraction

The audit offered "define a meaningful per-member deadline-derived metric" or "remove
`DAYS_REMAINING` as a member sort". Removal chosen.

**Why not define a per-member metric.** It requires per-member target dates — a new column, a
migration, editor UI to set them, and a product answer to "what does a member deadline mean
when the collection also has one?". That is a feature with real design surface, and it is not
the audit's finding. The finding is that the app currently claims an ordering it does not
produce.

**Why completion fraction as the deadline default.** It has to become something, since
`defaultSort()` maps `DEADLINE_GOAL → DAYS_REMAINING` today. `NAME` is available and honest
but inert. Completion fraction — most complete first — is the metric a player racing a
deadline actually wants: it says what is closest to finished, which is what you act on when
time is short. It is already implemented and already the completion-goal default, so it adds
no new ordering code.

**Why no migration is needed.** `CollectionSort` is persisted by constant *name*, and
`collectionSortOrNull` already returns null for a name it cannot parse, which falls back to
the mode default. `CollectionSort`'s own KDoc describes this as the intended behaviour for a
renamed constant. So an existing deadline collection storing `DAYS_REMAINING` lands on
completion fraction with no schema work — and the spec now states that fallback as a
requirement rather than leaving it as an implementation detail.

**What the user actually observes.** Almost nothing, which is worth being explicit about:
these collections are sorted alphabetically *today*, because that is the bug. After this they
are sorted by completion fraction, and the picker no longer offers a "Deadline" option that
did nothing. The days-remaining figure keeps appearing in the deadline banner
(`custom-collections/spec.md:139`) — that is a collection-level value, correct, and untouched.

### Decision 4: Delete the shared fallback branch rather than repointing it

`CollectionSummary.order()` at `:107-113` groups `DAYS_REMAINING` with `MANUAL_SEQUENCE`. That
branch is dead for manual sequence — `order()` returns early for `ORDERED_QUEUE` at `:98`, so
a `MANUAL_SEQUENCE` collection never reaches the `when`. The branch exists to satisfy
exhaustiveness, and `DAYS_REMAINING` was evidently swept into it.

With `DAYS_REMAINING` gone the branch handles only an unreachable case, so it should
disappear and let the `when` be exhaustive over what remains. Leaving a fallback branch in
place is how the next unhandled sort key silently becomes alphabetical, which is precisely
this bug's mechanism.

## Risks / Trade-offs

**The transactional save is the riskiest edit** → It moves membership reconciliation from the
ViewModel into the data layer. Task 2.7 injects a failure between what used to be two separate
commits and asserts nothing was stored, which is the property the current code lacks and the
only test that proves the change worked.

**Room transaction scope and suspending calls** → `DatabaseTransactionScope` already exists in
`data/backup/` and `SessionActionWriter` uses it, so there is a house pattern for
"the caller owns the transaction" rather than a new mechanism to design. Task 2.2 uses it.

**Removing an enum constant could break something reading it by name** → `collectionSortOrNull`
is the only parse path and already tolerates unknown names. Task 4.5 verifies an existing
collection storing `DAYS_REMAINING` loads and lands on the new default rather than failing.

**Users may read "Deadline" disappearing as a lost feature** → It never worked; it produced
alphabetical order. There is nothing to preserve, and the deadline banner's days-remaining
figure is unaffected. If per-member deadlines are wanted later, they arrive as a feature with
the data model to support them.

**Adding to `CollectionRepository` while a boundary breach is open there** → `CLAUDE.md`
records the `HomeViewModel` entity leak as deferred debt on this repository's public API. Task
2.3 requires the new method to take and return plain values rather than entities, so it does
not enlarge the surface that eventual fix has to cover.

## Migration Plan

No schema migration. `DAYS_REMAINING`'s removal is absorbed by the existing
unknown-name fallback (Decision 3).

Order: name guard first (smallest, independently useful), then the sort removal (no
dependency on either other item), then the transactional save last — it is the largest edit
and benefits from the name guard already preventing one class of bad input from reaching it.

Rollback: all three are code-only reverts with no stored-data consequence.
