## Context

See proposal.md for motivation. On master `d46a711c`, Home reads `DayProgress.minutesPlayed` through `homeDayFields`; History instead sums visible sessions and uses stored progress for quest outcome. `SessionRepository` filters hidden games and exposes date-bounded reads. Whole sessions belong to their local start date, including overnight sessions. Hiding can leave earned daily credit intact while making sessions invisible. Manual/imported lifetime credit has no guaranteed date.

The projection spans repository/domain and UI state, so a design is required. Draft PR #176 owns general provenance labels; draft PR #177 owns unlock-only days and daily XP. Neither is implemented on this branch.

## Goals / Non-Goals

**Goals:** give Home and later History one reusable daily evidence contract; render coherent snapshots; disclose discrepancies without changing earned progress.

**Non-Goals:** recomputing quests, recovering missing sessions, changing hidden-game policy, adding daily XP, reconstructing past Focus membership, or allocating Steam lifetime minutes to dates.

## Decisions

### One domain projection with two totals

Add a repository-backed daily read model containing date/account identity, visible game/session totals, recorded subtotal `V`, optional authoritative credited total `Q`, optional stored quest outcome, and reconciliation state. Build it from committed local inputs in a read transaction or an equivalent version-consistent snapshot. Separate reactive query emissions cannot by themselves prove coherence; invalidate and reload the snapshot after relevant writes, and retain the last coherent result while loading.

When `Q` exists, describe `R = Q - V` as a signed comparison. For `R > 0`, say some credited activity is unavailable in this visible breakdown; for `R < 0`, state that visible recorded minutes exceed credited minutes. Display the magnitude and both scopes. Never create a synthetic played-minutes row or clip/reweight game totals. A known cause may be named only if existing evidence proves it. Without `Q`, credit and recorded quest outcome are unavailable. The ordinary empty day still shows no recorded activity, and this read never evaluates a missing quest.

Alternative: sum sessions directly in Home and overwrite its total. Rejected because that changes the meaning of an authoritative earned quest and disagrees after visibility changes. Treating every residual as hidden/imported time is also unsupported.

### Bound the evidence, preserve the ledger's semantics

Request sessions for the selected complete local day with an exclusive next-midnight upper bound, using the same zone provider as attribution. Sum recorded `minutes`, including open rows; do not extend them from a UI ticker. Join visible identity with a fallback `App <id>` when unavailable. Sort by descending minutes, then localized presentation name, then stable app ID. Totals use wider accumulation and checked presentation conversion so large ledgers cannot silently wrap.

Read hidden exclusions and session/progress changes in the same snapshot. Do not expose hidden titles, counts, or detail links through reconciliation. A fallback identity can be inspected only when an eligible current detail target exists. Lifetime import/manual values are not added to dated rows.

Alternative: read all history into Home. Rejected because a single-day surface needs only bounded detail. `2c` will reuse this model within History's existing loaded bounds rather than issue a query per rendered row.

### Compact disclosure and stable navigation

Add an accessible expand/collapse action to the quest card, initially collapsed. Expansion state is keyed by active account and local date, survives normal navigation to detail and back, and resets on either key changing. Resolve detail eligibility at action time and use the shell's existing detail navigation. Resource-backed copy distinguishes recorded total, credited total, and unavailable allocation; essential reconciliation remains inline, with longer measurement explanation reachable as needed.

Coherent date and account keys prevent yesterday's or another account's data being displayed under today's label. During an account transition clear the old snapshot; during a same-account recomputation keep a marked prior snapshot without relabelling it.

Alternative: an always-expanded card or a separate screen. Rejected for this scope because the requested action should retain Home's existing compact collapsed hierarchy.

## Risks / Trade-offs

- Existing source ambiguity → conservative copy and explicit unknown states; share vocabulary with PR #176 when available.
- Stored credit differs from visible sessions → show the comparison, preserve authority, and test both difference directions.
- Home and PR #177 both gain content → coordinate placement and expansion semantics; this card's game breakdown does not implement XP attribution.
- Snapshot reads could repeat excessively → coalesce relevant invalidations and keep queries date-bounded/off the main thread.
- Late navigation or account changes → verify target visibility and reject results from an obsolete account/date key.

## Migration Plan

Implement the read model first, then Home state and disclosure. No database migration is expected; schema/query changes must be proposed explicitly if implementation discovers otherwise. Rollback removes the read projection and disclosure without changing persisted activity. Validate local/domain behavior, compile and lint, then inspect affected device renders and accessibility. Implement `1c` before `2c`; the Analytics path (`3c` then `4c`) can proceed independently.
