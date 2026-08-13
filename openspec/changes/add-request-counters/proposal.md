## Why

The sync diagnostics view answers "what did *that run* cost" but never "what does Steam
*actually cost me*": per-run request counts can't be summed across days without doing it by hand,
and the 200-run retention window (~2 days of the 15-minute periodic sync) makes monthly and
yearly figures unreachable in principle — the rows are pruned before they can ever be counted.

## What Changes

- A new `request_totals` rollup table (Room v14 → v15) accumulating request counts in epoch-hour
  buckets keyed by (hour, API route, status), written at each sync run's finish — before raw run
  rows are pruned — so counters survive the existing 200-run retention.
- Each rollup row classifies requests as successful (2xx) or unsuccessful at write time and keeps
  the exact status code (`200`, `403`, `429`, `network` for transport failures) so long-term data
  retains its diagnostic value.
- The rollup itself is bounded: buckets older than 400 days are pruned alongside run pruning.
- `MIGRATION_14_15` backfills the rollup from the still-retained `sync_runs` and
  `request_breakdowns` rows, so existing installs start with ~2 days of real history instead of
  zero.
- The diagnostics screen gains a "Request counters" section: rolling 24h / 30d / 365d totals split
  into successful and failed counts, plus a per-endpoint breakdown table with a period selector.
  Endpoints are aggregated at route level (host + path), which also makes the rollup structurally
  credential-free — routes never contain query parameters.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-diagnostics`: new requirement for rolling request-counter recording — the rollup, its
  write path, structural redaction, bounded retention, and migration backfill.
- `app-settings`: the Diagnostics section requirement gains scenarios for the counters UI —
  rolling windows, successful/unsuccessful split, endpoint breakdown, offline rendering, and the
  empty state.

## Impact

- `BacklogiumDatabase`: version 14 → 15, hand-written `MIGRATION_14_15` following the established
  pattern, including the backfill of the new table from retained diagnostic rows.
- New `RequestTotal` entity and `DiagnosticsDao` methods: an incrementing upsert (SQLite
  `ON CONFLICT DO UPDATE`), windowed aggregation queries exposed as Flows, and rollup pruning.
- `SyncRunRecorder.finish()`: derives route + hour bucket + success flag from each run's metrics
  and upserts rollup rows; adds rollup pruning next to `pruneRuns`.
- `DiagnosticsScreen` / `DiagnosticsViewModel`: new counters section above "Recent sync runs",
  fed by three windowed Flows plus a per-period endpoint breakdown.
- Tests: DAO increment-upsert and window aggregation, recorder rollup writes, migration backfill,
  success classification, and UI state for the counters section.
