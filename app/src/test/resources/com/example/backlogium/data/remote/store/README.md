# Captured Steam Store fixtures

Verbatim responses, captured 2026-09-11, used to pin the participation-category identifier table
and the review-summary shape from real data rather than from memory (add-gap-plan-suggestions
task 2.1/2.2). Each is the unmodified body of one request.

| File | Request | Why this app |
|---|---|---|
| `appdetails-570.json` | `api/appdetails?appids=570&l=english` | Dota 2 — multiplayer (`1`) with **no** single-player category |
| `appdetails-548430.json` | `api/appdetails?appids=548430&l=english` | Deep Rock Galactic — online co-op (`38`) |
| `appdetails-238960.json` | `api/appdetails?appids=238960&l=english` | Path of Exile — MMO (`20`) |
| `appdetails-400.json` | `api/appdetails?appids=400&l=english` | Portal — single-player only; carries `2` and none of `1`/`20`/`38` |
| `appreviews-570.json` | `appreviews/570?json=1&language=all&purchase_type=all&num_per_page=0` | Confirms `num_per_page=0` suppresses review bodies while `query_summary` still carries the totals |

The category ids the app recognises are asserted against these files by
`SteamStoreCategoryFixtureTest`, so a guessed id cannot pass review: the test reads the ids out of
the captured response rather than restating the constant table.

Descriptions in a response are localized; ids are not. Recognition therefore matches on the
numeric id only — see `ParticipationCategories`.
