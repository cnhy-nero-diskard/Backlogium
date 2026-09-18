import json
import unittest
from pathlib import Path

from release_notes import (
    MAX_ITEM_LENGTH,
    REPOSITORY_URL,
    check_release_note,
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

    def test_crlf_body_still_supplies_metadata(self):
        # A body saved with CRLF endings — what an editor on Windows produces — must not hide its
        # release note. When it did, the composer fell back to the raw pull-request title without
        # any warning, and that title is what v1.13.0 published to users.
        body = (
            "## Summary\r\n\r\n- implementation detail\r\n\r\n"
            "## Release note\r\n\r\n- A readable result.\r\n"
        )
        self.assertEqual(("A readable result.",), parse_release_note_metadata(body).entries)

        model, warnings = compose_release_model(
            current_tag="v1.8.0",
            previous_tag="v1.7.0",
            pull_requests=[{"number": 12, "title": "feat: crlf", "body": body}],
        )
        self.assertEqual((), warnings)
        self.assertEqual(("A readable result.",), model.sections[0].items)

    def test_crlf_body_honours_explicit_none(self):
        body = "## Summary\r\n\r\n- detail\r\n\r\n## Release note\r\n\r\nNone\r\n"
        metadata = parse_release_note_metadata(body)
        self.assertTrue(metadata.explicit_none)
        self.assertEqual((), metadata.entries)


class ReleaseNoteCheckTest(unittest.TestCase):
    def test_missing_section_is_an_error(self):
        errors, warnings = check_release_note(
            body="## Summary\n\n- implementation detail\n", title="feat: a thing"
        )
        self.assertEqual(1, len(errors))
        self.assertEqual((), warnings)

    def test_bullets_and_explicit_none_are_accepted(self):
        self.assertEqual(
            ((), ()), check_release_note(body="## Release note\n\n- A result.\n", title="feat: x")
        )
        self.assertEqual(
            ((), ()), check_release_note(body="## Release note\n\nNone\n", title="chore: x")
        )

    def test_crlf_body_passes_the_check(self):
        body = "## Summary\r\n\r\n- detail\r\n\r\n## Release note\r\n\r\n- A result.\r\n"
        self.assertEqual(((), ()), check_release_note(body=body, title="feat: x"))

    def test_empty_section_is_an_error(self):
        errors, _ = check_release_note(
            body="## Release note\n\n<!-- fill this in -->\n", title="feat: x"
        )
        self.assertEqual(1, len(errors))

    def test_entry_that_would_be_truncated_is_an_error(self):
        long_note = "x" * (MAX_ITEM_LENGTH + 40)
        errors, _ = check_release_note(
            body=f"## Release note\n\n- {long_note}\n", title="feat: x"
        )
        self.assertEqual(1, len(errors))

    def test_unprefixed_title_with_entries_warns_about_maintenance(self):
        errors, warnings = check_release_note(
            body="## Release note\n\n- A result.\n", title="Add a thing"
        )
        self.assertEqual((), errors)
        self.assertEqual(1, len(warnings))
        self.assertIn("Maintenance", warnings[0])

    def test_declared_none_on_a_feature_annotated_title_warns(self):
        errors, warnings = check_release_note(body="## Release note\n\nNone\n", title="feat: x")
        self.assertEqual((), errors)
        self.assertEqual(1, len(warnings))


if __name__ == "__main__":
    unittest.main()
