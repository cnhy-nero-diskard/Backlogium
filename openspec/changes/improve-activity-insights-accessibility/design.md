## Context

History is a single lazy timeline with transient day/game expansion. Analytics is a vertically scrolling screen whose custom Canvas chart owns touch selection, while window length/anchor and chart-display controls precede the chart. Existing session attribution, resolved window bounds, active-days default, solid baseline, and metric calculations are established behavior and must not move into UI code.

## Goals / Non-Goals

**Goals:**

- Make the History-to-Analytics journey understandable, accessible, and locale-correct.
- Preserve chart direct touch while providing complete non-precision alternatives.
- Put a useful interpretation before configuration without inventing data.
- Make period navigation reversible and state transitions explicit.

**Non-Goals:**

- Changing session grouping, day attribution, quest truth, analytics queries, or achievement calculations.
- Adding predictive health claims, recommendations, or cross-user comparisons.
- Replacing the charting implementation solely for visual novelty.

## Decisions

### 1. Keep History’s timeline and correct its framing

Rename the divider to `Earlier history` and give today a visible `Today` context when present. Merge each clickable header’s semantics so date, time, quest state, expansion state, and action are announced together. Add the same expanded-state treatment to game rows. Measurement help opens as a compact explanatory sheet from a labeled info action.

Flattening History into sessions was rejected because the current hierarchy is its strongest quality.

### 2. Derive one bounded Analytics insight in the ViewModel

Add an `AnalyticsHeadline` presentation value derived from existing window results with deterministic priority: no-data statement; comparison with the immediately preceding comparable window when both contain data; otherwise leading game or active-day summary. It uses only factual totals already available or fetched through the same bounded repository queries.

Multiple generated “insight cards” were rejected because they would add noise and invite speculative interpretation.

### 3. Preserve Canvas drawing but move operability outside it

Keep the Canvas for efficient rendering and direct taps. Add explicit previous/next day buttons tied to the same selected index, a merged chart summary, selected-day state description, and a text breakdown below. This gives TalkBack, switch access, keyboard/D-pad, and imprecise touch users a reliable path without trying to create fragile virtual child nodes inside Canvas.

### 4. Collapse secondary chart configuration

Keep the selected period label and primary range choice visible. Put rolling/calendar explanation, alternate lengths, and active-days/all-days behind an expandable `Chart options` section that announces its state. The active-days-only default remains unchanged.

### 5. Model anchor navigation symmetrically

Expose `canStepLater` and `isCurrentWindow` alongside the existing earlier bound. Earlier/later use the current calendar-versus-rolling step rules; return-to-current resets only the anchor, not the chosen length or chart-display option. Retain the selected period during recomputation and mark it updating.

### 6. Localize presentation without changing attribution

Replace `Locale.US` and English casing with localized resource formatting. Dates continue to originate from the same local `LocalDate` bounds; localization changes only presentation, never grouping or query boundaries.

## Risks / Trade-offs

- **[Risk] Previous-window comparison adds repository work.** → Reuse bounded aggregate queries and compute only for the selected window; omit comparison if unavailable rather than blocking the screen.
- **[Risk] Explicit day controls consume chart space.** → Place compact labeled controls below the plot beside the selected-date summary, with 48dp targets.
- **[Risk] Progressive disclosure hides range power.** → Keep the selected range and period visible, and label the options control with the active mode.
- **[Risk] Locale changes destabilize tests.** → Pin locale in unit/semantic tests and let the visual-regression change own fixed-locale screenshot baselines.

## Migration Plan

No database migration is required. Implement History semantics/copy independently, then add Analytics headline state, accessible chart navigation, reversible period navigation, loading behavior, and localization. Rollback leaves existing stored activity intact.
