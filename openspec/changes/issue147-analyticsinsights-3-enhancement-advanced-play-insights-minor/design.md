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
