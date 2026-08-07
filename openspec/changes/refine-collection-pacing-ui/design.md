## Context

Home collection cards currently format several independent banner values into one text-heavy line and render up to five thumbnails. Deadline feasibility is derived by multiplying days remaining by 1,440 minutes, which treats all clock time as playable. Backlogium already stores synthesized sessions, local-date analytics, collection membership, HLTB estimates, live now-playing identity, Steam header artwork, and collection accents, so the refinement can remain offline and derived from existing data.

The change crosses the session repository, pure domain derivation, collection summaries, Home state, collection state, and Compose presentation. The derived forecast must not imply precision that the local history or missing HLTB data cannot support.

## Goals / Non-Goals

**Goals:**

- Derive a conservative, explainable Personal Pace profile from recent local session history.
- Forecast realistic gaming capacity through a target date without assuming every calendar hour is available.
- Use confidence and completeness gates before declaring a collection infeasible.
- Give deadline, completion-goal, and ordered-queue modes useful but mode-appropriate pacing guidance.
- Make Home cards concise, cap previews at three games, and visually connect live play with every affected collection.
- Reuse the Library's faded header-art language for game cards inside Collections.
- Keep all derivation deterministic, offline, and JVM-testable.

**Non-Goals:**

- Persisting or syncing a schedule, calendar availability, or derived forecast values.
- Asking users to manually enter weekly gaming hours.
- Reserving one global capacity budget across competing collections or automatically prioritizing collections.
- Predicting exact completion dates when required HLTB data is missing.
- Changing HLTB scraping, Steam session synthesis, collection membership, or collection modes.
- Automatically changing a deadline without user confirmation.

## Decisions

### 1. Personal Pace is a pure domain engine over daily session totals

A `PersonalPace` domain component will accept dated session totals, an injected local date/zone, and a requested forecast range. It will return a habit profile and forecast values rather than formatted UI strings. Session rows remain the source of truth; the engine performs no network or database work.

Alternative considered: reuse the Analytics UI state. Rejected because collection behavior should not depend on a screen-specific view model and must remain independently testable.

### 2. Use the latest 56 completed local days

Sessions are bucketed by their local start date and multiple sessions on the same date are summed. The current date and open sessions are excluded from habit training because both may be incomplete. The standard lookback is eight weeks, giving each weekday up to eight observations while remaining responsive to changed habits.

Recent observations receive exponentially greater weight with a 28-day half-life. A weighted median of active-day minutes, with the upper tail bounded by the observed 90th percentile, prevents a single marathon session from defining normal capacity.

Alternative considered: a simple 30-day average including zero days. Rejected because it loses weekday behavior and is too sensitive to one unusually long session.

### 3. Blend weekday habits toward the global profile when samples are sparse

For each weekday the engine derives an active-day probability and typical active-day duration. Weekday duration estimates are blended toward the global active-day estimate until that weekday has at least four active observations. Each future date contributes:

`expected capacity = blended active probability * blended typical active-day minutes`

Summing date contributions produces projected active days and projected gaming minutes for any future range. UI copy rounds these estimates to human-scale values and labels them as approximate.

### 4. Confidence gates definitive collection advice

The profile is `RELIABLE` only after at least 28 completed local days are covered and at least six of those days contain tracked play. Otherwise it is `LEARNING`. Learning profiles may expose required pace from known work, but they do not declare a future deadline safe or infeasible and do not recommend changing it.

This threshold favors avoiding false warnings over early personalization. It does not claim that Steam captured every gaming session; the UI describes the result as based on Backlogium's recent tracked activity.

### 5. Collection work and forecast capacity remain separate values

Remaining work continues to be the sum of `max(selected HLTB estimate - stored playtime, 0)` per member. Members missing the applicable estimate remain unknown and are never treated as zero. Forecast capacity comes only from Personal Pace. The collection layer combines them into:

- required known minutes;
- projected gaming minutes through the target;
- projected active gaming days;
- required minutes per projected active day;
- capacity margin (`projected minutes - required known minutes`);
- completeness and confidence states.

A future deadline is definitively `AT_RISK` only when the profile is reliable, every member has the applicable estimate, unfinished known work remains, and projected capacity is below required work. It is `ON_TRACK` under the same gates when capacity covers the work. Other cases are `LEARNING` or `INCOMPLETE_DATA`.

### 6. Mode-specific use avoids forcing forecasts onto basic lists

- Deadline goal uses its selected HLTB basis to show required pace, capacity, feasibility, and an estimated fit date.
- Completion goal uses Completionist estimates to show an approximate finish horizon when history and all required estimates are available.
- Ordered queue uses the next unfinished member's Completionist estimate for a next-game horizon and may show a whole-queue horizon when all remaining estimates exist.
- Basic list presents no Personal Pace output.

Forecast detail belongs in the collection overview. Home uses at most one concise pacing/status line and preserves a structured progress indicator where applicable.

### 7. Deadline intervention has one shared eligibility rule

`Change deadline` appears only for a non-empty deadline collection with unfinished or unknown work when either:

1. the target date is today or earlier; or
2. the target is in the future and the collection is definitively `AT_RISK`.

The action stays hidden for on-track, learning, or incomplete future forecasts. When an at-risk reliable forecast can find an earliest date whose cumulative capacity covers the work, that date may initialize the picker as a suggestion, but the user must confirm it.

Alternative considered: always show the shortcut. Rejected because a permanent corrective action adds noise and contradicts the goal of intervening only when needed.

### 8. Home cards prioritize identity, status, and progress

The uppercase mode label is removed. The existing mode icon carries the visual distinction and receives an accessibility description. Cards show the collection name, one concise mode-relevant line, a compact progress surface for goal modes, and up to three ordered member thumbnails. More members use the existing remaining-count convention after the third preview.

Missing HLTB details are not enumerated on Home. A compact incomplete-forecast state may be shown when it materially affects the status; the collection overview explains which estimates are missing.

### 9. Live play energizes every matching collection without changing layout

Home joins the live game app id against collection memberships. Every matching card animates a low-alpha accent-colored outer glow on a slow pulse while that game is active, then fades it after play ends. The effect animates drawing properties only, so card measurements do not change. A collection without an accent uses the theme's live-playing color. Reduced-motion mode replaces the pulse with a static faint outline.

### 10. Extract a shared faded game-art treatment

The Library's right-aligned header image, low opacity, and horizontal alpha mask will become a reusable Compose component or modifier. Collection overview and management game cards layer content and controls above it while retaining the collection accent strip. Missing or failed header art leaves the normal card surface intact without a broken placeholder.

Alternative considered: copy the Library implementation into Collections. Rejected because independent constants and masks would drift.

## Risks / Trade-offs

- [Tracked sessions may be incomplete when monitoring or syncing was disabled] -> Label the profile as tracked activity, gate definitive advice behind history, and never imply calendar access.
- [A recent vacation or temporary gaming burst can skew capacity] -> Use eight weeks, recency weighting, robust duration statistics, and approximate copy.
- [Independent forecasts can overcommit the same future time across several collections] -> Keep portfolio scheduling out of this change and avoid claiming that forecasts reserve time.
- [Completionist estimates may not describe how an ordered-queue user intends to finish a game] -> Treat queue horizons as approximate and only show them when Completionist data exists.
- [Continuous glow can distract or consume rendering resources] -> Animate only visible matching cards at low alpha, use a slow cycle, avoid layout invalidation, and honor reduced motion.
- [Header art can reduce text contrast] -> Reuse the Library's proven low-alpha mask and verify bright and missing-art cases in both themes/surfaces.

## Migration Plan

1. Add the pure forecast types/engine and session-history projection without changing existing deadline behavior.
2. Add unit tests for date bucketing, weighting, sparse history, outliers, forecast ranges, and confidence.
3. Feed Personal Pace into collection summaries and replace the calendar-minute differential behind the new eligibility states.
4. Update Home and collection UIs, then remove the obsolete 1,440-minutes-per-day comparison.
5. Validate existing collections without session history: they remain readable, show learning/incomplete states, and retain editable deadlines through customization.

Rollback restores the previous summary/UI derivation. No stored collection or database data requires reversal because forecasts remain derived.

## Open Questions

- Device verification should determine the final glow alpha, blur radius, and pulse duration; these are visual tuning constants rather than behavioral contracts.

