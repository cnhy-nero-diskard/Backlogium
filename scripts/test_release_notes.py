import json
import unittest
from pathlib import Path

from release_notes import (
    MAX_ITEM_LENGTH,
    REPOSITORY_URL,
    compose_release_model,
    model_to_dict,
    parse_release_note_metadata,
    render_markdown,
    validate_payload,
)


FIXTURES = Path(__file__).parent / "fixtures" / "release-notes"


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class ReleaseNotesTest(unittest.TestCase):
    def test_metadata_parser_accepts_bullets_and_explicit_none(self):
        metadata = parse_release_note_metadata(
            "## Summary\n- implementation detail\n\n## Release note\n\n- A readable result.\n"
        )
        self.assertEqual(("A readable result.",), metadata.entries)
        self.assertTrue(parse_release_note_metadata("## Release note\n\nNone\n").explicit_none)

    def test_feature_fix_and_performance_sections_are_ordered(self):
        model, warnings = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            current_sha="new",
            previous_sha="old",
            pull_requests=load_fixture("feature.json"),
        )
        self.assertEqual((), warnings)
        self.assertEqual(
            ["features", "fixes", "performance", "maintenance"],
            [section.key for section in model.sections],
        )
        self.assertEqual("See your weekly play summary at a glance.", model.sections[0].items[0])
        self.assertIn("v1.7.0...v1.8.0", model.full_changelog_url)

    def test_missing_metadata_uses_conventional_title_and_warns(self):
        model, warnings = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            pull_requests=[
                {
                    "number": 11,
                    "title": "fix: recover a failed update",
                    "body": "No release note section",
                }
            ],
        )
        self.assertEqual(("recover a failed update",), model.sections[1].items)
        self.assertEqual(1, len(warnings))

    def test_internal_only_release_is_maintenance(self):
        model, warnings = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            pull_requests=load_fixture("internal.json"),
        )
        self.assertEqual((), warnings)
        self.assertEqual((), model.sections[0].items)
        self.assertIn("maintenance release", model.sections[3].items[0])

    def test_same_commit_does_not_repeat_pull_request_notes(self):
        model, _ = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            current_sha="same",
            previous_sha="same",
            pull_requests=load_fixture("same-commit.json"),
        )
        self.assertEqual((), model.technical_details)
        self.assertEqual((), model.sections[0].items)
        self.assertIn("No application changes", model.sections[3].items[0])

    def test_output_is_bounded_and_product_links_are_validated(self):
        long_note = "x" * (MAX_ITEM_LENGTH + 100)
        model, _ = compose_release_model(
            current_tag="v1.8.0",
            previous_tag=None,
            pull_requests=[
                {
                    "number": 1,
                    "title": "feat: long note",
                    "body": f"## Release note\n\n- {long_note}\n",
                }
            ],
        )
        payload = model_to_dict(model)
        self.assertEqual([], validate_payload(payload, expected_tag="v1.8.0"))
        self.assertLessEqual(len(payload["sections"][0]["items"][0]), MAX_ITEM_LENGTH)
        self.assertTrue(payload["technical_details"][0]["url"].startswith(REPOSITORY_URL + "/pull/"))

    def test_markdown_uses_collapsed_technical_details_and_full_changelog(self):
        model, _ = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            pull_requests=load_fixture("feature.json"),
        )
        markdown = render_markdown(model)
        self.assertLess(markdown.index("## Features"), markdown.index("Technical details"))
        self.assertIn("<details>", markdown)
        self.assertIn("[View full changelog]", markdown)


if __name__ == "__main__":
    unittest.main()
