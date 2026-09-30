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
#   NPM_REGISTRY           pnpm registry (default https://registry.npmjs.org/;
#                          the default machine npmrc often points at an
#                          unreachable internal registry, so this script always
#                          overrides it)
#   HTTPS_PROXY            used for the registry fetch when set
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/appserver" && pwd)"
PROFILE="${APPSERVER_PROFILE_DIR:-$HOME/.dsh/profiles/appserver}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}"
# Keep in lockstep with the Desktop bundle this profile cooperates with.
DSH_PACKAGE_VERSION="0.2.0-rc.2"
# Prefer the Desktop app's own pnpm: it matches the manager that owns this
# profile and works without corepack's network shim.
APP_PNPM="/Applications/DeepSeek Harness.app/Contents/Resources/runtime/pnpm/bin/pnpm.mjs"
APP_NODE="/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness"
VENDOR_TOOL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/vendor_from_bundle.py"

command -v dsh >/dev/null 2>&1 || { echo "error: dsh CLI not found on PATH" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "error: python3 not found on PATH" >&2; exit 1; }
if [ -f "$APP_PNPM" ] && [ -x "$APP_NODE" ]; then
  pnpm() { ELECTRON_RUN_AS_NODE=1 "$APP_NODE" --expose-internals "$APP_PNPM" "$@"; }
else
  command -v pnpm >/dev/null 2>&1 || { echo "error: pnpm not found on PATH" >&2; exit 1; }
  pnpm() { command pnpm "$@"; }
fi

if [ ! -f "$PROFILE/package.json" ]; then
  echo "initializing profile $PROFILE"
  dsh plugin --profile appserver add "$PLUGIN_DIR" >/dev/null
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

echo "dsh appserver profile ready: $PROFILE"
echo "smoke: dsh --profile appserver --listen stdio://"
echo "bridge: harness-bridge serve --backend codex --backend dsh"
