#!/usr/bin/env python3
"""Resolve the previous published release and collect PR metadata for a tag comparison."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable, Sequence

from release_notes import parse_tag


def _run(command: Sequence[str]) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def resolve_previous_release(
    *,
    current_tag: str,
    current_sha: str,
    releases: Sequence[dict[str, Any]],
    tag_sha: Callable[[str], str],
    is_ancestor: Callable[[str, str], bool],
) -> str:
    current_version = parse_tag(current_tag)
    if current_version is None:
        raise ValueError(f"Unsupported current release tag: {current_tag}")
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for release in releases:
        if release.get("isDraft") or release.get("isPrerelease"):
            continue
        tag = str(release.get("tagName", ""))
        version = parse_tag(tag)
        if version is None or version >= current_version or tag == current_tag:
            continue
        candidates.append((version, tag))

    for _, candidate in sorted(candidates, reverse=True):
        try:
            candidate_sha = tag_sha(candidate)
        except subprocess.CalledProcessError:
            continue
        if is_ancestor(candidate_sha, current_sha):
            return candidate
    return ""


def _git_tag_sha(tag: str) -> str:
    return _run(["git", "rev-list", "-n", "1", f"{tag}^{{commit}}"]).strip()


def _is_ancestor(candidate_sha: str, current_sha: str) -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", candidate_sha, current_sha],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0


def _github_api(repository: str, path: str) -> Any:
    return json.loads(
        _run([
            "gh",
            "api",
            "--header",
            "Accept: application/vnd.github+json",
            f"repos/{repository}/{path}",
        ])
    )


def collect_pull_requests(repository: str, current_tag: str, previous_tag: str) -> list[dict[str, Any]]:
    if previous_tag:
        commits = _run(["git", "rev-list", "--reverse", f"{previous_tag}..{current_tag}"]).splitlines()
    else:
        commits = _run(["git", "rev-list", "--reverse", "--max-count=200", current_tag]).splitlines()

    collected: dict[int, dict[str, Any]] = {}
    for sha in commits:
        pulls = _github_api(repository, f"commits/{sha}/pulls")
        if not isinstance(pulls, list):
            raise ValueError(f"GitHub returned an invalid pull-request list for commit {sha}")
        for pull in pulls:
            if not isinstance(pull, dict) or not pull.get("merged_at"):
                continue
            try:
                number = int(pull["number"])
            except (KeyError, TypeError, ValueError):
                continue
            collected[number] = {
                "number": number,
                "title": pull.get("title", ""),
                "body": pull.get("body") or "",
                "html_url": pull.get("html_url", ""),
                "merged_at": pull.get("merged_at"),
                "merge_commit_sha": pull.get("merge_commit_sha", ""),
            }
    return [collected[number] for number in sorted(collected)]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--current-tag", required=True)
    parser.add_argument("--current-sha", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--github-output", default="")
    args = parser.parse_args(argv)

    releases = json.loads(_run(["gh", "release", "list", "--limit", "1000", "--json", "tagName,isDraft,isPrerelease"]))
    if not isinstance(releases, list):
        raise ValueError("GitHub returned an invalid release list")
    previous_tag = resolve_previous_release(
        current_tag=args.current_tag,
        current_sha=args.current_sha,
        releases=releases,
        tag_sha=_git_tag_sha,
        is_ancestor=_is_ancestor,
    )
    pull_requests = collect_pull_requests(args.repository, args.current_tag, previous_tag)
    Path(args.output).write_text(json.dumps(pull_requests, indent=2) + "\n", encoding="utf-8")
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as output:
            output.write(f"previous_tag={previous_tag}\n")
    print(f"Previous published release: {previous_tag or '(none)'}")
    print(f"Merged pull requests collected: {len(pull_requests)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
