import unittest

from check_visual_baseline import (
    NO_CHANGE_LABEL,
    check_visual_baseline,
    is_fixture_input,
    is_golden,
    normalize_path,
)


HISTORY_SCREEN = "app/src/main/java/com/example/backlogium/ui/history/HistoryScreen.kt"
STRINGS = "app/src/main/res/values/strings.xml"
SETTINGS_OVERVIEW_GOLDEN = "app/src/test/snapshots/main/settings/overview/dark/standard.png"
HISTORY_EMPTY_GOLDEN = "app/src/test/snapshots/alternatives/history/empty/dark/standard.png"
VIEW_MODEL = "app/src/main/java/com/example/backlogium/ui/history/HistoryViewModel.kt"


class PathClassificationTest(unittest.TestCase):
    def test_normalize_removes_leading_dot_slash_without_eating_dotfiles(self):
        self.assertEqual(".github/workflows/ci.yml", normalize_path("./.github/workflows/ci.yml"))
        self.assertEqual("app/build/reports", normalize_path("app\\build\\reports"))
        self.assertEqual("app/main.kt", normalize_path("  app/main.kt  "))

    def test_goldens_are_matched_but_a_similarly_named_file_is_not(self):
        self.assertTrue(is_golden(HISTORY_EMPTY_GOLDEN))
        self.assertTrue(is_golden("app/src/test/snapshots"))
        self.assertFalse(is_golden("app/src/test/snapshotsX/main.png"))
        self.assertFalse(is_golden("app/src/test/java/com/example/backlogium/ui/screenshot/Verify.kt"))

    def test_fixture_inputs_cover_a_directory_and_its_new_files(self):
        self.assertTrue(is_fixture_input(HISTORY_SCREEN))
        self.assertTrue(is_fixture_input(STRINGS))
        self.assertTrue(
            is_fixture_input("app/src/main/java/com/example/backlogium/ui/history/BrandNewFile.kt")
        )
        self.assertTrue(
            is_fixture_input(
                "app/src/test/java/com/example/backlogium/ui/screenshot/MainScreenshotFixtures.kt"
            )
        )

    def test_fixture_inputs_exclude_a_similarly_named_prefix(self):
        self.assertFalse(is_fixture_input("app/src/main/java/com/example/backlogium/ui/historyical/X.kt"))
        self.assertFalse(is_fixture_input("app/src/main/res/values/strings-es.xml"))


class CheckVisualBaselineTest(unittest.TestCase):
    def test_change_outside_the_golden_fixture_scope_never_blocks(self):
        errors, warnings = check_visual_baseline(
            changed=["functions/src/index.ts", "app/src/main/java/com/example/backlogium/data/repo/SteamRepository.kt"]
        )
        self.assertEqual((), errors)
        self.assertEqual((), warnings)

    def test_view_model_only_edit_still_asks_because_history_is_a_rendered_screen(self):
        errors, warnings = check_visual_baseline(changed=[VIEW_MODEL])
        self.assertEqual((), warnings)
        self.assertEqual(1, len(errors))
        self.assertIn(NO_CHANGE_LABEL, errors[0])
        self.assertIn("docs/visual-regression-screenshots.md", errors[0])

    def test_rendered_ui_with_a_re_recorded_golden_passes(self):
        errors, warnings = check_visual_baseline(changed=[HISTORY_SCREEN, HISTORY_EMPTY_GOLDEN])
        self.assertEqual((), errors)
        self.assertEqual((), warnings)

    def test_any_recorded_golden_satisfies_the_gate_by_design(self):
        # Deliberately not screen-matched. recordRoborazziDebug re-records the whole suite, so the
        # reachable states are "recorded nothing" and "recorded everything"; pairing a source with
        # its own golden would add a mapping to maintain for a case the pixel check already covers.
        errors, warnings = check_visual_baseline(changed=[HISTORY_SCREEN, SETTINGS_OVERVIEW_GOLDEN])
        self.assertEqual((), errors)
        self.assertEqual((), warnings)

    def test_copy_only_change_counts_as_visual(self):
        errors, _ = check_visual_baseline(changed=[STRINGS])
        self.assertEqual(1, len(errors))

    def test_acknowledgement_label_downgrades_the_failure_to_a_warning(self):
        errors, warnings = check_visual_baseline(
            changed=[HISTORY_SCREEN], labels=["chore", f" {NO_CHANGE_LABEL} "]
        )
        self.assertEqual((), errors)
        self.assertEqual(1, len(warnings))
        self.assertIn(NO_CHANGE_LABEL, warnings[0])

    def test_unrelated_label_does_not_acknowledgement(self):
        errors, _ = check_visual_baseline(changed=[HISTORY_SCREEN], labels=["do-not-merge"])
        self.assertEqual(1, len(errors))

    def test_empty_and_blank_change_sets_are_not_a_trigger(self):
        errors, warnings = check_visual_baseline(changed=["", "   "])
        self.assertEqual((), errors)
        self.assertEqual((), warnings)

    def test_sweeping_refactor_reports_a_bounded_path_list(self):
        changed = [
            f"app/src/main/java/com/example/backlogium/ui/history/File{index}.kt" for index in range(40)
        ]
        errors, _ = check_visual_baseline(changed=changed)
        self.assertEqual(1, len(errors))
        self.assertIn("and 32 more", errors[0])
        self.assertLessEqual(errors[0].count("File"), 8)


if __name__ == "__main__":
    unittest.main()
