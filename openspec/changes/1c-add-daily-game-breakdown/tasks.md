## 1. Daily evidence projection

- [x] 1.1 Add a date-bounded repository/domain read projection with visible game totals, optional stored credit/outcome, and signed reconciliation; verify equal totals, both difference directions, progress-only rows, and sessions without credit in focused tests.
- [x] 1.2 Assemble the projection from coherent committed inputs and coalesce invalidations; verify a session/credit correction does not publish an intermediate mismatch and a date/account change rejects the obsolete result.
- [x] 1.3 Preserve start-date attribution, recorded open-session amounts, hidden exclusions, fallback identity, and wider summation; verify overnight sessions, unavailable game identity, hidden games, large sums, and undated import/manual inputs.

## 2. Home presentation

- [ ] 2.1 Wire the projection into Home and make Today's quest initially collapsed and expandable; verify the collapsed layout remains compact and multi-game rows reconcile with authoritative credit without changing quest state.
- [ ] 2.2 Add localized recorded/credited/difference/unavailable wording and explanation access; verify no copy invents a source, time allocation, or missing quest result.
- [ ] 2.3 Add named game-detail actions and date/account-keyed expansion state; verify navigation/return, midnight reset, account replacement, and a target hidden or removed before navigation.

## 3. Acceptance

- [ ] 3.1 Run relevant domain/repository/Home tests and `./gradlew.bat :app:compileDebugKotlin :app:lintDebug`; verify offline viewing triggers no sync, request, XP award, or persistent progress write.
- [ ] 3.2 Capture and inspect affected Home device/emulator states in both themes and larger font scale, checking expansion semantics and touch targets; record the evidence and label any unavailable context.
- [ ] 3.3 Run `openspec.cmd validate 1c-add-daily-game-breakdown --strict` and `git diff --check`; verify shared vocabulary/placement remain compatible with drafts #176/#177 and leave `2c` a reusable daily read contract.
