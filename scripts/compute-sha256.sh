#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <apk-path>" >&2
  exit 64
fi

shasum -a 256 "$1" | awk '{print $1}'
