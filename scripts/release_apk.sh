#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/release_apk.sh <test|prod> [--upload] [--version-code CODE] [--version-name NAME]

Builds the selected channel, prepares update.json plus APK chunks, and optionally
uploads the prepared files to OSS when --upload is provided.
USAGE
}

if [ "$#" -lt 1 ]; then
  usage
  exit 64
fi

CHANNEL="$1"
shift
UPLOAD=false
VERSION_CODE_OVERRIDE="${APK_VERSION_CODE:-}"
VERSION_NAME_OVERRIDE="${APK_VERSION_NAME:-}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --upload)
      UPLOAD=true
      ;;
    --version-code)
      shift
      if [ "$#" -eq 0 ]; then
        usage
        exit 64
      fi
      VERSION_CODE_OVERRIDE="$1"
      ;;
    --version-name)
      shift
      if [ "$#" -eq 0 ]; then
        usage
        exit 64
      fi
      VERSION_NAME_OVERRIDE="$1"
      ;;
    *)
      usage
      exit 64
      ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi
if [ -n "${ANDROID_HOME:-}" ]; then
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
fi

require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "Missing required environment variable: $name" >&2
    exit 64
  fi
}

require_signing_env() {
  local prefix="$1"
  require_env "${prefix}_STORE_FILE"
  require_env "${prefix}_STORE_PASSWORD"
  require_env "${prefix}_KEY_ALIAS"
  require_env "${prefix}_KEY_PASSWORD"
}

normalize_endpoint() {
  local endpoint="$1"
  endpoint="${endpoint#https://}"
  endpoint="${endpoint#http://}"
  echo "${endpoint%/}"
}

resolve_base_url() {
  local bucket="$1"
  local endpoint="$2"
  local prefix="$3"
  echo "https://${bucket}.${endpoint}/${prefix}"
}

OSS_BUCKET="${OSS_BUCKET:-harness--zerg}"
OSS_ENDPOINT="$(normalize_endpoint "${OSS_ENDPOINT:-oss-ap-southeast-1.aliyuncs.com}")"
OSS_ACL="${OSS_ACL:-public-read}"
BASE_VERSION_CODE="$(sed -n 's/.*orElse(\([0-9][0-9]*\)).*/\1/p' app/build.gradle.kts | head -n 1)"
BASE_VERSION_NAME="$(sed -n 's/.*orElse("\([^"]*\)").*/\1/p' app/build.gradle.kts | head -n 1)"
VERSION_CODE="${VERSION_CODE_OVERRIDE:-$BASE_VERSION_CODE}"
BASE_VERSION_NAME="${VERSION_NAME_OVERRIDE:-$BASE_VERSION_NAME}"
if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
  echo "version-code must be a positive integer: $VERSION_CODE" >&2
  exit 64
fi
NOTES_DIR="build/release-notes"
mkdir -p "$NOTES_DIR"
NOTES_FILE="$NOTES_DIR/${CHANNEL}.txt"
if [ ! -s "$NOTES_FILE" ]; then
  git log --pretty=format:'%s' -n 8 > "$NOTES_FILE"
fi

case "$CHANNEL" in
  test)
    require_signing_env ANDROID_TEST
    BUILD_TASK=":app:assembleDebug"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    ARTIFACT_NAME="app-debug.apk"
    VERSION_NAME="${BASE_VERSION_NAME}-debug"
    OSS_PREFIX="${OSS_TEST_PREFIX:-${OSS_PREFIX:-harness-apk/test}}"
    OSS_PUBLIC_BASE_URL="${OSS_TEST_PUBLIC_BASE_URL:-${OSS_PUBLIC_BASE_URL:-https://www.zerg.work/harness-apk/test}}"
    GRADLE_MANIFEST_ARG="-PtestUpdateManifestUrl=${OSS_PUBLIC_BASE_URL%/}/update.json"
    ;;
  prod)
    require_signing_env ANDROID_RELEASE
    BUILD_TASK=":app:assembleRelease"
    ARTIFACT_NAME="app-release.apk"
    VERSION_NAME="${BASE_VERSION_NAME}"
    OSS_PREFIX="${OSS_PROD_PREFIX:-harness-apk/prod}"
    OSS_PUBLIC_BASE_URL="${OSS_PROD_PUBLIC_BASE_URL:-${OSS_PUBLIC_BASE_URL:-https://www.zerg.work/harness-apk/prod}}"
    GRADLE_MANIFEST_ARG="-PprodUpdateManifestUrl=${OSS_PUBLIC_BASE_URL%/}/update.json"
    ;;
  *)
    usage
    exit 64
    ;;
esac

./gradlew "$BUILD_TASK" "$GRADLE_MANIFEST_ARG" \
  "-PversionCodeOverride=${VERSION_CODE}" \
  "-PversionNameOverride=${BASE_VERSION_NAME}" \
  --console=plain --no-daemon

if [ "$CHANNEL" = "prod" ]; then
  APK_PATH="$(find app/build/outputs/apk/release -maxdepth 1 -name 'app-release*.apk' | sort | head -n 1)"
else
  APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi
if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
  echo "APK output not found for channel: $CHANNEL" >&2
  exit 66
fi
if [ "$CHANNEL" = "prod" ] && [ "$UPLOAD" = true ] && [[ "$APK_PATH" == *unsigned* ]]; then
  echo "Refusing to upload unsigned prod APK: $APK_PATH" >&2
  echo "Set ANDROID_RELEASE_STORE_FILE, ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS, and ANDROID_RELEASE_KEY_PASSWORD before prod upload." >&2
  exit 65
fi

OUTPUT_DIR="build/release-oss/${CHANNEL}"
python3 scripts/prepare_apk_release.py \
  --apk "$APK_PATH" \
  --output-dir "$OUTPUT_DIR" \
  --public-base-url "${OSS_PUBLIC_BASE_URL%/}" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --artifact-name "$ARTIFACT_NAME" \
  --min-supported-version-code "$VERSION_CODE" \
  --release-notes-file "$NOTES_FILE"

python3 -m json.tool "$OUTPUT_DIR/update.json" >/dev/null
cat "$OUTPUT_DIR/chunks/${ARTIFACT_NAME}.part-"* > "build/release-oss/${CHANNEL}-reassembled.apk"
cmp -s "build/release-oss/${CHANNEL}-reassembled.apk" "$APK_PATH"

if [ "$UPLOAD" = true ]; then
  require_env ALIYUN_ACCESS_KEY_ID
  require_env ALIYUN_ACCESS_KEY_SECRET
  OSS_PREFIX="$OSS_PREFIX" \
  OSS_BUCKET="$OSS_BUCKET" \
  OSS_ENDPOINT="$OSS_ENDPOINT" \
  OSS_ACL="$OSS_ACL" \
    python3 scripts/upload_to_oss.py "$OUTPUT_DIR"
else
  echo "Prepared ${CHANNEL} release at ${OUTPUT_DIR}"
  echo "Upload with: scripts/release_apk.sh ${CHANNEL} --upload"
fi
