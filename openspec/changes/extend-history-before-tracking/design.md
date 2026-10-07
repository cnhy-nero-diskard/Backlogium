## Context

See proposal.md. HistoryGrouping.groupHistory currently unions session and progress dates only. historyHasOlderRecords already accounts for progress-only dates as well as sessions, but not the earliest dated unlock. HistoryViewModel uses a 30-calendar-day initial window and 30-day load steps, with bounded session/unlock queries.

GamificationUpdater currently uses each non-hidden game's backfillMinutes + manualSharedMinutes + tracked session minutes, sums in Long, then clamps the per-game minutes to Int.MAX_VALUE. It uses current safe-ceiling rules, current HLTB completionist lengths, and active unlocked achievements' frozen snapshot percentages. Orphan contributing rows are not automatically excluded simply because a Library row is absent. Retired achievements and hidden-game contributions are excluded. Daily progress is authoritative for quest state but is not an additional XP input.

## Goals / Non-Goals

**Goals:** represent all dated evidence honestly; reconcile date-attributed plus undated XP to the same engine inputs; preserve bounded UI and current ledger semantics.

**Non-Goals:** estimating missing minutes, reconstructing acquisition/install boundaries, rewriting stored history/quests/streaks, persisting daily XP, or claiming the date on which XP was historically awarded.

## Decisions

### Evidence is a union, not a tracking-era classifier

Union local dates from sessions, recorded daily progress, and valid dated unlocks after the existing hidden/retired policy. A day with only unlocks is EvidenceOnly regardless of whether it lies before or inside the tracking era. It has unknown total/Focus minutes and unavailable quest state; an actual recorded zero remains a distinct recorded value.

Use explicit day-state/value availability in the domain/UI model rather than renderer-only checks. A progress-only day uses its actual recorded totals/quest result with no invented game/session expansion; a session day derives displayed totals from its listed sessions and keeps stored quest outcomes authoritative. Sessions without a progress row do not invent a quest outcome. Achievement icons retain their cap/overflow behavior; unknown game names use the existing fallback.

No synthetic daily_progress/session rows are written, and evidence-only dates never become inputs to streak or Personal Pace training. The minimum date across visible sessions, recorded progress, and dated non-retired unlocks is the older-content floor. Preserve calendar paging, bounds, expansion keys, midnight behavior, cloud detail, and reveal-to-session actions. Empty windows may be paged through; stop only at the floor, not at an empty page.

### Replay the complete contributing ledger

Build one repository/domain snapshot of the same inputs as GamificationUpdater (plus date attribution). Keep Room details behind the repository. Use the existing gameXp/achievementXp functions and safe-ceiling config, with Long accumulation and the same Int input clamp. Do not use displayed Steam lifetime minutes, daily_progress counters, or the current viewport as a substitute for the complete contributing ledger.

For game g let U_g = backfill + manual/shared estimate + any genuinely undated engine-bearing minutes.
Let C_g(d) be dated session minutes cumulatively through local start date d.
Apply the exact engine clamp to each cumulative input:

```
P_g(d) = gameXp(clamp(U_g + C_g(d)), currentHLTB_g, safeRules)
dailyPlayXp_g(d) = P_g(d) - P_g(previous date)
undatedPlayXp_g = gameXp(clamp(U_g), currentHLTB_g, safeRules)
```

The dated session partition must sum to the tracked input used by the engine; any explicit genuinely undated tracked remainder is identified as such, never guessed from DailyProgress. Clamp before each engine call, not each day's increment; this preserves saturation and telescoping. Use all prior cumulative minutes even when only the last 30 days are rendered. Cross-midnight sessions remain entirely on their current local start date. Imported minutes already consumed/re-filed by existing workflows must not be counted again as a new independent offset.

Achievement XP partitions the same active unlocked rows by valid unlockedAt; null/zero dates go to undated. Frozen non-null snapshotPercent drives credit; missing snapshots contribute the engine's zero, not a fabricated percentage. Date changes, retirement, hidden-state changes, manual edits, imports/resets, rule edits, and HLTB changes rebuild the read projection. No historical unlock-time rarity is reconstructed.

The all-date marginal series plus undated terms must equal the current engine total over the same snapshot, including contributors without a current Library row. Compare to persisted totalXp only when it represents those same inputs/config; if a sync/recompute is pending, label the presentation updating rather than inventing a balancing adjustment. Do not recompute/persist simply to read the UI.

### One projection, bounded presentation

A shared attribution repository supplies Home and History. Read per-game per-date aggregates plus all-time starting inputs in one coherent snapshot; retain bounded detail queries for expansion. Cache the derived series per contributing-ledger/config/HLTB/visibility revision and perform aggregation off the main thread. Changing viewport/expansion only slices the cached series rather than replaying it. A large-history acceptance case must verify correctness of the first visible day and reasonable loading behavior.

Dates outside the shown window remain part of reconciliation. Undated credit is an explicit separate value with named sources, never assigned to first sync/today/oldest unlock. Home shows today's contribution and a reachable accounting explanation; History shows each date's contribution and the undated remainder explanation.

### The wording states the accounting model

Use “XP attributed under current rules”/“XP attributed today”; avoid “XP earned on this date” or “historical awarded XP.” Explain that current taper/rules and sync-observed rarity snapshots allocate today's total to known activity dates. An evidence-only day can have achievement XP beside “playtime unknown.” A rule change can change displayed old values without changing historical quests.

Coordinate with disclose-data-provenance when it lands, but keep this draft independently applicable using truthful attribution copy. Do not duplicate the separate #169 Home breakdown/banner redesign.

## Risks / Trade-offs

- Sparse old dates require multiple calendar pages → preserve predictable bounded paging and do not stop on an empty page.
- Full-history replay can be expensive → aggregate once per revision, off-main caching, bounded detail, large-ledger verification.
- Current-rule attribution looks like historic awarded XP → explicit label and reachable undated/snapshot explanation.
- Stored totals can lag fresh rows → coherent input snapshots and updating state, not silent residual fudging.

## Migration Plan

No schema migration or stored XP changes. Add minimum-unlock/read queries and domain projection, then day states and presentation. Validate existing backup/re-file/import behavior through read-projection tests before release.
