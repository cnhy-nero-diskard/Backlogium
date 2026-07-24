# Security Policy

## Reporting a vulnerability

If you discover a security issue, please **do not open a public issue**.
Instead, report it privately via GitHub's
[private vulnerability reporting](https://github.com/cnhy-nero-diskard/Backlogium/security/advisories/new)
or by contacting the maintainer directly.

Please include steps to reproduce and any relevant logs. You can expect an
initial response within a few days.

## Secrets

This app requires a Steam Web API key and SteamID64. They are normally entered
through the in-app onboarding flow and stored **encrypted at rest** using an Android
Keystore-backed key (encrypted DataStore) — never in plaintext preferences, and never
committed to source. The API key is masked wherever it is displayed and is not logged.

Optionally, `local.properties` (git-ignored, and which must **never** be committed) can
seed those values at build time via `BuildConfig`; they are imported into the encrypted
store once and thereafter the encrypted store is the source of truth.

If you believe a credential has been exposed, rotate it immediately (regenerate the key
at <https://steamcommunity.com/dev/apikey>).
