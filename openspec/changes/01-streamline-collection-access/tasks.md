## 1. Preference Persistence and Domain Boundary

- [x] 1.1 Add the app-owned game-preference table, domain repository, and additive migration from the current schema; verify migration-chain retention and default unfavorited behavior without changing existing games or collection members.
- [x] 1.2 Integrate preferences with account reset and transactional identity guards; verify account-switch clearing, sync preservation, and shared-to-owned retention with focused repository tests.
- [x] 1.3 Expose domain picker/member action models and safe direct mutations; verify duplicate add preserves queue position/done state, missing targets fail safely, and hidden memberships are retained.
- [x] 1.4 Guard stale editor saves against concurrent shortcut changes; verify a conflicting save requires refresh/retry without silently dropping the shortcut member or partially committing editor fields.

## 2. Shared Derived Collections

- [x] 2.1 Extend the shared smart-collection feed with Favorites using committed preferences and visible tracked games; verify owned/shared inclusion, hidden/absent exclusion, and Home/list count parity.
- [x] 2.2 Add Played recently with injected current instant/date/zone and supported Steam/session evidence; verify fourteen-day boundaries, future rejection, offline date/zone changes, and exclusion of undated/manual-only playtime.
- [x] 2.3 Extend derived order, labels, hide settings, and empty-list handling; verify the existing five rules and Completed disclosures remain unchanged and the new lists are read-only.

## 3. Functional UI Actions

- [ ] 3.1 Add the retained-state favorite heart to every tracked-game detail entry point; verify committed-state feedback, pending duplicate suppression, offline use, failure/retry, and Focus/XP independence.
- [ ] 3.2 Add labeled Library collection menus/picker in list and both grids; verify existing-membership labels, duplicate suppression, preserved achievement long-press selection, and no controls for wishlist-only matches.
- [ ] 3.3 Wire the empty picker to existing creation navigation; verify query, filters, density, and scroll survive picker/creation return under the current Library visit policy.
- [ ] 3.4 Add direct removal to custom overviews and retain normal detail navigation; verify only the chosen membership changes, surviving queue state is preserved, and derived overviews offer no removal control.

## 4. Backup Compatibility

- [x] 4.1 Export explicit favorite preference rows in manual backups and snapshots; verify true/false and absent-game preferences round-trip from one export snapshot.
- [x] 4.2 Validate and merge optional preference records in supported versions 1/2; verify omitted legacy sections preserve local preferences, invalid/duplicate keys fail before writes, and repeated import is idempotent.
- [x] 4.3 Preserve the existing identity warning and session-provenance rules; verify confirmed cross-account preference merge leaves configured credentials unchanged and rejected imports leave all data intact.

## 5. Presentation and Integrated Verification

- [ ] 5.1 Refine derived cards after functional actions work, using shared counts/rules and consistent artwork; verify hidden/empty cases, owned/shared examples, accessible labels, and large-font layout.
- [ ] 5.2 Exercise hearts, membership changes, derived lists, wishlist restrictions, and Library return in all densities on a device/emulator; record actual evidence and update affected screenshot baselines or justify unchanged goldens under repository rules.
- [ ] 5.3 Run focused unit/migration/backup tests, debug build, relevant lint, strict OpenSpec validation, and diff whitespace checks; record exact results and any device/visual limitations without marking unperformed checks complete.
