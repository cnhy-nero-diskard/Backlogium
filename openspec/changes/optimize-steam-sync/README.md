# optimize-steam-sync

Replace the whole-library hourly achievement sweep with tiered refresh driven by playtime deltas, split static-data TTLs, and move the reconciliation sweep to its own deferred worker
