## Context

See `proposal.md` — Why. Seven audit findings where the audit concluded the spec is stale
and the code is right, plus one verification gap in the same capability as one of them.

The constraint that shapes this change is `CLAUDE.md`: `openspec/specs/` is normative, and
behaviour changes go through a change with a delta spec that is synced on archive. There is
no sanctioned path for "just fix the wording" — a stale clause is repaired the same way a
behaviour change is proposed. That makes the batching question below a real one rather than
a matter of taste.

## Goals / Non-Goals

**Goals:**

- Make the normative text describe the shipped behaviour in the six capabilities where the
  audit found it does not.
- Remove the two contradictions that later audit-fix changes would otherwise have to reason
  around (`live-status` idle monitoring; `live-status` XP inputs vs `game-sources`
  presence-derived sessions).
- Restore the deep-history migration test to the current database version, and make the
  target follow the version rather than a literal.

**Non-Goals:**

- Any production-code behaviour change. The only code touched is `MigrationTest.kt`.
- The drift findings where the code is wrong (#99, #104, #107, #109, #111, #112) — those are
  behaviour changes and belong with their fixes.
- #110, where the audit concluded the *specification* needs redesign rather than repair. A
  per-member deadline metric is a product decision, not a wording fix; it lives in
  `auditfix-collections-editor`.

## Decisions

### Decision 1: Batch the six spec repairs into one change rather than distributing them

**Chosen**: one change carrying all six capabilities' delta specs.

**Alternative considered**: fold each repair into the change that fixes co-located code —
#113 with `auditfix-background-work-contracts` (both `steam-sync`), #102 with
`auditfix-settings-boundary` (both the stale-Home story), and so on.

**Why batched wins here:**

- Two of the six are prerequisites for reasoning in *other* changes, not just in their own
  capability. `auditfix-session-ledger-integrity` has to describe presence-derived session
  behaviour while `live-status` still says XP comes "solely from playtime-delta-synthesized
  sessions". Distributing the repair means that change either inherits the contradiction or
  fixes it as a side effect, which is worse than fixing it deliberately.
- `live-status` receives two repairs (#105, #106). Distributing them puts two changes into
  the same spec file, which is the merge conflict this change exists to avoid.
- The batch has a uniform review question — "does the new text match what the code does?" —
  answerable without reading any implementation diff, because there is none. Mixed into a
  behaviour change, the same question competes for attention with the behaviour.

**Cost accepted**: this change's archive sync rewrites six capability specs at once, so the
sync commit is broad. That is a legible one-time diff, not a lasting hazard.

### Decision 2: Repair `schema-migration` by adding an intent test, not by listing exemptions

`MIGRATION_13_14` and `MIGRATION_17_18` both violate the current absolute wording, and both
are correct. Two ways to reconcile:

- **Enumerate the exceptions** — name those two migrations in the spec as permitted.
  Rejected: it dates the spec to a schema version, and the next legitimate repair migration
  re-opens the same drift. A normative document should state the rule, not the roster.
- **State the rule** (chosen) — a transformation is permitted when it is the migration's
  declared purpose *and* a test asserts the intended result. Both current migrations satisfy
  it; `MigrationTest.kt` already provides the tests, which is what makes them
  distinguishable from accidental loss in the first place.

The two-part test matters: intent alone would license any deletion the author felt was
tidy. The verification half is what makes "designed repair" a claim someone can check.

### Decision 3: The chain test asserts against the current version, not a literal

#121's proximate cause is that `MigrationTest.kt` hard-codes v14 as its target, so the test
kept passing while the schema advanced twelve versions past it. Extending it to v26 fixes
today's gap and rebuilds tomorrow's — the same test will silently stop tracking current at
v27.

The fixture therefore opens the populated v13 database with the real current database
version and asserts survival at whatever that is, rather than at a constant. Incrementing
`BacklogiumDatabase.version` then either passes (the chain still works) or fails (it does
not); neither outcome is "quietly validating v26 forever".

This is why the spec change is worded as a requirement about the *test's* target rather than
about v26 specifically.

### Decision 4: `backup-restore` narrows to export-only rather than gaining rule restore

The audit offered both directions. Export-only chosen because:

- It matches the shipped code (`BackupFile.kt:13-15`, `BackupRepository.kt:118-119`) and the
  archived design that produced it, so nothing has to change but the text.
- The alternative has genuinely surprising semantics: importing a backup would silently
  rewrite the receiving device's rules, and every derived value on that device — not just
  the imported history — would shift. A restore that reaches outside the data being restored
  is a bigger promise than this capability wants to make.

**Consequence to handle by hand**: `backup-restore`'s `## Purpose` lists "rules" among the
data the capability backs up and restores. A delta spec cannot modify a Purpose — the
OpenSpec `specs` instruction is explicit that the delta's Purpose is ignored for an existing
capability and the main spec must be edited directly. Task 6.4 does that edit at archive
time. It is the one place this change touches `openspec/specs/` outside the sync, and it is
called out rather than done silently.

## Risks / Trade-offs

**The audit's "the code is correct" judgement is inherited, not re-derived** → Each of the
six was verified against the cited implementation lines while writing the delta, and the
citations are in the proposal. Task 1.1 re-checks them before the deltas are reviewed,
because ratifying shipped behaviour into normative text is exactly where an inherited
judgement is expensive to get wrong.

**A batched sync rewrites six specs in one commit** → Accepted (Decision 1). Mitigated by
this change containing no production code, so the sync diff is reviewable on its own terms.

**`live-status` narrowing could be read as endorsing idle polling by default** → The
narrowed scenario explicitly conditions on Live monitor being disabled, and a new scenario
states the enabled case. Neither weakens the requirement that the service starts only from a
user-visible interaction.

**Extending the chain fixture to v26 may surface a real composed-path failure** → That is
the point of the test, but it would turn a documentation change into a migration bug fix
mid-flight. Task 5.4 handles it: if the chain fails, the failure is filed as its own issue
and the fixture lands with the failing hop documented rather than being quietly narrowed to
pass. Do not "fix" it by reducing the target version.

## Migration Plan

No schema or data migration. Deployment is the archive sync, which rewrites the six
capability specs from the deltas here.

Rollback is `git revert` of the sync commit; nothing on a device depends on this change.
