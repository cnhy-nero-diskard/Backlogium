---
name: make-pr
description: Create a GitHub pull request from the current branch using the repository's pull-request template when present, with accurate change and validation details. Use when the user asks to create, open, or prepare a pull request.
allowed-tools: Bash(git:*), Bash(gh:*), Bash(powershell:*)
license: MIT
compatibility: Requires Git and an authenticated GitHub CLI (`gh`).
metadata:
  author: cnhy-nero-diskard
  version: "1.0"
---

# Make PR

Create one pull request for the current branch, using the repository's own template and only
claiming work or validation that is actually present.

## Preconditions

1. Resolve the repository root, current branch, remote, and default base branch before mutating
   anything. Use the repository's configured default branch unless the user specifies another base.
2. Refuse a detached `HEAD` and refuse to open a PR from `master`, `main`, `release/*`,
   `hotfix/*`, or `prod/*`. Never switch the original checkout to another branch.
3. Check for an existing open PR whose head is the current branch. Report it and stop instead of
   creating a duplicate.
4. Inspect `git status --short --branch`. Do not stage, commit, amend, stash, or discard the
   user's changes. If the working tree is dirty, report the exact files and stop; a PR must be
   built from committed changes only.
5. Confirm that the branch has commits relative to the selected base. If it is not on the remote,
   push it with `git push -u origin <branch>` only when the user's request clearly authorizes
   publishing the branch as part of creating the PR. Never force-push.

## Resolve the PR template

Look for templates in this order, preserving the repository's case-sensitive paths when present:

1. `.github/PULL_REQUEST_TEMPLATE.md`
2. `.github/pull_request_template.md`
3. A matching file under `.github/PULL_REQUEST_TEMPLATE/` or `.github/pull_request_template/`

If more than one directory template could apply and the user did not choose one, stop and ask
which template to use. Read the selected template before composing the body.

The generated body must use the selected template's headings and order. Preserve sections, remove
HTML instructions, and replace placeholder bullets or checkboxes with truthful content. Do not
silently substitute a generic body when a repository template exists.

For this repository's current template, use these conventions:

- `Summary` explains what the PR does and why.
- `Changes` lists the notable changes without copying an entire diff.
- `Release note` contains one or two short user-facing bullets, or exactly `None` when users see
  no product change. Do not invent user-facing prose for documentation, tests, chores, proposals,
  or other technical-only work.
- `Testing` marks only commands or device checks that actually ran. Leave unchecked items
  unchecked and state any relevant limitation.
- `Notes` records follow-ups, tradeoffs, screenshots, or other reviewer context; omit empty
  placeholder text.

If no template exists, use a concise `Summary`, `Changes`, `Testing`, and `Notes` body. Do not
invent a release-note requirement that the repository has not defined.

## Prepare and validate

1. Inspect the committed change with `git log <base>..<branch>`, `git diff --stat <base>...HEAD`,
   and the relevant diff. Derive the title from the user's request or the change; otherwise use a
   concise conventional title when the repository uses conventional prefixes.
2. Run the smallest relevant project checks for the changed files when practical. Report the
   exact commands and results. Never mark a template checkbox based on an intended or inferred
   check.
3. Assemble the completed body in a temporary file outside the repository. Prefer an explicit
   `gh pr create --body-file` call so the filled template is the body that GitHub receives; do not
   rely on an interactive editor or an unverified default template. Remove the temporary file
   after creation.

## Create and verify

Create a draft PR by default:

```powershell
gh pr create --base <base> --head <branch> --title <title> --body-file <body-file> --draft
```

Only create a ready-for-review PR when the user explicitly requests that state. Do not merge,
assign reviewers, add labels, or close/delete branches unless separately requested.

After creation, verify and report the PR URL, number, title, base, head, draft state, the selected
template, and the validation that actually ran. Check the initial CI state with `gh pr checks
<number>`; pending checks are expected and must not be reported as passing.

If creation fails, leave the branch and working tree unchanged apart from any explicitly
authorized branch push, and report the exact failure.
