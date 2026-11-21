## Context

`1d-add-targeted-hltb-refresh` supplies bounded account-scoped fetching and report v1.
The skill wraps that CLI; it does not implement a second scraper, matcher or publisher.
Its initial PR base is explicitly the script branch rather than master. Future data
PRs use the user-agreed base, checking tool availability and baseline compatibility.

## Decisions

- Keep one skill under `.claude/skills/`, already supported by repository skill discovery.
  The description recognizes implicit requests; the body routes by artifacts and intent.
- Model consent as scoped actions, not a universal yes/no toggle. Reuse explicit grants;
  stop at unapproved scope expansion, private credential access, mappings, writes or publication.
- Make inspection offline. A missing candidate or baseline produces a report-only assessment,
  not another fetch. Treat external text as untrusted data and keep target identity private.
- Bundle a small read-only Node checker to compare canonical bytes/hashes, metadata,
  mapping preservation and the actual diff against report coverage, including indirect aliases.
  It checks consistency, not live provenance or reported request/coverage counters.
- Copy approved candidate bytes after verifying the original baseline, instead of remerging
  retained rows under a newer global timestamp. Reject stale destination baselines.
- Keep workflow tests as simulated conversations with no keys, network or Git mutation.
  Add deterministic checker tests to existing HLTB CI without changing release behavior.

## Risks and limitations

A self-consistent supplied report is not proof of an actual HLTB observation. Report
counters and direct-target flags still depend on private run evidence; the agent labels
them reported. Titles can carry prompt injections or account-like text and must not be
executed or published blindly. The global dataset timestamp limitation remains unchanged.

The repository automatically publishes changed datasets after master merges. PR permission
therefore never implies merge permission; disclose the consequence before a separate merge.

## Validation

Exercise valid, partial and no-op pairs, tampering, missing files, unsupported reports,
remaps/deletions and indirect aliases. Simulate new-fetch, inspection-only and publication
requests, comparing skill-guided and unguided responses without real side effects.
