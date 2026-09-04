---
name: openspec-worktree-operator
description: Create and manage isolated Git worktrees for autonomous OpenSpec implementation. Use when Codex should start a branch worktree, apply an OpenSpec proposal in an isolated worktree, inspect worktree status, prepare a handoff, create a draft PR, or clean up a completed OpenSpec worktree.
license: MIT
compatibility: Requires Git; examples assume Windows PowerShell, with optional gh and openspec CLI support.
metadata:
  author: openspec
  version: "1.0"
  provider: codex
---

# OpenSpec Worktree Operator (Codex)

Invoke this skill explicitly with `$openspec-worktree-operator`, or let Codex
select it from the skill description. The user's remaining text is the command
payload. Use the Codex skill interface for related workflows:

- Apply a proposal inside the target worktree with
  `$openspec-apply-change <proposal>`.
- Use `$auto-commit-agent checkpoint` only inside that worktree when the user
  has requested autonomous checkpoints.
- For PR creation, prefer the available GitHub connector/app. Fall back to an
  authenticated `gh` CLI when no connector is available.

Do not interpret loading this skill as permission to commit, push, create a PR,
or remove anything. Those actions require the command payload and the gates
below.

## Command Payloads

- `start branch <branch> apply proposal <proposal>`: create a sibling worktree
  from the default base, then apply the proposal there.
- `start branch <branch> from current apply proposal <proposal>`: use the
  original checkout's `HEAD` as the base.
- `start branch <branch> from <base>`: create the worktree without assuming a
  proposal.
- `status`: inspect the repository and all known worktrees without mutation.
- `handoff branch <branch>`: print a paste-ready Codex handoff prompt.
- `pr branch <branch>`: create a draft PR after the branch and template checks.
- `cleanup branch <branch>` or `cleanup branch <branch> force`: remove a
  completed worktree subject to the safeguards below.

Use placeholders literally in examples: `<branch>`, `<proposal>`, `<base>`,
`<repo-root>`, `<repo-name>`, and `<worktree-path>`.

## Planning Gate

Before creating, inspecting, or removing a worktree, run these checks in the
original checkout:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git worktree list --porcelain
git status --short --branch
```

Record the repository root, current branch, dirty files, every active worktree,
and a verified base ref. Use `origin/master` by default, or `origin/main` when
the repository clearly uses `main`.

Refuse when the requested branch is already checked out elsewhere, the sibling
destination exists, the base is ambiguous, or continuing would require
switching the original checkout. Codex must leave the original checkout on its
current branch for the entire task.

## Worktree Creation

Fetch `origin` when remote access is available. Derive the destination as a
sibling of `<repo-root>`:

```text
<parent-of-repo-root>\<repo-name>-<sanitized-branch>
```

Replace `/`, `\`, `:`, and whitespace in `<branch>` with `-`. Never overwrite
an existing folder. After the planning gate, choose exactly one command:

```powershell
git worktree add <worktree-path> -b <branch> <base>
git worktree add <worktree-path> <branch>
git worktree add <worktree-path> -b <branch> origin/<branch>
```

Use the first form for a new local branch, the second only for an existing local
branch not checked out elsewhere, and the third only when the user clearly
requested an origin-only branch. Report the resolved path, branch, and base
before doing any implementation work.

## Local Environment Provisioning

Provision immediately after creation, before `$openspec-apply-change` or any
validation:

1. Copy only `local.properties` from the original checkout, and only when Git
   reports it as ignored or untracked. Never copy arbitrary ignored files,
   `.env` files, credentials, keystores, signing files, or build output.
2. Preserve an existing `sdk.dir`. If no safe source exists, use the common
   Windows Android SDK path if present or expose `ANDROID_HOME` to the current
   Codex process. Do not invent a path.
3. Verify the target file:

   ```powershell
   git -C <worktree-path> status --short --ignored -- local.properties
   ```

   A copied or generated file must be `!!` or `??`; remove it if it is tracked
   or fails that check.
4. Report copied, already present, created, environment-provided, skipped, or
   failed provisioning. Do not apply or validate until that result is reported.

## OpenSpec Apply and Validation

After provisioning, keep all implementation work inside `<worktree-path>`:

1. Read `openspec/changes/<proposal>` and every context artifact needed by the
   change, including proposal, design, specs, and tasks files.
2. Run `$openspec-apply-change <proposal>` when the skill is available. If the
   `openspec` command is absent from this Codex process, follow the checked-in
   artifacts directly and state that the CLI is unavailable in this process;
   do not claim it is uninstalled.
3. Run validation from inside the target worktree only. Report only commands
   that actually ran. Build or static checks do not prove device or integration
   behavior that was not exercised.
4. Keep code, tests, and task checkboxes scoped to the proposal. Leave local
   machine files, secrets, ignored files, and unrelated edits untouched.

## Handoff

For `handoff branch <branch>`, print this with resolved values:

```text
Open <worktree-path> as a Codex task. Use $openspec-apply-change <proposal> to
apply the proposal on branch <branch>. Stay inside this worktree. Use
$auto-commit-agent checkpoint after coherent increments if requested. Do not
switch branches in the original repo.
```

If the proposal is unknown, omit only its instruction and identify the missing
value.

## Draft PR

Only create a PR for an explicit `pr branch <branch>` request. Before using the
GitHub connector or `gh pr create`:

1. Refuse `master`, `main`, `release/*`, `hotfix/*`, and `prod/*`.
2. Verify the branch exists on `origin/<branch>` and that any configured
   upstream is exactly `origin/<branch>`.
3. Inspect `.github/pull_request_template.md`,
   `.github/PULL_REQUEST_TEMPLATE.md`, and matching files under
   `.github/PULL_REQUEST_TEMPLATE/` when present.
4. Summarize commits and the branch diff, include the proposal when known, and
   report only validation actually run.
5. Prefer a draft PR unless the user explicitly requests ready-for-review.
   Verify and report the URL, title, base, head, and draft status.

Never force-push, push tags, delete remote branches, or silently change the PR
base.

## Cleanup

For normal cleanup, fetch `origin`, locate `<worktree-path>` from
`git worktree list --porcelain`, and then:

1. Verify `<branch>` is an ancestor of the fresh remote base.
2. Inspect `git -C <worktree-path> status --short --branch`.
3. Refuse if the branch is unmerged or the worktree is dirty.
4. Run `git worktree remove <worktree-path>` and then `git branch -d <branch>`.
5. Refresh the remote base ref after successful removal.

For `force`, inspect and report merge and dirty status first. Never remove a
dirty worktree or force-delete an unmerged branch without explicit confirmation.
Never delete the remote branch unless separately requested.

## Safety Rules

- Never mutate another active worktree.
- Never switch branches in the original checkout.
- Never overwrite an existing folder.
- Never delete unmerged work unless the user explicitly forces that exact action.
- Treat `.gitignore` as ignore policy, not proof that a file is safe to commit.
- Keep `$auto-commit-agent` actions inside the target worktree.
- Never force-push, amend, squash, rebase automatically, push tags, or delete
  remote branches.
