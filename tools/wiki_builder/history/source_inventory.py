"""Content-addressed inventory for the two pinned history repositories."""

from __future__ import annotations

import codecs
import hashlib
import os
import re
import stat
import subprocess
import unicodedata
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from tools.package_format import canonical_json_bytes

from ..models import BuildError

TWENTY_FOUR_HISTORIES_SOURCE_ID = "twenty-four-histories"
ZIZHI_TONGJIAN_SOURCE_ID = "zizhi-tongjian"
_REVISION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
_HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
_TRANSLATION_MARKERS = ("-白话", "-译文", "-段译")
_BUFFER_BYTES = 1024 * 1024


class InventoryError(BuildError):
    """Raised when a history source cannot be locked safely."""


@dataclass(frozen=True)
class LicenseRecord:
    path: str
    sha256: str
    size_bytes: int

    @classmethod
    def from_dict(cls, raw: object) -> "LicenseRecord":
        value = _mapping(raw, "license")
        _exact_fields(value, {"path", "sha256", "sizeBytes"}, "license")
        path = _safe_relative_path(value["path"], "license.path")
        digest = _hash(value["sha256"], "license.sha256")
        size = _nonnegative_integer(value["sizeBytes"], "license.sizeBytes")
        return cls(path, digest, size)

    def to_dict(self) -> dict[str, object]:
        return {"path": self.path, "sha256": self.sha256, "sizeBytes": self.size_bytes}


@dataclass(frozen=True)
class SourceInventory:
    source_id: str
    path: str
    git_remote: str
    git_revision: str
    dirty: bool
    relevant_file_count: int
    relevant_byte_count: int
    support_file_count: int
    support_byte_count: int
    tree_hash: str
    licenses: tuple[LicenseRecord, ...]

    @classmethod
    def from_dict(cls, raw: object) -> "SourceInventory":
        value = _mapping(raw, "source")
        _exact_fields(
            value,
            {
                "sourceId",
                "path",
                "gitRemote",
                "gitRevision",
                "dirty",
                "relevantFileCount",
                "relevantByteCount",
                "supportFileCount",
                "supportByteCount",
                "treeHash",
                "licenses",
            },
            "source",
        )
        source_id = _string(value["sourceId"], "source.sourceId")
        path = _string(value["path"], "source.path")
        remote = _string(value["gitRemote"], "source.gitRemote", allow_empty=True)
        revision = _revision(value["gitRevision"], "source.gitRevision")
        if type(value["dirty"]) is not bool:
            raise InventoryError("source.dirty 必须是布尔值")
        licenses_value = value["licenses"]
        if not isinstance(licenses_value, list):
            raise InventoryError("source.licenses 必须是数组")
        licenses = tuple(LicenseRecord.from_dict(item) for item in licenses_value)
        if tuple(sorted(record.path for record in licenses)) != tuple(
            record.path for record in licenses
        ):
            raise InventoryError("source.licenses 必须按 path 排序")
        return cls(
            source_id=source_id,
            path=path,
            git_remote=remote,
            git_revision=revision,
            dirty=value["dirty"],
            relevant_file_count=_nonnegative_integer(
                value["relevantFileCount"], "source.relevantFileCount"
            ),
            relevant_byte_count=_nonnegative_integer(
                value["relevantByteCount"], "source.relevantByteCount"
            ),
            support_file_count=_nonnegative_integer(
                value["supportFileCount"], "source.supportFileCount"
            ),
            support_byte_count=_nonnegative_integer(
                value["supportByteCount"], "source.supportByteCount"
            ),
            tree_hash=_hash(value["treeHash"], "source.treeHash"),
            licenses=licenses,
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "sourceId": self.source_id,
            "path": self.path,
            "gitRemote": self.git_remote,
            "gitRevision": self.git_revision,
            "dirty": self.dirty,
            "relevantFileCount": self.relevant_file_count,
            "relevantByteCount": self.relevant_byte_count,
            "supportFileCount": self.support_file_count,
            "supportByteCount": self.support_byte_count,
            "treeHash": self.tree_hash,
            "licenses": [record.to_dict() for record in self.licenses],
        }


@dataclass(frozen=True)
class SourceLock:
    sources: tuple[SourceInventory, ...]
    schema_version: int = 1

    @classmethod
    def from_dict(cls, raw: object) -> "SourceLock":
        value = _mapping(raw, "source lock")
        _exact_fields(value, {"type", "schemaVersion", "sources"}, "source lock")
        if value["type"] != "hwiki-history-source-lock":
            raise InventoryError("source lock type 无效")
        if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
            raise InventoryError("source lock schemaVersion 必须是 1")
        raw_sources = value["sources"]
        if not isinstance(raw_sources, list):
            raise InventoryError("source lock sources 必须是数组")
        sources = tuple(SourceInventory.from_dict(item) for item in raw_sources)
        ids = [source.source_id for source in sources]
        if ids != sorted(ids) or len(ids) != len(set(ids)):
            raise InventoryError("source lock sources 必须按 sourceId 排序且不能重复")
        return cls(sources=sources)

    def to_dict(self) -> dict[str, object]:
        return {
            "type": "hwiki-history-source-lock",
            "schemaVersion": self.schema_version,
            "sources": [source.to_dict() for source in self.sources],
        }


@dataclass(frozen=True)
class _FileRecord:
    path: str
    sha256: str
    size_bytes: int
    kind: str

    def to_dict(self) -> dict[str, object]:
        return {
            "kind": self.kind,
            "path": self.path,
            "sha256": self.sha256,
            "sizeBytes": self.size_bytes,
        }


def inventory_history_sources(
    twenty_four_histories: Path,
    zizhi_tongjian: Path,
    *,
    expected_lock: SourceLock | None = None,
) -> SourceLock:
    sources = tuple(
        sorted(
            (
                _inventory_repository(
                    TWENTY_FOUR_HISTORIES_SOURCE_ID,
                    twenty_four_histories,
                ),
                _inventory_repository(ZIZHI_TONGJIAN_SOURCE_ID, zizhi_tongjian),
            ),
            key=lambda source: source.source_id,
        )
    )
    lock = SourceLock(sources)
    if expected_lock is not None:
        expected_by_id = {source.source_id: source for source in expected_lock.sources}
        actual_by_id = {source.source_id: source for source in sources}
        if set(expected_by_id) != set(actual_by_id):
            raise InventoryError("source lock 来源集合发生变化")
        for source_id in sorted(actual_by_id):
            expected = expected_by_id[source_id]
            actual = actual_by_id[source_id]
            if actual.git_revision != expected.git_revision:
                raise InventoryError(
                    f"{source_id} Git revision 与既有锁不一致："
                    f"{expected.git_revision} -> {actual.git_revision}"
                )
            if actual != expected:
                raise InventoryError(f"{source_id} 内容或来源元数据与既有锁不一致")
    return lock


def inventory_history_repository(
    source_id: str,
    path: Path,
    *,
    expected: SourceInventory | None = None,
) -> SourceInventory:
    if source_id not in {
        TWENTY_FOUR_HISTORIES_SOURCE_ID,
        ZIZHI_TONGJIAN_SOURCE_ID,
    }:
        raise InventoryError(f"不支持的 history sourceId：{source_id}")
    actual = _inventory_repository(source_id, path)
    if expected is not None:
        if actual.git_revision != expected.git_revision:
            raise InventoryError(
                f"{source_id} Git revision 与既有锁不一致："
                f"{expected.git_revision} -> {actual.git_revision}"
            )
        if actual != expected:
            raise InventoryError(f"{source_id} 内容或来源元数据与既有锁不一致")
    return actual


def write_source_lock(path: Path, lock: SourceLock) -> Path:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        stream.write(canonical_json_bytes(lock.to_dict()))
    return target


def load_source_lock(path: Path) -> SourceLock:
    target = Path(path)
    if target.is_symlink() or not target.is_file():
        raise InventoryError(f"source lock 不可安全读取：{target}")
    try:
        import json

        raw = target.read_bytes()
        value = json.loads(raw)
    except (OSError, UnicodeDecodeError, ValueError) as error:
        raise InventoryError(f"source lock 无法读取：{error}") from error
    if raw != canonical_json_bytes(value):
        raise InventoryError("source lock 必须是规范 JSON")
    return SourceLock.from_dict(value)


def _inventory_repository(source_id: str, raw_root: Path) -> SourceInventory:
    root = Path(raw_root).expanduser()
    if root.is_symlink() or not root.is_dir():
        raise InventoryError(f"{source_id} 不是安全的 Git 目录：{root}")
    root = root.resolve()
    top_level = _git(root, "rev-parse", "--show-toplevel")
    if Path(top_level).resolve() != root:
        raise InventoryError(f"{source_id} 路径不是 Git 仓库根目录")
    revision = _revision(_git(root, "rev-parse", "HEAD"), f"{source_id} revision")
    remote = _git(root, "config", "--get", "remote.origin.url", allow_failure=True)

    records = _collect_files(source_id, root)
    dirty_paths = _dirty_paths(root)
    dirty_relevant = sorted(path for path in dirty_paths if _classify(source_id, path) is not None)
    if dirty_relevant:
        raise InventoryError(
            f"{source_id} 存在未提交的 relevant/dirty 文件：{', '.join(dirty_relevant[:10])}"
        )

    content = [record for record in records if record.kind == "content"]
    support = [record for record in records if record.kind == "support"]
    licenses = tuple(
        LicenseRecord(record.path, record.sha256, record.size_bytes)
        for record in records
        if record.kind == "license"
    )
    tree_payload = [
        record.to_dict() for record in records if record.kind in {"content", "support"}
    ]
    return SourceInventory(
        source_id=source_id,
        path=str(root),
        git_remote=remote,
        git_revision=revision,
        dirty=False,
        relevant_file_count=len(content),
        relevant_byte_count=sum(record.size_bytes for record in content),
        support_file_count=len(support),
        support_byte_count=sum(record.size_bytes for record in support),
        tree_hash=hashlib.sha256(canonical_json_bytes(tree_payload)).hexdigest(),
        licenses=licenses,
    )


def _collect_files(source_id: str, root: Path) -> tuple[_FileRecord, ...]:
    records: list[_FileRecord] = []
    seen_casefold: dict[str, str] = {}
    for current, directories, files in os.walk(root, followlinks=False):
        directories[:] = sorted(directory for directory in directories if directory != ".git")
        current_path = Path(current)
        for directory in directories:
            child = current_path / directory
            if child.is_symlink():
                raise InventoryError(f"{source_id} 包含目录符号链接：{child.relative_to(root)}")
        for name in sorted(files):
            path = current_path / name
            relative = _canonical_relative(path.relative_to(root))
            kind = _classify(source_id, relative)
            if kind is None:
                continue
            folded = relative.casefold()
            previous = seen_casefold.get(folded)
            if previous is not None and previous != relative:
                raise InventoryError(f"{source_id} 存在大小写冲突路径：{previous} / {relative}")
            seen_casefold[folded] = relative
            if path.is_symlink():
                try:
                    resolved = path.resolve(strict=True)
                except OSError as error:
                    raise InventoryError(f"{source_id} 符号链接无法解析：{relative}") from error
                if not resolved.is_relative_to(root):
                    raise InventoryError(f"{source_id} 符号链接越界：{relative}")
                raise InventoryError(f"{source_id} relevant 文件不能是符号链接：{relative}")
            digest, size = _read_regular(
                path,
                relative,
                validate_utf8=kind in {"content", "support"},
            )
            records.append(_FileRecord(relative, digest, size, kind))
    return tuple(sorted(records, key=lambda record: record.path))


def _read_regular(
    path: Path,
    label: str,
    *,
    validate_utf8: bool,
) -> tuple[str, int]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise InventoryError(f"文件不可安全读取：{label}：{error}") from error
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode):
        os.close(descriptor)
        raise InventoryError(f"relevant 路径不是普通文件：{label}")
    digest = hashlib.sha256()
    size = 0
    decoder = codecs.getincrementaldecoder("utf-8")(errors="strict")
    try:
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(_BUFFER_BYTES):
                digest.update(block)
                size += len(block)
                if validate_utf8:
                    try:
                        decoder.decode(block)
                    except UnicodeDecodeError as error:
                        raise InventoryError(f"文件不是 UTF-8 编码：{label}") from error
        if validate_utf8:
            try:
                decoder.decode(b"", final=True)
            except UnicodeDecodeError as error:
                raise InventoryError(f"文件不是 UTF-8 编码：{label}") from error
        after = os.fstat(descriptor)
        if (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ):
            raise InventoryError(f"文件在清点期间发生变化：{label}")
        return digest.hexdigest(), size
    finally:
        os.close(descriptor)


def _dirty_paths(root: Path) -> set[str]:
    output = _git_bytes(
        root,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
    )
    parts = output.split(b"\0")
    result: set[str] = set()
    index = 0
    while index < len(parts):
        raw = parts[index]
        index += 1
        if not raw:
            continue
        if len(raw) < 4:
            raise InventoryError("Git status 输出格式无效")
        status = raw[:2]
        path = raw[3:].decode("utf-8", errors="strict")
        result.add(_canonical_relative(Path(path)))
        if status[:1] in {b"R", b"C"} or status[1:2] in {b"R", b"C"}:
            if index >= len(parts) or not parts[index]:
                raise InventoryError("Git rename status 输出格式无效")
            result.add(_canonical_relative(Path(parts[index].decode("utf-8"))))
            index += 1
    return result


def _classify(source_id: str, relative: str) -> str | None:
    path = PurePosixPath(relative)
    lower_name = path.name.casefold()
    if lower_name.startswith("license") or lower_name.startswith("copying"):
        return "license"
    if source_id == TWENTY_FOUR_HISTORIES_SOURCE_ID:
        if any(marker in relative for marker in _TRANSLATION_MARKERS):
            return None
        if path.suffix.casefold() != ".html":
            return None
        return "content" if path.stem.endswith("-原文") else "support"
    if relative == "SUMMARY.md":
        return "support"
    if (
        len(path.parts) == 2
        and path.parts[0] == "chapters"
        and re.match(r"^[0-9]{3}_.*\.md\Z", path.name)
    ):
        return "content"
    return None


def _canonical_relative(path: Path) -> str:
    value = unicodedata.normalize("NFC", path.as_posix())
    return _safe_relative_path(value, "relative path")


def _git(root: Path, *args: str, allow_failure: bool = False) -> str:
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=root,
            check=not allow_failure,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise InventoryError(f"Git 命令失败：git {' '.join(args)}") from error
    return result.stdout.strip() if result.returncode == 0 else ""


def _git_bytes(root: Path, *args: str) -> bytes:
    try:
        return subprocess.run(
            ["git", *args],
            cwd=root,
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise InventoryError(f"Git 命令失败：git {' '.join(args)}") from error


def _mapping(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise InventoryError(f"{label} 必须是对象")
    return value


def _exact_fields(value: dict[str, object], fields: set[str], label: str) -> None:
    unknown = sorted(set(value) - fields)
    missing = sorted(fields - set(value))
    if unknown:
        raise InventoryError(f"{label} 包含未知字段：{', '.join(unknown)}")
    if missing:
        raise InventoryError(f"{label} 缺少字段：{', '.join(missing)}")


def _string(value: object, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise InventoryError(f"{label} 必须是字符串")
    return value


def _revision(value: object, label: str) -> str:
    revision = _string(value, label)
    if not _REVISION_PATTERN.fullmatch(revision):
        raise InventoryError(f"{label} 必须是 40 位小写 Git revision")
    return revision


def _hash(value: object, label: str) -> str:
    digest = _string(value, label)
    if not _HASH_PATTERN.fullmatch(digest):
        raise InventoryError(f"{label} 必须是 64 位小写 SHA-256")
    return digest


def _nonnegative_integer(value: object, label: str) -> int:
    if type(value) is not int or value < 0:
        raise InventoryError(f"{label} 必须是非负整数")
    return value


def _safe_relative_path(value: object, label: str) -> str:
    raw = _string(value, label)
    path = PurePosixPath(raw)
    if (
        path.is_absolute()
        or not path.parts
        or any(part in {"", ".", ".."} for part in path.parts)
        or "\\" in raw
        or "\0" in raw
    ):
        raise InventoryError(f"{label} 不是安全相对路径：{raw}")
    return str(path)


__all__ = [
    "InventoryError",
    "LicenseRecord",
    "SourceInventory",
    "SourceLock",
    "TWENTY_FOUR_HISTORIES_SOURCE_ID",
    "ZIZHI_TONGJIAN_SOURCE_ID",
    "inventory_history_sources",
    "inventory_history_repository",
    "load_source_lock",
    "write_source_lock",
]
