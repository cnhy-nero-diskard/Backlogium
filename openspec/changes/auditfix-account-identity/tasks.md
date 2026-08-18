## 1. Make the product decision before writing anything

- [x] 1.1 Choose between design.md Decision 0's options: (A) refuse a mismatched SteamID outright, (B) detect and reset — recommended, or (C) namespace every table by account
- [x] 1.2 Establish whether the configured SteamID will ever plausibly change on this install; if it will not, option A is the correct outcome and the rest of this change collapses to section 2 plus the spec work
- [x] 1.3 If option A is chosen, narrow `onboarding-credentials`' "change credentials at any time" requirement to the API key and skip sections 4 through 7
- [x] 1.4 If option C is chosen, this task list does not apply — rewrite it around the account dimension and its migration before proceeding
- [x] 1.5 Confirm `auditfix-sync-write-integrity` and `auditfix-verification-coverage` have landed; the Room reset needs the same transactional discipline as the sync, and section 5 adds a migration

## 2. Detect the identity change

- [x] 2.1 In `CredentialsRepository`, compare an incoming SteamID against the stored one on save and return the identity-change condition rather than acting on it
- [x] 2.2 Ensure an API-key-only change takes no special path — key rotation is normal and safe, and the comparison is on SteamID alone
- [x] 2.3 Ensure first configuration, with no stored SteamID, proceeds without confirmation
- [x] 2.4 Keep the destructive response out of the repository — a save must not wipe the database as a side effect
- [x] 2.5 Test: API key changed alone triggers nothing; SteamID changed returns the condition without saving; first configuration saves directly

## 3. Guard the diffing boundary

- [x] 3.1 Ensure no code path can diff a stored baseline against a poll for a different account, independent of how the identity change is handled
- [x] 3.2 Add the regression test for the original defect: with account A's baselines stored, a poll for account B with lower totals must produce no session and no suppression
- [x] 3.3 Test: a poll for B with higher totals produces no fabricated session

## 4. Confirmation and export offer

- [x] 4.1 Add a confirmation step to the credentials flow in `ui/onboarding/OnboardingViewModel.kt` that names what will be discarded
- [x] 4.2 Offer an export before discarding, and verify the exported file is complete and importable — this is the user's only recovery path and it must actually work
- [x] 4.3 Ensure declining leaves both credentials and stored data untouched
- [x] 4.4 Test: declining is a complete no-op; the export offer produces a valid backup

## 5. Resolve the HLTB cascade before writing the reset

- [x] 5.1 Confirm the cascade: `HltbData.kt:31-35` foreign-keys `appId` to `games.appId` with `onDelete = ForeignKey.CASCADE`, so deleting games destroys HLTB rows — the original "delete games, keep HLTB" plan was self-defeating
- [x] 5.2 Choose per design.md Decision 2: (i) accept re-scraping, (ii) snapshot-and-restore, or (iii) drop the FK and make `hltb_data` standalone — recommended, since completion times are a property of a game title rather than of ownership
- [x] 5.3 If (iii), write the migration removing the foreign key and commit the exported schema; verify `Session`, `Achievement`, `CollectionMember`, `GameGenreCache`, and the diagnostics entities keep their cascades, which are correct
- [x] 5.4 Note that under (iii) removing a single game from the library also stops discarding its HLTB data — an incidental behaviour change worth stating in the commit message

## 6. Resumable reset and re-baseline

- [x] 6.1 Implement the Room reset as one transaction clearing games, sessions, daily progress, achievements and their rarity snapshots, collections, members, and the profile's XP, level, streaks, and backfill state
- [x] 6.2 Retain rule configuration and UI preferences; retain HLTB data per the option chosen in 5.2
- [x] 6.3 Clear `longestStreak`, with a comment explaining why the never-decreases invariant does not apply here: attributing one person's record to another is not preserving a fact
- [x] 6.4 Zero the profile's `lastSyncAt` so the next poll takes the existing baseline path at `SteamSyncWorker.kt:142-143`, reusing the specified first-sync behaviour rather than inventing a reset-specific one
- [x] 6.5 **Do not attempt to commit the credential change and the Room reset in one transaction** — `EncryptedCredentialStore` uses a Preferences DataStore plus Keystore, which shares no transaction with Room
- [x] 6.6 Implement the marker protocol from design.md Decision 2: write the intent marker first, run the idempotent Room reset, commit credentials, clear the marker
- [x] 6.7 Resume an incomplete reset on app start, before any sync can be enqueued or run
- [x] 6.8 Block polling while a reset marker is present, so no poll can diff against a half-applied change
- [x] 6.9 Test: all listed tables cleared, kept tables intact, `lastSyncAt` zeroed
- [x] 6.10 Test: each of the three crash points in design.md's table resolves correctly on the next start
- [x] 6.11 Test: the Room reset is idempotent when resumed
- [x] 6.12 Test: the first poll after a reset takes the baseline path and synthesizes no sessions
- [x] 6.13 Test: HLTB data survives a reset under the chosen option, or is documented as re-scraped under (i)

## 7. Reconcile the cross-account import allowance

- [x] 7.1 Extend the existing mismatch warning to state that the imported data belongs to a different account and will be merged with the current account's
- [x] 7.2 Confirm a cross-account import leaves the configured SteamID unchanged and triggers no account-change consequence
- [x] 7.3 Keep the import permitted — it is an existing deliberate allowance and no finding argues against it
- [x] 7.4 Test: cross-account import still warns and still proceeds, and does not trigger a reset

## 8. Verification and close-out

- [x] 8.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 8.2 Exercise a real account switch on-device: confirm the warning, take the export, verify the reset, and confirm the first sync baselines cleanly
- [ ] 8.3 Re-import the export taken during that switch and confirm the original account's data is recoverable — the destructive path is only acceptable if this works
- [ ] 8.4 Run the instrumented migration tests if any schema change was made
- [x] 8.5 Run `openspec validate auditfix-account-identity`
- [x] 8.6 Record in the commit message that this is the one audit fix that deliberately deletes user data, and that the export offer is the mitigation
