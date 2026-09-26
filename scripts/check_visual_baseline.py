#!/usr/bin/env python3
"""Deterministic guard that tracked Roborazzi baselines stay current with the UI that feeds them.

The module intentionally uses only the Python standard library, matching ``release_notes.py``.

``verifyRoborazziDebug`` already fails when a rendered screen stops matching its golden, so this
does not replace that pixel check. It answers the question the pixel check cannot: *this pull
request edited a screen the goldens render, and re-recorded nothing*. Commit 1eda51d5 removed
History's early-return empty state, which changed what the empty History screenshot draws, and the
stale golden was not re-recorded until the branch had already published four consecutive red runs.
Discovering that cost a full Gradle round-trip per push, fixing it cost another, and nothing in the
change's task list mentioned re-recording at all.

The gate is scoped to the composables ``MainScreenshotFixtureHost`` actually renders, so the
overwhelming majority of pull requests never reach it. Where a covered directory changes without
moving a pixel -- a ViewModel edit, a log line, a screen the fixtures do not draw -- the pull
request acknowledges that with the ``no-golden-change`` label instead of editing this list, so the
scope stays a statement about the fixtures rather than a judgement about the change.
"""

from __future__ import annotations

import argparse
import codecs
import sys
from pathlib import Path
from typing import Iterable, Sequence


SNAPSHOT_ROOT = "app/src/test/snapshots"

# The exact fixture inputs. This list is the gate's entire scope, so each entry names the
# composable or resource it feeds and a new fixture-rendered screen adds an entry here and
# nowhere else. Directories rather than files, so a screen that grows a new file is still covered;
# that deliberately over-matches, which is what the acknowledgement label exists for.
FIXTURE_RENDERED_SOURCES: tuple[str, ...] = (
    # AnalyticsContent
    "app/src/main/java/com/example/backlogium/ui/analytics",
    # EmptyState, SectionHeader, and the shared rows the five screens draw.
    "app/src/main/java/com/example/backlogium/ui/components",
    # HistoryContent
    "app/src/main/java/com/example/backlogium/ui/history",
    # HomeContent
    "app/src/main/java/com/example/backlogium/ui/home",
    # LibraryContent
    "app/src/main/java/com/example/backlogium/ui/library",
    # SettingsOverviewScreen. Over-matches SettingsScreen.kt, which the fixtures do not draw.
    "app/src/main/java/com/example/backlogium/ui/settings",
    # BacklogiumTheme
    "app/src/main/java/com/example/backlogium/ui/theme",
    # Every rendered label, so a copy change is treated as a visual change.
    "app/src/main/res/values/strings.xml",
)

# Editing a fixture changes what the goldens draw by definition, so these are fixture inputs
# rather than ordinary test code. The rest of app/src/test is deliberately not covered: most of it
# cannot move a pixel.
FIXTURE_SOURCES: tuple[str, ...] = (
    "app/src/test/java/com/example/backlogium/ui/screenshot/MainScreenshotFixtures.kt",
    "app/src/test/java/com/example/backlogium/ui/screenshot/ScreenshotTestSupport.kt",
)

# The single label that acknowledges a covered change which provably cannot move a pixel.
NO_CHANGE_LABEL = "no-golden-change"

# Enough paths to identify the trigger, bounded so a sweeping refactor cannot flood the log.
MAX_REPORTED_PATHS = 8


def normalize_path(raw: str) -> str:
    """Return one repository-relative path in the forward-slash form git reports."""

    path = raw.strip().replace("\\", "/")
    # Remove only a leading "./" so a real dotfile such as .github/... survives intact.
    while path.startswith("./"):
        path = path[2:]
    return path


def is_golden(path: str) -> bool:
    """Report whether a path is a tracked Roborazzi baseline."""

    return path == SNAPSHOT_ROOT or path.startswith(f"{SNAPSHOT_ROOT}/")


def is_fixture_input(path: str) -> bool:
    """Report whether a path can change what any tracked baseline draws.

    A directory entry covers itself and everything beneath it, which is what lets a screen pick up
    a new file without touching this list.
    """

    if path in FIXTURE_SOURCES:
        return True
    return any(
        path == source or path.startswith(f"{source}/") for source in FIXTURE_RENDERED_SOURCES
    )


def _summarize(paths: Sequence[str]) -> str:
    shown = list(paths[:MAX_REPORTED_PATHS])
    remainder = len(paths) - len(shown)
    listed = ", ".join(shown)
    return f"{listed} and {remainder} more" if remainder > 0 else listed


def check_visual_baseline(
    *, changed: Iterable[str], labels: Iterable[str] = ()
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Audit one pull request's Roborazzi baseline currency before it merges.

    Returns ``(errors, warnings)``. An error means the pull request can change a rendered screen
    while re-recording nothing, which is the exact shape of the failure this exists to prevent. A
    warning means the pull request said so out loud and asked a human to agree, which is worth
    recording without blocking.

    Any recorded baseline satisfies the gate, because ``recordRoborazziDebug`` re-records the whole
    suite: the reachable states are "recorded nothing" and "recorded everything". Pairing each
    source with its own golden would mean maintaining a second map of the fixtures to catch a
    partial record that :app:verifyRoborazziDebug already rejects on pixels.
    """

    paths = [normalized for normalized in map(normalize_path, changed) if normalized]
    goldens = [path for path in paths if is_golden(path)]
    fixture_inputs = [path for path in paths if is_fixture_input(path)]

    if not fixture_inputs:
        return (), ()
    if goldens:
        return (), ()

    triggered = _summarize(fixture_inputs)
    if NO_CHANGE_LABEL in {label.strip() for label in labels}:
        return (), (
            f"This pull request changes screen UI that the Roborazzi goldens render ({triggered}) "
            f"but carries no baseline change, acknowledged with the '{NO_CHANGE_LABEL}' label. "
            "Confirm the edited files cannot alter a rendered pixel; if they can, re-record instead.",
        )

    return (
        f"This pull request changes screen UI that the Roborazzi goldens render ({triggered}) but "
        f"re-records no baseline, so ':app:verifyRoborazziDebug' will compare new pixels against "
        f"stale goldens. Re-record and commit the changed PNGs as described in "
        f"docs/visual-regression-screenshots.md, or, if the edit provably cannot move a rendered "
        f"pixel, label the pull request '{NO_CHANGE_LABEL}'.",
    ), ()


def read_path_list(path: Path) -> list[str]:
    """Read a newline-separated path list written by ``git diff --name-only``.

    Tolerates a UTF-16 byte-order mark because PowerShell's ``>`` redirection writes UTF-16 while
    the bash redirection in CI writes UTF-8, and this repository documents PowerShell as a supported
    way to work. Only the encoding is in question: git quotes any non-ASCII path, so the bytes are
    ASCII either way.
    """

    raw = path.read_bytes()
    if raw.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        return raw.decode("utf-16").splitlines()
    return raw.decode("utf-8-sig").splitlines()


def _check_command(args: argparse.Namespace) -> int:
    changed = read_path_list(Path(args.changed_file))
    labels = (args.labels or "").splitlines()
    errors, warnings = check_visual_baseline(changed=changed, labels=labels)
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        return 1
    print("tracked Roborazzi baselines are current with the UI that feeds them")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser(
        "check", help="audit one pull request's Roborazzi baseline currency before it merges"
    )
    # The path list arrives through a file and the labels through the environment, so neither
    # repository content nor pull-request metadata can be interpreted as shell syntax.
    check.add_argument("--changed-file", required=True)
    check.add_argument("--labels", default="")
    check.set_defaults(handler=_check_command)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.handler(args)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
