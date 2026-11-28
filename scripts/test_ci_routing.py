import subprocess
import tempfile
import unittest
from pathlib import Path

from ci_routing import active_changes, changed_paths, selected_jobs


APP_PACKAGE = "app/src/main/java/com/example/backlogium/"
APP = {"test", "visual-regression", "lint"}
ANDROID = APP | {"instrumented"}
ALL_JOBS = ANDROID | {"scripts", "functions", "openspec"}
INSTRUMENTATION = "app/src/androidTest/java/com/example/backlogium/ui/UnrelatedTest.kt"


class RoutingTest(unittest.TestCase):
    def test_isolated_paths(self):
        cases = {
            "docs/README.md": set(),
            "tools/hltb-dataset/dataset.json": set(),
            "app/src/main/res/drawable/icon.xml": APP,
            "app/src/test/snapshots/Screen.png": APP,
            APP_PACKAGE + "data/repo/CollectionMembershipRepository.kt": APP,
            APP_PACKAGE + "data/local/dao/GameDao.kt": APP,
            "gamification/src/main/kotlin/Engine.kt": {"test", "visual-regression"},
            "app/build.gradle.kts": ANDROID,
            "build.gradle.kts": ANDROID,
            "settings.gradle.kts": ANDROID,
            "gradle.properties": ANDROID,
            "gradle/libs.versions.toml": ANDROID,
            "gradle/wrapper/gradle-wrapper.jar": ANDROID,
            "gradlew": ANDROID,
            "gradlew.bat": ANDROID,
            "app/schemas/BacklogiumDatabase/1.json": ANDROID,
            APP_PACKAGE + "data/local/BacklogiumDatabase.kt": ANDROID,
            APP_PACKAGE + "data/local/Converters.kt": ANDROID,
            APP_PACKAGE + "data/local/entity/Game.kt": ANDROID,
            APP_PACKAGE + "di/DatabaseModule.kt": ANDROID,
            APP_PACKAGE + "data/local/DeadlineEvidenceSchema.kt": ANDROID,
            APP_PACKAGE + "data/local/dao/HltbDataDao.kt": ANDROID,
            APP_PACKAGE + "data/diagnostics/Diagnostics.kt": ANDROID,
            "app/src/androidTest/java/com/example/backlogium/data/local/MigrationTest.kt": ANDROID,
            INSTRUMENTATION: ANDROID,
            "functions/src/index.ts": {"functions"},
            "firebase.json": {"functions"},
            "scripts/release_notes.py": {"scripts"},
            ".github/workflows/release.yml": {"scripts"},
            ".github/workflows/release-note-check.yml": {"scripts"},
            ".github/workflows/release-notes-dry-run.yml": {"scripts"},
            ".github/workflows/visual-baseline-check.yml": {"scripts"},
            "openspec/specs/schema-migration/spec.md": {"openspec"},
            ".github/workflows/ci.yml": ALL_JOBS,
            "scripts/ci_routing.py": ALL_JOBS,
            "scripts/test_ci_routing.py": ALL_JOBS,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(selected_jobs([path]), expected)

    def test_combines_independent_matches(self):
        self.assertEqual(selected_jobs([INSTRUMENTATION, "functions/src/index.ts"]), ANDROID | {"functions"})

    def test_active_changes_exclude_archive_and_deleted_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("touched", "untouched", "archive"):
                (root / "openspec" / "changes" / name).mkdir(parents=True)
            paths = [
                "openspec/changes/touched/specs/a/spec.md",
                "openspec/changes/touched/tasks.md",
                "openspec/changes/deleted/tasks.md",
                "openspec/changes/archive/archived/tasks.md",
                "openspec/specs/a/spec.md",
            ]
            self.assertEqual(active_changes(paths, root), ["touched"])


class DiffTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git("init", "--initial-branch=base")
        self.original = "functions/src/old name.ts"
        (self.root / self.original).parent.mkdir(parents=True)
        (self.root / self.original).write_text("function example() {}\n", encoding="utf-8")
        self.start = self.commit("initial source", self.original)

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
             "-c", "commit.gpgSign=false", *args],
            cwd=self.root,
            text=True,
        ).strip()

    def commit(self, message, *paths):
        self.git("add", "--", *paths)
        self.git("commit", "-m", message)
        return self.git("rev-parse", "HEAD")

    def test_single_file_deletion_is_a_build_input(self):
        (self.root / self.original).unlink()
        head = self.commit("remove source", self.original)
        paths = changed_paths(self.start, head, self.root)
        self.assertEqual(paths, [self.original])
        self.assertEqual(selected_jobs(paths), {"functions"})

    def test_merge_base_diff_includes_both_sides_of_a_cross_boundary_move(self):
        (self.root / "firebase.json").write_text("{}\n", encoding="utf-8")
        base = self.commit("base-only change", "firebase.json")
        self.git("checkout", "-q", "-b", "pr", self.start)
        moved = "app/src/main/java/New.kt"
        (self.root / moved).parent.mkdir(parents=True)
        (self.root / self.original).rename(self.root / moved)
        head = self.commit("move source across build boundaries", self.original, moved)
        paths = changed_paths(base, head, self.root)
        self.assertCountEqual(paths, [self.original, moved])
        self.assertEqual(selected_jobs(paths), APP | {"functions"})


if __name__ == "__main__":
    unittest.main()
