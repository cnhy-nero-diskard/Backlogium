# Security Policy

## Reporting a vulnerability

If you discover a security issue, please **do not open a public issue**.
Instead, report it privately via GitHub's
[private vulnerability reporting](https://github.com/cnhy-nero-diskard/Backlogium/security/advisories/new)
or by contacting the maintainer directly.

Please include steps to reproduce and any relevant logs. You can expect an
initial response within a few days.

## Secrets

This app requires a Steam Web API key and SteamID64. These are configured in
`local.properties`, which is git-ignored and must **never** be committed. If you
believe a credential has been exposed, rotate it immediately.
