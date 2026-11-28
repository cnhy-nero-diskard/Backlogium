# Design

## Context

Deadline collections use a selected HLTB estimate basis and report capacity/remaining work with date-based pacing. Current lifetime Steam playtime and current estimates alone cannot prove what the user had achieved at a historical cutoff.

## Goals / Non-Goals

Freeze a reviewable outcome once the user confirms. Keep forecast, recorded evidence, and verified completion concepts distinct, and preserve archive retention through backup merges.

## Decisions

### Use the end of the selected deadline day

Resolve the cutoff as the start of the following local date in the goal's saved zone. Capture that zone for newly created or edited deadlines. Existing rows need a documented migration assumption and may lack trustworthy historical evidence. Preserve existing active deadline editing, and reject a preview if the active collection revision or evidence it depends on changes before confirmation.

### Capture deadline evidence before it becomes unrecoverable

Retain effective goal/membership revisions and estimate evidence needed by unarchived deadlines. Reuse date-scoped playtime and supported cumulative observations at the cutoff where coverage is adequate. Keep exact quantities distinct from bounds: a trusted pre-cutoff Steam total is a lower bound, while a later total is an upper bound only when trusted observations are monotonic. Preserve provenance and surface missing evidence. Avoid substituting current HLTB values, undated minutes or post-deadline play as a lower bound or inferring exact remaining minutes from an interval. Legacy collections may archive with an undetermined outcome.

### Freeze cutoff membership, not current UI visibility

For each unarchived deadline, retain timestamped membership and hidden-visibility revisions so finalization can reconstruct both the persisted members and which of them were visible at the saved-zone cutoff. The goal-evaluation membership is the persisted cutoff membership that was visible at that cutoff, consistent with hidden-games; hidden-at-cutoff members remain identified in provenance but are excluded from goal evaluation. A member hidden or removed after cutoff remains in the frozen evaluation and evidence. Current filtered reads (`getMembers`, `members`, `allMembers`, or `customOverviews`) are never cutoff evidence. If cutoff membership or visibility cannot be established, the outcome is undetermined rather than inferred from today's filtered collection. Hiding after cutoff cannot remove frozen evidence or change the outcome; while hidden, the member's game identity is not shown or navigable, though its frozen evidence may remain as a non-navigable archive entry. Unhiding after archive cannot change the snapshot or outcome.

### Persist archive and active transition atomically

Give the frozen record an identity tied to the collection's persisted identity and creation record. Save immutable member identities and metrics without requiring current game rows, and exclude archived collections from active selectors and mutation paths. Make the archive domain model readable by later Analytics work. Reuse validated backup export and merge. Distinguish independent collections with colliding imported numeric IDs from an older active copy of the same finalized collection.

## Risks / Trade-offs

A device may never run near the cutoff and older records may lack estimate or membership provenance. Undetermined remains a valid frozen outcome. Keep archived evidence inspectable while respecting hidden-game navigation and account boundaries.

## Migration Plan

Add archival schema, goal evidence and a compatible backup extension. Preserve older active collections and imports. Validate the backup reader's handling of unknown fields and version projections so older readers cannot reactivate a collection from a new archive export. Test identity collision, repeat import and transaction rollback.
