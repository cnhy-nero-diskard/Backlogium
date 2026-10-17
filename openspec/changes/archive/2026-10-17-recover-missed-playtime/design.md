## Context

See proposal.md for motivation and issue #181 for acceptance examples. Exploration was against local master at `8b481464`.

`PlaytimeObservationCommitter` already shares the periodic/targeted write path and reads quantity baselines inside the caller's Room transaction. `SessionDiffer` computes positive deltas and preserves the high-water mark after decreases. The defect is placement: new sessions inherit the profile's global `lastSyncAt`, and `attributeDailyProgress` credits their start date.

`Session.startAt` is mandatory. `SessionDao` supplies session-only totals to `GamificationUpdater`, Library, game detail, collections, backfill, Analytics and Personal Pace. `dailyProgressCorrections` also reconstructs from sessions alone. The backup format is v2 and does not export Steam baselines. Both owned/recently-played DTOs default a missing cumulative field to zero.

Cloud placement is implemented in this checkout; `attribute-playtime-from-presence` has three real-data tasks still open. Its accepted intervals absorb the entire delta proportionally, and absent/rejected evidence retains the old synthetic session. These fallback and partial-coverage contracts are superseded by this change. Reader identity fences, coverage rejection, account boundaries and explicitly confirmed historical transfers remain required.

## Goals / Non-Goals

**Goals:** one quantity authority, exact conservation, honest unknown-time activity, transaction-safe retry, bounded local reads, and compatibility across every quantity consumer. Ship non-cloud recovery without waiting for cloud validation or another proposal.

**Non-Goals:** infer invisible gameplay before Steam reports it; recover family-shared play from nonexistent owned totals; change XP formulas; distribute imported lifetime offsets over guessed dates; infer missing historical snapshots from approximate legacy session boundaries; add a cloud deployment, permanent foreground service or independent detector.

## Decisions

### 1. Keep quantity diffing and introduce one attribution plan

Keep `SessionDiffer`'s high-water quantity calculation; separate its authoritative per-game delta from its candidate session actions. The committer builds one pure `PlaytimeAttributionPlan` containing funded contributions, timed placements, unknown-time placements, pending date-unknown quantity and changed-date credits. Both observers commit that plan through the existing raw boundary.

```
successful typed Steam read
          |
Room transaction: account + per-game baseline -> existing diff
          |
one quantity authorization -> pure attribution plan
          |                     |
    evidence-informed       residual day allocations
          +---------------------+
          |
baseline + contribution ledger + placements + raw daily credit
          |
recoverable derived-state persistence with explicit provenance
```

Discard a candidate positive synthetic action when its minutes instead become buckets. Do not write the old session and subsequently add fallback credit. A stale open row is closed at its last trustworthy boundary; its existing credited minutes remain, but the new delta is not appended across an uncovered gap.

Alternative: a standalone nightly snapshot worker or a second gap detector. Rejected because it duplicates quantity detection, adds polling and weakens exactly-once persistence.

### 2. Add durable observation and contribution records, retaining session rows

Use additive Room tables rather than making every existing timestamp field nullable:

| Record | Required facts |
| --- | --- |
| Per-game recovery state | account/app ID, baseline epoch, quantity high-water mark, last usable observation instant/date/zone, validity/clock state |
| Daily Steam snapshot | account/app ID/local date/zone, latest successful raw absolute minutes and observation instant, trust/regression classification |
| Funded play contribution | stable ID, account/app ID/epoch, cumulative range `(fromMinutes, toMinutes]`, observation-window endpoints, authorized quantity, allocation revision and provenance |
| Timed contribution placement | contribution ID, session ID, funded share, frozen attribution date/zone, timing evidence identity/quality |
| Unknown-time placement | contribution ID/revision, date or explicit pending-date state, minutes, method, frozen zone/window, superseded/protected state |
| Replacement journal | operation/evidence identity, prior/current placement revision and affected dates, durable derived-finalization status |

Contribution IDs are derived from account, app, baseline epoch and cumulative range, not poll timestamp or an autoincrement session ID. A session can contain shares from several contributions; each share is linked separately. Authorized quantity is summed from contributions once. Timed placements explain where that quantity lives and are not additional XP inputs. Legacy quantity is each session's remaining unlinked minutes after subtracting its active funded timed shares, not an all-or-nothing exclusion of a mixed session row. Reject a mapping whose shares exceed that row's minutes.

A dated bucket is never open and has no clock boundaries. Store only positive shares. Keep the original observed device date/zone separate from the normalized allocation dates. Use wider arithmetic for cumulative subtraction, interval lengths and sums; reject negative/unrepresentable readings at ingestion rather than wrap.

Alternative: use midnight sessions with a provenance flag. Rejected because SQL, exports and session metrics would continue treating invented times as actual boundaries. Alternative: replace the whole session ledger. Rejected as unnecessary migration and shared-game risk.

### 3. Capture trustworthy snapshots on normal reads

Both owned-game and targeted recently-played responses must preserve field presence, distinguishing omitted minutes from explicit zero. Validate envelope, account/game, nonnegative bounded cumulative amount and observation metadata before baseline eligibility. Missing/private/failed data does not touch quantity state. A valid explicit empty library remains a successful library-level result but does not create zero snapshots for absent games.

A first reading seeds quantity only. A reading at or above the existing high-water amount may advance the usable observation boundary, including a zero delta; therefore delayed Steam publication cannot reveal the actual dates of play between earlier unchanged readings. Disclose that limit. A lower reading can be recorded as a regression diagnostic/daily raw snapshot but does not advance the usable quantity or placement boundary. Stale responses cannot rewind a newer boundary; an older response that exposes genuinely new high-water quantity remains recoverable with conservative timing instead of silently losing it.

Reuse `Game.lastPlaytime` as the existing detector baseline during transition and keep it transactionally consistent with the new recovery state. Read state in bulk. Account switches, resets and shared-to-owned conversion create/reestablish a baseline epoch using the existing account barrier; foreign in-flight work cannot commit.

### 4. Define timing usability and the date window explicitly

For a new recovery window use the recovery reading's device `ZoneId`, convert both observation instants into it, and freeze the resulting date keys. A same-date fallback has that one date. A consecutive-date fallback credits the recovery date. A multi-date fallback starts the date after the normalized previous observation and ends on the recovery date. Store original endpoint metadata for explanations and timezone diagnostics. Later timezone changes do not re-bucket existing allocations.

Preserve ordinary same-date short-gap diff behavior where the interval is at most 30 minutes, has no clock anomaly or known publication-delay conflict, and its existing session estimate remains usable. Across midnight or a stale open-session gap, preserve a timestamped session only with validated continuity/timing evidence; old session existence is not proof. The 30-minute bound reuses the existing cloud consultation threshold and adds no request. A delta exceeding ceil(elapsed milliseconds / 60000) + 1 is inconsistent with that estimate and uses fallback; this guard changes placement only and never clamps Steam quantity.

Targeted fetches retain their supplied known play instant. An end instant alone can establish a known activity date but cannot invent a start; it can produce date-known/clock-unknown placement when no usable interval exists. Preserve the existing valid session path when its local timing is available.

For clock rollback/inverted windows, commit quantity once as pending date-unknown play and retain the last usable timing anchor. It participates in cumulative XP, not dated quests/charts or habit training. A subsequent trustworthy reading with a valid window can place that pending contribution even with zero new delta, through corrective provenance. No future date or pre-baseline activity is invented to force placement.

Alternative: reinterpret all history using today's timezone. Rejected because daily progress uses stored date keys and would disagree with re-grouped activity after travel. Alternative: treat any prior poll or stale open session as valid time evidence. Rejected because that reproduces the multi-day defect.

### 5. Bound evidence placement and distribute only residual

Validate account, reader generation, existing gap tolerance and endpoint ordering before planning. Persist lightweight local observed timing facts/coverage alongside the existing live observer where needed; they supply placement, never owned-game quantity. An isolated current-presence sample or end event is not continuous interval evidence. Preserve coverage loss rather than bridge a process/network gap with the live timer.

1. Preserve already funded protected/usable placements for this contribution. Do not subtract unrelated sessions merely because they overlap its wall-clock window.
2. Normalize and deduplicate new usable spans for the same game, clipping them to the contribution window. Overlapping local/cloud spans form a union; validated local timing wins equivalent conflicts, then cloud timing, then estimates. Existing manually protected placement is never automatically replaced.
3. Let D be remaining authorized minutes and C the confirmed unused union span in milliseconds divided by 60000. Assign E = min(D, floor(C)) to timing evidence, proportionally by span, using largest-remainder rounding with chronological interval/evidence-ID tie breaks. This is a placement assumption, not a claim that presence duration equals Steam minutes. Fractional spans are combined before rounding; spans already funding another cumulative contribution cannot be reused.
4. Allocate residual R = D - E over eligible unknown dates with `q = R / N`, `r = R % N`, giving `q + 1` to the earliest r dates and q to the rest. Completely resolved dates are excluded; a merely partly covered date remains eligible. Evidence absence alone does not establish a verified zero day.

| Case | Result |
| --- | --- |
| Tue 500 -> Wed 570, no timing | Wed 70, single-day unknown time |
| Mon 500 -> Fri 605, no timing | Tue/Fri window: 27/26/26/26, multi-day estimate |
| Same range, 35 new usable minutes | 35 evidence-informed + 70 fallback; 105 total |
| Prior commit 500 -> 535, then 605 | 70 new quantity; do not subtract earlier 35 again |
| Two minutes over four eligible dates | First two dates get one each; no zero activity rows |

If every date is claimed resolved but residual remains, evidence is inconsistent: record a diagnostic, retain valid existing placements, and fall back across unresolved portions of the permitted window rather than drop quantity. Reject a full-resolution claim inconsistent with Steam before using it to exclude dates.

Alternative: distribute the entire delta over any surviving cloud interval. Rejected because a tiny observed fragment would absorb days of invisible play. Alternative: count raw interval minutes independently. Rejected because Steam alone authorizes quantity.

### 6. Reconcile by contribution revision, including existing candidate minutes

Delayed evidence re-plans only unlocked estimated shares of an existing funded contribution. Atomically supersede those placements, replace their dated/session representation, update raw daily differences for old and new dates, and journal the new revision. Stable evidence identity and current revision make repeats no-ops. Maintain `authorized minutes = active timed shares + active unknown-time shares + pending shares` before and after every operation.

For an already written diff-synthesized session with a provable contribution mapping, move only that funded share out of the session: reduce its minutes, preserve other funded or legacy shares, close at retained evidence boundaries if needed, and retire an empty row only when no independent state references it. Replacement buckets/sessions inherit the same contribution. Never clone the full original session plus buckets. Approximate legacy timestamps alone do not prove a contribution/window, so no automatic wholesale legacy sweep is added.

Routine late evidence can upgrade estimates after the old Steam baseline has been retired because the contribution retains its window and authorized quantity. Do not require the cloud pending-evidence cursor to retain raw events forever. Work within existing acquisition horizons; outside them an estimate remains an estimate until valid evidence is actually obtained. Cloud-disabled paths require no acquisition.

Existing historical re-filing/reversal must detect contribution revisions and preserve independent later upgrades. Keep separate pre-data imported-balance transfers and their explicit confirmation/journals; cumulative recovery never consumes a manual estimate or lifetime offset. Older operations without a safe mapping cannot rewrite recovery-backed placements.

Alternative: add the new sessions and subtract daily totals afterward. Rejected because readers/recompute could see duplicated quantity and retries cannot prove which copy owns XP.

### 7. Generalize read models and corrections once

Expose domain activity projections through the repository: timestamped session, dated unknown-time activity and pending date-unknown quantity. Keep storage entities/DAO projections out of product ViewModels. Provide shared per-game credited totals and dated totals; both timed and unknown-time views consume these. Audit all direct `SessionDao` quantity readers, including XP, game detail, Library badges, collection metrics, smart collections, gap-plan inputs, frozen lifetime backfill subtraction, backup computed rollups and cloud historical applications.

History groups by canonical attributed date and can expand allocation-only game/day groups. Analytics minute totals, comparison periods and top games include buckets, while session count/length and time-of-day use only actual session projections. First/last-play recency must not convert an allocation date or observation timestamp into an exact gameplay instant. Collection session counts exclude buckets. Mixed unknown-time figures use their stored provenance rather than present ownership/cloud settings to infer source.

Generalize `dailyProgressCorrections` to canonical dated activity and target affected dates. Apply contribution deltas, not an indiscriminate full-history rewrite: hiding and Focus edits have existing historical semantics and must not silently re-evaluate unrelated days. Record credit-time Focus/visibility eligibility for new contribution shares so moving them preserves their original daily eligibility. Legacy rows keep their existing correction policy.

Use the shared raw coordinator and Room transaction for ledger/baseline/day writes, then the existing derived-state coordinator and WAL protocol. Derived completion remains retryable after process death. Large windows retain a compact quotient/remainder recipe and coverage exclusions at the contribution level; query/expand bounded date slices, avoid zero rows, and use a journaled resumable derived materialization for long intervals. The authoritative recipe and logical raw daily credit are committed with the baseline, so deferred materialization never strands minutes or exposes a partial quantity credit. Normal windows may materialize day rows inline; every reader must combine pending recipe-derived credit with materialized dates exactly once.

Alternative: teach each screen to add fallback minutes independently. Rejected because XP, exports and correction paths would still disagree or erase the buckets.

### 8. Keep progression provenance correct in mixed operations

New current-date single-day unknown-time quantity can follow `SYNC` earned provenance. Multi-day recovery, past-day recovery, pending-date credit, evidence upgrades and representation-only corrections use `RETROACTIVE_PLAY`. Every placement-only change leaves per-game quantity and XP unchanged under unchanged rules.

A mixed commit needs a durable derived-finalization description identifying corrective versus fresh earned contribution IDs and all other fresh earned inputs under their existing policies, including newly refreshed achievement XP. Under the existing derived-write lock, compute/persist the corrective baseline excluding this operation's fresh earned inputs, then compute/persist the final state including them with `SYNC`. The journal retains the input identities/versions needed to resume those same stages; a correction must not quietly absorb a new achievement's level transition. This uses the same engine and persist/recovery protocol; it does not introduce a second XP author. Persist stage completion in the journal so a retry cannot earn the same transition again. Preserve explicitly owed events and acknowledgement high-water marks at both stages. Tests must cover process termination between each raw/derived/mark write; if staging cannot preserve those invariants, implementation must resolve it before shipping rather than suppress all events in a mixed sync.

Alternative: mark the entire mixed sync retroactive. Rejected because it would swallow legitimate fresh events. Alternative: mark everything earned. Rejected because historical placement would create duplicate celebrations.

### 9. Protect Personal Pace from invented habits

The user selected exclusion of multi-day estimates from habit training and confidence. Pass explicit date eligibility into the pure engine; do not merely filter bucket rows and then densify those dates into zeros. Dates touched by unresolved multi-day estimated windows are unknown for training, including mixed dates whose total is not fully resolved. Independently fully resolved dates remain eligible. Single-day date-known/clock-unknown activity can train completed-date totals. Keep the 56-completed-date horizon, current/open-session exclusions and normative 14-covered/six-active confidence floors. Estimated dates cannot satisfy either floor or extend the covered-date count indirectly. Late complete timing can restore eligibility on the next derivation.

Alternative: include equal-spread shares in weekday habits but lower confidence. Rejected by the user's selected behavior; it could fabricate a regular weekday pattern.

### 10. Use backup v3 and contribution-aware merge

v3 adds mandatory explicit recovery sections and preserves v2 cloud contribution facts on sessions. Decode v1/v2 as legacy; do not accept v3 sections under their older version markers. Preserve missing-version legacy rules. Preflight all references, cumulative ranges, quantities, dates/zones, revision links, limits and duplicate conflicts before writes. Export observations and contribution/placement graphs in the same database snapshot as other state; compute XP/rollups from canonical credited quantities.

Normalize overlapping cumulative ranges within the same account/app/baseline epoch into disjoint funded segments when a v3 import overlaps differently partitioned local contributions. Select one placement per segment: protected local/manual state, then validated timing, then the newer consistent estimate revision. Preserve replacement aliases/tombstones so older backups cannot resurrect superseded sessions. Reject inconsistent/ambiguous funding graphs instead of summing them. Unlinked legacy activity that may duplicate a recovery-backed replacement is not assumed new credit; require a safe mapping or retain local state with a reported unresolved merge conflict.

Same-account baseline import preserves a newer coherent live baseline; foreign baselines remain foreign and never become active detector state even when their history is explicitly merged under the existing warning policy. Legacy restores without coherent recovery state baseline on the next read. Reset and same-account coherent restore preserve baseline epoch semantics so replay is not available again. Do not export credentials.

Alternative: extend v2 with optional estimate flags. Rejected because an older importer would silently lose quantity/uncertainty and a missing field could erase a known recovery fact.

## Risks / Trade-offs

- Equal spreading can satisfy quests/streaks on unobserved dates -> required policy in #181; retain the ! explanation and exclude those dates from habit confidence.
- Steam can publish late despite successful intervening reads -> only observed quantity is authoritative; disclose that fallback dates are derived rather than actual session dates.
- Confirmed duration and cumulative quantity can disagree -> bound evidence placement and retain an explicitly estimated residual; never label interval duration as a separately earned quantity.
- Very long gaps can expand into many dates -> compact exact recipes, bounded queries, resumable materialization and a ten-year-gap fixture; no dropping minutes through a maximum-day cap.
- Legacy sessions/backups lack funding provenance -> preserve unprovable history and use fresh baselines, not a speculative migration sweep.
- Existing cloud historical journals can overlap upgrades -> revision-aware reconciliation/reversal regression tests before integration ships.
- Mixed raw/derived crashes can consume events -> staged operation journals plus the existing WAL/locks; assert owed-event survival and exactly-once fresh event delivery.

## Migration Plan

1. Start from the implementation branch's actual Room version (currently 42); add the next additive migration, exported schema and per-hop/composed-chain tests. No destructive migration or convenience data reset.
2. Preserve all legacy session/progress/manual/backfill/profile values. Seed recovery state from an existing matching-account per-game high-water baseline and trustworthy `lastSyncedAt` where available, retaining that historical zone as unknown rather than claiming it was captured. Normalize an ensuing recovery window in its recovery zone. Missing/untrustworthy timestamps keep quantity intact and produce pending-date credit until a valid new observation window exists. A genuine first baseline never imports lifetime play.
3. Add v3 backup support and reset/account-conversion integration with the schema. A migrated install still reads v1/v2 backups; old apps must reject v3 instead of pretending it is v2.
4. Route normal non-cloud sync and all shared quantity consumers through the canonical attribution plan first. This is the P0 shipping path; no cloud phase completion gate.
5. Integrate partial and delayed validated timing through existing cloud/local evidence acquisition, without another detector. Replace only fallback-specific regression assertions in the older cloud change; keep its authority, identity and historical transfer protections.
6. Update localized UI explanations, accessible inspection fixtures and matching Roborazzi baselines; validate deterministic fixtures before real-device acceptance.

Rollback must preserve the new durable recovery tables and v3 data. Disabling automatic fallback/upgrade can stop new placement while canonical readers continue honoring existing credited quantities. Do not downgrade the database, delete buckets or export a v2 file omitting them; a code rollback requiring the old schema needs a separately reviewed lossless compatibility migration.
