#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional


CHUNK_SIZE = 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def clean_url(url: str) -> str:
    return url.rstrip("/")


def read_notes(path: Optional[Path]) -> list[str]:
    if not path:
        return []
    notes = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    return notes


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare Harness APK release files for static hosting.",
    )
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--public-base-url", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--artifact-name")
    parser.add_argument("--min-supported-version-code", type=int)
    parser.add_argument("--release-notes-file", type=Path)
    args = parser.parse_args()

    apk_path = args.apk
    if not apk_path.is_file():
        raise SystemExit(f"APK not found: {apk_path}")
    artifact_name = args.artifact_name or apk_path.name

    output_dir = args.output_dir
    chunks_dir = output_dir / "chunks"
    if output_dir.exists():
        shutil.rmtree(output_dir)
    chunks_dir.mkdir(parents=True, exist_ok=True)

    hosted_apk = output_dir / artifact_name
    shutil.copy2(apk_path, hosted_apk)

    chunk_names: list[str] = []
    with apk_path.open("rb") as source:
        index = 0
        while True:
            payload = source.read(CHUNK_SIZE)
            if not payload:
                break
            name = f"{artifact_name}.part-{index:03d}"
            (chunks_dir / name).write_bytes(payload)
            chunk_names.append(name)
            index += 1

    base_url = clean_url(args.public_base_url)
    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "minSupportedVersionCode": args.min_supported_version_code or args.version_code,
        "apkUrl": f"{base_url}/{artifact_name}",
        "apkChunks": [
            f"{base_url}/chunks/{name}"
            for name in chunk_names
        ],
        "sha256": sha256(apk_path),
        "releaseNotes": read_notes(args.release_notes_file)
        or [f"更新测试版本到 {args.version_name}"],
        "publishedAt": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
    }

    (output_dir / "update.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(json.dumps({
        "outputDir": str(output_dir),
        "versionCode": manifest["versionCode"],
        "versionName": manifest["versionName"],
        "sha256": manifest["sha256"],
        "chunkCount": len(chunk_names),
        "manifestUrl": f"{base_url}/update.json",
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
