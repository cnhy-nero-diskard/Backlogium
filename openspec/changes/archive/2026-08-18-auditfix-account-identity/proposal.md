## Why

Changing the configured SteamID replaces credentials and nothing else. No table is
account-scoped, and no reset happens.

The consequence chain is specific. `SteamSyncWorker.kt:141-146` reads the existing profile
and the existing `Game` rows, then diffs the *new* account's `playtimeForever` against the
*previous* account's `lastPlaytime` baseline. Where the new account has played a shared game
more, the difference is synthesized as a session that never happened, credited to today, and
converted to XP and quest progress. Where the new account has played it less — the common
case — the delta is non-positive, so `lastPlaytime` stays high and future genuine playtime is
suppressed until it exceeds the old account's total. Sessions, achievements, daily progress,
collections, and the singleton profile then hold a permanent, unlabelled mixture of two
people's histories.

The spec makes this reachable by design. `onboarding-credentials` requires a credentials card
that "lets the user reopen the onboarding flow to change credentials at any time", and no
requirement anywhere states what happens to stored data when the identity behind it changes.
This is a genuine specification gap, not an implementation slip.

**And the project's existing normative stance points the other way.** `backup-restore`
requires that "Cross-account import is allowed with a warning" — the app deliberately permits
importing another account's backup after showing a mismatch warning, without blocking. So
mixing account data is currently *sanctioned* in one capability and *catastrophic* in another.
Whatever this change does, it has to reconcile those, and that reconciliation is the real work
here.

## What Changes

The mechanism depends on a product decision that is genuinely the owner's, not a technical
one. Both options are laid out in design.md; this proposal recommends one and scopes the work
for it.

**Recommended: detect and reset.** On a SteamID change, require explicit confirmation naming
what will be discarded, offer an export first, then clear derived and account-specific state
and re-baseline from the new account's current playtime as a first sync. One account's data on
the device at a time.

**The alternative: namespace everything.** Add an account dimension to games, sessions,
achievements, daily progress, collections, and the profile, keying all reads and writes by it.
It preserves both histories and allows switching back. It also means a migration touching
nearly every table, an account dimension threaded through every query and repository, and a
UI answer for which account is being viewed — for a scenario whose frequency in a
single-user personal app may be zero.

Either way:

- **Identity change is detected rather than silently applied.** Saving a SteamID different
  from the stored one becomes an explicit event with a defined consequence.
- **Diffing can never cross an identity boundary.** No baseline from one account is ever
  compared against a poll from another. This is the invariant that matters; the two options
  are just different ways of guaranteeing it.
- **The cross-account import allowance is reconciled**, so the two capabilities agree.

## Capabilities

### Modified Capabilities

- `onboarding-credentials`: define what happens to stored data when the configured SteamID
  changes — currently unspecified, which is why the code does nothing.
- `steam-sync`: require that a playtime baseline is only ever diffed against a poll from the
  same account, so a fabricated or suppressed session is structurally impossible.
- `backup-restore`: reconcile the cross-account import allowance with the chosen approach.
  Under reset-on-change, importing another account's backup and switching to that account
  should behave consistently rather than one path warning and the other wiping.

## Impact

**Under the recommended reset approach**

| Path | Change |
|---|---|
| `data/repo/CredentialsRepository.kt` | detect identity change on save |
| `ui/onboarding/OnboardingViewModel.kt` | confirmation step; export offer |
| `data/local/BacklogiumDatabase.kt` | transactional reset covering all account-derived tables |
| `data/local/entity/HltbData.kt` | drop the `CASCADE` foreign key so HLTB survives the reset |
| reset marker store | intent marker enabling a resumable cross-store reset |
| `work/SteamSyncWorker.kt` | re-baseline path after a reset |
| `openspec/specs/backup-restore/spec.md` | cross-account allowance reconciled |

**Under the namespace alternative**, add an account column plus migration to every table
listed in the Why, and an account parameter to every query and repository method that reads
them — a substantially larger change with a correspondingly larger migration risk.

**BREAKING under the recommended approach**: switching accounts discards local history. That
is the intended behaviour and it destroys data, so the confirmation must be explicit and the
export offer must work. This is the only `auditfix-*` change that deliberately deletes user
data, and it deserves proportionate care.

**Should land last.** It is the most invasive change on the audit list for the rarest
scenario, and it wants `auditfix-sync-write-integrity`'s transactional commit and
`auditfix-verification-coverage`'s migration tests already in place — the reset must be
crash-safe
for the same reason the sync must be, and a wipe-and-rebaseline that half-completes is worse
than the bug.

**Worth stating plainly**: if the owner establishes that the SteamID will never change on this
install, the correct outcome may be to close this change with a documented finding and a
guard that refuses a mismatched SteamID outright, rather than to build either mechanism. That
is a legitimate resolution and cheaper than both. It is not the same as leaving things as they
are, because today the app silently corrupts instead of refusing.

**Not addressed here**: multiple simultaneous accounts as a feature, and Family Sharing —
`add-family-shared-games` is a separate in-flight change and its interaction with account
identity should be considered there once this decision is made.
