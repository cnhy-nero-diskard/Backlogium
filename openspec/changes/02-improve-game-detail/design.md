## Context

See `proposal.md`. `GameDetailViewModel` currently renders local content, sorts with an explicit unlocked-first comparator, and refreshes only player count through pull-to-refresh. `AchievementRepository.refreshOne` already coalesces per-account/app-ID fetches, merges behind a mutex, and distinguishes persisted, unusable-data, and unavailable outcomes. Its convenience method is not by itself a complete account-reset fence or UI no-change result. The normative `Achievement sorting`, `Achievement unlock rate`, and `Game detail artwork fallback` requirements therefore need complete replacement blocks, included in this change's delta.

Apply `01-streamline-collection-access` first for preference persistence and the detail heart. This change adds distinct preference fields and requirements; it does not recreate that table or own the Library discovery work. Existing Steam cover families and integrity-checked offline asset storage already cover all proposed candidates.

## Goals / Non-Goals

**Goals:** Separate local rendering from network action state; keep all detail entry points equivalent; coordinate refresh through the existing repository; persist a cover choice without confusing it with Steam-owned metadata.

**Non-Goals:** New active monitoring, notifications, playtime measurement, HLTB refresh policy, arbitrary remote images, wishlist preference editing, or changes to earned rarity/XP rules. Keep the current explicit player-count pull gesture intact.

## Decisions

### 1. Visit-local filter and deterministic mixed comparator

Carry All/Unlocked/Locked and sort in the detail visit's retained state, restoring through recreation but defaulting to All/date for a new visit. Derive filtered rows after computing summary totals from the complete cached set. Date sorts descending with nulls last; rarity sorts ascending on `globalPercent` with nulls last; display name and API name settle ties. Neither comparator starts with unlock state.

Show current global percentage separately from the earned `rarityPercent` snapshot. The former explains list order; the latter explains earned tier and XP. A snapshot-only unlocked row retains its earned disclosure but has an unknown current sort key. This deliberately modifies the old rate/order contract rather than silently changing a comparator while leaving misleading labels.

Alternative: intermingle unlocked snapshot percentages and locked current percentages. Rejected because they describe different observations and would make the supposedly common rarity order misleading.

### 2. An account-safe refresh use case around the common funnel

Add a domain action using the existing fetch/merge coordination, not a second network client. Capture account/reset generation, credentials, and app ID; reject pending account transitions or missing credentials. Before commit, recheck the generation, stored profile identity, game existence/source, and hidden/excluded state under the same transaction/exclusion protocol as reset. A UI-only check after `refreshOne` returns is insufficient because its merge has already occurred. Extend the repository with a guarded fetch/commit entry point where needed, retaining its same-account coalescing, merge timestamp guards, schema caching, and cancellation behavior for all callers. An A-to-B-to-A identity round trip still invalidates the original generation.

Fingerprint canonical achievement content before/after the committed merge, including names/descriptions, unlocks/dates, current rarity, and schema-visible changes, excluding fetch timestamps. Counts alone cannot distinguish an updated description from no change. Recompute changed progression through its existing owner and publish success after the committed result. Do not replace first-unlock rarity or create extra sessions. Duplicate taps while pending do not queue work. Cancelling or switching detail abandons its presentation token; safe same-account cached writes remain governed by the repository, while account-reset/hide/removal invalidation prevents stale commits.

Alternative: let the ViewModel read DAO counts and trigger a whole sync. Rejected because it breaches the repository boundary, adds unrelated work, and mishandles account races.

### 3. Extend game preferences with a variant token

Add nullable `artworkVariant` to the first change's preference table. Supported tokens map to HEADER, LIBRARY_HERO, WIDE_CAPSULE, HERO_CAPSULE, and LIBRARY_CAPSULE using `SteamIconMapper`; never persist arbitrary URLs. A shared cover resolver tries the selected variant first, then each surface's deduplicated existing fallback chain. Detail and horizontal cards retain their common wide chain; portrait cards retain their frame/crop and normal chain. Icons remain independent. Accent sampling consumes the actually decoded selected/fallback image and preserves full-screen versus overlay containment.

Expose Choose cover when all current cover candidates fail, and Manage/Reset cover whenever an override exists. Preview at most five candidates using the existing loader/interceptor, cached images first. Failed and uncached-offline entries are visibly unavailable and not selectable; an existing unavailable selection can still be reset. Reuse the normal image cache, without starting or forging a bulk-download manifest entry.

Alternative: persist a URL or download user-selected files. Rejected because variant tokens remain bounded, same-app-ID, and compatible with existing asset inventory.

### 4. Presence-aware artwork backup extension

Extend optional version-2 preference records with an optional `artwork` object. Omitted object means leave the current selection unchanged; `{variant: null}` explicitly resets; a supported variant explicitly selects. New exports emit the object for every retained preference row. Preserve existing favorite values and version-1/2 session provenance contracts. Validate object shape and variants before import, including duplicate records; old favorite-only backups cannot clear the new field. Cross-account imports still use the existing warning and per-app-ID preference merge.

## Risks / Trade-offs

- [Old-account responses arrive after reset] -> Fence the commit with account identity and reset generation, and test A-to-B-to-A.
- [No-change is inferred from equal counts] -> Compare canonical committed content and exclude freshness-only timestamps.
- [Mixed rarity looks inconsistent with earned tiers] -> Display current and earned values with explicit labels; test snapshot-only rows.
- [Selected artwork fails later] -> Retain preference, reuse fallback, and keep Reset available.
- [Layout refinements overwhelm dense detail] -> Make behavior functional first, then capture owned/shared/missing-data states at ordinary and large fonts in both entry presentations.

## Migration Plan

Apply after change 1 and add the nullable variant column from the actual current Room schema. Default existing rows to no override; do not reset favorite values. Extend backup mapper/validator/merge and asset resolution together. Run focused filter/comparator, refresh outcome/race/recompute, migration, preference round-trip, and asset fallback checks, then device entry-point and large-font verification with required baselines. Rollback must retain the additive preference column and backup records; no destructive downgrade, deployment, or main-spec sync occurs during proposal creation.
