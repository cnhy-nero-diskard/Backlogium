import subprocess
import unittest

from collect_release_metadata import resolve_previous_release


class ReleaseMetadataTest(unittest.TestCase):
    def test_previous_release_is_latest_published_valid_semver_in_current_history(self):
        tag_shas = {
            "v1.7.0": "old",
            "v1.6.0": "older",
            "v1.8.0": "current",
        }
        releases = [
            {"tagName": "v1.8.0", "isDraft": False, "isPrerelease": False},
            {"tagName": "v1.7.0", "isDraft": False, "isPrerelease": False},
            {"tagName": "v1.6.0", "isDraft": False, "isPrerelease": False},
            {"tagName": "v9.0.0", "isDraft": True, "isPrerelease": False},
            {"tagName": "v1.7.1-beta", "isDraft": False, "isPrerelease": False},
        ]

        previous = resolve_previous_release(
            current_tag="v1.8.0",
            current_sha="current",
            releases=releases,
            tag_sha=lambda tag: tag_shas[tag],
            is_ancestor=lambda candidate, current: candidate in {"old", "older"},
        )

        self.assertEqual("v1.7.0", previous)

    def test_missing_or_unreachable_candidates_are_skipped(self):
        releases = [
            {"tagName": "v1.7.0", "isDraft": False, "isPrerelease": False},
            {"tagName": "v1.6.0", "isDraft": False, "isPrerelease": False},
        ]

        previous = resolve_previous_release(
            current_tag="v1.8.0",
            current_sha="current",
            releases=releases,
            tag_sha=lambda tag: (_ for _ in ()).throw(subprocess.CalledProcessError(128, "git"))
            if tag == "v1.7.0" else "older",
            is_ancestor=lambda candidate, current: False,
        )

        self.assertEqual("", previous)


if __name__ == "__main__":
    unittest.main()
