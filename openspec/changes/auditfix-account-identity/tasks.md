## 1. Make the product decision before writing anything

- [ ] 1.1 Choose between design.md Decision 0's options: (A) refuse a mismatched SteamID outright, (B) detect and reset — recommended, or (C) namespace every table by account
- [ ] 1.2 Establish whether the configured SteamID will ever plausibly change on this install; if it will not, option A is the correct outcome and the rest of this change collapses to section 2 plus the spec work
- [ ] 1.3 If option A is chosen, narrow `onboarding-credentials`' "change credentials at any time" requirement to the API key and skip sections 4 through 6
- [ ] 1.4 If option C is chosen, this task list does not apply — rewrite it around the account dimension and its migration before proceeding
- [ ] 1.5 Confirm `auditfix-sync-write-integrity` and `auditfix-verification-coverage` have landed; a reset must be atomic for the same reason the sync must be, and a half-completed wipe is worse than the defect

## 2. Detect the identity change

- [ ] 2.1 In `CredentialsRepository`, compare an incoming SteamID against the stored one on save and return the identity-change condition rather than acting on it
- [ ] 2.2 Ensure an API-key-only change takes no special path — key rotation is normal and safe, and the comparison is on SteamID alone
- [ ] 2.3 Ensure first configuration, with no stored SteamID, proceeds without confirmation
- [ ] 2.4 Keep the destructive response out of the repository — a save must not wipe the database as a side effect
- [ ] 2.5 Test: API key changed alone triggers nothing; SteamID changed returns the condition without saving; first configuration saves directly

## 3. Guard the diffing boundary

- [ ] 3.1 Ensure no code path can diff a stored baseline against a poll for a different account, independent of how the identity change is handled
- [ ] 3.2 Add the regression test for the original defect: with account A's baselines stored, a poll for account B with lower totals must produce no session and no suppression
- [ ] 3.3 Test: a poll for B with higher totals produces no fabricated session

## 4. Confirmation and export offer

- [ ] 4.1 Add a confirmation step to the credentials flow in `ui/onboarding/OnboardingViewModel.kt` that names what will be discarded
- [ ] 4.2 Offer an export before discarding, and verify the exported file is complete and importable — this is the user's only recovery path and it must actually work
- [ ] 4.3 Ensure declining leaves both credentials and stored data untouched
- [ ] 4.4 Test: declining is a complete no-op; the export offer produces a valid backup

## 5. Atomic reset and re-baseline

- [ ] 5.1 Implement the reset as one transaction clearing games, sessions, daily progress, achievements and their rarity snapshots, collections, members, and the profile's XP, level, streaks, and backfill state
- [ ] 5.2 Retain rule configuration, UI preferences, and HLTB data — the latter is a property of the game rather than the account and is expensive to rebuild
- [ ] 5.3 Clear `longestStreak`, with a comment explaining why the never-decreases invariant does not apply here: attributing one person's record to another is not preserving a fact
- [ ] 5.4 Zero the profile's `lastSyncAt` so the next poll takes the existing baseline path at `SteamSyncWorker.kt:142-143`, reusing the specified first-sync behaviour rather than inventing a reset-specific one
- [ ] 5.5 Apply the credential change and the reset in the same transaction, so no state exists where credentials name one account and data reflects another
- [ ] 5.6 Test: all listed tables cleared, kept tables intact, `lastSyncAt` zeroed
- [ ] 5.7 Test: an interrupted reset leaves the database fully pre-reset or fully post-reset, never mixed
- [ ] 5.8 Test: the first poll after a reset takes the baseline path and synthesizes no sessions
- [ ] 5.9 Test: HLTB data survives a reset

## 6. Reconcile the cross-account import allowance

- [ ] 6.1 Extend the existing mismatch warning to state that the imported data belongs to a different account and will be merged with the current account's
- [ ] 6.2 Confirm a cross-account import leaves the configured SteamID unchanged and triggers no account-change consequence
- [ ] 6.3 Keep the import permitted — it is an existing deliberate allowance and no finding argues against it
- [ ] 6.4 Test: cross-account import still warns and still proceeds, and does not trigger a reset

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 7.2 Exercise a real account switch on-device: confirm the warning, take the export, verify the reset, and confirm the first sync baselines cleanly
- [ ] 7.3 Re-import the export taken during that switch and confirm the original account's data is recoverable — the destructive path is only acceptable if this works
- [ ] 7.4 Run the instrumented migration tests if any schema change was made
- [ ] 7.5 Run `openspec validate auditfix-account-identity`
- [ ] 7.6 Record in the commit message that this is the one audit fix that deliberately deletes user data, and that the export offer is the mitigation
