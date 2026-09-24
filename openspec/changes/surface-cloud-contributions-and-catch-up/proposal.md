## Why

Cloud observations can already recover family-shared play and improve when Steam-owned play is credited, but History cannot tell the player which results benefited. Reads also depend on manual actions or incidental sync conditions, so an otherwise quiet phone has no routine path to catch up on observed play. The integration needs an honest, low-noise explanation and a bounded background catch-up policy.

## What Changes

- Record durable per-session evidence that cloud observations actually contributed to recovered play or to the timing of Steam-reported minutes, including mixed local/cloud sessions. Show a small cloud mark only on eligible History sessions, with distinct explanations for recovery and timing; never infer provenance merely from configuration.
- Add an unobtrusive Cloud activity entry to History only when the visible period contains a cloud contribution. Its detail leads with recovered and cloud-timed sessions, then states last read and coverage limitations; retain the raw interval comparison in Diagnostics.
- Add opt-in-by-configuration routine cloud catch-up: Automatic is the default, with a daily safety net and play-end opportunities; bounded 12-hour, daily, and 48-hour cadence overrides govern routine catch-up with a shared minimum gap. Keep manual Read now and existing accuracy-driven reads during Steam sync independent of this preference.
- Make catch-up outcomes and incomplete pagination legible without claiming that a successful reader call proves the poller is healthy, or that a WorkManager interval runs at an exact wall-clock time.
- Preserve provenance through backup/restore and historical re-filing/reversal. Keep cloud acquisition additive, offline-first, account-bound, and separate from Steam's authority over owned-game minute totals.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cloud-presence-reader`: add durable contribution provenance and bounded automatic catch-up while preserving existing read, ingest, and placement authorities.
- `app-ui`: identify actual cloud contributions on History sessions and expose a focused Cloud activity detail for the visible period.
- `app-settings`: expose Automatic and bounded cadence choices, routine catch-up status, and their scope separately from manual reads and accuracy-driven sync reads.
- `backup-restore`: carry and merge session contribution provenance compatibly with older backup formats.

## Impact

- Android Room session storage, repository domain models, cloud ingest and owned-play placement writers, backup schema/merge, WorkManager scheduling, History and Settings UI, string resources, and focused tests.
- No new client access to Firestore, server-derived gamification, change to the poller's minute schedule, or dependency of ordinary Steam sync on cloud availability.
- Coordinate Settings placement with the in-flight `restructure-settings-information-architecture` change, which puts cloud controls under Data & privacy. The in-flight `attribute-playtime-from-presence` change supplies the owned-game placement path; this change records its contribution rather than replacing its rules.
