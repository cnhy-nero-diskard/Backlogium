# Design — rolling request counters in sync diagnostics

## Context

The diagnostics pipeline today records per-run aggregates and prunes them quickly:

```
SteamApi request ──► RedactingTimingInterceptor ──► RunScope.recordRequest(endpoint, status, ms)
                                                          │ in-memory per run
                                                          ▼
                    SyncRunRecorder.finish() ──► sync_runs + request_breakdowns
                                                          │
                                                          └─► pruneRuns(200)  ← oldest runs deleted
```

- The periodic sync runs every **15 minutes** (`SyncScheduler.kt:116`), so ~96 runs/day; with
  `RETAINED_RUNS = 200` the raw table holds **~2 days** of history. Monthly and yearly request
  figures are unreachable in principle: the rows are pruned long before they could be counted.
- The recorded `endpoint` is the full redacted URL (only `key`/`steamids` stripped), so per-run
  breakdown keys include `appid` variants — hundreds of distinct identifiers per sweep.
- Room is at `version = 18` with hand-written migrations and no `autoMigration`
  (`BacklogiumDatabase.kt:48`), following the established per-version SQL pattern.
- The ask, settled during exploration: counters for the **last 24 hours / 30 days / 365 days**
  (rolling), split into **successful vs unsuccessful**, with a **per-API-route** breakdown, and
  migration-time **backfill** from whatever raw history is still retained.

## Goals / Non-Goals

**Goals:**

- Report request counts for rolling 24h / 30d / 365d windows, split successful/unsuccessful, and
  broken down per API route — served entirely from local storage, offline.
- Make the counters survive the existing 200-run pruning, with storage bounded by its own
  retention.
- Preserve exact status codes in long-term data so future questions ("how many 429s this month")
  remain answerable.
- Backfill counters from retained raw records on upgrade so existing installs don't start at zero.

**Non-Goals:**

- Requests made outside a sync run scope — presence polls and player-count probes are untagged
  today and never reach a `RunScope`; they remain uncounted.
- Per-appid granularity in the counters (per-run detail keeps that for its ~2-day window).
- Charts, sparklines, or export of counters.
- Changing the retention of `sync_runs` / `presence_decisions` or their UI sections.

## Decisions

### D1: Hour-bucket rollup table — `request_totals`

```
┌────────────────────────────────────────────────────────────┐
│  request_totals                                  (v18→v19) │
│  hourStart  INTEGER   epoch-hour of the run's start        │
│  route      TEXT      "ISteamUserStats/GetPlayerAchievements/v1/" │
│  status     TEXT      "200" | "403" | "429" | "network"    │
│  ok         INTEGER   1 when status is 2xx, else 0         │
│  count      INTEGER                                         │
│  PK (hourStart, route, status)                              │
└────────────────────────────────────────────────────────────┘
```

Rolling windows require sub-day granularity: a calendar-day bucket cannot be split at
"now minus 24 hours", so "last 24h" computed from day buckets would be wrong by up to a day.
Hour buckets serve all three windows uniformly (`WHERE hourStart >= :cutoff`).

The bucket key is the **epoch hour** (`startedAt - startedAt.mod(3_600_000)`), deliberately
timezone-free: rolling windows are absolute-time measurements, so no zone, DST, or local-calendar
logic appears anywhere in the write or query path.

*Alternatives considered:*

| Option | Verdict |
|---|---|
| Calendar-day buckets + calendar periods | Rejected — user chose rolling windows; also requires local-timezone boundary math |
| Time-based retention of raw runs + read-time aggregation | Rejected — ~35k runs + ~350k breakdown rows per year, aggregation over them on every UI open, and it silently changes the existing bounded-retention contract for raw records |
| Per-run-level rollup rows | Rejected — just raw retention again, no aggregation |

Volume: ~8 routes × ~3 statuses × 24 hours ≈ worst case ~500 rows/day, realistically 100–200 —
well under 100k rows/year before pruning.

### D2: Aggregation at route level, not full identifier

A "route" is the request identifier's path — host + encoded path, no query parameters
(`HttpUrl.encodedPath` of the redacted URL). There are ~8 distinct Steam routes; the full
identifiers are per-appid (hundreds per sweep).

- The user asked for a breakdown "of what API endpoint" — route level answers that directly and
  legibly.
- Storage stays trivial and the endpoint table stays readable (8 rows, not hundreds).
- **Structural redaction**: routes never contain query parameters, so the rollup physically cannot
  hold the API key, a steamid, or any parameter value. (Side observation: the singular `steamid`
  query parameter — used by `getOwnedGames`/`getPlayerAchievements` — is *not* in
  `secretParameters` today, so full-identifier aggregation would have persisted it; route-level
  aggregation makes the question moot for the rollup.)

Route derivation happens at `finish()` from the already-redacted stored identifier string
(`toHttpUrl().encodedPath`); the per-run breakdown continues to store full identifiers as today.

### D3: Keep the exact status, classify success once at write time

`status` is stored as `TEXT NOT NULL` with the sentinel `"network"` for transport failures
(SQLite primary-key columns cannot be NULL, so the nullable `Int?` of the raw records cannot be
the rollup key directly). A companion `ok` flag (1 = 2xx) is classified **once, at write time**.

Why keep exact statuses instead of pre-merging into a failed bucket: the write path is identical
either way, the storage delta is negligible, and merging would irrecoverably destroy the
distinction between rate-limiting (429), a bad key (403), a server error (5xx), and the phone
being offline — precisely the distinctions this diagnostics surface exists to surface. The UI
still displays only the split the user asked for (`ok · failed`); the `ok` flag keeps that display
query a trivial `SUM` filter rather than per-emission parsing of status strings.

*Alternative:* two count columns (`okCount`, `failedCount`) — simpler queries, but status
information is gone forever once written.

### D4: Incrementing upsert, because Room's `@Upsert` replaces

Room's `@Upsert` performs `INSERT OR REPLACE`, which would overwrite rather than accumulate. The
DAO therefore carries a raw SQL incrementing upsert:

```sql
INSERT INTO request_totals (hourStart, route, status, ok, count)
VALUES (:hourStart, :route, :status, :ok, :count)
ON CONFLICT(hourStart, route, status) DO UPDATE SET count = count + excluded.count
```

executed once per metric row inside a transaction in `finish()`. Concurrency is safe: two workers
can finish simultaneously (sync vs reconciliation), but SQLite serializes writers and the
`ON CONFLICT ... DO UPDATE` statement is atomic, so two runs landing in the same bucket both
increment it.

### D5: `MIGRATION_18_19` with Kotlin-side backfill

The migration creates the table and backfills it from the still-retained raw records:

```
sync_runs (startedAt) × request_breakdowns (endpoint, status, requestCount)
        │  hourStart = startedAt floored to hour
        │  route     = path substring of the stored redacted URL
        │  status    = breakdown.status?.toString() ?: "network"
        │  ok        = status in 200..299
        ▼
request_totals  (accumulated, then inserted)
```

Backfill runs in Kotlin inside the migration (a `Cursor` over the two tables), not in SQL:
extracting the path from the stored URL string is string surgery that is far more legible in code,
and a run crossing an hour boundary simply attributes its requests to its start hour — a ~15
minute run makes the distortion negligible. The parser is tolerant: rows whose URL shape it cannot
parse are dropped rather than failing the migration.

Only ~2 days of raw history is ever backfillable; the rest was pruned long ago. Accepted: the
counters start with real recent history and accrue from there.

### D6: The rollup has its own bounded retention

`finish()` prunes buckets older than 400 days alongside `pruneRuns(200)`:

```sql
DELETE FROM request_totals WHERE hourStart < :cutoff   -- cutoff = now - 400 days
```

The composite primary key already provides the `hourStart`-prefixed index this scan needs, and a
single DELETE per run finish is trivially cheap. This extends the existing "bounded diagnostic
retention" contract to the new table without touching the raw tables' count-based pruning.

### D7: Failure isolation

The rollup write and its pruning are wrapped in `runCatching`, mirroring the existing pruning
behavior: a counter failure must never fail the sync run it accompanies.

### D8: UI shape — three summary rows, one period-selected table

```
Request counters
   24h       148   (132 ok · 16 failed)
   30d     4,092   (3,977 ok · 115 failed)
   365d   31,224   (30,554 ok · 670 failed)

   By endpoint                [24h | 30d | 365d]
   GetPlayerAchievements/v1/   2,310 ok · 42 ✗
   ...
```

`DiagnosticsViewModel` exposes three windowed total flows plus an endpoint-breakdown flow
parameterized by the selected window (`GROUP BY route` over the same cutoff); the selector changes
a query parameter and Room re-queries. Labels say `24h / 30d / 365d` to stay honest about rolling
semantics. The section sits above "Recent sync runs"; per-run detail and presence sections are
untouched.

## Risks / Trade-offs

- **Hour-bucket edges overcount the window by up to 59 minutes** (a run starting at now−23h59m
  lands in the bucket at now−23h, inside a 24h cutoff) → Accepted: the counters are diagnostics,
  not billing; all three windows share the same inclusive-hour semantics, so the figures stay
  self-consistent.
- **Device clock set backwards** increments already-existing past buckets → Honest behavior for
  bucket sums (no negative deltas exist to corrupt); no mitigation needed.
- **Backfill recovers only ~2 days; counters start near zero on existing installs** → Accepted by
  decision (user approved); new installs start at zero regardless.
- **Route derivation depends on the stored URL format** → The migration parser is tolerant
  (unparseable rows dropped, migration never fails); the runtime path uses OkHttp's own
  `HttpUrl`, not string parsing, so it cannot drift.
- **Route proliferation** if future Steam endpoints are added → Still bounded (~routes × statuses
  × 24 rows/day) and capped by the 400-day prune.
- **Status is TEXT, not INTEGER** (NULL not allowed in a PK) → Any future query needing numeric
  comparison must cast; the `ok` flag deliberately carries the only classification the UI needs,
  so no query parses status strings today.

## Migration Plan

`MIGRATION_18_19` is additive: create `request_totals`, backfill from `sync_runs` ×
`request_breakdowns`. No existing table is altered. Rollback = revert to v18, which drops the
table; the loss is limited to counters that can be partially regenerated from retained raw rows
and fully rebuilt by future runs. Version bump follows the file's established hand-written
pattern; no `autoMigration` is introduced. The database is at version 18 as of this update —
renumber at apply time if another schema change lands first.

## Open Questions

None outstanding — the four exploration threads (route granularity, status retention, rolling
windows, backfill) are all settled above. Future work, explicitly out of scope: counting presence
and player-count requests, which today are untagged and never attributed to a run scope.


