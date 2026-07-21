"""Signing, publication, and independent inspection of ``.hwiki`` files."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import sqlite3
import stat
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from tools.package_format import (
    PackageFormatError,
    ZIP_TIMESTAMP,
    canonical_json_bytes,
    load_ed25519_private_key,
    write_signed_package,
)

from .models import BuildError
from .reporting import build_report_document, write_build_reports
from .schema import WikiManifest
from .sqlite_schema import validate_sqlite_shape
from .validation import validate_workspace
from .workspace import WikiWorkspace, load_workspace

_PACKAGE_ENTRIES = frozenset(
    {"manifest.json", "content.sqlite", "checksums.json", "signature.json"}
)
_SIGNED_PAYLOAD_ENTRIES = frozenset({"manifest.json", "content.sqlite"})
_MAX_JSON_ENTRY_BYTES = 1024 * 1024
_MAX_CONTENT_BYTES = 16 * 1024 * 1024 * 1024
_COPY_BUFFER_BYTES = 1024 * 1024


@dataclass(frozen=True)
class WikiPackResult:
    package: Path
    report_json: Path
    report_markdown: Path


@dataclass(frozen=True)
class WikiInspection:
    manifest: dict[str, object]
    publisher_fingerprint: str
    package_hash: str
    package_size: int

    def to_dict(self) -> dict[str, object]:
        return {
            "manifest": self.manifest,
            "publisherFingerprint": self.publisher_fingerprint,
            "packageSha256": self.package_hash,
            "packageSizeBytes": self.package_size,
        }


def pack_workspace(
    workspace: Path,
    output: Path,
    private_key_path: Path,
    evaluation_path: Path | None = None,
) -> WikiPackResult:
    loaded = load_workspace(workspace)
    content_hash_before, content_size_before = _hash_regular_file(
        loaded.database_path
    )
    validation = validate_workspace(loaded.root, evaluation_path)
    if not validation.publishable:
        raise BuildError("validate 未通过：" + "；".join(validation.errors))
    content_hash, content_size = _hash_regular_file(loaded.database_path)
    if (content_hash, content_size) != (content_hash_before, content_size_before):
        raise BuildError("content.sqlite 在 validate 期间发生变化")
    key_path = Path(private_key_path)
    if key_path.is_symlink() or not key_path.is_file():
        raise BuildError("publisher key 必须是已存在的普通文件")
    try:
        private_key = load_ed25519_private_key(key_path)
    except PackageFormatError as error:
        raise BuildError(str(error)) from error

    target = Path(output)
    existed_empty = False
    if target.is_symlink():
        raise BuildError(f"输出目录不能是符号链接：{target}")
    if target.exists():
        if not target.is_dir():
            raise BuildError(f"输出路径不是目录：{target}")
        if any(target.iterdir()):
            raise BuildError(f"输出目录非空：{target}")
        existed_empty = True
    target.parent.mkdir(parents=True, exist_ok=True)

    manifest = _build_manifest(loaded, private_key, content_hash)
    manifest_bytes = canonical_json_bytes(manifest)
    stem = f"{loaded.wiki_id}-v{loaded.version}"
    staging = Path(
        tempfile.mkdtemp(prefix=f".{target.name}.hwiki-release-", dir=target.parent)
    )
    published = False
    removed_existing = False
    try:
        package = staging / f"{stem}.hwiki"
        try:
            write_signed_package(
                package,
                {"manifest.json": manifest_bytes, "content.sqlite": loaded.database_path},
                private_key,
                expected_files={"content.sqlite": (content_hash, content_size)},
            )
        except (OSError, PackageFormatError) as error:
            raise BuildError(f"hwiki 写入失败：{error}") from error
        package_hash, package_size = _hash_regular_file(package)
        report = build_report_document(
            manifest=manifest,
            validation=validation,
            package_name=package.name,
            package_hash=package_hash,
            package_size=package_size,
        )
        report_json, report_markdown = write_build_reports(staging, report)

        if target.exists():
            if any(target.iterdir()):
                raise BuildError(f"输出目录在发布前变为非空：{target}")
            target.rmdir()
            removed_existing = True
        staging.rename(target)
        published = True
        return WikiPackResult(
            package=target / package.name,
            report_json=target / report_json.name,
            report_markdown=target / report_markdown.name,
        )
    finally:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)
            if existed_empty and removed_existing and not target.exists():
                target.mkdir()


def inspect_package(package: Path) -> WikiInspection:
    source = Path(package)
    if source.is_symlink() or not source.is_file():
        raise BuildError(f"hwiki 不是安全的普通文件：{source}")
    package_hash, package_size = _hash_regular_file(source)
    try:
        with zipfile.ZipFile(source) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)) or set(names) != _PACKAGE_ENTRIES:
                raise BuildError("hwiki 条目集合无效或包含重复条目")
            for info in infos:
                if (
                    info.date_time != ZIP_TIMESTAMP
                    or info.create_system != 3
                    or info.external_attr >> 16 != 0o100644
                    or info.is_dir()
                ):
                    raise BuildError(f"hwiki 条目元数据无效：{info.filename}")

            manifest_bytes = _read_small_entry(archive, "manifest.json")
            checksums_bytes = _read_small_entry(archive, "checksums.json")
            signature_bytes = _read_small_entry(archive, "signature.json")
            manifest_raw = _canonical_object(manifest_bytes, "manifest.json")
            checksums = _canonical_object(checksums_bytes, "checksums.json")
            signature = _canonical_object(signature_bytes, "signature.json")
            manifest = WikiManifest.from_dict(manifest_raw)
            _verify_signature_and_checksums(
                checksums,
                checksums_bytes,
                signature,
                manifest_bytes,
            )

            with tempfile.TemporaryDirectory(prefix="harness-hwiki-inspect-") as temp:
                database_path = Path(temp) / "content.sqlite"
                content_hash = _extract_database(archive, database_path)
                if checksums["files"]["content.sqlite"] != content_hash:
                    raise BuildError("content.sqlite 校验和不匹配")
                if manifest.content_hash != content_hash:
                    raise BuildError("manifest contentHash 与 content.sqlite 不一致")
                _inspect_database(database_path)

            public_key = base64.b64decode(signature["publicKey"], validate=True)
            fingerprint = _publisher_fingerprint(public_key)
            if manifest.publisher_key_id != fingerprint:
                raise BuildError("manifest publisher.keyId 与签名公钥不一致")
            verified_hash, verified_size = _hash_regular_file(source)
            if (verified_hash, verified_size) != (package_hash, package_size):
                raise BuildError("hwiki 在检查期间发生变化")
            return WikiInspection(
                manifest=manifest.to_dict(),
                publisher_fingerprint=fingerprint,
                package_hash=package_hash,
                package_size=package_size,
            )
    except BuildError:
        raise
    except (
        InvalidSignature,
        KeyError,
        OSError,
        TypeError,
        ValueError,
        zipfile.BadZipFile,
    ) as error:
        raise BuildError(f"hwiki 无法验证：{error}") from error


def _build_manifest(
    workspace: WikiWorkspace,
    private_key: Ed25519PrivateKey,
    content_hash: str,
) -> dict[str, object]:
    public_key = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    connection = _open_read_only(workspace.database_path)
    try:
        counts = {
            table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("documents", "sections", "chunks", "summaries", "terms", "links")
        }
        temporal = connection.execute(
            """
            SELECT COUNT(*) FROM annotations
            WHERE kind IN ('time_range', 'reign_year', 'dynasty')
            """
        ).fetchone()[0]
        registry_row = connection.execute(
            "SELECT value FROM build_metadata WHERE key='conceptRegistryHash'"
        ).fetchone()
    finally:
        connection.close()
    if registry_row is None:
        raise BuildError("工作区缺少 conceptRegistryHash")
    raw = {
        "type": "hwiki",
        "schemaVersion": 1,
        "wiki": {
            "id": workspace.wiki_id,
            "version": workspace.version,
            "title": workspace.title,
            "language": ["zh-Hant", "zh-Hans"],
            "description": f"{workspace.title}离线知识库",
            "contentHash": content_hash,
        },
        "publisher": {
            "keyId": _publisher_fingerprint(public_key),
            "name": "用户本地发布者",
        },
        "capabilities": {
            "sourceHierarchy": counts["documents"] > 0 and counts["sections"] > 0,
            "sourceSearch": counts["chunks"] > 0,
            "hierarchicalSummaries": counts["summaries"] > 0,
            "termIndex": counts["terms"] > 0,
            "temporalAnnotations": temporal > 0,
            "crossWikiLinks": counts["links"] > 0,
            "generatedPages": "none",
            "claimGraph": False,
            "vectorIndex": False,
            "sourceAttachments": False,
        },
        "conceptNamespace": workspace.concept_namespace,
        "conceptRegistryHash": registry_row[0],
        "builder": {
            "name": "harness-wiki-builder",
            "version": "1",
            "profile": workspace.builder_profile,
        },
    }
    return WikiManifest.from_dict(raw).to_dict()


def _verify_signature_and_checksums(
    checksums: dict[str, object],
    checksums_bytes: bytes,
    signature: dict[str, object],
    manifest_bytes: bytes,
) -> None:
    if set(checksums) != {"files"} or not isinstance(checksums["files"], dict):
        raise BuildError("checksums.json 结构无效")
    if set(checksums["files"]) != _SIGNED_PAYLOAD_ENTRIES:
        raise BuildError("checksums.json 文件集合无效")
    if checksums["files"]["manifest.json"] != hashlib.sha256(manifest_bytes).hexdigest():
        raise BuildError("manifest.json 校验和不匹配")
    if set(signature) != {"algorithm", "publicKey", "signature", "signedFile"}:
        raise BuildError("signature.json 结构无效")
    if signature["algorithm"] != "Ed25519" or signature["signedFile"] != "checksums.json":
        raise BuildError("signature.json 算法或签名目标无效")
    public_key = base64.b64decode(signature["publicKey"], validate=True)
    signature_value = base64.b64decode(signature["signature"], validate=True)
    if len(public_key) != 32 or len(signature_value) != 64:
        raise BuildError("signature.json 密钥或签名长度无效")
    Ed25519PublicKey.from_public_bytes(public_key).verify(
        signature_value,
        checksums_bytes,
    )


def _extract_database(archive: zipfile.ZipFile, target: Path) -> str:
    info = archive.getinfo("content.sqlite")
    if info.file_size > _MAX_CONTENT_BYTES:
        raise BuildError("content.sqlite 超过检查上限")
    digest = hashlib.sha256()
    written = 0
    with archive.open(info) as source, target.open("xb") as output:
        while block := source.read(_COPY_BUFFER_BYTES):
            written += len(block)
            if written > info.file_size or written > _MAX_CONTENT_BYTES:
                raise BuildError("content.sqlite 解压大小无效")
            digest.update(block)
            output.write(block)
    if written != info.file_size:
        raise BuildError("content.sqlite 解压长度不匹配")
    return digest.hexdigest()


def _inspect_database(path: Path) -> None:
    connection = _open_read_only(path)
    try:
        validate_sqlite_shape(connection)
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise BuildError(f"content.sqlite integrity_check 失败：{integrity}")
        foreign_keys = connection.execute("PRAGMA foreign_key_check").fetchall()
        if foreign_keys:
            raise BuildError(f"content.sqlite 外键错误：{len(foreign_keys)}")
    except ValueError as error:
        raise BuildError(str(error)) from error
    finally:
        connection.close()


def _canonical_object(payload: bytes, label: str) -> dict[str, object]:
    try:
        value = json.loads(payload, parse_constant=_reject_json_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        raise BuildError(f"{label} 无法解析：{error}") from error
    if not isinstance(value, dict) or payload != canonical_json_bytes(value):
        raise BuildError(f"{label} 必须是规范 JSON 对象")
    return value


def _read_small_entry(archive: zipfile.ZipFile, name: str) -> bytes:
    info = archive.getinfo(name)
    if info.file_size > _MAX_JSON_ENTRY_BYTES:
        raise BuildError(f"{name} 超过大小上限")
    payload = archive.read(info)
    if len(payload) != info.file_size:
        raise BuildError(f"{name} 长度不匹配")
    return payload


def _publisher_fingerprint(public_key: bytes) -> str:
    return f"ed25519:{hashlib.sha256(public_key).hexdigest()}"


def _hash_regular_file(path: Path) -> tuple[str, int]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise BuildError(f"文件无法安全读取：{path}：{error}") from error
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode):
        os.close(descriptor)
        raise BuildError(f"路径不是普通文件：{path}")
    digest = hashlib.sha256()
    size = 0
    try:
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(_COPY_BUFFER_BYTES):
                digest.update(block)
                size += len(block)
        after = os.fstat(descriptor)
        if (
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        ) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ):
            raise BuildError(f"文件在读取期间发生变化：{path}")
        return digest.hexdigest(), size
    finally:
        os.close(descriptor)


def _open_read_only(path: Path) -> sqlite3.Connection:
    try:
        return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
    except sqlite3.Error as error:
        raise BuildError(f"content.sqlite 无法只读打开：{error}") from error


def _reject_json_constant(value: str) -> object:
    raise ValueError(f"不允许 JSON 常量 {value}")


__all__ = [
    "WikiInspection",
    "WikiPackResult",
    "inspect_package",
    "pack_workspace",
]
