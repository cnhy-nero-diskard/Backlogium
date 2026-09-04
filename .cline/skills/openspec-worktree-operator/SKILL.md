---
name: openspec-worktree-operator
description: Create and manage isolated Git worktrees for autonomous OpenSpec implementation. Use when Cline should start a branch worktree, apply an OpenSpec proposal in an isolated worktree, inspect worktree status, prepare a handoff, create a draft PR, or clean up a completed OpenSpec worktree.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(openspec:*), Bash(powershell:*)
license: MIT
compatibility: Requires Git and PowerShell; gh and the openspec CLI are optional.
metadata:
  author: openspec
  version: "1.0"
  provider: cline
---

# OpenSpec Worktree Operator (Cline)

Use this skill after Cline activates it automatically or after the user selects
`/openspec-worktree-operator` from the skill picker. Treat the user's remaining
text as the command payload.

When this skill needs another project workflow, use Cline's interface:

- Apply a proposal with `/opsx-apply <proposal>` inside the new worktree.
- Use `/auto-commit-agent checkpoint` only inside the new worktree when the user
  has requested checkpoint commits or pushes.
- Use the `gh` CLI for draft PR work; do not assume a GitHub connector is
  available to Cline.

## Command Payloads

- `start branch <branch> apply proposal <proposal>`: create a sibling worktree
  from the default base, then apply the proposal there.
- `start branch <branch> from current apply proposal <proposal>`: use the
  original checkout's `HEAD` as the base.
- `start branch <branch> from <base>`: create the worktree without assuming a
  proposal.
- `status`: inspect the repository and all known worktrees without mutation.
- `handoff branch <branch>`: print a paste-ready Cline handoff prompt.
- `pr branch <branch>`: create a draft PR after the branch and template checks.
- `cleanup branch <branch>` or `cleanup branch <branch> force`: remove a
  completed worktree subject to the safeguards below.

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
the sibling path already exists, the base is ambiguous, or the original
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

## OpenSpec Apply and Validation

Work only inside `<worktree-path>` after provisioning:

1. Inspect `openspec/changes/<proposal>` and its proposal, design, specs, and
   tasks artifacts.
2. Run `/opsx-apply <proposal>` when the Cline workflow is available. If the
   `openspec` command or workflow is unavailable in this Cline process, follow
   the checked-in artifacts directly and say the limitation is process-scoped.
3. Run validation from the new worktree only. Report commands that actually ran;
   compilation or a static check is not evidence that an unrun device or
   integration check passed.
4. Keep implementation, task checkboxes, and tests limited to the proposal.
   Never stage local machine files, secrets, build outputs, or unrelated work.

## Handoff

For `handoff branch <branch>`, print this with resolved values:

```text
Open <worktree-path> in Cline. Run /opsx-apply <proposal> to apply the proposal
on branch <branch>. Stay inside this worktree. Use /auto-commit-agent checkpoint
after coherent increments if requested. Do not switch branches in the original repo.
```

If the proposal is unknown, omit only the proposal-specific instruction and say
which value is missing.

## Draft PR

Only create a PR for an explicit `pr branch <branch>` request. Before invoking
`gh pr create`:

1. Refuse `master`, `main`, `release/*`, `hotfix/*`, and `prod/*`.
2. Verify the branch is pushed to `origin/<branch>` and that its upstream, when
   present, is exactly `origin/<branch>`.
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
- Never switch branches in the original checkout.
- Never overwrite an existing folder.
- Never delete unmerged work unless the user explicitly forces that exact action.
- Treat `.gitignore` as ignore policy, not proof that a file is safe to commit.
- Keep Cline's `/auto-commit-agent` actions inside the target worktree.
- Never force-push, amend, squash, rebase automatically, push tags, or delete
  remote branches.
