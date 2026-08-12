#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REMOTE_DIR="$REPO_DIR/remote"
ADB_SERVER_PORT="${HARNESS_M2_ADB_SERVER_PORT:?set HARNESS_M2_ADB_SERVER_PORT to the isolated server port}"
M2_SERIAL="${HARNESS_M2_SERIAL:?set HARNESS_M2_SERIAL to the isolated emulator serial}"
ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [[ -z "$ANDROID_SDK_DIR" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
  exit 2
fi
if [[ "$ADB_SERVER_PORT" == "5037" ]]; then
  echo "refusing to use the default adb server port 5037" >&2
  exit 2
fi
if [[ "$M2_SERIAL" != emulator-* ]]; then
  echo "automated acceptance requires the dedicated M2 emulator serial" >&2
  exit 2
fi

ADB_BIN="$ANDROID_SDK_DIR/platform-tools/adb"
if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found at $ADB_BIN" >&2
  exit 2
fi

CONNECTED_SERIALS="$($ADB_BIN -P "$ADB_SERVER_PORT" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [[ "$CONNECTED_SERIALS" != "$M2_SERIAL" ]]; then
  echo "isolated adb server must contain exactly $M2_SERIAL; found: ${CONNECTED_SERIALS:-none}" >&2
  exit 2
fi

export ANDROID_ADB_SERVER_PORT="$ADB_SERVER_PORT"
export ANDROID_SERIAL="$M2_SERIAL"
export ADB_LOCAL_TRANSPORT_MAX_PORT="${ADB_LOCAL_TRANSPORT_MAX_PORT:-5553}"

cd "$REPO_DIR"
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest

cd "$REMOTE_DIR"
go test -race ./...
go vet ./...
go build ./cmd/relay ./cmd/bridge

echo "M2 automated acceptance passed on adb $ADB_SERVER_PORT / $M2_SERIAL"
