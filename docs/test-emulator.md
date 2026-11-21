# Local Android test emulator

Issue [#185](https://github.com/cnhy-nero-diskard/Backlogium/issues/185) tracks CPU
rendering in agent-started headless emulators. The launcher is adapted from
[AirchiveApp's launcher](https://github.com/cnhy-nero-diskard/AirchiveApp/blob/main/scripts/test-emulator.sh).
It boots with an explicit GPU mode, waits for `sys.boot_completed`, and checks
the actual guest renderer. Software and unrecognized renderers fail the command;
a stale emulator log cannot bypass the check.

## macOS

```sh
scripts/test-emulator.sh start
scripts/test-emulator.sh status
export ANDROID_SERIAL=emulator-5554  # use the serial printed by start
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.serial="$ANDROID_SERIAL"
```

`start` reuses the emulator at the selected port, including waiting for an
already-starting guest. It rejects a reused software-rendered emulator. Use
`restart` to replace it only when another task is not using it. `stop` shuts down
only the emulator at the selected port. Every ADB command uses `-s`.

The macOS default AVD is `Medium_Phone_API_37.0`, configured on the maintainer's
Mac with an ARM64 image. Backlogium requires API 33 or newer; `Airchive_API_27`
cannot run it. Launches use `-gpu host -feature GuestAngle -no-snapshot`; a cold
boot avoids restoring graphics state from a software-rendered snapshot.

## Windows

```powershell
$env:BACKLOGIUM_TEST_AVD = 'Medium_Phone_API_35' # select an installed API 33+ AVD
$env:BACKLOGIUM_TEST_EMULATOR_GPU_ARGS = '-gpu host' # explicit unbenchmarked choice
scripts/test-emulator.ps1 start
scripts/test-emulator.ps1 status
$env:ANDROID_SERIAL = 'emulator-5554' # use the serial printed by start
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.serial=$env:ANDROID_SERIAL"
```

The native PowerShell launcher has the same four commands and renderer guard.
It defaults to the historical Windows AVD `Medium_Phone_API_35`. It deliberately
requires an explicit GPU-args selection for a new launch: **Windows performance
has not been measured, so there is no benchmark-backed Windows GPU default yet.**
Successful Mac verification does not establish Windows performance or support.

Before choosing that default, benchmark `-gpu host` and `-gpu host -feature
GuestAngle` on a Windows GPU host, using `restart` between modes. Record the
Windows version, GPU/driver, emulator version, AVD/API/architecture, guest
renderer, median Settings cold-start time over five launches, scrolling jank and
frame-time percentiles from `dumpsys gfxinfo`, idle emulator CPU, and the same
UI-heavy instrumented test duration in both modes. Retain the raw measurements
and pick the faster verified hardware mode. This acceptance item in #185 remains
open until those measurements exist; do not infer numbers from Airchive's Mac
benchmark.

`ANDROID_SERIAL` selects the connected test device. The instrumentation runner
argument shown above is forwarded to the runner; it does not independently
filter Gradle's devices. With this repository's AGP 9.3.0, the task's `--serial`
option fails before testing: `DeviceProviderInstrumentTestTask.getFilteredDevices`
calls `ImmutableCollection.remove`, throwing `UnsupportedOperationException`.
Use `ANDROID_SERIAL` instead of that option. Both paths were exercised locally;
this is separate from GPU configuration and does not require changing AGP for
the launcher work.

## Overrides

| Environment variable | Default / purpose |
|---|---|
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | SDK directory; otherwise `~/Library/Android/sdk` on Mac or `%LOCALAPPDATA%\Android\Sdk` on Windows. Set explicitly on Linux. |
| `BACKLOGIUM_TEST_AVD` | `Medium_Phone_API_37.0` (shell), `Medium_Phone_API_35` (Windows). Must already exist. |
| `BACKLOGIUM_TEST_EMULATOR_PORT` | `5554`; even ports from 5554 through 5682. |
| `BACKLOGIUM_TEST_EMULATOR_GPU_ARGS` | `-gpu host -feature GuestAngle` (shell); explicit selection required on Windows. Whitespace-separated flags. |
| `BACKLOGIUM_TEST_EMULATOR_BOOT_TIMEOUT` | `300` seconds. |
| `BACKLOGIUM_ALLOW_SOFTWARE_GPU` | Set to `1` only deliberately on a host without a usable GPU. Accepts a known software renderer; unknown renderers still fail. |

Logs go to the OS temporary directory as `backlogium-test-emulator-<port>.log`
(PowerShell also writes `.log.err`). The launcher does not change the AVD config
or install APKs. For installs, set `ANDROID_SERIAL` and pass
`-Pandroid.injected.device.serial=<serial>` to `:app:installDebug`, then use
`adb -s <serial>` to launch or inspect the app.

## CI

The GitHub-hosted emulator runner in `.github/workflows/ci.yml` intentionally
uses `-gpu swiftshader_indirect` because it has no host GPU. Its options remain
unchanged. The local guard does not apply to that workflow; if CI ever invokes
these launchers, configure the software-GPU escape hatch explicitly.

## Verification on 2026-11-12

Host: Apple M5, macOS 27.2.1 ARM64; Android Emulator 37.2.13.0;
`Medium_Phone_API_37.0` ARM64 AVD; Gradle 9.5.0, AGP 9.3.0, Android Studio JBR 25.

| Check | Result |
|---|---|
| Real `start`, `status`, reuse, and `stop` | Passed; cold boots took 6–9 seconds. Guest SurfaceFlinger reported `RE Vulkan (Ganesh)` and `Name: Goldfish GFXStream (Apple M5)`. |
| Targeted instrumented smoke/UI checks | Four passed in a 14-second Gradle run: `ExampleInstrumentedTest` and `HomeLoadingPresentationTest`. Used `ANDROID_SERIAL=emulator-5554`, the runner serial argument, and the class filter. This was not the full instrumentation suite. |
| Launcher regression checks | Twelve passed, covering simulated SwiftShader/lavapipe/llvmpipe/swangle rejection, unknown renderer rejection despite a stale host log, explicit escape hatch, GPU launch flags, boot timeout, restart/stop, invalid ports, and wireless-phone isolation. No real software-rendered guest was started on this GPU-capable Mac. |
| Existing plus new Python script tests | All 47 passed with `python3 -m unittest discover -s scripts`. |
| Shell checks | `bash -n scripts/test-emulator.sh` and `shellcheck scripts/test-emulator.sh` passed. |
| PowerShell checks on Mac | PowerShell 7.7 parser accepted the script; seven mocked SDK cases passed (host reuse, software rejection, unknown rejection, escape hatch, stop, missing Windows GPU selection, and launching a mock emulator). This does not verify native Windows process launching. |
| CI | `.github/workflows/ci.yml` unchanged. |

Windows hardware launch validation and the host-vs-GuestAngle benchmark remain
unperformed. Issue #185's Windows acceptance item remains open.
