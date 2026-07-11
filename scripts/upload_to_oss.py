#!/usr/bin/env python3
import argparse
import base64
import email.utils
import hashlib
import hmac
import mimetypes
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


APK_CONTENT_TYPE = "application/vnd.android.package-archive"


def env_required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"Missing environment variable: {name}")
    return value


def normalize_endpoint(endpoint: str) -> str:
    endpoint = endpoint.strip().removeprefix("https://").removeprefix("http://")
    return endpoint.rstrip("/")


def content_type(path: Path) -> str:
    if path.suffix == ".apk":
        return APK_CONTENT_TYPE
    guessed, _ = mimetypes.guess_type(path.name)
    return guessed or "application/octet-stream"


def sign(secret: str, string_to_sign: str) -> str:
    digest = hmac.new(
        secret.encode("utf-8"),
        string_to_sign.encode("utf-8"),
        hashlib.sha1,
    ).digest()
    return base64.b64encode(digest).decode("ascii")


def upload_file(
    *,
    source: Path,
    bucket: str,
    endpoint: str,
    key: str,
    access_key_id: str,
    access_key_secret: str,
    acl: str,
    dry_run: bool,
) -> None:
    payload = source.read_bytes()
    date = email.utils.formatdate(usegmt=True)
    ctype = content_type(source)
    headers = {
        "Date": date,
        "Content-Type": ctype,
        "Content-Length": str(len(payload)),
    }
    canonical_oss_headers = ""
    if acl:
        headers["x-oss-object-acl"] = acl
        canonical_oss_headers = f"x-oss-object-acl:{acl}\n"
    resource = f"/{bucket}/{key}"
    string_to_sign = "\n".join([
        "PUT",
        "",
        ctype,
        date,
        f"{canonical_oss_headers}{resource}",
    ])
    headers["Authorization"] = (
        f"OSS {access_key_id}:{sign(access_key_secret, string_to_sign)}"
    )
    quoted_key = urllib.parse.quote(key, safe="/")
    url = f"https://{bucket}.{endpoint}/{quoted_key}"
    if dry_run:
        print(f"DRY RUN {source} -> {url}")
        return
    request = urllib.request.Request(
        url,
        data=payload,
        headers=headers,
        method="PUT",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            if response.status // 100 != 2:
                raise SystemExit(f"Upload failed {source}: HTTP {response.status}")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Upload failed {source}: HTTP {error.code}\n{body}") from error
    print(f"Uploaded {source} -> {url}")


def ordered_release_files(source_dir: Path) -> list[Path]:
    files = sorted(path for path in source_dir.rglob("*") if path.is_file())
    manifests = [
        path
        for path in files
        if path.relative_to(source_dir).as_posix() == "update.json"
    ]
    assets = [path for path in files if path not in manifests]
    return assets + manifests


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload release directory to Aliyun OSS.")
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    source_dir = args.source_dir
    if not source_dir.is_dir():
        raise SystemExit(f"Source directory not found: {source_dir}")

    bucket = env_required("OSS_BUCKET")
    endpoint = normalize_endpoint(env_required("OSS_ENDPOINT"))
    prefix = os.environ.get("OSS_PREFIX", "harness-apk/test").strip().strip("/")
    access_key_id = env_required("ALIYUN_ACCESS_KEY_ID")
    access_key_secret = env_required("ALIYUN_ACCESS_KEY_SECRET")
    acl = os.environ.get("OSS_ACL", "public-read").strip()

    for source in ordered_release_files(source_dir):
        relative = source.relative_to(source_dir).as_posix()
        key = f"{prefix}/{relative}" if prefix else relative
        upload_file(
            source=source,
            bucket=bucket,
            endpoint=endpoint,
            key=key,
            access_key_id=access_key_id,
            access_key_secret=access_key_secret,
            acl=acl,
            dry_run=args.dry_run,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
