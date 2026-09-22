---
name: openspec-worktree-operator
description: Create and manage isolated Git worktrees for autonomous OpenSpec implementation with autoship commit-and-push. Use when Cline should start a branch worktree, apply an OpenSpec proposal in an isolated worktree, inspect worktree status, prepare a handoff, create a draft PR, or clean up a completed OpenSpec worktree.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(openspec:*), Bash(powershell:*)
license: MIT
compatibility: Requires Git and PowerShell; gh and the openspec CLI are optional.
metadata:
  author: openspec
  version: "1.1"
  provider: cline
---

# OpenSpec Worktree Operator (Cline)

Use this skill after Cline activates it automatically or after the user selects
`/openspec-worktree-operator` from the skill picker. Treat the user's remaining
text as the command payload.

When this skill needs another project workflow, use Cline's interface:

- Apply a proposal with `/opsx-apply <proposal>` inside the new worktree.
- Autoship checkpoints inside the new worktree: commit AND push each coherent
  increment to `origin/<branch>` without further prompts once a `start`
  payload with a proposal was given.
- Use the `gh` CLI for draft PR work; do not assume a GitHub connector is
  available to Cline.

Activating this skill alone is not permission to commit, push, or remove
anything. A `start branch <branch> apply proposal <proposal>` payload IS
permission to commit and push inside the new worktree to `origin/<branch>`
only — never in the original checkout, never to a protected branch, never
without the checkpoint gates below.

## Command Payloads

- `start branch <branch> apply proposal <proposal> [--dry-run] [--no-push]`: create a sibling worktree
  from the default base, then autoship the proposal there.
- `start branch <branch> from current apply proposal <proposal> [--dry-run] [--no-push]`: use the
  original checkout's `HEAD` as the base.
- `start branch <branch> from <base> [--dry-run] [--no-push]`: create the worktree without assuming a
  proposal.
- `status`: inspect the repository and all known worktrees without mutation.
- `handoff branch <branch>`: print a paste-ready Cline handoff prompt.
- `pr branch <branch>`: create a draft PR after the branch and template checks.
- `cleanup branch <branch>` or `cleanup branch <branch> force`: remove a
  completed worktree subject to the safeguards below.

`--dry-run` prints would-be commits/pushes without running them. `--no-push`
commits locally but skips pushes.

Use placeholders literally in examples: `<branch>`, `<proposal>`, `<base>`,
`<repo-root>`, `<repo-name>`, and `<worktree-path>`.

## Planning Gate

Before creating, inspecting, or removing a worktree, run all four checks in the
original checkout:

```text
git rev-parse --show-toplevel
git branch --show-current
git worktree list --porcelain
git status --short --branch
```

Resolve and record:

1. `<repo-root>` from `git rev-parse --show-toplevel`.
2. The original checkout's current branch and dirty files.
3. Every active worktree and the branch checked out in it.
4. A verified base ref, defaulting to `origin/master`, or `origin/main` when
   the repository clearly uses `main`.

Refuse to continue when the requested branch is already checked out elsewhere,
the sibling path already exists, the base is ambiguous, the requested branch is
protected (`master`, `main`, `release/*`, `hotfix/*`, `prod/*`), or the original
checkout is being asked to switch branches. The original checkout must remain
on its current branch throughout the task.

## Worktree Creation

Fetch `origin` when remote access is available, then derive the destination as a
sibling of `<repo-root>`:

```text
<parent-of-repo-root>\<repo-name>-<sanitized-branch>
```

Replace `/`, `\`, `:`, and whitespace in `<branch>` with `-`. Never overwrite
an existing folder.

Choose exactly one Git command after the planning gate:

```text
git worktree add <worktree-path> -b <branch> <base>
git worktree add <worktree-path> <branch>
git worktree add <worktree-path> -b <branch> origin/<branch>
```

Use the first form for a new local branch, the second only for an existing local
branch not checked out elsewhere, and the third only when the user clearly
requested an origin-only branch. Report the resolved path, branch, and base.
Announce: `"Autoshipping proposal <proposal> in worktree <worktree-path> on branch <branch> — commits and pushes will happen automatically."`

## Local Environment Provisioning

Immediately after creation, before `/opsx-apply` or validation, provision only
safe, ignored local environment state:

1. Copy `local.properties` from the original checkout only if it is ignored or
   untracked. Never copy `.env` files, credentials, keystores, signing files,
   or arbitrary ignored files.
2. Preserve an existing `sdk.dir` value. If there is no safe source, use the
   common Windows Android SDK path when it exists, or set `ANDROID_HOME` for the
   current Cline process. Do not guess a missing SDK path.
3. Verify the result with:

   ```text
   git -C <worktree-path> status --short --ignored -- local.properties
   ```

   A copied or created file must report as `!!` or `??`; remove it if it is
   tracked or otherwise fails the check.
4. Report whether provisioning was copied, already present, created, supplied
   through `ANDROID_HOME`, skipped, or failed. Do not apply or validate before
   reporting that result.

## Autoship Apply Loop (inside the worktree)

Work only inside `<worktree-path>` after provisioning:

1. Inspect `openspec/changes/<proposal>` and its proposal, design, specs, and
   tasks artifacts.
2. Run `openspec status --change "<proposal>" --json` and
   `openspec instructions apply --change "<proposal>" --json` when available to
   get contextFiles, progress, tasks, `context`, and `operationGuidance`. If the
   `openspec` command or workflow is unavailable in this Cline process, follow
   the checked-in artifacts directly and say the limitation is process-scoped.
   Handle `blocked` / `all_done` the same way `/opsx-apply` does.
3. Read every context file before implementing.
4. Worktree preflight (once before the first task, cheaply re-verified per
   checkpoint): `git -C <worktree-path> rev-parse --show-toplevel` must succeed;
   `git -C <worktree-path> branch --show-current` must equal `<branch>`;
   note pre-existing dirty files unrelated to the proposal via
   `git -C <worktree-path> status --short --branch` and never stage/commit/push
   them (stage only task hunks via explicit paths, or block that task if
   inseparable); no upstream yet means the first push is `push -u origin <branch>`.
5. For each pending task: announce it, make minimal scoped changes, run
   validation from the new worktree only, mark `- [ ]` → `- [x]`, run the
   checkpoint below, and continue without stopping between tasks.
6. On a partially applied proposal, pick up remaining tasks and leave prior
   commits untouched. Only mark `- [x]` when the behavior is fully implemented.
   Report commands that actually ran; compilation is not evidence of unrun
   device/integration checks. Keep implementation, checkboxes, and tests limited
   to the proposal.

## Checkpoint Commit + Push (inside the worktree)

After each completed task (or tightly-coupled group):

1. Stage explicit paths only: `git -C <worktree-path> add <files-for-this-task>`.
   Never `git add -A` / `git add .`. Always include the tasks-checkbox update;
   never include `local.properties`, `.env*`, keystores, build outputs, or
   ignored files. Review the diff before staging.
2. Verify with `git -C <worktree-path> diff --cached --check` and
   `git -C <worktree-path> diff --cached --stat`. Fix `--check` failures first.
   Scan for secrets (keys, tokens, passwords, connection strings); a leak is a
   hard blocker for that task.
3. Commit conventionally: `git -C <worktree-path> commit -m "<type>: <lowercase imperative>"`
   (`feat`, `fix`, `test`, `docs`, `refactor`, `chore`; scope optional).
4. Push immediately: `git -C <worktree-path> push` (or `push -u origin <branch>`
   on first push). Skip with `--no-push`; skip commit+push with `--dry-run`
   and report intent instead.
5. On push rejection (remote ahead): `git -C <worktree-path> pull --no-rebase`
   (merge, never rebase). If clean, re-run checks, commit the merge, push again
   and continue. On conflicts needing judgment: hard blocker — report files and
   stop. Never force-push.

After the last task (or `all_done`): final `git -C <worktree-path> push`,
then report shipped checkpoint hashes, branch `→ origin/<branch>`, progress,
untouched pre-existing files, and next step (`pr branch <branch>` or archive).

## Pause Conditions (exhaustive)

Pause ONLY for: genuinely ambiguous proposal selection (ask once); unclear task
or spec超越 needing design input; secrets in the diff; inseparable overlap with
pre-existing worktree changes; unresolvable merge conflicts after push
rejection; openspec CLI `blocked` on out-of-scope artifacts; user interrupt.
Every pause states what blocked, committed/pushed hashes, and remaining tasks.
Never pause for commit/push/branch permission or "should I push now?".

## Handoff

For `handoff branch <branch>`, print this with resolved values:

```text
Open <worktree-path> in Cline. Autoship <proposal> on branch <branch>.
Stay inside this worktree. Checkpoints commit and push to origin/<branch>
automatically after each task. Do not switch branches in the original repo.
```

If the proposal is unknown, omit only the proposal-specific instruction and say
which value is missing.

## Draft PR

Only create a PR for an explicit `pr branch <branch>` request. Before invoking
`gh pr create`:

1. Refuse `master`, `main`, `release/*`, `hotfix/*`, and `prod/*`.
2. Verify the branch is pushed to `origin/<branch>` and that its upstream, when
   present, is exactly `origin/<branch>` (autoship pushes per checkpoint; push
   now if `--no-push` / `--dry-run` was used).
3. Read `.github/pull_request_template.md`,
   `.github/PULL_REQUEST_TEMPLATE.md`, and matching files under
   `.github/PULL_REQUEST_TEMPLATE/` when they exist.
4. Summarize commits and the branch diff, include the proposal when known, and
   list only validation actually run.
5. Prefer `gh pr create --draft` unless the user explicitly requests ready for
   review. Verify and report the resulting URL, title, base, head, and draft
   state.

Do not force-push, push tags, delete remote branches, or silently change the PR
base.

## Cleanup

For normal cleanup, fetch `origin`, locate `<worktree-path>` from
`git worktree list --porcelain`, and then:

1. Verify `<branch>` is an ancestor of the repository's fresh remote base.
2. Inspect `git -C <worktree-path> status --short --branch`.
3. Refuse if the branch is unmerged or the worktree is dirty.
4. Run `git worktree remove <worktree-path>` followed by `git branch -d <branch>`.
5. Refresh the remote base ref after successful removal.

For `force`, still inspect and report merge and dirty status first. Never remove
a dirty worktree or force-delete an unmerged branch without explicit
confirmation. Never delete the remote branch unless separately requested.

## Safety Rules

- Never mutate another active worktree.
- Never switch branches in the original checkout; never run task work, commits,
  or pushes outside `<worktree-path>`.
- Never overwrite an existing folder.
- Never delete unmerged work unless the user explicitly forces that exact action.
- Treat `.gitignore` as ignore policy, not proof that a file is safe to commit.
- Never stage unrelated, pre-existing, secret, or ignored build-artifact files.
- Keep Cline's autoship commits/pushes inside the target worktree on `origin/<branch>`.
- Never force-push, amend, squash, rebase automatically, push tags, or delete
  remote branches.
- Always run available quick checks before committing; always push after each
  checkpoint (unless `--no-push` / `--dry-run`); always report hashes.
