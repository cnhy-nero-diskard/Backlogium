## Why

Backlogium loads Steam imagery only when a screen needs it, so artwork can remain unavailable
offline and a large library can repeatedly expose the user to loading placeholders. Users need an
explicit way to make the app's Steam imagery available offline without making image acquisition
part of normal Steam data sync or removing the existing on-demand loader.

## What Changes

- Add an "Offline Steam assets" Settings control that starts a dedicated, manually triggered
  asset-download job, separate from "Sync now" and its state.
- Let the user choose between downloading only locally missing assets and refreshing all known
  Steam assets.
- Cover every Steam image Backlogium currently renders: the player avatar; owned-game icons;
  `header.jpg`, `hero_capsule.jpg`, `library_hero.jpg`, `library_600x900.jpg`, and
  `capsule_616x353.jpg` for every owned game; and known achievement icons.
- Store successful downloads in durable app-private storage with enough metadata to distinguish a
  valid local asset, an expected unavailable Steam asset, and a transient failure.
- Show a dedicated determinate progress bar and counts for the asset job, independent of Steam
  sync progress, with state surviving navigation and process recreation through WorkManager.
- Resolve stored assets locally first while retaining the existing on-the-fly network loader and
  themed fallback behavior whenever no valid stored copy exists.
- Preserve the previous valid file when a refresh attempt fails, and treat legitimate missing Steam
  artwork as unavailable rather than failing the whole batch.

## Capabilities

### New Capabilities

- `offline-steam-assets`: Defines asset discovery, the missing-only and refresh-all modes, durable
  app-private storage, progress and completion semantics, retries, and independence from Steam data
  sync.

### Modified Capabilities

- `app-settings`: Adds the dedicated asset-download control, mode choice, storage summary, and
  progress presentation separate from the existing Sync section controls.
- `app-ui`: Changes Steam image resolution to prefer durable local assets while preserving existing
  on-demand loading, ordered artwork fallbacks, and themed failure states.

## Impact

- **Affected code:** Settings UI/state, a dedicated WorkManager worker and scheduler surface, game,
  achievement, and profile asset discovery, shared Steam image components, and the game-detail
  accent image request.
- **Storage:** Adds an app-private Steam asset directory plus a small persisted manifest or index.
  Downloaded assets are derived data, excluded from user backups, and independently replaceable.
- **Network:** Downloads use Steam-hosted image URLs and require connectivity, but do not require a
  Steam Web API call or become part of the periodic/manual Steam data-sync worker.
- **Dependencies:** Reuses the current Coil 2.7, OkHttp, Room, Hilt, and WorkManager stack; no new
  external service is introduced.
- **Scale:** Work is proportional to the owned library, its known achievements, and all supported
  per-game artwork variants, so execution must be bounded, resumable, and robust to partial failure.

## Non-goals

- Downloading HowLongToBeat covers, Steam screenshots, videos, backgrounds, or other imagery that
  Backlogium does not currently render.
- Automatically running the bulk asset download after sync or on a schedule.
- Removing or disabling on-the-fly image loading.
- Including downloaded image files in Backlogium backup exports.
