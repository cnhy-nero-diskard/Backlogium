#!/usr/bin/env bash
#
# Tags the current commit with the next semver version and pushes the tag to origin.
# WSL/bash counterpart to scripts/bump-tag.ps1 — same behavior, same tag scheme.
#
# Usage:
#   ./scripts/bump-tag.sh                  # patch bump: v1.2.3 -> v1.2.4 (or v0.1.0 if no tags yet)
#   ./scripts/bump-tag.sh --minor          # v1.2.3 -> v1.3.0
#   ./scripts/bump-tag.sh --major          # v1.2.3 -> v2.0.0
#   ./scripts/bump-tag.sh --dry-run        # show the next tag without creating/pushing
#   ./scripts/bump-tag.sh -m "Custom note"

set -euo pipefail

bump="patch"
message=""
dry_run=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --major) bump="major"; shift ;;
        --minor) bump="minor"; shift ;;
        --patch) bump="patch"; shift ;;
        -m|--message) message="$2"; shift 2 ;;
        --dry-run) dry_run=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if ! git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    echo "Not inside a git repository." >&2
    exit 1
fi

git fetch --tags --quiet

latest_tag=$(git tag --list 'v*.*.*' --sort=-v:refname | head -n1)

if [[ -z "$latest_tag" ]]; then
    major=0
    minor=0
    patch=0
else
    if [[ ! "$latest_tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        echo "Latest tag '$latest_tag' does not match expected vX.Y.Z format." >&2
        exit 1
    fi
    major="${BASH_REMATCH[1]}"
    minor="${BASH_REMATCH[2]}"
    patch="${BASH_REMATCH[3]}"
fi

case "$bump" in
    major)
        major=$((major + 1))
        minor=0
        patch=0
        ;;
    minor)
        minor=$((minor + 1))
        patch=0
        ;;
    patch)
        if [[ -z "$latest_tag" ]]; then
            minor=1
        else
            patch=$((patch + 1))
        fi
        ;;
esac

new_tag="v${major}.${minor}.${patch}"

current_commit=$(git rev-parse --short HEAD)
current_branch=$(git rev-parse --abbrev-ref HEAD)

echo "Latest tag: ${latest_tag:-(none)}"
echo "Next tag:   $new_tag"
echo "On commit:  $current_commit ($current_branch)"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Warning: working tree has uncommitted changes. The tag will still be created on the current HEAD commit ($current_commit), not on your uncommitted changes." >&2
fi

if git rev-list -n 1 "$new_tag" > /dev/null 2>&1; then
    existing_commit=$(git rev-list -n 1 "$new_tag")
    echo "Tag '$new_tag' already exists (commit $existing_commit). Refusing to overwrite." >&2
    exit 1
fi

if $dry_run; then
    echo "Dry run: not creating or pushing '$new_tag'."
    exit 0
fi

if [[ -z "$message" ]]; then
    message="Release $new_tag"
fi

git tag -a "$new_tag" -m "$message"

if ! git push origin "$new_tag"; then
    echo "git push origin $new_tag failed. The local tag was created; delete it with 'git tag -d $new_tag' if you need to retry." >&2
    exit 1
fi

echo "Tagged and pushed $new_tag."
