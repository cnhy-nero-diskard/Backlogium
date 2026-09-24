## Context

See proposal.md — Why. The current reader keeps a durable read position and bounded audit rows, but its last reconstructed snapshot is in memory. Settings' Read now consumes one page; placement drains pending pages only when a Steam delta has a sufficiently long observation gap. Cloud ingest writes shared-game sessions through the on-device presence mechanism, and owned-game placement replaces actions produced by Steam playtime diffing. Neither `Session` nor `PlaySession` stores contribution provenance. History expands day → game → session, while Diagnostics already shows a raw cloud/local comparison.

The reader's position is shared by Settings, placement, ingest, and historical re-file. `readCompleteHistory` resets/drains it, and `readRemainingHistory` holds a sequence mutex across pages; a new background path must join that sequencing rather than race it. The separate Settings-information-architecture change moves the cloud controls under Data & privacy. The normative reader requirement that an unconfigured app presents no cloud-derived state still applies.

## Goals / Non-Goals

**Goals:**

- Make a session's cloud contribution a durable fact, not an inference from configuration, game source, or timestamps; qualify mixed contributions.
- Let a configured phone eventually consume the poller's observations even when ordinary Steam sync does not ask for placement, while keeping routine volume deliberately small.
- Keep History oriented toward play, with a short route to the evidence and the limits of that evidence.

**Non-Goals:**

- Change the cloud poller's one-minute cadence, Firestore access rules, or raw observation schema.
- Add a server-side session detector, derived values, a complete local mirror of cloud transitions, or precise wall-clock background execution.
- Guarantee the entire Firebase project remains inside a free allowance: other functions, reads, writes, storage, and traffic share its budget.

## Decisions

### 1. Record contribution at the same write boundary as the session

Extend the session ledger with nullable per-session contribution evidence, independent of `GameSource` and `RecomputeSource`. Distinguish recovered shared-game play from timing-only placement of Steam-owned minutes, and track whether the contributed portion is partial when a local and cloud observation extend the same session. The domain repository projects that evidence for History; product UI does not read a Room entity. Legacy rows remain unknown, including sessions created before provenance existed, even where their timestamps resemble cloud intervals.

Shared-game ingest marks a recovery only when accepted cloud evidence actually credits time. Owned-game placement marks timing only when the replacement actions materially differ from the unaided actions; an unchanged or rejected suggestion does not earn a mark. An extension of a pre-existing unmarked open session is partial, as is an extension that adds unmarked local play to a marked session. Store enough contribution metadata to keep that statement truthful on future extension, re-file, rollback, and merge; do not equate a boolean `cloud=true` with every minute in the session having been observed. Persist provenance atomically with the credited session and its baseline/progress write, and do not make a cloud read itself write a label. A historical sweep saves/restores the provenance with the original rows.

*Alternative rejected:* deduce provenance from whether a game is family-shared or a cloud interval overlaps a session. Both create false positives, and a missed/failed read becomes indistinguishable from an actually used one.

### 2. Treat routine catch-up as a third acquisition trigger, not a new derivation path

Extend the existing repository's account-bound, serialized read/consume path with a bounded multi-page catch-up. Reuse the ingest callback and shared read cursor; hold the read-sequence lock across a bounded attempt and advance the cursor only after each page's effects are consumed. Before advancing a routine page, persist its owned-game intervals, including their coverage metadata, in a small, account-bound pending-placement evidence store. Upsert by Steam account, app id, and interval start so page overlap refines an interval rather than duplicating it. This is acquired timing evidence only: a routine read does not write a session, place Steam minutes, or create session provenance. If a routine read runs before Steam reports the matching increase, a later accuracy-driven placement combines pending intervals intersecting its diff window with any still-unread intervals before applying the existing complete-window and coverage rules; it must not judge completeness from the suffix alone. Keep the evidence until the Steam sync commits the corresponding baseline/actions, removing consumed evidence in that same commit so a failed or interrupted placement can retry it. Account changes and reader removal clear account-bound pending evidence. Report complete only at `hasMore=false`. A cap of four pages per attempt bounds one job even if the 31-day initial window is unusually dense; another eligible attempt resumes from the saved position. A failed page preserves already-consumed pages and their pending evidence, and reports partial/failure rather than falsely clearing the backlog. Keep the historical re-file's complete-history drain and placement's time-window proof unchanged. A routine catch-up alone must not retrospectively attribute owned-game minutes: the existing placement path and deliberate historical sweep retain that authority.

*Alternative rejected:* let routine pages call only the shared-game ingest callback. That callback discards owned-game intervals, so advancing the shared cursor before Steam reports the matching increase leaves placement with a suffix it must reject.

*Alternative rejected:* call `read()` once per day. One page is not necessarily the end, and a read that advances the shared cursor before the client consumes it can permanently skip shared-game observations.

### 3. One minimum-gap admission gate for the new routine triggers

Configure Automatic on successful reader verification. Automatic offers one periodic opportunity daily plus a play-end opportunity, with a 12-hour minimum gap across both. Overrides set a shared minimum gap of 12, 24, or 48 hours and a corresponding periodic opportunity; play-end can run sooner than the next periodic opportunity only when its gate has elapsed. Persist the selected policy and last admitted routine attempt (including a failed attempt, recorded when work actually begins rather than when it is merely queued offline) so process death, reboot, duplicate events, and network retries cannot produce an automatic request burst. Use unique WorkManager work with network constraints; an already-running routine job absorbs another routine request. Treat a recent successful manual or placement read as satisfying routine freshness only if it reached the end of unread history; a partial read must not suppress catch-up. Do not throttle manual reads or accuracy-driven placement. Only a configured, account-matching reader can admit work. On replacement/removal/account change, fence in-flight reads and clear/cancel scheduled work as appropriate; preference handling follows the configured reader, not the cloud service's poll schedule.

Recording *attempt* rather than success for the minimum gap is deliberate: an unreachable endpoint must not be hammered by every play-end event. WorkManager's periodic wake-up is an opportunity, not a promised execution time. The schedule is intentionally coarse; measured project usage, page count, and function cost should be checked before raising the offered frequency. This gate caps additional routine starts, not all cloud HTTP calls or the number of pages in one bounded catch-up.

*Alternatives rejected:* an unbounded free-form minute selector (invites excessive reads); applying the gate to correctness-driven placement (can advance a Steam baseline before cloud timing can be used); a separate cloud-side scheduler for phone downloads (cannot force an offline or sleeping phone to ingest).

### 4. Derive a quiet History entry from locally visible contributed sessions

Use the existing day → game → session expansion. The per-session cloud mark is secondary to time and minutes, text-explained through an accessible, sufficiently large action separate from the expansion control. Summarize proven recovery/timing contributions only for the currently loaded visible History period; hidden games are already filtered at the session repository boundary. Show the Cloud activity entry only when its visible count is nonzero and the reader is configured. The pushed detail leads with two labeled groups — recovered play and cloud-timed Steam play — linked back to their day/game/session, followed by read status and known coverage limitations. Preserve History's approximate start-time wording; do not imply a timeline-derived duration or a date change when only boundary timing changed. The raw interval-by-interval diagnostic stays in Diagnostics.

*Alternative rejected:* mark a whole day, game, achievement, or quest because cloud was available. A result can combine cloud-assisted and unaided sessions, and the higher-level badge would overstate the evidence.

### 5. Persist a small read summary, not the timeline, for offline status

Keep the existing bounded read audit and persist enough latest accepted read metadata (time, observation watermark where provided, coverage/partial status, trigger/outcome) to show the last attempt and last success after restart. Read success and observation freshness are separate fields; neither proves that the poller's next minute succeeded. The detail must handle no snapshot after restart, a stale latest observation, a failed read after prior success, incomplete history, and an endpoint not configured. Do not invent coverage percentages from transition counts or present a missing observation as no play. Reader endpoint/token remain out of History and backup.

*Alternative rejected:* store only the current in-memory `snapshot`. It disappears on process death and makes an offline detail screen appear empty despite existing contributed sessions.

### 6. Version backup provenance without changing session identity

Export explicit nullable provenance with sessions in a newer backup format, leaving credentials/endpoint/watermark device-local. Accept supported older backups with no provenance: an inserted legacy session stays unknown and an overlapping session retains known local provenance when the old file is silent; an explicit value in a newer file replaces the matching session's provenance. Keep natural-key matching and recompute semantics otherwise intact. Re-file reversal must restore the prior provenance alongside prior attribution, and removing the reader hides cloud-specific UI without deleting existing recorded minutes or inventing new session origins.

*Alternative rejected:* reconstruct provenance after restore by replaying cloud history. The old evidence may be unavailable, account or coverage may differ, and guessing would make the badge less reliable than an unknown label.

## Risks / Trade-offs

- **[Mixed open sessions accidentally claim full recovery]** → Model partial contributions at the writer boundary; test local-then-cloud and cloud-then-local extensions and reversals.
- **[Routine catch-up advances the cursor before Steam reports an owned-game increase]** → Persist owned-game intervals before each page advances, combine them with later placement evidence, and remove them only with the corresponding successful Steam commit; serialize the bounded drain using the existing sequence mutex.
- **[A routine cooldown still allows costly placement/manual traffic]** → Settings states the scope of the cap; keep its values conservative and verify real read/page usage against the project's budget rather than claiming the schedule guarantees free usage.
- **[A server outage strands a long backlog]** → Keep per-page position durable, bound attempt work, show partial state, and retry only on another eligible opportunity or manual action.
- **[Older sessions have unknown provenance]** → Never infer it, even when a visible session likely benefited historically; only future writes and explicit reversible re-file can supply evidence.
- **[Two in-flight Settings changes collide]** → Land the Settings-information-architecture change first or rebase this change against it; add the cadence controls once under Data & privacy and keep shared view state/actions.

## Migration Plan

Add a nullable session-provenance migration that leaves historical rows unknown, then version backup serialization/import to preserve explicit evidence. Integrate writes at the current shared session/placement boundaries before turning on the UI mark. Schedule routine work only for a verified configured reader, defaulting to Automatic; changing the policy replaces its periodic request without resetting the cloud read cursor or ingest position. A rollback can stop new routine scheduling and hide the History marks while keeping session minutes, the durable read position, and old backups intact. Verify on-device: restore an older backup, recover shared play, place owned play, undo a re-file, interrupt a multi-page drain, and show History offline.
