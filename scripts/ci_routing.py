"""Select CI jobs using the complete pull request diff."""

import json
import os
import subprocess
from pathlib import Path


JOBS = ("test", "visual-regression", "lint", "instrumented", "scripts", "functions", "openspec")
APP_JOBS = ("test", "visual-regression", "lint")
SHARED_GRADLE_FILES = {
    "app/build.gradle", "app/build.gradle.kts", "build.gradle", "build.gradle.kts",
    "settings.gradle", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
}
ROUTING_FILES = {".github/workflows/ci.yml", "scripts/ci_routing.py", "scripts/test_ci_routing.py"}
SCRIPT_WORKFLOWS = {
    ".github/workflows/release.yml",
    ".github/workflows/release-note-check.yml",
    ".github/workflows/release-notes-dry-run.yml",
    ".github/workflows/visual-baseline-check.yml",
}

APP_PACKAGE = "app/src/main/java/com/example/backlogium/"
# Register every new migration dependency here. If its paths cannot be enumerated
# confidently, add "app/" to MIGRATION_PREFIXES until the dependency is registered.
MIGRATION_FILES = {
    APP_PACKAGE + "data/local/BacklogiumDatabase.kt",
    APP_PACKAGE + "data/local/Converters.kt",
    APP_PACKAGE + "data/local/DeadlineEvidenceSchema.kt",
    APP_PACKAGE + "data/local/dao/HltbDataDao.kt",
    APP_PACKAGE + "data/diagnostics/Diagnostics.kt",
    APP_PACKAGE + "di/DatabaseModule.kt",
}
MIGRATION_PREFIXES = (
    "app/schemas/",
    # The emulator job compiles all instrumentation sources and runs only MigrationTest.
    "app/src/androidTest/",
    APP_PACKAGE + "data/local/entity/",
)


def changed_paths(base_sha, head_sha, root=Path(".")):
    # A move must classify both the old and new paths, even if Git detects a rename.
    diff = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z", f"{base_sha}...{head_sha}", "--"],
        cwd=root,
    )
    return [os.fsdecode(path) for path in diff.split(b"\0") if path]


def selected_jobs(paths):
    selected = set()
    for path in paths:
        if path in ROUTING_FILES:
            return set(JOBS)
        if path in SHARED_GRADLE_FILES or path.startswith("gradle/"):
            selected.update((*APP_JOBS, "instrumented"))
        if path.startswith("app/"):
            selected.update(APP_JOBS)
        if path in MIGRATION_FILES or path.startswith(MIGRATION_PREFIXES):
            selected.add("instrumented")
        # App rendering depends on :gamification, so its changes can affect screenshots.
        if path.startswith("gamification/"):
            selected.update(("test", "visual-regression"))
        if path.startswith("functions/") or path == "firebase.json":
            selected.add("functions")
        if path.startswith("scripts/") or path in SCRIPT_WORKFLOWS:
            selected.add("scripts")
        if path.startswith("openspec/"):
            selected.add("openspec")
    return selected


def active_changes(paths, root=Path(".")):
    changes = {path.split("/", 3)[2] for path in paths if path.startswith("openspec/changes/")}
    # A deleted or archived change is absent from the checked-out tree.
    return sorted(
        change for change in changes - {"archive"}
        if (root / "openspec" / "changes" / change).is_dir()
    )


if __name__ == "__main__":
    paths = changed_paths(os.environ["BASE_SHA"], os.environ["HEAD_SHA"])
    selected = selected_jobs(paths)
    routing = {"jobs": {job: job in selected for job in JOBS}, "changes": active_changes(paths)}
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        print(f"routing={json.dumps(routing)}", file=output)
