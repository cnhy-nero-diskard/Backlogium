## 1. Schema and migration

- [ ] 1.1 Add a `RequestTotal` entity in `entity/Diagnostics.kt` for table `request_totals` with composite primary key (hourStart, route, status), the write-time `ok` flag, and `count`
- [ ] 1.2 Bump `BacklogiumDatabase` to version 19 and write `MIGRATION_18_19` creating `request_totals`, following the file's hand-written SQL pattern (no autoMigration)
- [ ] 1.3 Backfill inside the migration: walk retained `sync_runs` × `request_breakdowns` via cursor, bucket by the run's start hour, derive route from the stored URL path with a tolerant parser (drop unparseable rows, never fail the migration), map null status to the `"network"` sentinel, classify `ok` from 2xx
- [ ] 1.4 Register `MIGRATION_18_19` in `DatabaseModule` alongside the existing migrations
- [ ] 1.5 Test the migration: seed a v18 database with runs and breakdowns (including a null-status row and a malformed URL), migrate to 19, and assert the bucket contents and the tolerant-parse behavior

## 2. Recording path

- [ ] 2.1 Add a route-derivation helper in `data/diagnostics` mapping the stored redacted identifier to its route via `HttpUrl.encodedPath`, with a unit test covering credential-bearing URLs (key/steamids must never survive into the route)
- [ ] 2.2 Add DAO methods: the incrementing upsert (`ON CONFLICT(hourStart, route, status) DO UPDATE SET count = count + excluded.count`), the 400-day rollup prune, and the windowed aggregation queries (totals for 24h/30d/365d split by `ok`, plus `GROUP BY route` per window) exposed as Flows
- [ ] 2.3 Extend `SyncRunRecorder.finish()` to upsert one rollup row per metric — hour bucket from `scope.startedAt`, status sentinel, ok flag — inside a transaction and wrapped in `runCatching` so a counter failure never fails the run
- [ ] 2.4 Prune rollup buckets older than 400 days in `finish()` alongside `pruneRuns(200)`, equally failure-isolated
- [ ] 2.5 Tests: recorder writes correct buckets/ok/sentinel values; a failed rollup write still records the run; the DAO increment accumulates across two writes to the same bucket; window cutoffs include and exclude buckets at the boundary correctly

## 3. Counters UI

- [ ] 3.1 Extend `DiagnosticsViewModel` with 24h/30d/365d total flows (each split ok/failed) and an endpoint-breakdown flow parameterized by the selected window
- [ ] 3.2 Add the "Request counters" section to `DiagnosticsScreen` above "Recent sync runs": three summary rows labeled 24h / 30d / 365d with the ok · failed split, and an endpoint table with a `[24h | 30d | 365d]` selector driving the breakdown query
- [ ] 3.3 Render a neutral empty state for the counters section when no requests have been counted, and confirm the section never blocks on network (Room flows only)
- [ ] 3.4 Test the view-model state: three windows compose correctly and switching the selected window recomputes the endpoint breakdown

## 4. Validation

- [ ] 4.1 Run `openspec validate` for the change and fix any spec or artifact issues
- [ ] 4.2 Run the app unit test suite via Gradle and fix failures
- [ ] 4.3 Manual smoke check on a device/emulator: trigger a sync, confirm the counters section appears, and confirm counters survive a run prune
