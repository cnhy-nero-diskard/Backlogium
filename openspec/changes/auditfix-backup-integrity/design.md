# Design

## Context

```
  IMPORT today
    read whole URI into memory ──────────── no size bound
    parse JSON ───────────────────────────── checks: decodes? version supported?
    merge ────────────────────────────────── semantic problems surface HERE,
      games                                   mid-write, one table at a time
      sessions
      daily progress        ← a failure at any of these leaves
      hltb                    everything above it applied and
      achievements            everything below it not
      collections
      members
      profile
```

Two independent problems that compound: validation happens too late, and there is no
boundary to roll back to when it fails. Fixing either alone leaves a bad outcome — full
validation without a transaction still fails halfway on a crash; a transaction without
validation still rolls back a restore the user could have been warned about in advance.

## Decision 1: Validate everything, then write everything

**Chosen**: a distinct preflight pass over the fully parsed backup that returns either a
list of problems or a validated value, followed by a merge that assumes validity.

```
  parse ──▶ preflight ──┬── problems ──▶ reject, report, DB untouched
                        │
                        └── validated ──▶ merge in one transaction
```

What preflight checks, drawn from what the merge currently discovers at write time:
dates parse and are plausible; timestamps are non-negative and ordered where ordering is
implied (`endAt >= startAt`); appIds are well-formed; collection members reference
collections present in the file; achievement rows reference games present in the file;
`snapshotPercent` is within range; no duplicate natural keys within one collection.

**The merge should then assume validity rather than re-checking.** Defensive re-checking
inside the merge means two places encode the same rule and they drift. If preflight
passed and the merge still fails, that is a bug in preflight and should surface as one,
not be silently absorbed.

**Rejected: validate lazily but wrap in a transaction**, letting the rollback handle bad
files. It technically satisfies atomicity and produces a terrible error experience — the
user learns only "import failed" after an expensive full-table write and rollback, with no
indication of which record was wrong. Preflight can name the problem.

**Recommendation on error reporting**: report *what* failed and *where* — record type and
index at minimum. A rejected restore with no diagnosis is the worst outcome in this whole
change, because the user's alternative is no data at all. This is the one place where the
new behaviour is strictly less forgiving than the old, and a good message is what makes
that trade acceptable.

## Decision 2: One transaction for the merge

Room's `withTransaction` around the whole merge. `BacklogiumDatabase` gains a transactional
entry point; the merge engine stops owning its own write sequencing.

Two constraints that are easy to get wrong:

- **No suspension on anything but the database inside the transaction.** No file reads, no
  network, no `DataStore` access. Room transactions are tied to a connection and a
  suspending call that hops threads can deadlock or silently break atomicity. If the merge
  currently reads settings mid-way, hoist that read out.
- **The post-import gamification recompute is inside the transaction.** `backup-restore`
  already requires aggregates be recomputed from merged raw data rather than taken from the
  file. If the recompute is outside, a crash between merge and recompute leaves raw data
  restored and aggregates describing the pre-import state — which is precisely the hybrid
  this change exists to prevent, just moved one step later.

**Size**: a full library merge in one transaction is a large write. Acceptable — restore is
a rare, explicitly-initiated, foreground operation, and the user is already waiting. Do not
chunk it into several transactions to reduce peak cost; chunking is how you get a hybrid.

## Decision 3: Size bound before materializing

Check the size the content resolver reports, and cap the read regardless of what it claims
— a resolver-reported size is metadata, not a guarantee.

**Bound**: derive it from a realistic worst case rather than picking a round number. A
library with thousands of games, each with achievements and sessions across years, at JSON's
verbosity. Compute an estimate, apply a generous multiplier, write the arithmetic down in a
comment. A limit no one can justify is a limit someone will raise casually the first time it
trips.

Fail with a message stating the limit and the file's size. "Import failed" on a 2 GB file
teaches the user nothing.

## Decision 4: Export from one point in time

Wrap the entire multi-table export read in a single Room read transaction, giving all reads
one consistent view.

**Rejected: serializing export against the sync** via the same unique work name. It would
work and it is heavier than needed — a read transaction gets consistency without making an
export wait behind a poll, and without coupling the backup path to WorkManager scheduling.

Note the export also reads `SettingsDataStore`, which is not in the database and cannot join
the transaction. Read settings *before* opening it, and accept that settings and library data
come from instants milliseconds apart. Settings do not participate in the cross-table
invariants that make a hybrid dangerous — the hazard is games-from-before with
aggregates-from-after, not a stale quest threshold.

## Decision 5: Resolving the rarity-snapshot contradiction

The spec currently says both of these, in one requirement:

> ...the snapshot associated with the **earlier unlock timestamp** SHALL be retained.

> **THEN** the locally stored snapshot and its unlock timestamp are retained, and the
> imported value is discarded for that achievement.

**Chosen: earlier-unlock-wins.** The requirement text is the correct rule and the scenario
is the defect.

Reasoning: `snapshotPercent` is a *frozen observation of global rarity at the moment of
first unlock*. Its entire value is that it captures a fact about a specific instant. If two
sources disagree, the one whose unlock is earlier is by definition closer to the real first
unlock — the other is a later re-observation, which is exactly what freezing exists to
exclude. Local-wins preserves whichever copy this device happened to see first, which is an
accident of device history, not a property of the data.

This also makes the rule composable: import A then B, or B then A, converges on the same
snapshot. Local-wins is order-dependent, and an order-dependent merge rule in a restore path
is a bug waiting for someone to import twice.

**Consequence — a real one.** This *changes behaviour*: an import can now overwrite a local
snapshot, which today never happens. That is a write to data the current spec describes as
protected, so it must be deliberate and tested, and the migration question ("what about
snapshots already chosen by the wrong rule?") answers itself — nothing to migrate, since the
stored value is whichever one arrived first and there is no record of the alternative.

**Consequence — the invariant text needs care.** "SHALL NOT be overwritten... once it has
been set" reads as absolute today. The corrected requirement must preserve its intent
(a snapshot is never refreshed to a *current* rarity value) while permitting the
earlier-unlock replacement. Those are different operations and the spec should say so.

**If the owner prefers local-wins**, that is defensible — it is simpler and it is what ships
today. Then the *requirement text* changes and the scenario stays. Either way one of the two
must move; what is not acceptable is leaving both.

## Testing strategy

- preflight rejects each invalid category — bad date, negative timestamp, `endAt < startAt`,
  orphan collection member, out-of-range snapshot, duplicate natural key — with the database
  provably unmodified afterwards
- a valid file imports fully
- failure injected midway through the merge leaves the database exactly as before
- failure injected between merge and recompute leaves the database exactly as before
- an oversized file is rejected before allocation, with the limit in the message
- export during a concurrent sync produces internally consistent data — assert games and
  sessions agree, rather than merely that the export succeeded
- rarity: imported snapshot with an earlier unlock replaces local; with a later unlock does
  not; import order does not affect the outcome

## What this change deliberately does not do

- Does not encrypt backup files.
- Does not change whether cross-account import is permitted — `auditfix-account-identity`
  owns that question, and `backup-restore` has an existing requirement allowing it.
- Does not add incremental or partial restore. All-or-nothing is the point.
- Does not chunk the merge transaction. Rejected in Decision 2.
