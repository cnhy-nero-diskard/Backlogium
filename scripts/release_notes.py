#!/usr/bin/env python3
"""Deterministic Backlogium release-note composer.

The module intentionally uses only the Python standard library. It turns merged pull-request
metadata into one bounded model and renders that model as both GitHub Markdown and app-facing JSON.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence


SCHEMA_VERSION = 1
REPOSITORY_URL = "https://github.com/cnhy-nero-diskard/Backlogium"
MAX_SECTION_COUNT = 4
MAX_ITEMS_PER_SECTION = 12
MAX_ITEM_LENGTH = 180
MAX_TECHNICAL_ENTRIES = 100
MAX_TECHNICAL_TITLE_LENGTH = 180
MAX_JSON_BYTES = 64 * 1024

SECTION_ORDER = (
    ("features", "Features"),
    ("fixes", "Fixes"),
    ("performance", "Performance"),
    ("maintenance", "Maintenance"),
)
SECTION_KEYS = {key for key, _ in SECTION_ORDER}

_URL_RE = re.compile(r"https?://[^\s)\]>]+", re.IGNORECASE)
_HEADING_RE = re.compile(r"^[ \t]*#{2,6}[ \t]+release notes?[ \t]*:?[ \t]*$", re.IGNORECASE | re.MULTILINE)
_NEXT_HEADING_RE = re.compile(r"^[ \t]*#{2,6}[ \t]+\S", re.IGNORECASE | re.MULTILINE)
_CONVENTIONAL_RE = re.compile(
    r"^\s*(?P<prefix>[a-z]+)(?:\([^)]*\))?(?P<breaking>!)?\s*:\s*(?P<title>.+?)\s*$",
    re.IGNORECASE,
)
_PR_URL_RE = re.compile(
    rf"^{re.escape(REPOSITORY_URL)}/pull/(?P<number>[1-9][0-9]*)$",
)
_TAG_RE = re.compile(r"^v(?P<major>[0-9]+)\.(?P<minor>[0-9]+)\.(?P<patch>[0-9]+)$")
_COMPARISON_URL_RE = re.compile(
    rf"^{re.escape(REPOSITORY_URL)}/compare/v[0-9]+\.[0-9]+\.[0-9]+\.\.\."
    r"v[0-9]+\.[0-9]+\.[0-9]+$",
)

_CATEGORY_BY_PREFIX = {
    "feat": "features",
    "feature": "features",
    "fix": "fixes",
    "perf": "performance",
    "performance": "performance",
}


@dataclass(frozen=True)
class ReleaseNoteSection:
    key: str
    title: str
    items: tuple[str, ...]


@dataclass(frozen=True)
class TechnicalEntry:
    number: int
    title: str
    url: str
    category: str


@dataclass(frozen=True)
class ReleaseNotesModel:
    schema_version: int
    tag: str
    sections: tuple[ReleaseNoteSection, ...]
    technical_details: tuple[TechnicalEntry, ...]
    full_changelog_url: str | None


@dataclass(frozen=True)
class ReleaseNoteMetadata:
    entries: tuple[str, ...]
    explicit_none: bool
    present: bool


def parse_tag(tag: str) -> tuple[int, int, int] | None:
    match = _TAG_RE.fullmatch((tag or "").strip())
    if not match:
        return None
    return tuple(int(match.group(name)) for name in ("major", "minor", "patch"))


def clean_plain_text(value: Any, max_length: int = MAX_ITEM_LENGTH) -> str:
    """Remove presentation syntax and untrusted destinations from one user-facing string."""

    text = html.unescape(str(value or ""))
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.DOTALL)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<https?://[^>]+>", " ", text, flags=re.IGNORECASE)
    text = _URL_RE.sub(" ", text)
    text = re.sub(r"^\s*(?:[-*+]|\d+[.)]|>)[ \t]+", "", text)
    text = re.sub(r"[*_`~]", "", text)
    text = re.sub(
        r"\s*(?:\(#\d+\)|by\s+@[a-z0-9_.-]+|@[a-z0-9_.-]+)\s*$",
        "",
        text,
        flags=re.IGNORECASE,
    )
    text = re.sub(r"\s+", " ", text).strip(" -")
    if len(text) > max_length:
        text = text[: max_length - 1].rstrip() + "…"
    return text


def parse_release_note_metadata(body: str | None) -> ReleaseNoteMetadata:
    """Read the dedicated PR-template section without treating the rest of the body as notes."""

    source = body or ""
    heading = _HEADING_RE.search(source)
    if not heading:
        return ReleaseNoteMetadata(entries=(), explicit_none=False, present=False)

    section = source[heading.end() :]
    next_heading = _NEXT_HEADING_RE.search(section)
    if next_heading:
        section = section[: next_heading.start()]
    section = re.sub(r"<!--.*?-->", " ", section, flags=re.DOTALL)

    entries: list[str] = []
    saw_none = False
    for raw_line in section.splitlines():
        line = re.sub(r"^\s*(?:[-*+]|\d+[.)]|\[[ xX]\])[ \t]+", "", raw_line)
        cleaned = clean_plain_text(line)
        if not cleaned or cleaned in {"-", "_"}:
            continue
        if cleaned.casefold() == "none":
            saw_none = True
            continue
        if cleaned.casefold() in {"tbd", "n/a", "todo"}:
            continue
        if cleaned not in entries:
            entries.append(cleaned)
        if len(entries) >= MAX_ITEMS_PER_SECTION:
            break

    return ReleaseNoteMetadata(
        entries=tuple(entries),
        explicit_none=saw_none and not entries,
        present=True,
    )


def classify_title(title: str | None) -> tuple[str | None, str]:
    """Return a conventional category and a cleaned title, if one is available."""

    original = clean_plain_text(title, max_length=MAX_TECHNICAL_TITLE_LENGTH)
    match = _CONVENTIONAL_RE.match(original)
    if not match:
        return None, original
    prefix = match.group("prefix").casefold()
    cleaned = clean_plain_text(match.group("title"), max_length=MAX_ITEM_LENGTH)
    return _CATEGORY_BY_PREFIX.get(prefix), cleaned


def _pr_number(raw: dict[str, Any]) -> int | None:
    try:
        number = int(raw.get("number", 0))
    except (TypeError, ValueError):
        number = 0
    if number > 0:
        return number
    match = _PR_URL_RE.fullmatch(str(raw.get("html_url", "")))
    return int(match.group("number")) if match else None


def _technical_entry(raw: dict[str, Any], category: str | None, repo_url: str) -> TechnicalEntry | None:
    number = _pr_number(raw)
    if number is None:
        return None
    title = clean_plain_text(raw.get("title"), max_length=MAX_TECHNICAL_TITLE_LENGTH)
    if not title:
        title = "Untitled pull request"
    return TechnicalEntry(
        number=number,
        title=title,
        url=f"{repo_url}/pull/{number}",
        category=category or "technical",
    )


def _dedupe(items: Iterable[str]) -> tuple[str, ...]:
    result: list[str] = []
    for item in items:
        if item and item not in result:
            result.append(item)
        if len(result) >= MAX_ITEMS_PER_SECTION:
            break
    return tuple(result)


def _comparison_url(previous_tag: str | None, current_tag: str, repo_url: str) -> str | None:
    if not previous_tag or previous_tag == current_tag:
        return None
    if parse_tag(previous_tag) is None or parse_tag(current_tag) is None:
        return None
    return f"{repo_url}/compare/{previous_tag}...{current_tag}"


def compose_release_model(
    *,
    current_tag: str,
    previous_tag: str | None,
    current_sha: str | None = None,
    previous_sha: str | None = None,
    pull_requests: Sequence[dict[str, Any]] = (),
    repo_url: str = REPOSITORY_URL,
) -> tuple[ReleaseNotesModel, tuple[str, ...]]:
    """Compose a model and non-fatal fallback warnings from merged PR metadata."""

    if parse_tag(current_tag) is None:
        raise ValueError(f"Unsupported release tag: {current_tag!r}")
    if repo_url.rstrip("/") != REPOSITORY_URL:
        raise ValueError("Release-note links must target the Backlogium product repository")

    items_by_section: dict[str, list[str]] = {key: [] for key in SECTION_KEYS}
    technical: list[TechnicalEntry] = []
    warnings: list[str] = []
    same_commit = bool(previous_tag and previous_tag == current_tag)
    same_commit = same_commit or bool(current_sha and previous_sha and current_sha == previous_sha)

    if same_commit:
        items_by_section["maintenance"].append(
            f"No application changes since {previous_tag or 'the previous release'}."
        )
    else:
        for raw in pull_requests:
            if not isinstance(raw, dict):
                warnings.append("Ignored malformed pull-request metadata entry.")
                continue
            category, cleaned_title = classify_title(raw.get("title"))
            metadata = parse_release_note_metadata(raw.get("body"))
            technical_entry = _technical_entry(raw, category, repo_url)
            if technical_entry is None:
                warnings.append("Ignored pull-request metadata without a valid number.")
            elif len(technical) < MAX_TECHNICAL_ENTRIES:
                technical.append(technical_entry)

            if metadata.entries:
                user_category = category or "maintenance"
                items_by_section[user_category].extend(metadata.entries)
            elif metadata.explicit_none:
                continue
            elif category in {"features", "fixes", "performance"} and cleaned_title:
                items_by_section[category].append(cleaned_title)
                warnings.append(
                    f"PR #{technical_entry.number if technical_entry else '?'} used its cleaned title "
                    "because Release note metadata was missing."
                )

        for key in items_by_section:
            items_by_section[key] = list(_dedupe(items_by_section[key]))

        has_user_facing_items = any(
            items_by_section[key] for key in ("features", "fixes", "performance")
        )
        if not has_user_facing_items:
            items_by_section["maintenance"].append(
                "This is a maintenance release with no user-visible changes."
            )

    sections = tuple(
        ReleaseNoteSection(key=key, title=title, items=_dedupe(items_by_section[key]))
        for key, title in SECTION_ORDER
    )
    technical = sorted(
        {entry.number: entry for entry in technical}.values(),
        key=lambda entry: entry.number,
    )
    model = ReleaseNotesModel(
        schema_version=SCHEMA_VERSION,
        tag=current_tag,
        sections=sections,
        technical_details=tuple(technical[:MAX_TECHNICAL_ENTRIES]),
        full_changelog_url=_comparison_url(previous_tag, current_tag, repo_url),
    )
    return model, tuple(warnings)


def model_to_dict(model: ReleaseNotesModel) -> dict[str, Any]:
    return {
        "schema_version": model.schema_version,
        "tag": model.tag,
        "sections": [
            {"key": section.key, "title": section.title, "items": list(section.items)}
            for section in model.sections
        ],
        "technical_details": [
            {
                "number": entry.number,
                "title": entry.title,
                "url": entry.url,
                "category": entry.category,
            }
            for entry in model.technical_details
        ],
        "full_changelog_url": model.full_changelog_url,
    }


def validate_payload(payload: Any, expected_tag: str | None = None) -> list[str]:
    errors: list[str] = []
    if not isinstance(payload, dict):
        return ["structured notes must be a JSON object"]
    if payload.get("schema_version") != SCHEMA_VERSION:
        errors.append("unsupported schema_version")
    tag = payload.get("tag")
    if not isinstance(tag, str) or parse_tag(tag) is None:
        errors.append("tag is not a valid vX.Y.Z release tag")
    elif expected_tag is not None and tag != expected_tag:
        errors.append("tag does not match the current release")

    sections = payload.get("sections")
    if not isinstance(sections, list) or not sections:
        errors.append("sections must be a non-empty array")
        sections = []
    if len(sections) > MAX_SECTION_COUNT:
        errors.append("too many sections")
    canonical_keys = [name for name, _ in SECTION_ORDER]
    seen_keys: list[str] = []
    for section in sections:
        if not isinstance(section, dict):
            errors.append("section is not an object")
            continue
        key = section.get("key")
        title = section.get("title")
        if key not in SECTION_KEYS or key in seen_keys:
            errors.append("section keys are unknown or duplicated")
        else:
            seen_keys.append(key)
        if not isinstance(title, str) or not title.strip() or len(title) > 40:
            errors.append("section title is invalid")
        items = section.get("items")
        if not isinstance(items, list) or len(items) > MAX_ITEMS_PER_SECTION:
            errors.append("section items are missing or exceed the bound")
            continue
        for item in items:
            if not isinstance(item, str) or not item.strip() or len(item) > MAX_ITEM_LENGTH:
                errors.append("section item is empty or exceeds the bound")
    if seen_keys != sorted(seen_keys, key=canonical_keys.index):
        errors.append("sections are not in canonical order")

    technical = payload.get("technical_details", [])
    if not isinstance(technical, list) or len(technical) > MAX_TECHNICAL_ENTRIES:
        errors.append("technical_details is missing or exceeds the bound")
        technical = []
    numbers: set[int] = set()
    for entry in technical:
        if not isinstance(entry, dict):
            errors.append("technical detail is not an object")
            continue
        number = entry.get("number")
        url = entry.get("url")
        if not isinstance(number, int) or number <= 0 or number in numbers:
            errors.append("technical detail number is invalid or duplicated")
        else:
            numbers.add(number)
        title = entry.get("title")
        if not isinstance(title, str) or not title.strip() or len(title) > MAX_TECHNICAL_TITLE_LENGTH:
            errors.append("technical detail title is invalid")
        if not isinstance(url, str) or not _PR_URL_RE.fullmatch(url):
            errors.append("technical detail URL is outside the product repository")

    full_changelog_url = payload.get("full_changelog_url")
    if full_changelog_url is not None and (
        not isinstance(full_changelog_url, str) or not _COMPARISON_URL_RE.fullmatch(full_changelog_url)
    ):
        errors.append("full_changelog_url is outside the product repository")
    return errors


def render_markdown(model: ReleaseNotesModel) -> str:
    version = model.tag.removeprefix("v")
    lines = [f"# Backlogium {version}", ""]
    visible_sections = [section for section in model.sections if section.items]
    if visible_sections:
        for section in visible_sections:
            lines.append(f"## {section.title}")
            lines.extend(f"- {item}" for item in section.items)
            lines.append("")
    else:
        lines.extend(["## Maintenance", "- This release contains no user-visible changes.", ""])

    if model.technical_details:
        lines.extend(["<details>", "<summary>Technical details</summary>", ""])
        for entry in model.technical_details:
            lines.append(f"- [#{entry.number} {entry.title}]({entry.url}) ({entry.category})")
        lines.extend(["", "</details>", ""])
    if model.full_changelog_url:
        lines.append(f"[View full changelog]({model.full_changelog_url})")
    return "\n".join(lines).rstrip() + "\n"


def _load_pull_requests(path: Path) -> list[dict[str, Any]]:
    if path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("pull-request metadata exceeds the input size bound")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict):
        payload = payload.get("pull_requests", [])
    if not isinstance(payload, list):
        raise ValueError("pull-request metadata must be an array")
    return [item for item in payload if isinstance(item, dict)]


def _compose_command(args: argparse.Namespace) -> int:
    pull_requests = _load_pull_requests(Path(args.prs_file))
    model, warnings = compose_release_model(
        current_tag=args.current_tag,
        previous_tag=args.previous_tag or None,
        current_sha=args.current_sha or None,
        previous_sha=args.previous_sha or None,
        pull_requests=pull_requests,
        repo_url=args.repo_url.rstrip("/"),
    )
    markdown = render_markdown(model)
    payload = model_to_dict(model)
    errors = validate_payload(payload, expected_tag=args.current_tag)
    if not markdown.strip() or errors:
        raise ValueError("generated release notes failed validation: " + "; ".join(errors))
    markdown_path = Path(args.markdown_output)
    json_path = Path(args.json_output)
    markdown_path.write_text(markdown, encoding="utf-8")
    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    print(f"markdown_path={markdown_path}")
    print(f"json_path={json_path}")
    return 0


def _validate_command(args: argparse.Namespace) -> int:
    json_path = Path(args.json_input)
    if json_path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("structured notes exceed the size bound")
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    errors = validate_payload(payload, expected_tag=args.expected_tag)
    if args.markdown_input:
        markdown = Path(args.markdown_input).read_text(encoding="utf-8")
        if not markdown.strip():
            errors.append("Markdown output is empty")
    if errors:
        raise ValueError("; ".join(errors))
    print("structured release notes are valid")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    compose = subparsers.add_parser("compose", help="compose Markdown and structured notes")
    compose.add_argument("--current-tag", required=True)
    compose.add_argument("--previous-tag", default="")
    compose.add_argument("--current-sha", default="")
    compose.add_argument("--previous-sha", default="")
    compose.add_argument("--prs-file", required=True)
    compose.add_argument("--markdown-output", required=True)
    compose.add_argument("--json-output", required=True)
    compose.add_argument("--repo-url", default=REPOSITORY_URL)
    compose.set_defaults(handler=_compose_command)

    validate = subparsers.add_parser("validate", help="validate generated outputs")
    validate.add_argument("--json-input", required=True)
    validate.add_argument("--markdown-input", default="")
    validate.add_argument("--expected-tag", required=True)
    validate.set_defaults(handler=_validate_command)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.handler(args)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
