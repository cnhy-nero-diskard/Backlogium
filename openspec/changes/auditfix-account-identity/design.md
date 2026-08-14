# Design

## Context

```
  account A configured, library synced
     games.lastPlaytime = A's totals
     sessions, achievements, daily progress, collections, profile = A's history
        │
        ▼
  user edits credentials → SteamID = B          (permitted: onboarding-credentials
        │                                        "change credentials at any time")
        ▼
  next poll: getOwnedGames(B) → B's playtimeForever
        │
        ├─ B played a shared game MORE  → positive delta → fabricated session,
        │                                  credited to today, converted to XP
        │
        └─ B played it LESS (typical)   → non-positive delta → lastPlaytime stays
                                           at A's total → B's real future playtime
                                           suppressed until it passes A's
```

Nothing in the code is checking, because nothing in the spec says to.

## Decision 0: This is a product decision, and it should be made before any code

Three viable resolutions, in ascending cost:

| | Approach | Effort | Preserves both accounts | Migration |
|---|---|---|---|---|
| **A** | Refuse a mismatched SteamID outright | very low | no — cannot switch at all | none |
| **B** | Detect and reset (recommended) | moderate | no — export first | none |
| **C** | Namespace every table by account | high | yes | every table |

**A is a legitimate answer and should not be dismissed.** For a single-user personal app where
the SteamID will never change, a guard that refuses the change with a clear message is strictly
better than today's silent corruption, costs almost nothing, and can be upgraded to B or C
later if the need appears. It does contradict `onboarding-credentials`' "change credentials at
any time" requirement, so that requirement would narrow to the API key — which is the field
that actually rotates.

**C's cost is concentrated where it hurts.** An account column on games, sessions,
achievements, daily progress, collections, members, and the profile means every query gains a
parameter, every repository method gains an argument, and the migration has to invent an
account value for every existing row. The upside — switching back and forth with both histories
intact — is a feature nobody has asked for.

**B is recommended** as the balance: it makes the failure mode impossible, it is honest with the
user, and it does not require an account dimension.

## Decision 1: Detection at the credential boundary

`CredentialsRepository` is where a save happens and is the only place that can compare the
incoming SteamID against the stored one. Detection belongs there; the *response* does not —
a repository should not wipe the database as a side effect of a save.

```
  save(apiKey, steamId)
        │
        ├─ stored steamId absent        → configure (first run)
        ├─ stored steamId == incoming   → update API key only, no other effect
        └─ stored steamId != incoming   → return IdentityChanged, do not save yet
                                              │
                                              ▼
                                    caller confirms with the user,
                                    offers export, then commits
```

Returning the condition rather than acting on it keeps the destructive step under the UI's
control, where the confirmation lives. A save that silently wipes is how you get a support
question you cannot answer.

**The API-key-only case matters.** Rotating the Steam API key is a normal, safe operation and
must not trigger any of this. The comparison is on SteamID alone.

## Decision 2: What a reset clears, and what survives

| Cleared | Kept |
|---|---|
| games (all rows) | rule configuration |
| sessions | UI preferences, display density, sort |
| daily progress | HLTB data — but see the cascade problem below |
| achievements + rarity snapshots | |
| collections and members | |
| profile: XP, level, streaks, longest streak, backfill state | |

**The cascade problem: "delete games, keep HLTB" is currently impossible.** `HltbData.kt:31-35`
declares `appId` as a foreign key to `games.appId` with `onDelete = ForeignKey.CASCADE`.
Deleting the games rows destroys the HLTB rows with them. The same applies to `Session`,
`Achievement`, `CollectionMember`, `GameGenreCache`, and the diagnostics entities — all
cascade from `games`. For every table except HLTB that is exactly what we want.

Three ways out:

| | Approach | Cost |
|---|---|---|
| **i** | Accept the loss; re-scrape after the new account syncs | a full-library re-scrape against a service this app treats carefully |
| **ii** | Snapshot HLTB rows, delete, re-insert after the first sync for appIds the new account owns | restore is deferred until after a sync, so the reset is no longer a single step |
| **iii** | Drop the FK; make `hltb_data` standalone, keyed by `appId` | a migration |

**Chosen: iii.** It is the only option that is correct on its own merits rather than as a
workaround. HowLongToBeat completion times are a property of a game *title*, globally — they
do not depend on whether this user currently owns it. The cascade encodes "this data is about
the user's copy of the game", which was never true. Today it also means removing a game from
the library silently discards scraped data about it, which is incidental behaviour nobody
chose.

Option ii deserves a specific rejection: it makes the reset a two-phase operation spanning a
network sync, which directly contradicts the atomicity this change needs. Trading a crash-safe
reset for avoided re-scraping is the wrong trade.

If the owner prefers to keep this change small, **i is acceptable** — the outcome is a slower
first experience on the new account, not incorrect data. What is not acceptable is the
original text, which claimed HLTB survives while specifying a deletion that destroys it.

**`longestStreak` is cleared.** It is a high-water mark whose never-decreases invariant exists
so that *recomputation* cannot erase a record. A different person's device history is not the
same situation — keeping A's record on B's profile is not preserving a fact, it is attributing
one to the wrong person. This is the one place where clearing it is correct, and it is worth
the comment.

**The Room reset must be one transaction**, for the same reason import must be: a half-cleared
database with A's sessions and B's games is worse than either account's data alone.

**But the credential write cannot join it.** `EncryptedCredentialStore` keeps credentials in a
Preferences DataStore (`preferencesDataStore(name = "credentials")`) encrypted with an Android
Keystore key. Room and DataStore share no transaction, so "apply the credential change and the
reset atomically" is not implementable and must not be specified.

**Chosen: a resumable reset with an intent marker**, following the write-ahead pattern
`GamificationUpdater.persistWithinProtocol` already establishes in this codebase (Decision 4a
of `auditfix-sync-write-integrity`).

```
  1. write marker: { resetPendingFor: newSteamId }        ← DataStore
  2. Room reset transaction (clear tables, zero lastSyncAt)
  3. commit new credentials                                ← DataStore + Keystore
  4. clear marker

  on app start: marker present? → resume from step 2 (idempotent) and finish
```

Crash-consistent states, all recoverable:

| Crash after | Observed state | Resolution on next start |
|---|---|---|
| 1 | old credentials, old data, marker set | resume: reset, then commit credentials |
| 2 | old credentials, empty data, marker set | resume: commit credentials |
| 3 | new credentials, empty data, marker set | resume: clear marker |

**Step 2 must be idempotent** so resuming is always safe, and **the marker must be written
before the reset**, not after — a marker written afterwards cannot describe a crash that
happened during the reset.

**The dangerous window is between 2 and 3**: data cleared, credentials still naming the old
account. A poll landing there would re-baseline the *old* account into an empty library. The
resume must therefore run before any sync can start, and the diffing-boundary guard in
section 3 of the task list is the second line of defence — which is why that guard is
specified independently of how the reset is implemented.

**Re-baseline, do not zero.** After a reset the next poll must behave as a first sync —
`SteamSyncWorker.kt:142-143` already has this path (`isBaseline` when `lastSyncAt == 0L`, using
`differ.baseline(polls)`), and `steam-sync`'s "First-sync baselining" requirement already
covers it. Clearing the profile's `lastSyncAt` reuses existing, specified behaviour rather than
inventing a reset-specific one. That is the cheapest correct answer available and it should be
used.

## Decision 3: Reconciling the cross-account import allowance

`backup-restore` requires that a backup whose SteamID differs from the signed-in account
imports after a warning, without blocking. Under reset-on-change these two paths would
contradict each other:

- switch to account B → local data wiped
- import B's backup while signed in as A → B's data merged into A's, warned but permitted

**Chosen**: keep the import allowance, and be explicit about why it is different.

The distinction is real. An import is the user deliberately bringing specific data in, having
been told whose it is — a considered act with an informed warning. An identity change is a
credentials edit whose data consequences the user has no reason to anticipate. Warning is the
right response to the first; confirmation-plus-reset is the right response to the second.

What must change is that the two stop being silently inconsistent. The import warning should
say what it actually means — that the imported data belongs to a different account and will be
merged with the current account's — and the `backup-restore` requirement should note the
relationship to identity change so the next reader does not find them contradictory.

**Rejected: blocking cross-account import.** It is an existing, deliberate, specified
allowance, and the audit raises no finding against it. Removing a working feature to make two
specs rhyme is not a fix.

## Decision 4: If option A is chosen instead

Scope shrinks to: compare on save, refuse a mismatch with a message explaining that the
library is tied to the configured account and that switching requires clearing data; narrow
`onboarding-credentials`' editing requirement to the API key; add the diffing-boundary
invariant to `steam-sync` anyway, since it is worth stating regardless of how it is enforced.

Roughly a day's work versus a week, and it eliminates the same defect. The cost is that a user
who genuinely needs to switch has no in-app path and must clear app data.

## Testing strategy

- API key changed, SteamID unchanged → no reset, no confirmation, sync unaffected
- SteamID changed → confirmation required; declining leaves credentials and data untouched
- confirmed change → all listed tables cleared, kept tables intact, `lastSyncAt` zeroed
- reset interrupted → database is fully pre-reset or fully post-reset, never mixed
- first poll after reset takes the baseline path and synthesizes no sessions
- **the original defect, as a regression test**: with A's baselines stored, a poll for B whose
  totals are lower produces no session and no suppression once the guard is in place
- HLTB data survives a reset
- cross-account import still warns and still proceeds

## What this change deliberately does not do

- Does not support multiple simultaneous accounts.
- Does not add an account dimension to the schema, under the recommended approach.
- Does not block cross-account import. Decision 3.
- Does not address Family Sharing's interaction with account identity — `add-family-shared-games`
  is in flight and should absorb that once this decision is settled.
