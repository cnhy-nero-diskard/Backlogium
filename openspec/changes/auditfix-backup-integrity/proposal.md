## Why

Backup and restore is the app's answer to unrecoverable data loss, and it is currently
the least defended write path in the codebase. Five findings, and the first two are the
ones that matter:

- **Import is not atomic.** The merge writes games, sessions, daily progress, HLTB data,
  achievements, collections, members, and profile state sequentially with no transaction —
  there are only five `@Transaction` sites in the entire app and none of them is here. A
  malformed late record, a foreign-key problem, cancellation, or process death leaves the
  database holding a mixture of pre-import and imported state. The user asked to restore a
  backup and got a hybrid that corresponds to no point in time.
- **Import validates almost nothing before it starts writing.** Parsing checks that the
  JSON decodes and the format version is supported; semantic problems — invalid dates,
  impossible timestamps, broken relationships, malformed IDs — are discovered at merge
  time, which is to say after mutation has begun. Combined with the above, a bad file
  does not fail cleanly, it fails halfway.
- **Import has no size bound.** The selected URI is read fully into memory before
  decoding. Normal backups are small; a large or hostile file is an out-of-memory failure
  during the operation the user chose specifically to protect their data.
- **Export is not snapshot-consistent.** Settings, games, achievements, sessions, daily
  progress, HLTB state, profile, collections, and members are read sequentially. A sync
  landing midway produces a file combining games from before it with sessions and
  aggregates from after — every individual read succeeded, and the result describes no
  actual state the app was ever in. That file is then a *restore source*.
- **The spec contradicts itself about rarity snapshots**, inside a single requirement.
  `backup-restore` says: "When both the local database and the imported file have a
  snapshot for the same achievement, the snapshot associated with the **earlier unlock
  timestamp** SHALL be retained." Its own first scenario then says: "**THEN** the locally
  stored snapshot and its unlock timestamp are retained, and the imported value is
  discarded." If the import's unlock is earlier, those instructions disagree. The
  implementation follows local-wins. Until this is settled, any future "fix" here is as
  likely to reverse correct behaviour as to correct wrong behaviour.

That last one is why the spec item ships with this change rather than separately: making
the merge transactional means committing to *what* the merge does, and one of the things
it does is currently undefined.

## What Changes

- **Import validates completely before it writes anything.** A full structural and
  semantic preflight over the parsed backup, rejecting malformed files with a message
  naming the problem, while the database is still untouched.
- **Import becomes one transaction.** Every table the merge writes commits together. A
  restore either happened or it did not.
- **Import enforces a size bound** before materializing the payload, failing with a clear
  message rather than an out-of-memory error.
- **Export reads from one consistent point in time**, so a concurrent sync cannot produce
  a hybrid file.
- **The rarity-snapshot rule is disambiguated in the spec** — one rule, no contradicting
  scenario, with the reasoning recorded so it is not re-litigated.

## Capabilities

### Modified Capabilities

- `backup-restore`: resolve the rarity-snapshot contradiction to a single rule; add
  requirements that import is atomic, fully validated before mutation, and size-bounded,
  and that export is snapshot-consistent.

## Impact

| Path | Change |
|---|---|
| `data/backup/BackupMergeEngine.kt` | preflight validation; merge wrapped in one transaction |
| `data/backup/BackupRepository.kt` | size bound before read; consistent-read export |
| `data/backup/BackupFile.kt` | validation surface for parsed structures |
| `data/local/BacklogiumDatabase.kt` | transactional entry point for the merge |
| `openspec/specs/backup-restore/spec.md` | contradiction resolved (via delta) |

**BREAKING (behavioural)**: files that currently import partially will be rejected
outright. That is the intent — a clean rejection is strictly better than a half-restore —
but a user who previously got "most of" a slightly-corrupt backup will now get nothing
from it. Design covers whether to offer a diagnostic listing what failed validation, and
recommends it.

**No dependency on the other `auditfix-*` changes.** This path is self-contained and can
proceed in parallel with the sync work. It should still land after
`auditfix-verification-coverage` if it touches DAO surfaces, but the merge transaction
alone needs no migration.

**Relates to `auditfix-secrets-and-packaging`**, which moves the automatic snapshot
directory. Both touch `data/backup/`; sequence them to avoid a conflict rather than
combining them, since the concerns are unrelated.

**Not addressed here**: encrypting backup files, and whether cross-account import should
remain permitted. The latter is a live question raised by `auditfix-account-identity`, and
`backup-restore` currently has an explicit requirement permitting it — that requirement is
deliberately left alone here so the two changes do not both try to own it.
