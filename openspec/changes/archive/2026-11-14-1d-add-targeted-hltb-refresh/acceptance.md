# Refresh tool acceptance and 2d handoff

Accepted on 2026-10-03 with Node v22.21.0. Tool version 1, public reportVersion 1,
private state/history version 1. Implementation revision:
`8f2e8c44ac1e47e46e5e30243e7dfce398fe48f9` (built on `11f836f5`).

## Offline validation

All 37 tests passed. These commands ran from the repository root:

```text
node --test tools/hltb-dataset/test/*.test.mjs
node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/dataset.json
node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/.local/acceptance/verified-candidate.json
node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/.local/acceptance/resume-candidate.json
openspec.cmd validate 1d-add-targeted-hltb-refresh --strict
git diff --check
```

Tests cover arguments, private path/symlink/hard-link guards, readable/empty/unavailable
libraries, union/intersection, modes and evidence fingerprints, scope-only reviewed mappings,
global correspondence conflicts, ID and seconds validation, timeout including stalled bodies,
serial starts, transient retries/Retry-After/budgets, shared aliases, null/failure retention,
history, incompatible resume inputs, interruption recovery, read-only dry runs, source changes,
original-time merge precedence, report accounting and public error privacy. CI uses the same
offline glob and canonical validation; it needs no Steam credentials or network.

## Authorized live scope and observations

The user supplied three target accounts and authorized loading their existing local Steam key
into the process environment. Neither the key nor account IDs are recorded here, in tracked
fixtures, or in public candidate/report output. Live files and checkpoints remain ignored under
`tools/hltb-dataset/.local/acceptance/`; this is local evidence, not a published data update.

- Full-scope dry run: three readable libraries, 466 deduplicated app IDs, 270 reviewed unique
  HLTB IDs, three Steam attempts and no HLTB attempts. Unmapped games made the plan partial.
  No candidate, checkpoint or history writes occurred; an explicitly requested report was saved.
- Bounded dry run: app restriction 220/400/620, three readable libraries, three selected apps
  and three unique entries, exit 0, no HLTB fetches. Plan report SHA-256:
  `b9c708a72fbfc127376fdceb38b5560bd604a29f6cbd68f78696f8a9ef4b237c`.
- Separate-output refresh: same restriction, refresh-all, maximum eight attempts and 90 seconds.
  Exit 0/complete, six attempts (three Steam, three HLTB), three usable observations, no retries,
  failures or budget exhaustion. Candidate version 3 from baseline version 2.
- Current direct-page shape is `props.pageProps.game.data.game[]`. The first bounded run
  rejected that wrapper safely; it produced byte-identical retained data. Parser support was
  corrected and the successful run repeated. The sanitized live Portal 2 fixture contains only
  public game identity/name and the four seconds fields, excluding reviews or unrelated payload.
- Live identity/unit check: requested HLTB 7231 identified Portal 2. Seconds
  30883/49552/82580/38263 convert by nearest-minute rounding to 515/826/1376/638.
  Offline regression verifies this captured wrapper and rejects a requested-ID mismatch.

Bound file hashes for the complete refresh:

| Artifact | SHA-256 |
|---|---|
| Canonical baseline | `9d0e61cd09341b12daf547669a7b70baa1a1f22d89d5f22182f3ec997d0e88e8` |
| Separate candidate | `81c670ec17465afc0b1f9bcc704cc3aa4f629e2548ce67b4df3c8fbb730ce394` |
| Public report | `1a8cab6964d1c7467110d5ef5d1f6089a38f0fdc6a435ffcf7b7a01bb2d607bd` |

## Partial failure and resume

An injected interruption followed the first live successful checkpoint (HLTB 4248). Resume
kept that observation's exact saved timestamp and performed no Steam re-read or repeat of 4248.
HLTB 7230 received deterministic 503 responses on all three attempts; 7231 was fetched live
successfully. This injection exercises partial execution without depending on an upstream outage.

The run reported partial: two usable observations, one sanitized server-failed entry, two
retries, eight cumulative attempts and four resume attempts. Its failed tuple remained identical
to the baseline and no successful history was created for it. A subsequent invocation of the
actual CLI with `--resume` returned exit 2 and performed zero additional attempts. Original
successful timestamps remained unchanged. Offline tests additionally cover report-write crashes,
failed/no-lengths retention of prior evidence and incompatible resume rejection.

| Artifact | SHA-256 |
|---|---|
| Partial candidate | `bc7b29937861d71cab513b45d6e5d5abd15b6d3acebaf21d08b129aa98f2beaa` |
| Report after CLI resume | `e5f13fcad72264d0a878f6e91c6570f19b08303253fa9b1f1be2e6f60e2db9b9` |

Source bytes stayed identical throughout, with the baseline hash above. Both candidates passed
the canonical validator. Public candidate/report/stdout artifacts were scanned for the actual
key and all three supplied account IDs, with no matches. Secrets stayed in the process only.

## Issue #162 tool criteria trace

| Criterion | Implementation / evidence |
|---|---|
| Account-scoped deduplicated libraries | `refresh-targets.mjs`; mixed-library fixtures and live three-library dry runs |
| Missing/stale/all, app restriction, dry run, resume | CLI help/options; mode/history fixtures; bounded plan and CLI resume |
| Selected scope only | Library intersection fixtures; three-app live restriction; no catalog requests |
| Reviewed mappings and reviewable gaps | Global conflict/scope-only intake fixtures; full-scope unmapped report |
| Existing schema, integer/null/canonical/version rules | Existing merger exports; canonical checks and original-time/null/redundant tests |
| Partial failures and retry/evidence retention | Budget/Retry-After fixtures; live plus injected 503 interrupted/resumed run |
| Credentials/privacy and disclosure | Environment-only key; allowlisted reports and adversarial error tests; README/FORMAT limitations |
| Agent PR skill and invocation-based publication | Owned by `2d`; no skill implementation or data PR was performed in `1d` |

## Compatibility and dependent-change gate

Schema-v1 `gatheredAt` remains global merge metadata. Android consumers can assign that age to
untouched/failed imported rows; the tool cannot encode per-row freshness in this schema. Only
local evidence and report accounting describe actual per-entry fetches. A consumer/schema
migration is outside this change. No canonical dataset, app, release or issue state was changed.

Tool implementation/offline/live acceptance is complete. Before implementing `2d`, its task 1.1
also requires the supported CLI/report contract on the intended data-PR base. The default remote
base is `origin/master`, verified at `d46a711caa8f0b6b1eb6a3dc77eacebda7017a55`; it lacks
`refresh.mjs` and `refresh-report.mjs`. Thus the base-availability gate remains unmet. The
`2d` design explicitly forbids substituting the invoking feature branch when this base lacks
the tool. Land the tool on the intended base before continuing `2d`; merging or choosing a
different publication base is outside this autoship implementation run.
