#!/bin/sh
set -eu

CODEX_RUNTIME_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
PYTHON_BIN="${CODEX_PYTHON:-}"

if [ -z "$PYTHON_BIN" ] && [ -x "$CODEX_RUNTIME_PYTHON" ]; then
  PYTHON_BIN="$CODEX_RUNTIME_PYTHON"
fi
if [ -z "$PYTHON_BIN" ]; then
  PYTHON_BIN="$(command -v python3 || true)"
fi
if [ -z "$PYTHON_BIN" ]; then
  echo "未找到 Python 3；请在桌面 Codex 中运行，或设置 CODEX_PYTHON。" >&2
  exit 1
fi

if [ "${1:-}" = "-m" ]; then
  exec "$PYTHON_BIN" "$@"
fi
exec "$PYTHON_BIN" -m tools.wiki_builder "$@"
