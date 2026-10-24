#!/usr/bin/env bash
# Installs the dsh appserver backend (M4 G2) into a dsh profile so the Mac
# bridge can drive DeepSeek Harness through the canonical app-server
# JSON-RPC surface:
#
#   harness-bridge serve --backend codex --backend dsh
#
# Idempotent: re-running updates the plugin copy and patch layer.
#
# Env:
#   APPSERVER_PROFILE_DIR  profile directory (default ~/.dsh/profiles/appserver).
#                          Deliberately NOT DSH_PROFILE_DIR: a shell spawned by
#                          the Desktop app exports that as the desktop profile,
#                          and inheriting it installs this backend into Desktop.
#   DSH_CLI                dsh executable that will run this profile (default:
#                          `dsh` on PATH). It must be the same generation as the
#                          Desktop bundle; the bridge hosts its own copy, e.g.
#                          ~/.dsh/cli/node_modules/@deepseek-ai/dsh/lib/bin.js.
#   NPM_REGISTRY           registry for the one-time profile initialization
#                          (default https://registry.npmjs.org/; the machine
#                          npmrc often points at an unreachable internal one)
#   HTTPS_PROXY            used for the registry fetch when set
#   ALLOW_DSH_VERSION_MISMATCH=1
#                          install even when the CLI generation differs from the
#                          Desktop bundle (plugins get disabled at boot by dsh's
#                          peer version guard; use for diagnosis only)
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/appserver" && pwd)"
PROFILE="${APPSERVER_PROFILE_DIR:-$HOME/.dsh/profiles/appserver}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}"
DSH_CLI="${DSH_CLI:-dsh}"
# The Desktop app moved its bundled pnpm between releases; only profile
# initialization needs it, so neither layout is required for an upgrade.
APP_PNPM_LEGACY="/Applications/DeepSeek Harness.app/Contents/Resources/runtime/pnpm/bin/pnpm.mjs"
APP_PNPM_CURRENT="/Applications/DeepSeek Harness.app/Contents/Resources/runtime/primary-runtime/dependencies/pnpm/bin/pnpm.mjs"
APP_NODE="/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness"
VENDOR_TOOL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/vendor_from_bundle.py"

command -v "$DSH_CLI" >/dev/null 2>&1 || {
  echo "error: dsh CLI not found ($DSH_CLI); set DSH_CLI to the executable the bridge uses" >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || { echo "error: python3 not found on PATH" >&2; exit 1; }

if [ ! -f "$PROFILE/package.json" ]; then
  echo "initializing profile $PROFILE"
  APP_PNPM=""
  for candidate in "$APP_PNPM_CURRENT" "$APP_PNPM_LEGACY"; do
    if [ -f "$candidate" ] && [ -x "$APP_NODE" ]; then APP_PNPM="$candidate"; break; fi
  done
  if [ -n "$APP_PNPM" ]; then
    pnpm() { ELECTRON_RUN_AS_NODE=1 "$APP_NODE" --expose-internals "$APP_PNPM" "$@"; }
  elif command -v pnpm >/dev/null 2>&1; then
    pnpm() { command pnpm "$@"; }
  else
    echo "error: cannot initialize the profile: no bundled pnpm and no pnpm on PATH" >&2
    exit 1
  fi
  "$DSH_CLI" plugin --profile appserver add "$PLUGIN_DIR" >/dev/null
fi

echo "vendoring the dsh generation the Desktop app ships"
# The plugin imports `@deepseek-ai/dsh-agent` and friends, and those packages
# import `@deepseek-ai/cordis` and `@deepseek-ai/cosmokit`, which are NOT
# published to npm. Installing them from the registry therefore produces a
# profile whose plugin fails to import at boot. The Desktop bundle carries the
# whole generation, so the profile is filled from it instead — same versions as
# the app it cooperates with, no registry needed for the dsh packages.
if [ ! -f "$VENDOR_TOOL" ]; then
  echo "error: vendor tool not found at $VENDOR_TOOL" >&2
  exit 1
fi
# The bundle is the source of truth for the generation. Pinning a release here
# let the profile drift behind a Desktop auto-update; the mismatch surfaces as
# "event type ... unknown to this harness" when reading newer session logs, and
# thread/read + thread/turns/list then fail (the phone shows no history and no
# status for dsh threads).
DSH_PACKAGE_VERSION="$(python3 "$VENDOR_TOOL" --version)" || {
  echo "error: cannot read the dsh generation from the Desktop bundle" >&2
  exit 1
}
echo "Desktop bundle generation: $DSH_PACKAGE_VERSION"

# The profile loader comes from the CLI, and dsh's peer version guard disables
# plugins whose generation differs from the runtime. Check before touching the
# profile so a mismatched CLI fails fast instead of leaving a half-upgraded
# profile behind.
CLI_VERSION="$("$DSH_CLI" --version 2>/dev/null | head -n 1 | tr -d '[:space:]')"
if [ "$CLI_VERSION" != "$DSH_PACKAGE_VERSION" ]; then
  echo "error: dsh CLI is ${CLI_VERSION:-unknown}, Desktop bundle generation is $DSH_PACKAGE_VERSION" >&2
  echo "       update the CLI the bridge launches, for example:" >&2
  echo "         npm install --prefix ~/.dsh/cli @deepseek-ai/dsh@$DSH_PACKAGE_VERSION" >&2
  echo "       or set ALLOW_DSH_VERSION_MISMATCH=1 to install anyway (diagnosis only)." >&2
  if [ "${ALLOW_DSH_VERSION_MISMATCH:-0}" != "1" ]; then
    exit 1
  fi
fi

python3 "$VENDOR_TOOL" "$PROFILE/node_modules" >/dev/null

# The appserver resumes and interrupts sessions through dsh's own Agent API, so
# the profile must carry the same dsh generation as the Desktop bundle it
# cooperates with; a stale profile silently lacks newer Agent surface.
echo "verifying installed dsh generation"
for pkg in dsh-base dsh-agent dsh-cmdline dsh-llm dsh-session; do
  installed="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$PROFILE/node_modules/@deepseek-ai/$pkg/package.json" 2>/dev/null | head -n 1)"
  if [ "$installed" != "$DSH_PACKAGE_VERSION" ]; then
    echo "error: @deepseek-ai/$pkg is ${installed:-missing}, expected $DSH_PACKAGE_VERSION" >&2
    exit 1
  fi
done

# The profile loader resolves plugin imports from the profile's node_modules;
# a pnpm link: dependency would resolve from the repo instead, so copy the
# plugin verbatim (the repo stays the source of truth). The destination is
# replaced rather than overwritten: a module removed here must not linger in
# the profile and get imported by a later hand-written patch.
echo "installing dsh-appserver plugin"
PLUGIN_FILES="package.json index.js startup.js translate.js persist.js thread-list.js turn-queue.js"
rm -rf "$PROFILE/node_modules/dsh-appserver"
mkdir -p "$PROFILE/node_modules/dsh-appserver"
for file in $PLUGIN_FILES; do
  cp "$PLUGIN_DIR/$file" "$PROFILE/node_modules/dsh-appserver/$file"
done

cat > "$PROFILE/cordis.patch.yml" <<'YAML'
# dsh appserver backend (M4): codex-compatible stdio JSON-RPC over dsh-base.
- insert:
    - id: appserver-startup
      name: dsh-appserver/startup

    - id: appserver-runner
      name: dsh-appserver
      inject: [appserverStartup]
      config:
        listen: !!js ctx.appserverStartup.listen
YAML

echo "dsh appserver profile ready: $PROFILE ($DSH_PACKAGE_VERSION)"
echo "smoke: $DSH_CLI --profile appserver --listen stdio://"
echo "bridge: harness-bridge serve --backend codex --backend dsh"
