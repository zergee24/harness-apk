"""Shared deterministic signed-package primitives for Harness desktop tools."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import stat
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Callable, Mapping

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
    load_pem_private_key,
)


ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)
RESERVED_PATHS = frozenset({"checksums.json", "signature.json"})
SOURCE_HASH_BUFFER_SIZE = 1024 * 1024


class PackageFormatError(ValueError):
    """Raised when a signed-package input violates the shared format."""


@dataclass(frozen=True)
class _PreparedPackage:
    files: dict[str, bytes | Path]
    expected_identities: dict[str, os.stat_result]


StreamFileWriter = Callable[
    [zipfile.ZipFile, str, Path, os.stat_result | None],
    None,
]


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def load_ed25519_private_key(path: Path) -> Ed25519PrivateKey:
    try:
        key = load_pem_private_key(Path(path).read_bytes(), password=None)
    except (OSError, ValueError, TypeError) as error:
        raise PackageFormatError(f"发布者私钥无法读取：{path}：{error}") from error
    if not isinstance(key, Ed25519PrivateKey):
        raise PackageFormatError("发布者私钥必须是 Ed25519")
    return key


def write_signed_package(
    target: Path,
    files: Mapping[str, bytes | Path],
    private_key: Ed25519PrivateKey,
    *,
    expected_files: Mapping[str, tuple[str, int]] | None = None,
    stream_file_writer: StreamFileWriter | None = None,
) -> Path:
    prepared = _prepare_signed_package(files, private_key, expected_files)
    target = Path(target)
    created = False
    try:
        with target.open("xb") as raw_target:
            created = True
            with zipfile.ZipFile(
                raw_target,
                "w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=9,
                allowZip64=True,
            ) as archive:
                _write_prepared_package(
                    archive,
                    prepared,
                    stream_file_writer or stream_file_into_zip,
                )
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise
    return target


def write_signed_package_streaming(
    target: Path,
    files: Mapping[str, bytes | Path],
    private_key: Ed25519PrivateKey,
    *,
    expected_files: Mapping[str, tuple[str, int]] | None = None,
    stream_file_writer: StreamFileWriter | None = None,
) -> Path:
    prepared = _prepare_signed_package(files, private_key, expected_files)
    target = Path(target)
    created = False
    try:
        with target.open("xb") as raw_target:
            created = True
            writer = _NonSeekableWriter(raw_target)
            with zipfile.ZipFile(
                writer,
                "w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=9,
                allowZip64=True,
            ) as archive:
                _write_prepared_package(
                    archive,
                    prepared,
                    stream_file_writer or stream_file_into_zip,
                )
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise
    return target


def measure_signed_package(
    files: Mapping[str, bytes | Path],
    private_key: Ed25519PrivateKey,
    *,
    expected_files: Mapping[str, tuple[str, int]] | None = None,
    stream_file_writer: StreamFileWriter | None = None,
) -> tuple[str, int]:
    prepared = _prepare_signed_package(files, private_key, expected_files)
    writer = _HashingCountingWriter()
    with zipfile.ZipFile(
        writer,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
        allowZip64=True,
    ) as archive:
        _write_prepared_package(
            archive,
            prepared,
            stream_file_writer or stream_file_into_zip,
        )
    return writer.hexdigest(), writer.tell()


def _prepare_signed_package(
    files: Mapping[str, bytes | Path],
    private_key: Ed25519PrivateKey,
    expected_files: Mapping[str, tuple[str, int]] | None,
) -> _PreparedPackage:
    normalized: dict[str, bytes | Path] = {}
    for raw_path, raw_payload in files.items():
        path = _safe_package_path(raw_path)
        if path in normalized or path in RESERVED_PATHS:
            raise PackageFormatError(f"重复或保留的包内路径：{path}")
        if not isinstance(raw_payload, (bytes, Path)):
            raise PackageFormatError(f"不支持的包内容类型：{path}")
        normalized[path] = raw_payload

    expected = {
        _safe_package_path(path): identity
        for path, identity in (expected_files or {}).items()
    }
    if any(
        path not in normalized or isinstance(normalized[path], bytes)
        for path in expected
    ):
        raise PackageFormatError("预期哈希只能绑定已声明的文件条目")

    checksums: dict[str, str] = {}
    expected_identities: dict[str, os.stat_result] = {}
    for path, payload in sorted(normalized.items()):
        if isinstance(payload, bytes):
            checksums[path] = hashlib.sha256(payload).hexdigest()
            continue
        digest, size, identity = _hash_regular_file(payload)
        if path in expected and expected[path] != (digest, size):
            raise PackageFormatError(f"文件与声明大小或哈希不匹配：{path}")
        checksums[path] = digest
        expected_identities[path] = identity

    checksums_bytes = canonical_json_bytes({"files": checksums})
    public_key = private_key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    signature_bytes = canonical_json_bytes(
        {
            "algorithm": "Ed25519",
            "publicKey": base64.b64encode(public_key).decode("ascii"),
            "signature": base64.b64encode(
                private_key.sign(checksums_bytes)
            ).decode("ascii"),
            "signedFile": "checksums.json",
        }
    )
    return _PreparedPackage(
        files={
            **normalized,
            "checksums.json": checksums_bytes,
            "signature.json": signature_bytes,
        },
        expected_identities=expected_identities,
    )


def _write_prepared_package(
    archive: zipfile.ZipFile,
    prepared: _PreparedPackage,
    stream_file_writer: StreamFileWriter,
) -> None:
    for path, payload in sorted(prepared.files.items()):
        if isinstance(payload, bytes):
            _write_bytes_into_zip(archive, path, payload)
        else:
            stream_file_writer(
                archive,
                path,
                payload,
                prepared.expected_identities.get(path),
            )


def _write_bytes_into_zip(
    archive: zipfile.ZipFile,
    path: str,
    payload: bytes,
) -> None:
    archive.writestr(
        _zip_info(path),
        payload,
        compress_type=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    )


def stream_file_into_zip(
    archive: zipfile.ZipFile,
    path: str,
    source_path: Path,
    expected_identity: os.stat_result | None,
) -> None:
    descriptor = _open_regular_nofollow(source_path)
    try:
        before = os.fstat(descriptor)
        if expected_identity is not None and not _same_file_identity(
            expected_identity, before
        ):
            raise PackageFormatError(f"文件在写入 ZIP 前发生变化：{source_path}")
        info = _zip_info(path)
        info.file_size = before.st_size
        with archive.open(info, "w") as target:
            with os.fdopen(os.dup(descriptor), "rb") as source:
                while block := source.read(SOURCE_HASH_BUFFER_SIZE):
                    target.write(block)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise PackageFormatError(f"文件在写入 ZIP 期间发生变化：{source_path}")
    finally:
        os.close(descriptor)


def _zip_info(path: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    info.create_system = 3
    return info


def _safe_package_path(raw_path: str) -> str:
    if not isinstance(raw_path, str):
        raise PackageFormatError("包内路径必须是字符串")
    if not raw_path or "\x00" in raw_path or "\\" in raw_path:
        raise PackageFormatError(f"不安全的包内路径：{raw_path!r}")
    if raw_path.startswith("/") or raw_path.endswith("/"):
        raise PackageFormatError(f"不安全的包内路径：{raw_path}")
    segments = raw_path.split("/")
    if (
        any(segment in {"", ".", ".."} for segment in segments)
        or ":" in segments[0]
    ):
        raise PackageFormatError(f"不安全的包内路径：{raw_path}")
    return "/".join(segments)


def _hash_regular_file(path: Path) -> tuple[str, int, os.stat_result]:
    descriptor = _open_regular_nofollow(path)
    digest = hashlib.sha256()
    try:
        before = os.fstat(descriptor)
        with os.fdopen(os.dup(descriptor), "rb") as source:
            while block := source.read(SOURCE_HASH_BUFFER_SIZE):
                digest.update(block)
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise PackageFormatError(f"文件在计算哈希期间发生变化：{path}")
    finally:
        os.close(descriptor)
    return digest.hexdigest(), before.st_size, before


def _open_regular_nofollow(path: Path) -> int:
    path = Path(path)
    flags = os.O_RDONLY
    flags |= getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise PackageFormatError(f"无法安全读取文件：{path}：{error}") from error
    try:
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode):
            raise PackageFormatError(f"包内容必须是普通文件：{path}")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _same_file_identity(first: os.stat_result, second: os.stat_result) -> bool:
    return (
        first.st_dev == second.st_dev
        and first.st_ino == second.st_ino
        and first.st_mode == second.st_mode
        and first.st_size == second.st_size
        and first.st_mtime_ns == second.st_mtime_ns
        and first.st_ctime_ns == second.st_ctime_ns
    )


class _NonSeekableWriter:
    def __init__(self, target: BinaryIO) -> None:
        self._target = target
        self._offset = 0

    def write(self, value: bytes) -> int:
        written = self._target.write(value)
        if written is None:
            written = len(value)
        if written != len(value):
            raise OSError("ZIP 输出发生短写")
        self._offset += written
        return written

    def tell(self) -> int:
        return self._offset

    def flush(self) -> None:
        self._target.flush()

    def seekable(self) -> bool:
        return False


class _HashingCountingWriter:
    def __init__(self) -> None:
        self._digest = hashlib.sha256()
        self._offset = 0

    def write(self, value: bytes) -> int:
        self._digest.update(value)
        self._offset += len(value)
        return len(value)

    def tell(self) -> int:
        return self._offset

    def flush(self) -> None:
        pass

    def seekable(self) -> bool:
        return False

    def hexdigest(self) -> str:
        return self._digest.hexdigest()
