## Why

The historical re-file resets the read cursor but its first request still starts 31 days ago. More importantly, the poller's older observations cannot currently put *pre-Backlogium* Steam play into History: the app has no sessions for play counted in its initial baseline, only an optional frozen imported-minutes balance. The player needs to choose the available cloud range and their own data-start cutoff before deciding which already-counted minutes can safely be dated.

## What Changes

- Show the earliest retained cloud observation in Settings and let the player explicitly choose a historical start date, with a recent-31-days default and a confirmation of the effective range before re-filing.
- Add an authenticated, opt-in bounded historical read that can start at the chosen date, resume in user-controlled batches, and preserve the ordinary 31-day first-read behavior for verification, routine, placement, and Read now.
- Stage historical evidence until the selected range is completely acquired; never apply a partial re-file. Report progress and the actual applied range, including when no sessions changed.
- Ask the player to confirm the end of their pre-Backlogium period; for Steam-owned games whose historical Steam playtime was already imported, create History sessions only for safely confirmed cloud-observed play **before** that cutoff, drawing each minute from that game's frozen imported balance. Reduce imported minutes by exactly the amount added as dated sessions; leave unobserved or unallocatable history imported and undated. The game detail's tracked/imported split changes, but Steam's total, the combined credited minutes, XP and levels do not.
- Continue to re-date eligible already-recorded sessions inside the chosen range. Preserve one-time application, exact reversal of both re-dating and transfers, existing disclosures, account/reader fencing, coverage metadata, and Steam-owned pending evidence. Reversal is required before resetting the separate Steam-history import.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cloud-presence-reader`: opt-in range acquisition, bounded/resumable historical reads, complete-only application, conservative transfer of already-imported owned-game minutes to dated History, and range-aware one-time reversal without changing ordinary first reads.
- `app-settings`: available-range display, explicit validated start and pre-data cutoff choice, progress/confirmation, transfer counts, and applied-range disclosure in the existing cloud presence section. The separate Steam-history import still resets normally once the cloud re-file is reversed.

## Impact

- `functions/src/readPresence.ts` and its Vitest/FakeFirestore tests; no change to client Firestore rules or the poller.
- Android Retrofit/DTO, cloud repository and persistent historical staging, the re-filing and imported-playtime paths, Settings ViewModel/Compose/resources, session-backed History and tracked/imported UI, and their unit/migration tests. No new Android dependency.
- Update stale cloud descriptions in `README.md`, `docs/architecture-map.md`, and the reader contract in `functions/README.md` when implementing.

## Non-goals

- Automatic full-history downloads, expanding ordinary first reads beyond 31 days, inventing minutes from cloud presence, turning all imported hours into precisely dated play, changing Steam-counted minutes or XP, or re-filing family-shared sessions through the owned-game placement path.
