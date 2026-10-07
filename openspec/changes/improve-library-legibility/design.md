## Context

See proposal.md. GameListDensity already models a strict field subset, with ACHIEVEMENT_COUNT on LIST and GRID but absent from COMPACT_GRID. Existing game cells retain HLTB progress, the completed indicator, recency badge, artwork loading, and per-game XP where the density permits it. Hidden games, family-shared games, and a separate wishlist already exist.

LibraryScreen.SectionHeader currently renders a title and per-section sort control without a count. The current Library query targets ordinary library sections; wishlist-search expansion is tracked separately in #167. Settings uses task-oriented nested groups including Data & privacy.

## Goals / Non-Goals

**Goals:** scannable known trophy completion and honest counts with consistent scope.

**Non-Goals:** new densities/fields, a trophy bar on compact grids/Home teasers that do not expose counts, wishlist-search implementation, hidden-game policy changes, or an authoritative purchased-game count.

## Decisions

### Render an existing field at every applicable rung

LIST retains identity/playtime/HLTB/trophy count+bar/XP; GRID retains its current subset with trophy count+bar; COMPACT_GRID retains its current identity-oriented subset with no trophy field. Apply the same renderer where collection member cells already show the trophy count. Do not mutate visibleFields or add information only to a denser rung.

The fraction stays because a bar alone loses precision. Unknown schema/count data renders neither a zero fraction nor an empty bar. A confirmed total with zero unlocked renders 0/N and an empty track. A confirmed no-achievements game renders no trophy bar. A fully unlocked known positive total retains the current completed indicator and omits the full bar.

### Distinguish two kinds of progress

Use a semantic achievement-progress theme token, initially backed by the existing violet achievement palette, rather than gold milestone/HLTB-overrun or live-playing green. Distinction cannot rely on hue alone: trophy icon/count, separate labelled semantics, track shape/spacing, and consistent placement identify the bars. Do not imply the game itself is finished simply from HLTB elapsed time.

Validate adjacency against HLTB normal/overrun states, recency corners, artwork placeholders, long names, large fonts, narrow cells, theme contrast, and color-vision limitations. Preserve current achievement/HLTB semantics and overflow affordances; adjust layout only as necessary for readable field rendering.

### One explicit count universe

Provide a domain count summary from the same cached ordinary-library universe used by the Library repository before hiding/search/filtering. Count distinct app IDs, including owned/shared entries and whatever tools Steam admitted; exclude the separate wishlist and removed-shared archive. Derive hidden count only from hidden IDs actually belonging to that universe. Available/visible count is the actual baseline ordinary Library set under current visibility rules.

Focus and Your games remain disjoint partitions of that visible baseline. Header displayed counts follow their actual matching lists; when a query/filter narrows a section, show matching versus baseline count. Preserve omission of empty section headers and the existing empty-results recovery rather than adding a blank section only to show zero. A footer/summary may explain total matches where useful.

Analytics and Data & privacy use the same all-time summary, explicitly labelled cached-library scope; selected-period activity counts, if present, stay separate. The summary exposes visible/hidden totals so hiding does not silently redefine the cached count. Wishlist gets its own count when shown, never included implicitly in an owned-games label. No count is added to Home, and collection member totals remain their own existing scope.

Avoid direct Room reads in UI. If the existing visible Library flow lacks hidden entries, add a repository/domain summary over its underlying universe plus hidden projection; do not calculate cached totals from only the already-filtered list. Keep a consistent snapshot so a hide/ownership conversion cannot double-count or transiently disagree across surfaces.

## Risks / Trade-offs

- Two bars can crowd a grid → keep the established density contract and verify real cell states before accepting layout.
- Achievement hues already carry tier meaning → use a named semantic progress token and icon/shape rather than asserting a game has that rarity.
- Counts invite “owned games” disputes → explicitly name cached ordinary entries, shared inclusion, hidden scope, and separate wishlist.
- Other #167 changes affect search → derive headers from actual section projections rather than duplicated filtering logic.

## Migration Plan

No schema or data migration. Implement the shared summary and renderer, then wire Library/Analytics/Data & privacy. Reuse existing field visibility and current empty-state behavior. Record updated visual baselines for rendered surfaces after functional correctness is established.
