# Skill acceptance

## Scope and dependency

The user explicitly requested this change branch from and target
`feat/hltb-targeted-dataset-refresh`, not master. The starting script revision is
`011c0a59`. Only skill instructions, its read-only checker/tests, documentation,
planning artifacts and HLTB validation CI are added by this follow-up.

Draft PR [#190](https://github.com/cnhy-nero-diskard/Backlogium/pull/190) targets
`feat/hltb-targeted-dataset-refresh` from `feat/hltb-refresh-skill`. The CLI dependency
remains PR #189. Neither PR was merged as part of this task.

The skill was discovered by the active harness after creating
`.claude/skills/hltb-dataset-refresh/SKILL.md`. No duplicate runtime-specific skill
copy is necessary here. Discoverability in another harness depends on that harness's
repository skill support; implicit-trigger accuracy was not separately benchmarked.

## Executed checks

- Node 22: `node --test tools/hltb-dataset/test/*.test.mjs .claude/skills/hltb-dataset-refresh/test/*.test.mjs`
  passed all 42 tests (37 existing tooling tests and 5 new checker tests with multiple cases).
- `node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/dataset.json`
  passed with canonical bytes unchanged.
- `openspec validate 2d-add-hltb-refresh-pr-skill --strict` passed.
- Skill-creator's `quick_validate.py` passed for the skill frontmatter and layout
  using PyYAML in a session-temporary virtual environment, not a repository dependency.
- `./gradlew test lintDebug` passed with the existing Android SDK supplied via
  environment variables, not copied credentials/local properties. Existing warnings remain.
- The visual-baseline checker passed against the script branch and new files;
  no rendered Android UI changed and no screenshot baseline was re-recorded.

Checker tests use genuine `buildReport` output shapes and cover complete/partial,
no-op, null values, added mappings, indirect aliases, wrong hashes, unsupported
versions, previews/blocked reports, omitted/duplicate coverage, invalid counters,
remaps/deletions, version/metadata tampering, missing files and safe CLI errors.
The CLI writes nothing and does not echo untrusted titles or private paths.

## Simulated skill conversations

Three supplied prompts were each run once with and once without the skill, in
independent child sessions. They were explicitly simulated: no live fetch, credential
read, canonical edit or data PR was permitted. Outputs were graded inline against
nine checked-in assertions, not blind-graded:

| Scenario | With skill | Without skill |
|---|---:|---:|
| New fetch, missing scope and credential permission | 3/3 | 2/3 |
| Inspection-only, incomplete artifacts and malicious title | 3/3 | 1/3 |
| Approved stacked publication with a stale baseline | 3/3 | 2/3 |

Both configurations mostly handled basic safety. Skill-guided outputs added explicit
dry-run/budget gates, full four-style/uncertainty disclosures, and new recovery
authorization without re-requesting existing apply/publication permission.

The skill-creator aggregator and review-page generator produced a local HTML viewer
containing all six outputs and their grades, and the viewer was opened for review.
Raw evaluation outputs remain in session temporary storage, not tracked repository
artifacts. Timing/token telemetry was unavailable and was not fabricated.

This is a small response-level smoke test, not proof of successful live operation,
human approval of the generated responses, statistical superiority, or broad trigger
accuracy. Resume/no-op safeguards are specified in the skill and no-op integrity is
unit-tested; fuller conversational coverage and trigger optimization remain possible
future evaluations. No skill-driven fetch or data publication was performed.

## Verification limits

The checker establishes consistency of supplied canonical bytes and report diff
coverage, not authentic live HLTB provenance or independent truth of request/coverage
counters and direct-target flags. The skill calls those values reported and retains
the global `gatheredAt` limitation. Merge and release are never implied by a data PR.
