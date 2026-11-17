# Command patterns

Run from the repository root with Node 22. Uppercase tokens below are placeholders,
not executable values. Build argument arrays from validated inputs; never evaluate
strings from supplied reports or checkpoints. Keep actual SteamIDs out of public output.

## Preview, then execute

Agree on scope, mode, limits, credential access and the private working directory first.
The runtime reads `STEAM_WEB_API_KEY`; do not echo it or pass it as an argument. Never
copy a whole `local.properties` into a new worktree to obtain the key.

```text
node tools/hltb-dataset/refresh.mjs --help

node tools/hltb-dataset/refresh.mjs \
  --steam-id STEAMID64 --mode stale --max-age-days 180 \
  --max-requests 40 --max-run-seconds 180 \
  --state-dir tools/hltb-dataset/.local/UNIQUE-RUN \
  --dry-run --report tools/hltb-dataset/.local/UNIQUE-RUN/preview.json

node tools/hltb-dataset/refresh.mjs \
  --steam-id STEAMID64 --mode stale --max-age-days 180 \
  --max-requests 40 --max-run-seconds 180 \
  --state-dir tools/hltb-dataset/.local/UNIQUE-RUN \
  --output tools/hltb-dataset/.local/UNIQUE-RUN/candidate.json \
  --report tools/hltb-dataset/.local/UNIQUE-RUN/report.json
```

Those are example budgets, not an automatic policy or user consent. Repeat `--steam-id`
for multiple accounts and `--app-id` for restrictions. Omit `--max-age-days` outside stale
mode. Unmapped games need explicitly reviewed mappings, not title guessing. Avoid shell
`&&` chains that would skip inspection when a usable partial exits 2.

## Resume

Repeat the original options and state directory with `--resume RUN_ID`. Original SteamIDs
are still required by the CLI even though a compatible resume skips library rereads.
Resume needs a privately configured key too. Never expand budgets during resume or
claim it retries recorded failures. A new run may be needed, with fresh permission.

## Verify an existing pair (no network or key)

```text
node .claude/skills/hltb-dataset-refresh/scripts/verify-pair.mjs \
  tools/hltb-dataset/dataset.json \
  tools/hltb-dataset/.local/UNIQUE-RUN/candidate.json \
  tools/hltb-dataset/.local/UNIQUE-RUN/report.json
```

The first path must be the **original baseline**. Do not pass the candidate as both
baseline and candidate after applying it. Preserve/recover original baseline bytes and
recheck the destination baseline before application. The checker prints fixed status and
counts, not paths, titles or raw errors; a pass establishes consistency, not live provenance.

## Data publication

After separate apply/publication authorization and successful checks, stage only the
canonical dataset, commit and push the isolated data branch, then use:

```text
gh pr create --base AGREED-BASE --head DATA-BRANCH --title "chore: refresh HLTB dataset" --body-file SANITIZED-BODY
```

No implicit `master` override: keep an explicitly chosen stacked base. Never merge,
tag or release as part of creating the PR. Master's dataset workflow can release data
automatically after merge, so disclose that consequence before a later merge decision.


## Discovery → exact-pair review → direct fetching

Each network stage needs its own scoped authorization unless already granted. Budgets here
are examples. Default discovery is primary-only; add `--broader` only when explicitly allowed.

```text
node tools/hltb-dataset/discover.mjs --help
node tools/hltb-dataset/discover.mjs --steam-id STEAMID64 --app-id APPID --dry-run
node tools/hltb-dataset/discover.mjs --steam-id STEAMID64 --app-id APPID \
  --max-requests 30 --max-run-seconds 90 \
  --output tools/hltb-dataset/.local/discovery/UNIQUE-RUN/review.json \
  --report tools/hltb-dataset/.local/discovery/UNIQUE-RUN/report.json

node tools/hltb-dataset/discover.mjs \
  --inspect tools/hltb-dataset/.local/discovery/UNIQUE-RUN/review.json
```

Inspection is offline, needs no key/account, and recomputes current decisions. It accepts
only an optional separate `--report` alongside `--inspect`. Existing review outputs are
never overwritten. Every result starts pending. Edit only exact-pair `decision` after human
approval/rejection; no title match or preview automatically approves. Keep files private:
owned app IDs and review decisions disclose ownership. No discovery resume is supported.

```text
node tools/hltb-dataset/refresh.mjs --steam-id STEAMID64 \
  --review-candidates tools/hltb-dataset/.local/discovery/UNIQUE-RUN/review.json \
  --max-requests 30 --max-run-seconds 90 --dry-run

node tools/hltb-dataset/refresh.mjs --steam-id STEAMID64 \
  --review-candidates tools/hltb-dataset/.local/discovery/UNIQUE-RUN/review.json \
  --max-requests 30 --max-run-seconds 90 \
  --state-dir tools/hltb-dataset/.local/fetch-UNIQUE-RUN \
  --output tools/hltb-dataset/.local/fetch-UNIQUE-RUN/candidate.json \
  --report tools/hltb-dataset/.local/fetch-UNIQUE-RUN/report.json

node .claude/skills/hltb-dataset-refresh/scripts/verify-pair.mjs \
  tools/hltb-dataset/dataset.json \
  tools/hltb-dataset/.local/fetch-UNIQUE-RUN/candidate.json \
  tools/hltb-dataset/.local/fetch-UNIQUE-RUN/report.json
```

Stop before apply/publication and present the verified candidate. Only approved in-scope pairs
enter refresh; preview lengths never become final observations. Changed review bytes (including
withdrawn approvals) or a stale baseline block the old plan. New runs/reconciliations need scoped
authorization; never repair hashes. Shared IDs are read once, and actual canonical aliases are
reported without admitting rejected pairs. Reported review app/pair counts remain separate from
unique-HLTB fetch failures/no-lengths/unattempted and unmapped apps. Schema-v1 global age does not
establish freshness of untouched rows. Verification establishes consistency, not live provenance.

Alternatively use `--reviewed-mappings REVIEWED-CONTRIBUTION` (schema v1), including a reviewed
Android Match Center export. Do not combine both input options; export format alone is not proof
of exact human approval. Keep a custom review in `.local/reviews/` beside `.local/fetch-state/`
and `.local/outputs/`; never in `runs/` or at `history.json`.
