"""Conservative shared concept registry for multiple history Wikis."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import tempfile
import unicodedata
from collections.abc import Iterable, Mapping, Sequence
from pathlib import Path

from tools.package_format import canonical_json_bytes

from ..enrichment import import_enrichment
from ..models import BuildError
from ..workspace import load_workspace
from .history_profile import CONCEPT_KINDS, HIGH_CONFIDENCE_IDENTITY

_TOKEN = re.compile(r"[a-z0-9]+(?:[._-][a-z0-9]+)*\Z")
_REVIEW_STATES = {"auto-high-confidence", "reviewed", "unresolved"}


def merge_concept_candidates(
    candidates: Iterable[Mapping[str, object]],
    namespace: str,
) -> tuple[dict[str, object], ...]:
    parsed = [_parse_candidate(candidate, namespace) for candidate in candidates]
    high_identity: dict[tuple[str, str], set[str]] = {}
    high_aliases: dict[str, set[str]] = {}
    by_key: dict[str, list[dict[str, object]]] = {}
    for candidate in parsed:
        concept_key = str(candidate["conceptKey"])
        by_key.setdefault(concept_key, []).append(candidate)
        if _is_high_identity(candidate):
            identity = (
                str(candidate["kind"]),
                _normalized(str(candidate["canonicalText"])),
            )
            high_identity.setdefault(identity, set()).add(concept_key)
            for alias in candidate["aliases"]:
                high_aliases.setdefault(_normalized(alias), set()).add(concept_key)
    for (kind, canonical), keys in sorted(high_identity.items()):
        if len(keys) > 1:
            raise BuildError(
                "跨 Wiki 高置信同一概念必须使用同一 conceptKey："
                f"{kind}/{canonical} -> {', '.join(sorted(keys))}"
            )
    for alias, keys in sorted(high_aliases.items()):
        if len(keys) > 1:
            raise BuildError(
                f"高置信别名冲突：{alias} -> {', '.join(sorted(keys))}"
            )

    registry: list[dict[str, object]] = []
    for concept_key in sorted(by_key):
        group = by_key[concept_key]
        definitions = {
            (str(item["kind"]), str(item["canonicalText"])) for item in group
        }
        if len(definitions) != 1:
            raise BuildError(f"conceptKey 重复定义冲突：{concept_key}")
        kind, canonical_text = next(iter(definitions))
        aliases = sorted({alias for item in group for alias in item["aliases"]})
        review_states = {str(item["reviewState"]) for item in group}
        if "reviewed" in review_states:
            review_state = "reviewed"
        elif all(_is_high_identity(item) for item in group):
            review_state = "auto-high-confidence"
        else:
            review_state = "unresolved"
        sources = []
        for item in sorted(
            group,
            key=lambda value: (str(value["wikiId"]), tuple(value["evidence"])),
        ):
            sources.append(
                {
                    "wikiId": item["wikiId"],
                    "confidence": item["confidence"],
                    "reviewState": item["reviewState"],
                    "evidence": item["evidence"],
                }
            )
        registry.append(
            {
                "conceptKey": concept_key,
                "kind": kind,
                "canonicalText": canonical_text,
                "aliases": aliases,
                "reviewState": review_state,
                "metadata": {
                    "maxIdentityConfidence": max(
                        float(item["confidence"]) for item in group
                    )
                },
                "provenance": {"sources": sources},
            }
        )
    return tuple(registry)


def write_concept_registry(
    path: Path,
    rows: Sequence[Mapping[str, object]],
) -> Path:
    target = Path(path)
    if target.exists() or target.is_symlink():
        raise FileExistsError(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        for row in rows:
            stream.write(canonical_json_bytes(dict(row)) + b"\n")
    _read_registry(target)
    return target


def install_shared_registry(
    registry_path: Path,
    workspaces: Sequence[Path],
) -> dict[str, object]:
    if len(workspaces) < 2:
        raise BuildError("共享 registry 至少需要两个 Wiki 工作区")
    rows = _read_registry(registry_path)
    raw = b"".join(canonical_json_bytes(row) + b"\n" for row in rows)
    registry_hash = hashlib.sha256(raw).hexdigest()
    loaded = [load_workspace(workspace) for workspace in workspaces]
    namespaces = {workspace.concept_namespace for workspace in loaded}
    if len(namespaces) != 1:
        raise BuildError("双 Wiki conceptNamespace 不一致")
    namespace = next(iter(namespaces))
    for row in rows:
        _validate_registry_key(str(row["conceptKey"]), str(row["kind"]), namespace)

    backup_root = Path(tempfile.mkdtemp(prefix="hwiki-registry-backup-"))
    backups: list[tuple[Path, Path, Path, bytes]] = []
    try:
        for index, workspace in enumerate(loaded):
            database_backup = backup_root / f"{index}.sqlite"
            shutil.copy2(workspace.database_path, database_backup)
            registry_target = workspace.enrichment_path / "concept-registry.jsonl"
            backups.append(
                (
                    workspace.database_path,
                    database_backup,
                    registry_target,
                    registry_target.read_bytes(),
                )
            )
        for workspace in loaded:
            _atomic_write(
                workspace.enrichment_path / "concept-registry.jsonl",
                raw,
            )
        for workspace in loaded:
            import_enrichment(workspace.root)
    except BaseException:
        for database, database_backup, registry_target, previous_registry in backups:
            shutil.copy2(database_backup, database)
            _atomic_write(registry_target, previous_registry)
        raise
    finally:
        shutil.rmtree(backup_root, ignore_errors=True)
    return {
        "registryHash": registry_hash,
        "workspaceIds": sorted(workspace.wiki_id for workspace in loaded),
    }


def validate_pair_registry(left: Path, right: Path) -> dict[str, object]:
    workspaces = (load_workspace(left), load_workspace(right))
    hashes = []
    for workspace in workspaces:
        registry = workspace.enrichment_path / "concept-registry.jsonl"
        _read_registry(registry)
        digest = hashlib.sha256(registry.read_bytes()).hexdigest()
        with sqlite3.connect(workspace.database_path) as database:
            row = database.execute(
                "SELECT value FROM build_metadata WHERE key='conceptRegistryHash'"
            ).fetchone()
        if row is None or row[0] != digest:
            raise BuildError(
                f"{workspace.wiki_id} registry 文件与数据库哈希不一致"
            )
        hashes.append(digest)
    if len(set(hashes)) != 1:
        raise BuildError("双 Wiki registry hash 不一致")
    return {
        "registryHash": hashes[0],
        "workspaceIds": sorted(workspace.wiki_id for workspace in workspaces),
    }


def _parse_candidate(
    raw: Mapping[str, object],
    namespace: str,
) -> dict[str, object]:
    required = {
        "wikiId",
        "conceptKey",
        "kind",
        "canonicalText",
        "aliases",
        "confidence",
        "reviewState",
        "evidence",
    }
    unknown = sorted(set(raw) - required)
    missing = sorted(required - set(raw))
    if unknown or missing:
        raise BuildError(
            "concept candidate 字段无效："
            + ", ".join(unknown or missing)
        )
    wiki_id = _text(raw["wikiId"], "wikiId")
    concept_key = _text(raw["conceptKey"], "conceptKey")
    kind = _text(raw["kind"], "kind")
    canonical_text = _text(raw["canonicalText"], "canonicalText")
    _validate_registry_key(concept_key, kind, namespace)
    if kind not in CONCEPT_KINDS:
        raise BuildError(f"concept kind 不受支持：{kind}")
    aliases = raw["aliases"]
    if not isinstance(aliases, list) or any(
        not isinstance(alias, str) or not alias.strip() for alias in aliases
    ):
        raise BuildError("concept aliases 必须是非空字符串数组")
    if len({_normalized(alias) for alias in aliases}) != len(aliases):
        raise BuildError(f"concept aliases 重复：{concept_key}")
    confidence = _confidence(raw["confidence"], "confidence")
    review_state = _text(raw["reviewState"], "reviewState")
    if review_state not in _REVIEW_STATES:
        raise BuildError(f"reviewState 不受支持：{review_state}")
    if confidence < HIGH_CONFIDENCE_IDENTITY and review_state != "unresolved":
        raise BuildError("低置信 concept 必须保持 unresolved")
    evidence = raw["evidence"]
    if not isinstance(evidence, list) or not evidence or any(
        not isinstance(item, str) or not item.strip() for item in evidence
    ):
        raise BuildError("concept evidence 必须是非空 chunkId 数组")
    return {
        "wikiId": wiki_id,
        "conceptKey": concept_key,
        "kind": kind,
        "canonicalText": canonical_text,
        "aliases": list(aliases),
        "confidence": confidence,
        "reviewState": review_state,
        "evidence": sorted(set(evidence)),
    }


def _is_high_identity(candidate: Mapping[str, object]) -> bool:
    return (
        float(candidate["confidence"]) >= HIGH_CONFIDENCE_IDENTITY
        or candidate["reviewState"] == "reviewed"
    )


def _read_registry(path: Path) -> tuple[dict[str, object], ...]:
    source = Path(path)
    if source.is_symlink() or not source.is_file():
        raise BuildError(f"concept registry 不可安全读取：{source}")
    rows: list[dict[str, object]] = []
    previous: str | None = None
    for line_number, raw in enumerate(source.read_bytes().splitlines(keepends=True), start=1):
        if not raw.endswith(b"\n"):
            raise BuildError(f"concept registry 第 {line_number} 行缺少换行")
        try:
            row = json.loads(raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise BuildError(f"concept registry 第 {line_number} 行 JSON 无效") from error
        if not isinstance(row, dict) or raw != canonical_json_bytes(row) + b"\n":
            raise BuildError(f"concept registry 第 {line_number} 行不是规范 JSONL")
        required = {
            "conceptKey",
            "kind",
            "canonicalText",
            "aliases",
            "reviewState",
            "metadata",
            "provenance",
        }
        if set(row) != required:
            raise BuildError(f"concept registry 第 {line_number} 行字段无效")
        key = _text(row["conceptKey"], "conceptKey")
        if previous is not None and key <= previous:
            raise BuildError("concept registry 必须按 conceptKey 严格排序")
        previous = key
        rows.append(row)
    return tuple(rows)


def _validate_registry_key(concept_key: str, kind: str, namespace: str) -> None:
    parts = concept_key.split(":")
    if (
        len(parts) != 3
        or parts[0] != namespace
        or parts[1] != kind
        or not _TOKEN.fullmatch(parts[1])
        or not _TOKEN.fullmatch(parts[2])
    ):
        raise BuildError(f"conceptKey 无效：{concept_key}")


def _atomic_write(path: Path, payload: bytes) -> None:
    target = Path(path)
    temporary = target.with_name(f".{target.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _normalized(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).casefold().split())


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"{label} 必须是非空字符串")
    return value


def _confidence(value: object, label: str) -> float:
    if type(value) not in {int, float} or not math.isfinite(value) or not 0 <= value <= 1:
        raise BuildError(f"{label} 必须是 0 到 1 的有限数值")
    return float(value)


__all__ = [
    "install_shared_registry",
    "merge_concept_candidates",
    "validate_pair_registry",
    "write_concept_registry",
]
