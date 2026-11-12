# Backlogium agent instructions

Read [CLAUDE.md](CLAUDE.md) for repository architecture, invariants, and workflow.

## Android emulator for instrumented tests

Start local test/capture emulators only through `scripts/test-emulator.sh start`
on macOS/Linux or `scripts/test-emulator.ps1 start` on Windows. See
[the launcher guide](docs/test-emulator.md) for SDK, AVD, port, and GPU overrides.
The launcher waits for boot and verifies the renderer inside the guest; do not
proceed with tests when it fails. `status` checks a running emulator, `restart`
replaces one, and `stop` shuts down the selected emulator. Before restarting or
stopping a reused emulator, ensure another task is not using it.

Use `-gpu host` explicitly. Do not launch local emulators with `swiftshader*`,
`swangle`, `lavapipe`, `software`, or without `-gpu`. Headless `auto` can select
CPU rendering. On this Mac the launcher also uses `-feature GuestAngle`.
Windows GPU mode requires an explicit override until it is benchmarked there.
Only use `BACKLOGIUM_ALLOW_SOFTWARE_GPU=1` deliberately on a host without a
usable GPU; it is never an automatic fallback.

Always set `ANDROID_SERIAL` to the launcher's reported serial and pass
`-Pandroid.testInstrumentationRunnerArguments.serial=<serial>` to Gradle
`connectedDebugAndroidTest` (`-Pandroid.injected.device.serial=<serial>` for
`installDebug`). Use `adb -s <serial>` for every device command. This prevents
test APKs from landing on a phone attached over wireless ADB. Do not run an
untargeted connected test or install when other devices are attached.
`ANDROID_SERIAL` selects the connected test device; the instrumentation runner
argument alone does not filter devices. Avoid the task's `--serial` option with
the current AGP 9.3.0: it throws while removing a serial from an immutable list.
Use the environment variable instead; see the launcher guide for verification.

The GitHub-hosted CI emulator intentionally uses software rendering because its
runner has no GPU. Leave `.github/workflows/ci.yml` emulator options unchanged;
these local launch rules do not apply to that job. If CI ever uses the launcher,
set its software-GPU escape hatch explicitly.
