#!/usr/bin/env bash
# Adapted from AirchiveApp/scripts/test-emulator.sh (AirchiveApp#8).
# See docs/test-emulator.md for overrides and Windows verification limits.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${BACKLOGIUM_TEST_AVD:-Medium_Phone_API_37.0}"
PORT="${BACKLOGIUM_TEST_EMULATOR_PORT:-5554}"
SERIAL="emulator-$PORT"
GPU_ARGS="${BACKLOGIUM_TEST_EMULATOR_GPU_ARGS:--gpu host -feature GuestAngle}"
BOOT_TIMEOUT="${BACKLOGIUM_TEST_EMULATOR_BOOT_TIMEOUT:-300}"
LOG="${TMPDIR:-/tmp}/backlogium-test-emulator-$PORT.log"
ALLOW_SOFTWARE="${BACKLOGIUM_ALLOW_SOFTWARE_GPU:-0}"

die() { echo "test-emulator: $1" >&2; exit "${2:-1}"; }
if ! [[ "$PORT" =~ ^[0-9]{4}$ ]] || (( PORT < 5554 || PORT > 5682 || PORT % 2 != 0 )); then
  die "port must be even and between 5554 and 5682" 2
fi
[[ "$BOOT_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || die "boot timeout must be a positive number of seconds" 2
[[ -x "$ADB" && -x "$EMULATOR" ]] || die "Android tools missing under $SDK (set ANDROID_HOME)"

is_online() { [[ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = device ]]; }
is_present() {
  local devices
  devices="$("$ADB" devices)" || die "adb devices failed"
  tr -d '\r' <<< "$devices" | grep -qE "^${SERIAL}[[:space:]]"
}

renderer_details() {
  # Read only renderer fields, not unrelated layer or extension names. GuestAngle
  # uses the Vulkan RenderEngine Name field instead of a GLES renderer line.
  "$ADB" -s "$SERIAL" shell dumpsys SurfaceFlinger 2>/dev/null \
    | tr -d '\r' | grep -E 'GLES:|RE Vulkan|^[[:space:]]*Name: ' || true
}

renderer_class() {
  local details="$1"
  if grep -qiE 'swiftshader|lavapipe|llvmpipe|swangle|software' <<< "$details"; then
    echo software
  elif grep -qiE 'apple|metal|moltenvk|nvidia|amd|radeon|intel|adreno|mali' <<< "$details"; then
    echo gpu
  else
    echo unknown
  fi
}

require_gpu() {
  local details cls
  details="$(renderer_details)"
  cls="$(renderer_class "$details")"
  echo "test-emulator: $SERIAL renderer=$cls"
  printf '%s\n' "$details"
  [[ "$cls" = gpu ]] && return 0
  if [[ "$ALLOW_SOFTWARE" = 1 && "$cls" = software ]]; then
    echo 'test-emulator: WARNING software GPU explicitly accepted by BACKLOGIUM_ALLOW_SOFTWARE_GPU=1' >&2
    return 0
  fi
  # Never trust an old launch log as proof about the current guest.
  die "$SERIAL renderer is $cls; host GPU could not be confirmed.
  When no other task is using it, run: scripts/test-emulator.sh restart" 3
}

wait_for_boot() {
  local started=$SECONDS
  until is_online && [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]]; do
    (( SECONDS - started < BOOT_TIMEOUT )) || die "boot timed out after ${BOOT_TIMEOUT}s; log: $LOG"
    sleep 2
  done
  echo "test-emulator: $SERIAL booted in $(( SECONDS - started ))s"
}

start() {
  if is_present; then
    echo "test-emulator: reusing $SERIAL"
  else
    local -a gpu_flags
    read -r -a gpu_flags <<< "$GPU_ARGS"
    if [[ "$ALLOW_SOFTWARE" != 1 ]]; then
      [[ " $GPU_ARGS " = *' -gpu host '* ]] \
        && ! grep -qiE 'swiftshader|lavapipe|llvmpipe|swangle|software|[[:space:]]auto([[:space:]]|$)' <<< "$GPU_ARGS" \
        && [[ "$(grep -o -- '-gpu' <<< "$GPU_ARGS" | wc -l | tr -d ' ')" = 1 ]] \
        || die "GPU args must include exactly one '-gpu host'; software rendering needs BACKLOGIUM_ALLOW_SOFTWARE_GPU=1" 2
    fi
    echo "test-emulator: launching $AVD on $SERIAL with: $GPU_ARGS (log: $LOG)"
    # Cold boot so a saved software-rendered snapshot cannot override the mode.
    nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio \
      -no-boot-anim -no-snapshot "${gpu_flags[@]}" </dev/null >"$LOG" 2>&1 &
  fi
  wait_for_boot
  require_gpu
  echo "test-emulator: ready. Use: export ANDROID_SERIAL=$SERIAL"
}

status() {
  is_online || { echo "test-emulator: $SERIAL not online"; return 1; }
  require_gpu
  echo "ANDROID_SERIAL=$SERIAL"
}

stop() {
  is_present || { echo "test-emulator: $SERIAL not running"; return 0; }
  "$ADB" -s "$SERIAL" emu kill >/dev/null || die "could not stop $SERIAL"
  local started=$SECONDS
  while is_present; do
    (( SECONDS - started < 30 )) || die "$SERIAL did not exit"
    sleep 1
  done
  echo "test-emulator: stopped $SERIAL"
}

case "${1:-start}" in
  start) start ;;
  status) status ;;
  stop) stop ;;
  restart) stop; start ;;
  *) die "usage: $0 [start|status|restart|stop]" 2 ;;
esac
