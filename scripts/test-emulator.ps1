# Native Windows counterpart of test-emulator.sh. See docs/test-emulator.md.
param([ValidateSet('start', 'status', 'restart', 'stop')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'

function Get-Setting([string]$Name, [string]$Fallback) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrEmpty($value)) { return $Fallback }
    return $value
}

$sdk = Get-Setting 'ANDROID_HOME' (Get-Setting 'ANDROID_SDK_ROOT' "$env:LOCALAPPDATA\Android\Sdk")
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$avd = Get-Setting 'BACKLOGIUM_TEST_AVD' 'Medium_Phone_API_35'
$portText = Get-Setting 'BACKLOGIUM_TEST_EMULATOR_PORT' '5554'
$timeoutText = Get-Setting 'BACKLOGIUM_TEST_EMULATOR_BOOT_TIMEOUT' '300'
$allowSoftware = $env:BACKLOGIUM_ALLOW_SOFTWARE_GPU -eq '1'
# Windows host/GuestAngle performance has not been measured. Require an explicit
# selection until a Windows benchmark establishes the default (issue #185).
$gpuArgs = Get-Setting 'BACKLOGIUM_TEST_EMULATOR_GPU_ARGS' ''
if ($portText -notmatch '^\d{4}$' -or [int]$portText -lt 5554 -or [int]$portText -gt 5682 -or [int]$portText % 2 -ne 0) {
    throw 'Port must be even and between 5554 and 5682'
}
if ($timeoutText -notmatch '^[1-9]\d*$') { throw 'Boot timeout must be a positive number of seconds' }
$serial = "emulator-$portText"
$log = Join-Path ([IO.Path]::GetTempPath()) "backlogium-test-emulator-$portText.log"
if (!(Test-Path $adb) -or !(Test-Path $emulator)) { throw "Android tools missing under $sdk (set ANDROID_HOME)" }

function Test-Online {
    $state = & $adb -s $serial get-state 2>$null
    return $LASTEXITCODE -eq 0 -and "$state".Trim() -eq 'device'
}

function Test-Present {
    $devices = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
    return [bool]($devices -match "^$serial\s")
}

function Assert-Gpu {
    $dump = & $adb -s $serial shell dumpsys SurfaceFlinger
    $details = @($dump | Where-Object { $_ -match 'GLES:|RE Vulkan|^\s*Name: ' }) -join "`n"
    $class = 'unknown'
    if ($LASTEXITCODE -eq 0) {
        if ($details -match 'swiftshader|lavapipe|llvmpipe|swangle|software') { $class = 'software' }
        elseif ($details -match 'apple|metal|moltenvk|nvidia|amd|radeon|intel|adreno|mali') { $class = 'gpu' }
    }
    Write-Host "test-emulator: $serial renderer=$class"
    Write-Host $details
    if ($class -eq 'gpu') { return }
    if ($allowSoftware -and $class -eq 'software') {
        Write-Warning 'Software GPU explicitly accepted by BACKLOGIUM_ALLOW_SOFTWARE_GPU=1'
        return
    }
    throw "$serial renderer is $class; host GPU could not be confirmed. When unused, run: scripts/test-emulator.ps1 restart"
}

function Wait-Boot {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt [int]$timeoutText) {
        if (Test-Online) {
            $boot = & $adb -s $serial shell getprop sys.boot_completed
            if ($LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1') {
                Write-Host "test-emulator: $serial booted in $([int]$timer.Elapsed.TotalSeconds)s"
                return
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "Boot timed out after ${timeoutText}s; log: $log"
}

function Start-TestEmulator {
    if (Test-Present) { Write-Host "test-emulator: reusing $serial" }
    else {
        if (!$gpuArgs) {
            throw "Set BACKLOGIUM_TEST_EMULATOR_GPU_ARGS explicitly to '-gpu host' or '-gpu host -feature GuestAngle'. Windows benchmark pending; see docs/test-emulator.md."
        }
        $flags = @($gpuArgs.Trim() -split '\s+')
        if (!$allowSoftware -and (
            " $gpuArgs " -notmatch '\s-gpu\s+host\s' -or
            $gpuArgs -match 'swiftshader|lavapipe|llvmpipe|swangle|software|\bauto\b' -or
            @($flags | Where-Object { $_ -eq '-gpu' }).Count -ne 1)) {
            throw "GPU args must include exactly one '-gpu host'; software rendering needs BACKLOGIUM_ALLOW_SOFTWARE_GPU=1"
        }
        # Start-Process joins arguments on Windows; quote the AVD name explicitly.
        if ($avd.Contains('"')) { throw 'AVD name cannot contain quotes' }
        Write-Host "test-emulator: launching $avd on $serial with: $gpuArgs (log: $log)"
        Start-Process -FilePath $emulator -ArgumentList (@('-avd', "`"$avd`"", '-port', $portText,
            '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot') + $flags) `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
    }
    Wait-Boot
    Assert-Gpu
    Write-Host "test-emulator: ready. Use: `$env:ANDROID_SERIAL = '$serial'"
}

function Stop-TestEmulator {
    if (!(Test-Present)) { Write-Host "test-emulator: $serial not running"; return }
    & $adb -s $serial emu kill | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not stop $serial" }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (Test-Present) {
        if ($timer.Elapsed.TotalSeconds -ge 30) { throw "$serial did not exit" }
        Start-Sleep -Seconds 1
    }
    Write-Host "test-emulator: stopped $serial"
}

try {
    switch ($Action) {
        'start' { Start-TestEmulator }
        'status' {
            if (!(Test-Online)) { throw "$serial not online" }
            Assert-Gpu
            Write-Host "ANDROID_SERIAL=$serial"
        }
        'restart' { Stop-TestEmulator; Start-TestEmulator }
        'stop' { Stop-TestEmulator }
    }
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
