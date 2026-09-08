## Why

**Branch: `fix/auditfix-collections-editor`**

Three audit findings in the collections editor and its ordering. They share two files
(`CollectionViewModel.kt`, `CollectionScreen.kt`) plus `CollectionRepository`, so they are
one reviewer's context rather than three.

**#124 — the buffered save is not atomic, so a partial edit can commit.** The editor's whole
model is that add/remove/reorder/done changes are buffered and Cancel discards them; `save()`
documents itself as "Persist everything atomically". It is not atomic. `save()`
(`:429-479`) performs a sequence of independent repository calls: `create`/`updateDetails`,
then `addMember` per new member, then `reorderMembers`, then `removeMember` per departing
member, then `setMemberDone` per member. Each commits on its own. Process death or a Room
failure part-way leaves details and new members durable while removals and done marks never
land — so reopening shows a fragment of the user's edit. On an exception `_saving` is also
never reset, so the screen stays stuck in its saving state until it is recreated.

The buffered model makes this worse than it would otherwise be: Cancel is all-or-nothing in
memory, so the user has been told, correctly, that this is a transaction. Save then isn't
one. Note the individual repository operations `reorderMembers` and `reorderCollections`
already advertise "atomically" in their KDoc — the atomicity exists per-call and is missing
exactly where the user-visible unit of work needs it.

**#123 — blank collection names can be saved.** `app-ui` requires the save action to be
unusable when the name is blank. `CollectionScreen.kt:1429-1448` only *recolors* the FAB —
`containerColor`/`contentColor` switch on `state.name.isBlank()` while `onClick =
actions.onSave` stays live. `CollectionViewModel.save()` checks only `_saving`, never the
name, and passes `_name.value` straight to `create()`/`updateDetails()`. Room persists a
nameless collection and the screen navigates away as though it worked.

**#110 — the "Deadline" sort produces alphabetical order.** `CollectionSort.DAYS_REMAINING`
documents itself as "Days remaining to the deadline, fewest first" and
`CollectionScreen.kt:1716` presents it as **"Deadline"**. `CollectionSummary.order()`
(`:107-113`) groups it with `MANUAL_SEQUENCE` and sorts by lowercased name. Since
`order()` already returns early for `ORDERED_QUEUE` at `:98`, that shared branch is
unreachable for manual sequence — `DAYS_REMAINING` appears to have been swept into it as the
remaining unhandled case, and the alphabetical fallback is the accident.

Underneath is a modelling problem the audit identified and which no amount of sorting code
fixes: **`targetDate` is stored on the collection, not per member.** Every member of one
deadline collection therefore has the same days-remaining value. The specified ordering
cannot distinguish rows, so there is nothing to implement faithfully.

## What Changes

- **`save()` becomes one transaction.** The collection row plus the reconciled membership
  sequence and done marks commit together through a single repository/DAO transaction, and the
  busy flag is released on failure. Cancel and Save become symmetric, as the buffered model
  promises.
- **A blank name is refused in two places.** The FAB is genuinely disabled, and the ViewModel
  enforces the invariant independently, so a non-Compose caller or a test cannot bypass
  presentation state.
- **"Deadline" stops masquerading as a metric.** `DAYS_REMAINING` is removed as a member
  sort and the deadline mode's default becomes a metric that can actually order rows.
  `design.md` Decision 3 covers the choice and the persisted-value fallback.
- **BREAKING (behavioural, not schema)**: a deadline-goal collection currently storing
  `DAYS_REMAINING` will fall back to the new default. Its members are already in
  alphabetical order today, so the *observable* change is which sort the picker reports, not
  a reshuffle the user did not ask for. `collectionSortOrNull` already tolerates an
  unrecognised stored name by returning null, so no migration is required — this is the
  mechanism `CollectionSort`'s KDoc describes for exactly this case.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `custom-collections`: saving an editor's buffered changes is atomic; the available member
  sorts and the deadline mode's default no longer include a metric the data model cannot
  support
- `app-ui`: the collection save action is unusable with a blank name, and the invariant is
  enforced below the presentation layer

## Impact

| Path | Change |
|---|---|
| `ui/collections/CollectionViewModel.kt` | `save()` single transaction; name guard; busy flag released on failure (`:424-479`) |
| `ui/collections/CollectionScreen.kt` | FAB disabled on blank name (`:1429-1448`); sort picker loses "Deadline" (`:1701`, `:1716`) |
| `data/repo/CollectionRepository.kt` | transactional save surface (`:65-124`) |
| `data/local/dao/CollectionDao.kt` | transaction spanning row + membership reconciliation |
| `domain/CollectionSort.kt` | `DAYS_REMAINING` removed; `defaultSort()` for deadline mode |
| `domain/CollectionSummary.kt` | `order()` loses the alphabetical fallback branch (`:107-113`) |

**Depends on `auditfix-spec-truth`** only for sequencing: this change adds to `app-ui`, which
`auditfix-ui-async-identity` and `auditfix-settings-boundary` also touch. No code dependency
on any other change; nothing here shares a file with the other six.

**Can run in parallel** with `auditfix-ui-async-identity` and `auditfix-settings-boundary` —
the three UI changes are file-disjoint. Expect a textual merge in `app-ui/spec.md` at sync
time; requirement names are distinct.

**Known adjacent breach, deliberately out of scope**: `CLAUDE.md` records that
`ui/home/HomeViewModel.kt` imports the `Collection` and `CollectionMember` entities because
`CollectionRepository` exposes them across its whole public API, and that the fix is to map at
the repository boundary. This change adds a transactional method to that same repository. It
does **not** attempt the boundary fix — that is a broad refactor of the collections surface,
deferred for the reason `CLAUDE.md` gives. The new method should not widen the breach: it
takes and returns plain values, not entities.

**Not addressed here**: giving deadline collections per-member target dates, which would make
a real days-remaining ordering possible. That is a feature, not an audit fix — see
`design.md` Decision 3.
