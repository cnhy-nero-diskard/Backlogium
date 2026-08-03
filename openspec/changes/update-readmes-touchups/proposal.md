# Change: update readmes and forward-facing touchups

## Why

Backlogium's public README and screen descriptor have fallen behind the app. They
still frame cloud sync and an OBS overlay as the main remaining story, while the
current app now includes a polished four-tab shell, live presence, HLTB review and
batch refresh, rarity-weighted achievement XP, Focus games, and backup/restore
controls.

This makes the project harder to evaluate from the repository front page and gives
contributors stale expectations about what exists today.

## What

- Refresh the root README so it presents Backlogium as the current offline-first
  Android app, not an old phased plan.
- Document the main user-facing surfaces that exist today: Home, Library, History,
  Settings, onboarding, game detail, HLTB review, live presence, and backup/restore.
- Keep roadmap language accurate by separating implemented local features from
  future cloud/overlay work.
- Make small forward-facing copy touchups where wording is stale, unclear, or
  inconsistent with the current product vocabulary.

## Impact

The change is documentation and copy focused. It should not alter app behavior,
persistence, sync rules, credentials handling, or gamification calculations.
