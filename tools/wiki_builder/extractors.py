"""Descriptor-anchored source inventory and extraction."""

from __future__ import annotations

import hashlib
import os
import stat
import unicodedata
from collections.abc import Iterator, Sequence
from pathlib import Path

from tools.agent_builder.extractors import (
    SUPPORTED_SUFFIXES,
    iter_v2_source_sections_stream,
)
from tools.agent_builder.models import BuildError as AgentBuildError
from tools.agent_builder.models import ExtractedSection

from .models import BuildError, PreparedDocument

_HASH_BUFFER_BYTES = 1024 * 1024


def stable_id(kind: str, *parts: object) -> str:
    payload = "\0".join(
        unicodedata.normalize("NFC", str(part)) for part in (kind, *parts)
    ).encode("utf-8")
    return f"{kind}-{hashlib.sha256(payload).hexdigest()[:24]}"


def extract_documents(paths: Sequence[Path]) -> Iterator[PreparedDocument]:
    """Inventory regular source files in a deterministic order."""

    if not paths:
        raise BuildError("至少需要一个来源文件")
    normalized = [_absolute_without_following(path) for path in paths]
    if len({str(path) for path in normalized}) != len(normalized):
        raise BuildError("来源文件不能重复")
    normalized.sort(key=lambda path: (unicodedata.normalize("NFC", path.name), str(path)))

    document_ids: set[str] = set()
    for ordinal, path in enumerate(normalized):
        suffix = path.suffix.lower()
        if suffix not in SUPPORTED_SUFFIXES:
            raise BuildError(f"不支持的输入格式：{path.name}")
        source_hash, size_bytes = _hash_regular_file(path)
        document_id = stable_id("doc", source_hash, path.name)
        if document_id in document_ids:
            raise BuildError(f"来源身份重复：{path.name}")
        document_ids.add(document_id)
        yield PreparedDocument(
            document_id=document_id,
            title=path.stem,
            source_path=path,
            source_hash=source_hash,
            size_bytes=size_bytes,
            ordinal=ordinal,
        )


def iter_document_sections(document: PreparedDocument) -> Iterator[ExtractedSection]:
    """Hash and parse one source through the same no-follow descriptor."""

    descriptor = _open_regular_nofollow(document.source_path)
    before = os.fstat(descriptor)
    try:
        source_hash, size_bytes = _hash_descriptor(descriptor)
        if (source_hash, size_bytes) != (document.source_hash, document.size_bytes):
            raise BuildError(f"来源在清点后发生变化：{document.source_path.name}")
        os.lseek(descriptor, 0, os.SEEK_SET)
        try:
            with os.fdopen(os.dup(descriptor), "rb") as stream:
                yield from iter_v2_source_sections_stream(
                    stream,
                    document.source_path.suffix,
                    document.source_path.name,
                )
        except AgentBuildError as error:
            raise BuildError(str(error)) from error
        after = os.fstat(descriptor)
        try:
            current = os.stat(document.source_path, follow_symlinks=False)
        except OSError as error:
            raise BuildError(
                f"来源在解析期间不可再访问：{document.source_path.name}"
            ) from error
        if not _same_file_state(before, after) or not _same_file_state(after, current):
            raise BuildError(f"来源在解析期间发生变化：{document.source_path.name}")
    finally:
        os.close(descriptor)


def _absolute_without_following(path: Path) -> Path:
    return Path(os.path.abspath(os.path.expanduser(os.fspath(path))))


def _hash_regular_file(path: Path) -> tuple[str, int]:
    descriptor = _open_regular_nofollow(path)
    before = os.fstat(descriptor)
    try:
        digest, size_bytes = _hash_descriptor(descriptor)
        after = os.fstat(descriptor)
        if not _same_file_state(before, after):
            raise BuildError(f"来源在哈希期间发生变化：{path.name}")
        return digest, size_bytes
    finally:
        os.close(descriptor)


def _open_regular_nofollow(path: Path) -> int:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise BuildError(f"来源文件不可安全读取：{path.name}：{error}") from error
    if not stat.S_ISREG(os.fstat(descriptor).st_mode):
        os.close(descriptor)
        raise BuildError(f"来源不是普通文件：{path.name}")
    return descriptor


def _hash_descriptor(descriptor: int) -> tuple[str, int]:
    digest = hashlib.sha256()
    size_bytes = 0
    os.lseek(descriptor, 0, os.SEEK_SET)
    with os.fdopen(os.dup(descriptor), "rb") as stream:
        while block := stream.read(_HASH_BUFFER_BYTES):
            digest.update(block)
            size_bytes += len(block)
    os.lseek(descriptor, 0, os.SEEK_SET)
    return digest.hexdigest(), size_bytes


def _same_file_state(left: os.stat_result, right: os.stat_result) -> bool:
    return (
        left.st_dev,
        left.st_ino,
        left.st_size,
        left.st_mtime_ns,
        left.st_ctime_ns,
    ) == (
        right.st_dev,
        right.st_ino,
        right.st_size,
        right.st_mtime_ns,
        right.st_ctime_ns,
    )


__all__ = ["extract_documents", "iter_document_sections", "stable_id"]
