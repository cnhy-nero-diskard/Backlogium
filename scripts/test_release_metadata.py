import subprocess
import unittest
from unittest.mock import call, patch

from collect_release_metadata import _github_api, resolve_previous_release


class ReleaseMetadataTest(unittest.TestCase):
    @patch("collect_release_metadata.time.sleep")
    @patch("collect_release_metadata._run")
    def test_transient_github_failure_retries_without_losing_metadata(self, run, sleep):
        run.side_effect = [
            subprocess.CalledProcessError(1, "gh", stderr="gh: Bad Gateway (HTTP 502)"),
            '[{"number": 184}]',
        ]

        self.assertEqual([{"number": 184}], _github_api("owner/repo", "commits/sha/pulls"))
        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(1)

    @patch("collect_release_metadata.time.sleep")
    @patch("collect_release_metadata._run")
    def test_permission_failure_is_reported_without_retry(self, run, sleep):
        run.side_effect = subprocess.CalledProcessError(
            1, "gh", stderr="gh: Resource not accessible by integration (HTTP 403)",
        )

        with self.assertRaisesRegex(RuntimeError, "commits/sha/pulls.*HTTP 403"):
            _github_api("owner/repo", "commits/sha/pulls")
        run.assert_called_once()
        sleep.assert_not_called()

    @patch("collect_release_metadata.time.sleep")
    @patch("collect_release_metadata._run")
    def test_persistent_github_failure_stops_after_three_attempts(self, run, sleep):
        run.side_effect = subprocess.CalledProcessError(
            1, "gh", stderr="gh: Service Unavailable (HTTP 503)",
        )

        with self.assertRaisesRegex(RuntimeError, "commits/sha/pulls.*HTTP 503"):
            _github_api("owner/repo", "commits/sha/pulls")
        self.assertEqual(3, run.call_count)
        self.assertEqual([call(1), call(2)], sleep.call_args_list)

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
