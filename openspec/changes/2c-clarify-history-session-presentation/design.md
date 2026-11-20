## Context

See proposal.md for motivation. `HistoryGrouping.kt` groups sessions by start date, then game; games are sorted by recorded minutes and sessions within a game by start. `HistoryScreen.kt` already draws a timeline gutter, approximate starts, recorded minutes, live suffixes, measurement help, and qualified cloud contribution/reveal surfaces. Existing accessibility work was archived on 2026-09-22. The reported confusion has no device screenshot or exact example, so source inspection is evidence about structure rather than symptom reproduction.

`1c-add-daily-game-breakdown` supplies the reusable daily evidence/credit contract. Draft PR #177 may later introduce evidence-only dates and XP; PR #176 may supply shared explanation components. This design is required because temporal presentation, daily credit authority, and those future states must agree.

## Goals / Non-Goals

**Goals:** make the current tree understandable on representative days; preserve attribution and bounded interaction; show discrepancies without changing the ledger.

**Non-Goals:** an alternate chronological view, a proportional time chart, exact end times, changing sort/attribution policy, unlock-only paging, daily XP, or a second provenance system.

## Decisions

### Establish a representative baseline before presentation edits

Capture multi-game/interleaved sessions, multiple sessions for one game, overnight open/closed sessions, progress-only days, visibility discrepancies, sparse history, and dual/partial cloud facts. Record device/build/date/zone, theme, and font context. If the original symptom cannot be reproduced, record that outcome while evaluating the specific structural clarity criteria; do not label the selection/grouping algorithm faulty by inference alone.

This baseline is an implementation task, not a claim made by this planning change. It determines copy/layout refinements inside the specified contract, not whether to add a new view. A materially different view requires a subsequent proposal revision.

### Retain the tree, label the facts

Keep day → game → session grouping and existing deterministic sort. Use resource-backed visible labels for approximate start, recorded minutes, and live state, with merged semantics that identify the same facts. Keep the gutter decorative/grouping-oriented with no proportional clock axis. A compact group heading can identify the game ordering when necessary. Measurement help describes periodic discovery, possible mixed/unknown amount sources, and known cloud/post-play improvements conservatively; no fixed timing guarantee or blanket assertion that every amount came from a Steam counter.

Alternative: flatten all sessions into chronological order or add text/timeline toggles. Deferred because the current report does not demonstrate a purpose the existing tree cannot meet, and those alternatives would need new navigation, ordering, and accessibility decisions.

### Reuse daily evidence without loading one query per day

Extend `1c`'s projection/composition to the currently loaded History date bounds. Read a coherent bounded snapshot and map its days into History UI models off the main thread. Preserve each visible day's session subtotal and show authoritative stored credit/outcome separately when needed. Progress-only rows retain recorded amounts with no invented per-game distribution; sessions without recorded outcomes show unavailable quest state. Current Focus membership can describe current filtering only, never historical Focus membership inferred from today's flags.

When PR #177 lands, its explicit evidence-only states must flow through the same presentation without fabricating zero minutes or a failed quest. Do not implement that feature here. Broad provenance markers, if supplied by PR #176, reuse these facts rather than duplicate help actions.

Alternative: rewrite stored daily progress to match the currently visible tree. Rejected because visibility changes cannot revoke earned history, and presentation is a read operation.

### Preserve interaction state and evidence floor

Keep existing older-window bounds and fully-loaded calculation; this change does not redefine the evidence floor owned by PR #177. Stable day/app/session keys preserve expansion, scroll, and cloud reveal. Maintain today's date transition and reject obsolete account snapshots. A reveal target that disappears receives the current clear fallback, not a jump to the nearest game or day. Missing time gaps receive no invented zero-activity or continuous-play claim.

## Risks / Trade-offs

- More explicit labels increase row height → retain concise inline facts and verify narrow screens/large fonts.
- Progress-only or unknown states need UI model changes → preserve authoritative optional credit and avoid false default quest failure.
- PRs #176/#177 touch the same surfaces → reconcile requirement names and read models when applying, without copying their entire deltas.
- Full-history recomputation or per-day query fan-out → reuse one bounded projection and retain previous coherent content while updating.

## Migration Plan

Apply after `1c`, capture the baseline, integrate the bounded projection, then refine copy/semantics/layout. No persistence migration or activity writes are expected. Rollback restores presentation while leaving session/credit evidence untouched. Verify source/domain behavior and device interaction, including existing cloud reveal and earlier loading. `3c`/`4c` remain an independent Analytics path.
