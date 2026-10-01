#!/usr/bin/env python3
"""Vendor dsh packages from the Desktop bundle into a profile.

Several packages the profile needs are shipped only inside the Desktop app's
`app.asar` and are not published to npm — `@deepseek-ai/cordis` and
`@deepseek-ai/cosmokit` among them. A profile whose `node_modules` lacks them
carries a copy of `@deepseek-ai/dsh-agent` that cannot resolve its own imports,
so the plugin fails to import at boot.

This script copies the requested packages out of the bundle. Every asar entry
carries a two-byte prefix before its real content; the payload starts after it.

Usage:
    vendor_from_bundle.py <destination-node-modules> [package ...]

With no package names it copies every `@deepseek-ai/*` package except the
Desktop-only application packages, which need Electron or contain the built web
frontend.
"""

from __future__ import annotations

import json
import os
import shutil
import struct
import sys

ASAR = "/Applications/DeepSeek Harness.app/Contents/Resources/app.asar"
SCOPE = "@deepseek-ai"
PREFIX_BYTES = 2

# Desktop-only application packages: they need Electron, contain the built web
# frontend, or keep their payload unpacked for native execution. Nothing in the
# appserver composition imports them.
SKIP = {
    "dsh-desktop-host",
    "dsh-web-app",
    "dsh-web-frontend",
    "libreoffice-kit",
    "libreoffice-kit-darwin-arm64",
    "dsh-session-log-export",
}


def load_index(asar: str) -> tuple[dict, int]:
    with open(asar, "rb") as handle:
        header = handle.read(16)
        if len(header) < 16:
            raise SystemExit(f"error: {asar} is not an asar archive")
        header_size = struct.unpack("<I", header[12:16])[0]
        index = json.loads(handle.read(header_size).decode("utf-8"))
    return index, 16 + header_size


def scope_dir(index: dict) -> dict:
    node = index["files"]["dsh"]["files"]["node_modules"]["files"]
    return node[SCOPE]["files"]


def walk_files(node: dict, prefix: str, out: list[tuple[str, int, int]]) -> None:
    """Collect one package's own files, ignoring any nested node_modules.

    A package's nested dependencies are not vendored: resolution happens from
    the destination's top-level node_modules, which this tool fills in full.
    """
    for name, entry in (node.get("files") or {}).items():
        if name == "node_modules":
            continue
        path = f"{prefix}/{name}" if prefix else name
        if "files" in entry:
            walk_files(entry, path, out)
        else:
            out.append((path, int(entry.get("size") or 0), entry.get("offset")))


def vendor(packages: list[str], destination: str) -> int:
    index, data_offset = load_index(ASAR)
    scope = scope_dir(index)
    written = 0
    with open(ASAR, "rb") as archive:
        for package in packages:
            entry = scope.get(package)
            if entry is None:
                print(f"warning: {SCOPE}/{package} is not in the bundle", file=sys.stderr)
                continue
            files: list[tuple[str, int, int]] = []
            walk_files(entry, "", files)
            target_root = os.path.join(destination, SCOPE, package)
            shutil.rmtree(target_root, ignore_errors=True)
            unpacked = 0
            for relative, size, offset in files:
                if offset is None:
                    # The entry lives in app.asar.unpacked (native binaries and
                    # similar); copying it is outside this tool's purpose.
                    unpacked += 1
                    continue
                archive.seek(data_offset + int(offset) + PREFIX_BYTES)
                payload = archive.read(size)
                target = os.path.join(target_root, relative)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as out:
                    out.write(payload)
                written += 1
            note = f", {unpacked} unpacked entry/entries skipped" if unpacked else ""
            print(f"vendored {SCOPE}/{package} ({len(files) - unpacked} files{note})")
    return written


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    destination = os.path.abspath(argv[1])
    os.makedirs(destination, exist_ok=True)
    if len(argv) > 2:
        packages = argv[2:]
    else:
        index, _ = load_index(ASAR)
        packages = sorted(name for name in scope_dir(index) if name not in SKIP)
    total = vendor(packages, destination)
    print(f"vendored {len(packages)} package(s), {total} file(s) into {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
