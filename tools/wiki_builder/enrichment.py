"""Transactional import of evidence-linked Wiki enrichment JSONL."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import stat
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from .models import BuildError
from .normalization import chinese_ngrams, normalize_for_search
from .workspace import WikiWorkspace, load_workspace

_MAX_JSONL_LINE_BYTES = 4 * 1024 * 1024
_TOKEN_PATTERN = re.compile(r"[a-z0-9]+(?:[._-][a-z0-9]+)*\Z")


@dataclass(frozen=True)
class EvidenceRefInput:
    chunk_id: str
    role: str = "support"


@dataclass(frozen=True)
class EnrichmentStats:
    summaries: int
    terms: int
    aliases: int
    mentions: int
    annotations: int
    links: int
    concept_registry_hash: str


def import_enrichment(workspace: Path) -> EnrichmentStats:
    """Replace all semantic rows atomically from canonical JSONL assets."""

    loaded = load_workspace(workspace)
    registry_path = loaded.enrichment_path / "concept-registry.jsonl"
    registry_hash = _sha256_regular_file(registry_path)
    database = sqlite3.connect(loaded.database_path)
    database.execute("PRAGMA foreign_keys=ON")
    try:
        database.execute("BEGIN IMMEDIATE")
        _clear_enrichment(database)
        _load_registry(database, loaded, registry_path)
        summaries = _import_summaries(database, loaded)
        terms = _import_terms(database, loaded)
        aliases = _import_aliases(database, loaded)
        mentions = _import_mentions(database, loaded)
        annotations = _import_annotations(database, loaded)
        links = _import_links(database, loaded)
        _rebuild_term_alias_fts(database)
        if _sha256_regular_file(registry_path) != registry_hash:
            raise BuildError("concept-registry 在导入期间发生变化")
        database.execute(
            """
            INSERT INTO build_metadata(key, value) VALUES ('conceptRegistryHash', ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """,
            (registry_hash,),
        )
        database.commit()
        return EnrichmentStats(
            summaries=summaries,
            terms=terms,
            aliases=aliases,
            mentions=mentions,
            annotations=annotations,
            links=links,
            concept_registry_hash=registry_hash,
        )
    except BaseException:
        database.rollback()
        raise
    finally:
        database.close()


def _clear_enrichment(database: sqlite3.Connection) -> None:
    for table in (
        "evidence_refs",
        "summaries_fts",
        "terms_aliases_fts",
        "mentions",
        "aliases",
        "annotations",
        "links",
        "summaries",
        "terms",
    ):
        database.execute(f"DELETE FROM {table}")
    database.execute("DROP TABLE IF EXISTS temp.concept_registry")
    database.execute(
        """
        CREATE TEMP TABLE concept_registry(
            concept_key TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            canonical_text TEXT NOT NULL
        )
        """
    )


def _load_registry(
    database: sqlite3.Connection,
    workspace: WikiWorkspace,
    path: Path,
) -> None:
    previous: str | None = None
    for line_number, row in _iter_jsonl(path, "concept-registry"):
        _require_fields(
            row,
            required={"conceptKey", "kind", "canonicalText"},
            optional={"aliases", "reviewState", "metadata", "provenance"},
            label=f"concept-registry 第 {line_number} 行",
        )
        concept_key = _concept_key(row["conceptKey"], workspace.concept_namespace)
        previous = _require_sorted(previous, concept_key, "concept-registry", line_number)
        kind = _token(row["kind"], f"concept-registry 第 {line_number} 行 kind")
        canonical_text = _text(
            row["canonicalText"],
            f"concept-registry 第 {line_number} 行 canonicalText",
        )
        if concept_key.split(":", 2)[1] != kind:
            raise BuildError(
                f"concept-registry 第 {line_number} 行 kind 与 conceptKey 不一致"
            )
        aliases = row.get("aliases", [])
        if not isinstance(aliases, list) or any(
            not isinstance(alias, str) or not alias.strip() for alias in aliases
        ):
            raise BuildError(f"concept-registry 第 {line_number} 行 aliases 无效")
        for field in ("metadata", "provenance"):
            if field in row and not isinstance(row[field], dict):
                raise BuildError(f"concept-registry 第 {line_number} 行 {field} 必须是对象")
        if "reviewState" in row:
            _token(row["reviewState"], f"concept-registry 第 {line_number} 行 reviewState")
        database.execute(
            "INSERT INTO concept_registry(concept_key, kind, canonical_text) VALUES (?, ?, ?)",
            (concept_key, kind, canonical_text),
        )


def _import_summaries(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "summaries.jsonl"
    for line_number, row in _iter_jsonl(path, "summaries"):
        label = f"summaries 第 {line_number} 行"
        _require_fields(
            row,
            required={"id", "ownerType", "ownerId", "level", "text", "evidence"},
            label=label,
        )
        summary_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, summary_id, "summaries", line_number)
        owner_type = _token(row["ownerType"], f"{label} ownerType")
        owner_id = _text(row["ownerId"], f"{label} ownerId")
        _require_owner(
            database,
            owner_type,
            owner_id,
            {"document", "section", "chunk"},
            label,
        )
        level = _token(row["level"], f"{label} level")
        text = _text(row["text"], f"{label} text")
        evidence = _evidence(row["evidence"], label)
        database.execute(
            "INSERT INTO summaries(summary_id, owner_type, owner_id, level, text) VALUES (?, ?, ?, ?, ?)",
            (summary_id, owner_type, owner_id, level, text),
        )
        database.execute(
            "INSERT INTO summaries_fts(summary_id, text) VALUES (?, ?)",
            (summary_id, _semantic_fts_text(text)),
        )
        _insert_evidence(database, "summary", summary_id, evidence)
        count += 1
    return count


def _import_terms(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "terms.jsonl"
    for line_number, row in _iter_jsonl(path, "terms"):
        label = f"terms 第 {line_number} 行"
        _require_fields(
            row,
            required={
                "id",
                "conceptKey",
                "canonicalText",
                "kind",
                "confidence",
                "evidence",
            },
            optional={"metadata"},
            label=label,
        )
        term_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, term_id, "terms", line_number)
        concept_key = _concept_key(row["conceptKey"], workspace.concept_namespace)
        canonical_text = _text(row["canonicalText"], f"{label} canonicalText")
        kind = _token(row["kind"], f"{label} kind")
        registry = database.execute(
            "SELECT kind, canonical_text FROM concept_registry WHERE concept_key=?",
            (concept_key,),
        ).fetchone()
        if registry is None:
            raise BuildError(f"{label} conceptKey 不在 registry：{concept_key}")
        if registry != (kind, canonical_text):
            raise BuildError(f"{label} 与 registry 定义不一致：{concept_key}")
        metadata = row.get("metadata", {})
        if not isinstance(metadata, dict):
            raise BuildError(f"{label} metadata 必须是对象")
        confidence = _confidence(row["confidence"], f"{label} confidence")
        evidence = _evidence(row["evidence"], label)
        database.execute(
            """
            INSERT INTO terms(
                term_id, concept_key, canonical_text, kind, confidence, metadata_json
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                term_id,
                concept_key,
                canonical_text,
                kind,
                confidence,
                canonical_json_bytes(metadata).decode("utf-8"),
            ),
        )
        _insert_evidence(database, "term", term_id, evidence)
        count += 1
    return count


def _import_aliases(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "aliases.jsonl"
    for line_number, row in _iter_jsonl(path, "aliases"):
        label = f"aliases 第 {line_number} 行"
        _require_fields(
            row,
            required={"id", "termId", "aliasText", "confidence", "evidence"},
            optional={"normalizedAlias"},
            label=label,
        )
        alias_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, alias_id, "aliases", line_number)
        term_id = _token(row["termId"], f"{label} termId")
        if database.execute("SELECT 1 FROM terms WHERE term_id=?", (term_id,)).fetchone() is None:
            raise BuildError(f"{label} 引用了不存在的 term：{term_id}")
        alias_text = _text(row["aliasText"], f"{label} aliasText")
        normalized_alias = normalize_for_search(alias_text)
        if not normalized_alias:
            raise BuildError(f"{label} aliasText 规范化后为空")
        if "normalizedAlias" in row and row["normalizedAlias"] != normalized_alias:
            raise BuildError(f"{label} normalizedAlias 与规范化结果不一致")
        confidence = _confidence(row["confidence"], f"{label} confidence")
        evidence = _evidence(row["evidence"], label)
        database.execute(
            """
            INSERT INTO aliases(
                alias_id, term_id, alias_text, normalized_alias, confidence
            ) VALUES (?, ?, ?, ?, ?)
            """,
            (alias_id, term_id, alias_text, normalized_alias, confidence),
        )
        _insert_evidence(database, "alias", alias_id, evidence)
        count += 1
    return count


def _import_mentions(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "mentions.jsonl"
    for line_number, row in _iter_jsonl(path, "mentions"):
        label = f"mentions 第 {line_number} 行"
        _require_fields(
            row,
            required={
                "id",
                "termId",
                "chunkId",
                "startOffset",
                "endOffset",
                "text",
                "confidence",
            },
            label=label,
        )
        mention_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, mention_id, "mentions", line_number)
        term_id = _token(row["termId"], f"{label} termId")
        if database.execute("SELECT 1 FROM terms WHERE term_id=?", (term_id,)).fetchone() is None:
            raise BuildError(f"{label} 引用了不存在的 term：{term_id}")
        chunk_id = _text(row["chunkId"], f"{label} chunkId")
        chunk = database.execute(
            "SELECT original_text FROM chunks WHERE chunk_id=?", (chunk_id,)
        ).fetchone()
        if chunk is None:
            raise BuildError(f"{label} 引用了不存在的 chunk：{chunk_id}")
        start = _integer(row["startOffset"], f"{label} startOffset")
        end = _integer(row["endOffset"], f"{label} endOffset")
        mention_text = _text(row["text"], f"{label} text")
        if start < 0 or end <= start or end > len(chunk[0]) or chunk[0][start:end] != mention_text:
            raise BuildError(f"{label} offset 与原文不一致")
        confidence = _confidence(row["confidence"], f"{label} confidence")
        database.execute(
            """
            INSERT INTO mentions(
                mention_id, term_id, chunk_id, start_offset, end_offset, confidence
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (mention_id, term_id, chunk_id, start, end, confidence),
        )
        _insert_evidence(
            database,
            "mention",
            mention_id,
            (EvidenceRefInput(chunk_id=chunk_id, role="mention"),),
        )
        count += 1
    return count


def _import_annotations(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "annotations.jsonl"
    for line_number, row in _iter_jsonl(path, "annotations"):
        label = f"annotations 第 {line_number} 行"
        _require_fields(
            row,
            required={
                "id",
                "ownerType",
                "ownerId",
                "kind",
                "value",
                "confidence",
                "evidence",
            },
            label=label,
        )
        annotation_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, annotation_id, "annotations", line_number)
        owner_type = _token(row["ownerType"], f"{label} ownerType")
        owner_id = _text(row["ownerId"], f"{label} ownerId")
        _require_owner(
            database,
            owner_type,
            owner_id,
            {"document", "section", "chunk", "summary", "term"},
            label,
        )
        kind = _token(row["kind"], f"{label} kind")
        value_json = canonical_json_bytes(row["value"]).decode("utf-8")
        confidence = _confidence(row["confidence"], f"{label} confidence")
        evidence = _evidence(row["evidence"], label)
        database.execute(
            """
            INSERT INTO annotations(
                annotation_id, owner_type, owner_id, kind, value_json, confidence
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            (annotation_id, owner_type, owner_id, kind, value_json, confidence),
        )
        _insert_evidence(database, "annotation", annotation_id, evidence)
        count += 1
    return count


def _import_links(database: sqlite3.Connection, workspace: WikiWorkspace) -> int:
    count = 0
    previous: str | None = None
    path = workspace.enrichment_path / "links.jsonl"
    for line_number, row in _iter_jsonl(path, "links"):
        label = f"links 第 {line_number} 行"
        _require_fields(
            row,
            required={
                "id",
                "sourceType",
                "sourceId",
                "targetNamespace",
                "targetType",
                "targetId",
                "kind",
                "confidence",
                "evidence",
            },
            label=label,
        )
        link_id = _token(row["id"], f"{label} id")
        previous = _require_sorted(previous, link_id, "links", line_number)
        source_type = _token(row["sourceType"], f"{label} sourceType")
        source_id = _text(row["sourceId"], f"{label} sourceId")
        _require_owner(
            database,
            source_type,
            source_id,
            {"document", "section", "chunk", "summary", "term", "annotation"},
            label,
        )
        target_namespace = _text(
            row["targetNamespace"], f"{label} targetNamespace"
        )
        target_type = _token(row["targetType"], f"{label} targetType")
        target_id = _text(row["targetId"], f"{label} targetId")
        kind = _token(row["kind"], f"{label} kind")
        confidence = _confidence(row["confidence"], f"{label} confidence")
        evidence = _evidence(row["evidence"], label)
        database.execute(
            """
            INSERT INTO links(
                link_id, source_type, source_id, target_namespace, target_type,
                target_id, kind, confidence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                link_id,
                source_type,
                source_id,
                target_namespace,
                target_type,
                target_id,
                kind,
                confidence,
            ),
        )
        _insert_evidence(database, "link", link_id, evidence)
        count += 1
    return count


def _rebuild_term_alias_fts(database: sqlite3.Connection) -> None:
    for term_id, canonical_text in database.execute(
        "SELECT term_id, canonical_text FROM terms ORDER BY term_id"
    ):
        alias_values = [
            row[0]
            for row in database.execute(
                "SELECT alias_text FROM aliases WHERE term_id=? ORDER BY alias_id",
                (term_id,),
            )
        ]
        database.execute(
            """
            INSERT INTO terms_aliases_fts(owner_id, canonical_text, aliases_text)
            VALUES (?, ?, ?)
            """,
            (
                term_id,
                _semantic_fts_text(canonical_text),
                " ".join(_semantic_fts_text(alias) for alias in alias_values),
            ),
        )


def _semantic_fts_text(value: str) -> str:
    ngrams = " ".join(chinese_ngrams(value))
    return f"{value} {ngrams}" if ngrams else value


def _insert_evidence(
    database: sqlite3.Connection,
    owner_type: str,
    owner_id: str,
    evidence: Iterable[EvidenceRefInput],
) -> None:
    ordered = sorted(evidence, key=lambda item: (item.chunk_id, item.role))
    chunk_ids = [item.chunk_id for item in ordered]
    if len(chunk_ids) != len(set(chunk_ids)):
        raise BuildError(f"{owner_type} {owner_id} 包含重复 evidence chunk")
    for ordinal, item in enumerate(ordered):
        if database.execute(
            "SELECT 1 FROM chunks WHERE chunk_id=?", (item.chunk_id,)
        ).fetchone() is None:
            raise BuildError(f"enrichment 引用了不存在的 chunk：{item.chunk_id}")
        database.execute(
            """
            INSERT INTO evidence_refs(owner_type, owner_id, chunk_id, role, ordinal)
            VALUES (?, ?, ?, ?, ?)
            """,
            (owner_type, owner_id, item.chunk_id, item.role, ordinal),
        )


def _evidence(value: object, label: str) -> tuple[EvidenceRefInput, ...]:
    if not isinstance(value, list) or not value:
        raise BuildError(f"{label} evidence 必须是非空数组")
    result: list[EvidenceRefInput] = []
    for index, raw in enumerate(value):
        item_label = f"{label} evidence[{index}]"
        if isinstance(raw, str):
            result.append(EvidenceRefInput(_text(raw, item_label)))
            continue
        if not isinstance(raw, dict):
            raise BuildError(f"{item_label} 必须是 chunkId 或对象")
        _require_fields(raw, required={"chunkId"}, optional={"role"}, label=item_label)
        result.append(
            EvidenceRefInput(
                chunk_id=_text(raw["chunkId"], f"{item_label} chunkId"),
                role=_token(raw.get("role", "support"), f"{item_label} role"),
            )
        )
    return tuple(result)


def _require_owner(
    database: sqlite3.Connection,
    owner_type: str,
    owner_id: str,
    allowed: set[str],
    label: str,
) -> None:
    if owner_type not in allowed:
        raise BuildError(f"{label} ownerType 不受支持：{owner_type}")
    table, column = {
        "document": ("documents", "document_id"),
        "section": ("sections", "section_id"),
        "chunk": ("chunks", "chunk_id"),
        "summary": ("summaries", "summary_id"),
        "term": ("terms", "term_id"),
        "annotation": ("annotations", "annotation_id"),
    }[owner_type]
    if database.execute(
        f"SELECT 1 FROM {table} WHERE {column}=?", (owner_id,)
    ).fetchone() is None:
        raise BuildError(f"{label} 引用了不存在的 {owner_type}：{owner_id}")


def _iter_jsonl(path: Path, label: str) -> Iterator[tuple[int, dict[str, object]]]:
    descriptor = _open_regular(path)
    before = os.fstat(descriptor)
    try:
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            line_number = 0
            while True:
                raw = stream.readline(_MAX_JSONL_LINE_BYTES + 1)
                if not raw:
                    break
                line_number += 1
                if len(raw) > _MAX_JSONL_LINE_BYTES or not raw.endswith(b"\n"):
                    raise BuildError(f"{label} 第 {line_number} 行过长或缺少换行")
                try:
                    parsed = json.loads(raw, parse_constant=_reject_json_constant)
                except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                    raise BuildError(f"{label} 第 {line_number} 行不是有效 JSON：{error}") from error
                if not isinstance(parsed, dict):
                    raise BuildError(f"{label} 第 {line_number} 行必须是对象")
                if raw != canonical_json_bytes(parsed) + b"\n":
                    raise BuildError(f"{label} 第 {line_number} 行不是规范 JSONL")
                yield line_number, parsed
        after = os.fstat(descriptor)
        if not _same_file_state(before, after):
            raise BuildError(f"{label} 在读取期间发生变化")
    finally:
        os.close(descriptor)


def _sha256_regular_file(path: Path) -> str:
    descriptor = _open_regular(path)
    before = os.fstat(descriptor)
    digest = hashlib.sha256()
    try:
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(1024 * 1024):
                digest.update(block)
        after = os.fstat(descriptor)
        if not _same_file_state(before, after):
            raise BuildError(f"文件在哈希期间发生变化：{path.name}")
        return digest.hexdigest()
    finally:
        os.close(descriptor)


def _open_regular(path: Path) -> int:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise BuildError(f"增强资产无法安全读取：{path.name}：{error}") from error
    if not stat.S_ISREG(os.fstat(descriptor).st_mode):
        os.close(descriptor)
        raise BuildError(f"增强资产不是普通文件：{path.name}")
    return descriptor


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


def _require_fields(
    row: Mapping[str, object],
    *,
    required: set[str],
    optional: set[str] | None = None,
    label: str,
) -> None:
    allowed = required | (optional or set())
    unknown = sorted(set(row) - allowed)
    missing = sorted(required - set(row))
    if unknown:
        raise BuildError(f"{label} 包含未知字段：{', '.join(unknown)}")
    if missing:
        raise BuildError(f"{label} 缺少字段：{', '.join(missing)}")


def _require_sorted(
    previous: str | None,
    current: str,
    label: str,
    line_number: int,
) -> str:
    if previous == current:
        raise BuildError(f"{label} 第 {line_number} 行 ID 重复：{current}")
    if previous is not None and current < previous:
        raise BuildError(f"{label} 必须按 ID 升序排列：{current}")
    return current


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"{label} 必须是非空字符串")
    return value


def _token(value: object, label: str) -> str:
    token = _text(value, label)
    if not _TOKEN_PATTERN.fullmatch(token):
        raise BuildError(f"{label} 不是规范标识符")
    return token


def _concept_key(value: object, namespace: str) -> str:
    concept_key = _text(value, "conceptKey")
    segments = concept_key.split(":")
    if (
        len(segments) != 3
        or segments[0] != namespace
        or not _TOKEN_PATTERN.fullmatch(segments[1])
        or not _TOKEN_PATTERN.fullmatch(segments[2])
    ):
        raise BuildError(f"conceptKey 不属于工作区命名空间 {namespace}：{concept_key}")
    return concept_key


def _confidence(value: object, label: str) -> float:
    if type(value) not in {int, float} or not math.isfinite(value) or not 0 <= value <= 1:
        raise BuildError(f"{label} 必须是 0 到 1 的有限数值")
    return float(value)


def _integer(value: object, label: str) -> int:
    if type(value) is not int:
        raise BuildError(f"{label} 必须是整数")
    return value


def _reject_json_constant(value: str) -> object:
    raise ValueError(f"不允许 JSON 常量 {value}")


__all__ = [
    "EnrichmentStats",
    "EvidenceRefInput",
    "import_enrichment",
]
