# Design

## Context

Core insights establish consistent selected and comparison periods, visible playtime and eligible sessions. Genre tags can be missing or change, achievement rows can lack a dated unlock, and the clock pattern uses session starts.

## Goals / Non-Goals

Extend Analytics with evidence-aware charts and records. Keep optional annual recap/share polish or release-era analysis available for separate product planning.

## Decisions

### Reuse recorded bounds and account fences

Calculate genre shares using current distinct Steam Store tags, allocating each eligible game's minutes evenly and tracking Unknown. Suppress drift unless each comparable period has at least three genre-covered titles and known genres cover at least half its visible minutes. Round only when rendering. Derive the weekday matrix from the existing session-start buckets and omit a dominant-pattern claim for sparse records.

### Project supported unlocks and stable records

Query confirmed dated unlocks within the range and use the frozen rarity snapshot when available. A completion requires evidence dating the confirmed full-set transition, which may need prospective persistence. Keep undated unlocks and ambiguous completion out of window counts and disclose gaps. Report longest session, busiest day, most varied day, qualifying return, rarest dated unlock, and per-game runs with explicit scopes and deterministic ties. Suppress comparisons with fewer than three eligible active dates on either side.

### Progressively disclose the advanced sections

Use the existing selected-window snapshot and separate headings for genre, timing, achievement activity and records. Provide plain-text equivalents for every chart and evidence label. Reuse the established date inspector where appropriate.

## Risks / Trade-offs

Current genre tags are not historical classifications, and later metadata can change the distribution. Historical completion may remain unavailable until enough evidence is recorded. Never infer achievements from catalogue entries.

## Migration Plan

Add bounded projections and any required prospective dated evidence without backfilling unsupported completion dates. Keep lifetime rarity independent. Update affected visual baselines alongside the UI.

### Record eligibility and tie order

Records cover only the represented period. Longest session uses finalized positive-duration sessions; busiest day uses visible dated minutes; most varied day counts distinct positively credited games. Each game's run is consecutive positive local dates, clipped at the window edges. A qualifying return has at least 30 full quiet dates between positive records; the latest pre-window date is context only. Ties prefer the earliest date/start, then app id and row/API identity. Rarest dated unlock uses the retained rarity snapshot and excludes unknown rarity. Genre shares remain unrounded until display. Clock dominance and achievement count comparisons require at least three evidenced start/unlock dates (on each side for comparisons).

Completion dates are first observed confirmed incomplete-to-full-set transitions, retained prospectively in sync metadata. They are observation dates, not inferred Steam completion dates. A complete first baseline does not create a dated completion. Missing-date unlock and unsupported-completion coverage counts describe the visible account's stored evidence across all dates, since undated evidence cannot be assigned to a period.

### Portable completion evidence stays account-bound

Backups carry prospective completion observations only when their source account matches the
Room profile captured with the exported rows; a credentials/profile mismatch rejects the export
rather than relabeling or silently dropping the evidence. Same-account restore keeps the earliest
observation. A warned cross-account restore still merges ordinary history, but omits completion
observations because they assert a transition in one player's confirmed achievement set, not a
portable historical fact about the receiving player.
