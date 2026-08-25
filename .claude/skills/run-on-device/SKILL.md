---
name: run-on-device
description: Rebuild the Android app from a given worktree (or the current repo), install it on a connected device via Gradle, and launch it over ADB. Use when the user invokes this skill, asks to rebuild/install/launch the app on their phone, start the app from a worktree, or deploy the debug build to a device.
allowed-tools: Bash(git:*, gradlew:*, adb:*)
license: MIT
metadata:
  author: cline
  version: "1.0"
---

# Run On Device

Rebuild the Android app, install the debug APK on a connected device, and launch it over ADB.

## Usage

- `$run-on-device` — build + install + launch from the **current** repo directory.
- `$run-on-device <worktree>` — build + install + launch from the named worktree (e.g. `$run-on-device Backlogium-improve-search-relevance`).

Package id used for launch: `com.example.backlogium.debug` (the debug build applies an
`applicationIdSuffix`, so it installs side by side with the signed release app rather
than replacing it).

One-time cleanup of legacy debug installs: debug builds installed before this change
used the release package id `com.example.backlogium` (debug-signed). If such an install
is present on a device, export anything valuable from it first, then remove it with
`adb uninstall com.example.backlogium` before installing or restoring the signed
release build at that id.

## Workflow

### 1. Resolve the build directory

- If a `<worktree>` argument is given:
  - Find it under `D:\Codez\Projects\` (parent of this repo).
  - Verify it exists: `Test-Path <path>\gradlew.bat`.
  - Confirm it is on the expected feature branch: `git -C <path> rev-parse --abbrev-ref HEAD`.
- If no argument, use the current working directory.
- Report which directory and branch you are building from.

### 2. Build + Install (handle the 30s timeout)

Gradle builds can exceed the shell's 30s limit, so run it in the background and poll:

```powershell
cd <path>
Remove-Item -ErrorAction SilentlyContinue build_install.log, build_install_err.log
Start-Process -FilePath ".\gradlew.bat" -ArgumentList ":app:installDebug" `
  -RedirectStandardOutput build_install.log -RedirectStandardError build_install_err.log -NoNewWindow -PassThru
```

Then poll (e.g. `Start-Sleep -Seconds 25`) and read the logs until you see:

- `> Task :app:installDebug` … `Installed on 1 device.` and `BUILD SUCCESSFUL`.

If `BUILD FAILED`, read `build_install_err.log` and report the failure — do not proceed to launch.

### 3. Launch on device

```powershell
C:\Users\cnhyn\AppData\Local\Android\Sdk\platform-tools\adb.exe shell monkey -p com.example.backlogium.debug 1
```

Confirm `Events injected: 1` in the output.

### 4. Report

Summarize: build directory, branch, `BUILD SUCCESSFUL`, `Installed on <device>`, and launch confirmation.

## Guardrails

- **Run everything inside the target worktree.** Never switch branches in the original repo.
- **Do not launch if the build failed.** Stop and report the error.
- **Only commit log files if intended.** `build_install*.log` are build artifacts; prefer to leave them untracked.
- **Verify before reporting success.** Confirm the `installDebug` task and the monkey `Events injected` line actually appear in the output.