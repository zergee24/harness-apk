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
#   DSH_PROFILE_DIR  profile directory (default ~/.dsh/profiles/appserver)
#   NPM_REGISTRY     pnpm registry (default https://registry.npmjs.org/;
#                    the default machine npmrc often points at an unreachable
#                    internal registry, so this script always overrides it)
#   HTTPS_PROXY      used for the registry fetch when set
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/appserver" && pwd)"
PROFILE="${DSH_PROFILE_DIR:-$HOME/.dsh/profiles/appserver}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}"

command -v dsh >/dev/null 2>&1 || { echo "error: dsh CLI not found on PATH" >&2; exit 1; }
command -v pnpm >/dev/null 2>&1 || { echo "error: pnpm not found on PATH" >&2; exit 1; }

if [ ! -f "$PROFILE/package.json" ]; then
  echo "initializing profile $PROFILE"
  dsh plugin --profile appserver add "$PLUGIN_DIR" >/dev/null
fi

echo "installing plugin dependencies into $PROFILE"
pnpm --dir "$PROFILE" add --registry="$NPM_REGISTRY" \
  commander@15.0.0 \
  @deepseek-ai/schemastery@3.18.1 \
  @deepseek-ai/dsh-agent@0.1.0-rc.6 \
  @deepseek-ai/dsh-cmdline@0.1.0-rc.6 \
  @deepseek-ai/dsh-llm@0.1.0-rc.6 \
  @deepseek-ai/dsh-session@0.1.0-rc.6 >/dev/null

# The profile loader resolves plugin imports from the profile's node_modules;
# a pnpm link: dependency would resolve from the repo instead, so copy the
# plugin verbatim (the repo stays the source of truth).
echo "installing dsh-appserver plugin"
mkdir -p "$PROFILE/node_modules/dsh-appserver"
cp "$PLUGIN_DIR"/package.json "$PLUGIN_DIR"/index.js "$PLUGIN_DIR"/startup.js \
  "$PLUGIN_DIR"/translate.js "$PLUGIN_DIR"/persist.js \
  "$PLUGIN_DIR"/thread-list.js "$PLUGIN_DIR"/turn-queue.js \
  "$PROFILE/node_modules/dsh-appserver/"

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
