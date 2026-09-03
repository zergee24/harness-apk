#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REMOTE_DIR="$REPO_DIR/remote"
LOG_DIR="${HARNESS_M3_LOG_DIR:-/tmp/harness-m3-acceptance-$(date +%Y%m%d-%H%M%S)}"

EXPECTED_AVD="HarnessM3Api36"
EXPECTED_ADB_PORT="5041"
EXPECTED_SERIAL="emulator-15672"
EXPECTED_CONSOLE_PORT="15672"
EXPECTED_EMULATOR_ADB_PORT="15673"
EXPECTED_LOCAL_MAX_PORT="5553"

M3_AVD="${HARNESS_M3_AVD:-$EXPECTED_AVD}"
ADB_SERVER_PORT="${HARNESS_M3_ADB_SERVER_PORT:-$EXPECTED_ADB_PORT}"
M3_SERIAL="${HARNESS_M3_SERIAL:-$EXPECTED_SERIAL}"
CONSOLE_PORT="${HARNESS_M3_EMULATOR_CONSOLE_PORT:-$EXPECTED_CONSOLE_PORT}"
EMULATOR_ADB_PORT="${HARNESS_M3_EMULATOR_ADB_PORT:-$EXPECTED_EMULATOR_ADB_PORT}"
ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
DEFAULT_GO_BIN="$(command -v go 2>/dev/null || true)"
if [[ -z "$DEFAULT_GO_BIN" && -x /Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go ]]; then
  DEFAULT_GO_BIN=/Users/tony/.local/share/harness-apk-m2/go1.26.5/bin/go
fi
GO_BIN="${HARNESS_M3_GO_BIN:-$DEFAULT_GO_BIN}"

fail() {
  echo "M3 isolation failure: $*" >&2
  exit 2
}

[[ "$M3_AVD" == "$EXPECTED_AVD" ]] || fail "AVD must be $EXPECTED_AVD"
[[ "$ADB_SERVER_PORT" == "$EXPECTED_ADB_PORT" ]] || fail "adb server port must be $EXPECTED_ADB_PORT"
[[ "$M3_SERIAL" == "$EXPECTED_SERIAL" ]] || fail "serial must be $EXPECTED_SERIAL"
[[ "$CONSOLE_PORT" == "$EXPECTED_CONSOLE_PORT" ]] || fail "console port must be $EXPECTED_CONSOLE_PORT"
[[ "$EMULATOR_ADB_PORT" == "$EXPECTED_EMULATOR_ADB_PORT" ]] || fail "emulator adb port must be $EXPECTED_EMULATOR_ADB_PORT"
[[ "${ADB_LOCAL_TRANSPORT_MAX_PORT:-}" == "$EXPECTED_LOCAL_MAX_PORT" ]] || fail "ADB_LOCAL_TRANSPORT_MAX_PORT must be $EXPECTED_LOCAL_MAX_PORT"
[[ -n "$ANDROID_SDK_DIR" ]] || fail "ANDROID_SDK_ROOT or ANDROID_HOME is required"
[[ -x "$GO_BIN" ]] || fail "Go toolchain is required; set HARNESS_M3_GO_BIN"

ADB_BIN="$ANDROID_SDK_DIR/platform-tools/adb"
[[ -x "$ADB_BIN" ]] || fail "adb not found at $ADB_BIN"

mkdir -p "$LOG_DIR"

cleanup() {
  set +e
  "$ADB_BIN" -P "$ADB_SERVER_PORT" -s "$M3_SERIAL" emu kill >/dev/null 2>&1
  for _ in {1..30}; do
    if ! lsof -nP -iTCP:"$CONSOLE_PORT" -sTCP:LISTEN >/dev/null 2>&1 &&
       ! lsof -nP -iTCP:"$EMULATOR_ADB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  "$ADB_BIN" -P "$ADB_SERVER_PORT" -s "$M3_SERIAL" kill-server >/dev/null 2>&1
  for port in "$ADB_SERVER_PORT" "$CONSOLE_PORT" "$EMULATOR_ADB_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "M3 cleanup failure: port $port is still listening" >&2
      exit 3
    fi
  done
}
trap cleanup EXIT INT TERM

DEVICE_LINES="$("$ADB_BIN" -P "$ADB_SERVER_PORT" -s "$M3_SERIAL" devices | awk 'NR > 1 && NF { print $1 " " $2 }')"
[[ "$DEVICE_LINES" == "$M3_SERIAL device" ]] || fail "isolated server must contain exactly $M3_SERIAL device; found ${DEVICE_LINES:-none}"

ACTUAL_AVD="$("$ADB_BIN" -P "$ADB_SERVER_PORT" -s "$M3_SERIAL" emu avd name | tr -d '\r' | head -1)"
[[ "$ACTUAL_AVD" == "$M3_AVD" ]] || fail "serial $M3_SERIAL belongs to AVD ${ACTUAL_AVD:-unknown}"
[[ "$("$ADB_BIN" -P "$ADB_SERVER_PORT" -s "$M3_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == "1" ]] || fail "emulator is not fully booted"

export ANDROID_ADB_SERVER_PORT="$ADB_SERVER_PORT"
export ANDROID_SERIAL="$M3_SERIAL"
export ADB_LOCAL_TRANSPORT_MAX_PORT="$EXPECTED_LOCAL_MAX_PORT"
export GOTOOLCHAIN=local

cd "$REPO_DIR"
./gradlew :app:testDebugUnitTest :app:assembleDebug -PversionNameOverride=0.4.0 --console=plain 2>&1 | tee "$LOG_DIR/android-jvm-assemble.log"
./gradlew :app:connectedDebugAndroidTest -PversionNameOverride=0.4.0 --console=plain 2>&1 | tee "$LOG_DIR/android-connected.log"

cd "$REMOTE_DIR"
"$GO_BIN" test -race ./... 2>&1 | tee "$LOG_DIR/go-race.log"
"$GO_BIN" vet ./... 2>&1 | tee "$LOG_DIR/go-vet.log"
"$GO_BIN" build ./cmd/relay ./cmd/bridge 2>&1 | tee "$LOG_DIR/go-build.log"

echo "M3 automated acceptance passed: avd=$M3_AVD adb=$ADB_SERVER_PORT serial=$M3_SERIAL console=$CONSOLE_PORT emulatorAdb=$EMULATOR_ADB_PORT logs=$LOG_DIR"
