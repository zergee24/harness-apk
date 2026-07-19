"""Streaming V2 persona-asset validation and deterministic offline evaluation."""

from __future__ import annotations

import json
import errno
import os
import sqlite3
import stat
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Protocol

from .models import BuildError
from .schema_v2 import AgentAssetPaths, SourceGenre, WorkspaceV2


EVALUATION_CATEGORIES = (
    "grounding",
    "stance",
    "voice",
    "temporal",
    "diversity",
    "global",
)
MINIMUM_EVAL_COUNTS = {
    "grounding": 20,
    "stance": 30,
    "voice": 20,
    "temporal": 12,
    "diversity": 10,
    "global": 8,
}
MIN_GROUNDING_RATE = 0.85
MIN_STANCE_RATE = 1.0
MAX_JSONL_LINE_BYTES = 1024 * 1024
MAX_ASSET_BYTES = 4 * 1024 * 1024
_DIRECT_AUTHORSHIPS = frozenset(("direct", "edited_direct"))
_VALID_AUTHORSHIPS = _DIRECT_AUTHORSHIPS | {"secondary"}
_DIALOGUE_GENRES = frozenset(("speech", "conversation", "letter", "interview"))
_VALID_GENRES = frozenset(item.value for item in SourceGenre if item != SourceGenre.UNKNOWN)
_MAX_RETRIEVAL_TERMS = 64
_TOP_K = 8


@dataclass(frozen=True)
class CategoryMetric:
    total: int
    passed: int
    rate: float


@dataclass(frozen=True)
class EvaluationReport:
    category_metrics: dict[str, CategoryMetric]
    by_period: dict[str, CategoryMetric]
    by_authorship: dict[str, CategoryMetric]
    by_corpus: dict[str, CategoryMetric]
    errors: tuple[str, ...] = ()
    factual_coverage: dict[str, object] = field(default_factory=dict)
    stance_coverage: dict[str, object] = field(default_factory=dict)
    dialogue_material_coverage: dict[str, object] = field(default_factory=dict)
    voice_availability: dict[str, object] = field(default_factory=dict)
    minimum_grounding_rate: float = MIN_GROUNDING_RATE
    minimum_stance_rate: float = MIN_STANCE_RATE

    def metrics(self) -> dict[str, object]:
        def encode(values: Mapping[str, CategoryMetric]) -> dict[str, dict[str, object]]:
            return {
                name: {"total": item.total, "passed": item.passed, "rate": item.rate}
                for name, item in sorted(values.items())
            }

        return {
            "evaluation": {
                "byAuthorship": encode(self.by_authorship),
                "byCorpus": encode(self.by_corpus),
                "byPeriod": encode(self.by_period),
                "categories": encode(self.category_metrics),
                "minimumGroundingRate": self.minimum_grounding_rate,
                "minimumStanceRate": self.minimum_stance_rate,
            },
            "factualCoverage": self.factual_coverage,
            "stanceCoverage": self.stance_coverage,
            "dialogueMaterialCoverage": self.dialogue_material_coverage,
            "voiceAvailability": self.voice_availability,
        }


class _ChunkLookup(Protocol):
    def get(self, chunk_id: str) -> Mapping[str, str] | None: ...


class _SqliteChunks:
    def __init__(self, connection: sqlite3.Connection):
        self._connection = connection

    def get(self, chunk_id: str) -> dict[str, str] | None:
        row = self._connection.execute(
            """
            SELECT id, authorship, period, genre, corpus_id, source_id,
                   duplicate_group, parent_ids, route
            FROM chunks WHERE id = ?
            """,
            (chunk_id,),
        ).fetchone()
        return dict(row) if row else None

    def retrieve(self, question: str) -> tuple[str, ...]:
        terms = _query_terms(question)
        if not terms:
            return ()
        query = " OR ".join(_quote_fts_term(term) for term in terms)
        rows = self._connection.execute(
            """
            SELECT chunk_id
            FROM chunk_search
            WHERE chunk_search MATCH ?
            ORDER BY bm25(chunk_search), chunk_id COLLATE BINARY ASC
            LIMIT ?
            """,
            (query, _TOP_K),
        )
        return tuple(row["chunk_id"] for row in rows)

    def coverage_summaries(
        self, voice_evidence: tuple[str, ...]
    ) -> tuple[dict[str, object], dict[str, object]]:
        total = int(self._connection.execute("SELECT COUNT(*) FROM chunks").fetchone()[0])
        total_sources = int(self._connection.execute("SELECT COUNT(DISTINCT source_id) FROM chunks").fetchone()[0])
        direct_dialogue = self._connection.execute(
            """
            SELECT COUNT(*) AS chunks, COUNT(DISTINCT source_id) AS sources
            FROM chunks
            WHERE authorship IN ('direct', 'edited_direct')
              AND genre IN ('speech', 'conversation', 'letter', 'interview')
            """
        ).fetchone()
        authorship_rows = self._connection.execute(
            """
            SELECT authorship, COUNT(*) AS chunks, COUNT(DISTINCT source_id) AS sources
            FROM chunks GROUP BY authorship ORDER BY authorship
            """
        )
        by_authorship = {
            row["authorship"]: {"chunks": int(row["chunks"]), "sources": int(row["sources"])}
            for row in authorship_rows
        }
        evidence = tuple(sorted(set(voice_evidence)))
        if evidence:
            placeholders = ",".join("?" for _ in evidence)
            voice = self._connection.execute(
                f"""
                SELECT COUNT(*) AS chunks,
                       COUNT(DISTINCT source_id) AS sources,
                       SUM(CASE WHEN authorship IN ('direct', 'edited_direct') THEN 1 ELSE 0 END) AS direct_chunks,
                       COUNT(DISTINCT CASE WHEN authorship IN ('direct', 'edited_direct') THEN source_id END) AS direct_sources,
                       SUM(CASE WHEN authorship IN ('direct', 'edited_direct')
                                    AND genre IN ('speech', 'conversation', 'letter', 'interview')
                                THEN 1 ELSE 0 END) AS direct_dialogue_chunks
                FROM chunks WHERE id IN ({placeholders})
                """,
                evidence,
            ).fetchone()
        else:
            voice = {"chunks": 0, "sources": 0, "direct_chunks": 0, "direct_sources": 0, "direct_dialogue_chunks": 0}
        dialogue = {
            "byAuthorship": by_authorship,
            "directDialogueChunks": int(direct_dialogue["chunks"] or 0),
            "directDialogueSources": int(direct_dialogue["sources"] or 0),
            "rate": round(int(direct_dialogue["chunks"] or 0) / total, 6) if total else 0.0,
            "sourceRate": round(int(direct_dialogue["sources"] or 0) / total_sources, 6) if total_sources else 0.0,
            "totalChunks": total,
            "totalSources": total_sources,
        }
        availability = {
            "available": int(voice["direct_chunks"] or 0) > 0,
            "directDialogueEvidenceChunks": int(voice["direct_dialogue_chunks"] or 0),
            "directEvidenceChunks": int(voice["direct_chunks"] or 0),
            "directEvidenceSources": int(voice["direct_sources"] or 0),
            "referencedVoiceChunks": int(voice["chunks"] or 0),
            "referencedVoiceSources": int(voice["sources"] or 0),
        }
        return dialogue, availability


@dataclass(frozen=True)
class _EvalRow:
    category: str
    corpus_id: str
    expected_evidence: tuple[str, ...]
    period: str
    question: str


def validate_agent_assets(
    workspace: Path, chunks_by_id: _ChunkLookup | Mapping[str, Mapping[str, str]]
) -> list[str]:
    """Validate every V2 runtime asset against the immutable chunk metadata lookup."""
    workspace = Path(workspace).expanduser().resolve()
    errors: list[str] = []
    assets = _load_asset_paths(workspace, errors)
    if assets is None:
        return errors
    return _validate_agent_assets(workspace, assets, chunks_by_id, errors)


def _validate_agent_assets(
    workspace: Path,
    assets: AgentAssetPaths,
    chunks_by_id: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> list[str]:
    persona = _read_text_asset(workspace, assets.persona, "persona", errors)
    identity = _read_json_asset(workspace, assets.identity, "identity", errors)
    voice = _read_json_asset(workspace, assets.voice, "voice", errors)
    worldview = _read_jsonl_asset(workspace, assets.worldview, "worldview", errors)
    episodes = _read_jsonl_asset(workspace, assets.episodes, "episodes", errors)
    concepts = _read_json_asset(workspace, assets.concepts, "concepts", errors)
    examples = _read_jsonl_asset(workspace, assets.examples, "examples", errors)
    openers = _read_json_asset(workspace, assets.openers, "openers", errors)
    evaluations = _read_jsonl_asset(workspace, assets.eval, "eval", errors)

    if isinstance(persona, str) and not persona.strip():
        errors.append(f"persona 不能为空：{assets.persona}")
    _validate_identity(identity, chunks_by_id, errors)
    _validate_voice(voice, chunks_by_id, errors)
    _validate_worldview(worldview, chunks_by_id, errors)
    _validate_episodes(episodes, chunks_by_id, errors)
    _validate_concepts(concepts, chunks_by_id, errors)
    _validate_examples(examples, chunks_by_id, errors)
    _validate_openers(openers, errors)
    _validate_evaluations(evaluations, chunks_by_id, errors)
    return _unique(errors)


def evaluate_workspace(workspace: Path) -> EvaluationReport:
    """Evaluate a V2 workspace without retaining its complete corpus in memory."""
    workspace = Path(workspace).expanduser().resolve()
    errors: list[str] = []
    empty = _new_metrics()
    try:
        with tempfile.TemporaryDirectory(prefix=".harness-evaluation-") as temp_dir:
            connection: sqlite3.Connection | None = None
            try:
                connection = sqlite3.connect(Path(temp_dir) / "chunks.sqlite")
                connection.row_factory = sqlite3.Row
                _create_index(connection)
                manifest = _load_workspace(workspace, errors)
                source_hashes = (
                    {source.source_id: source.source_hash for source in manifest.sources}
                    if manifest is not None
                    else None
                )
                if source_hashes is not None:
                    nodes_are_valid = _stream_node_index(workspace, connection, source_hashes, errors)
                    _stream_chunk_index(
                        workspace, connection, errors, source_hashes, validate_parents=nodes_are_valid
                    )
                lookup = _SqliteChunks(connection)
                assets = manifest.assets if manifest is not None else None
                if assets is not None:
                    errors.extend(_validate_agent_assets(workspace, assets, lookup, []))
                rows = _read_eval_rows(workspace, assets, errors) if assets else ()
                category, periods, authorships, corpora = _evaluate_rows(rows, lookup)
                _append_release_errors(category, errors)
                voice_evidence = _voice_evidence(workspace, assets) if assets else ()
                factual, stance = _asset_coverage(workspace, assets, lookup) if assets else ({}, {})
                dialogue, availability = lookup.coverage_summaries(voice_evidence)
                return EvaluationReport(
                    category_metrics=category,
                    by_period=periods,
                    by_authorship=authorships,
                    by_corpus=corpora,
                    errors=tuple(_unique(errors)),
                    factual_coverage=factual,
                    stance_coverage=stance,
                    dialogue_material_coverage=dialogue,
                    voice_availability=availability,
                )
            finally:
                if connection is not None:
                    connection.close()
    except (BuildError, sqlite3.Error, OSError, UnicodeError, RecursionError, ValueError) as error:
        errors.append(f"V2 评测无法完成：{error}")
    return EvaluationReport(empty, {}, {}, {}, tuple(_unique(errors)))


def validate_declared_corpus_question_coverage(
    report: EvaluationReport, declared_corpora: Mapping[str, str]
) -> list[str]:
    """B4 hook for required/recommended package question coverage."""
    errors: list[str] = []
    for corpus_id, install_class in sorted(declared_corpora.items()):
        if install_class not in {"required", "recommended"}:
            continue
        metric = report.by_corpus.get(corpus_id, CategoryMetric(0, 0, 0.0))
        if metric.total < 2:
            errors.append(f"{install_class} corpus {corpus_id} 至少需要 2 道可归因评估题，实际 {metric.total}")
    return errors


def _create_index(connection: sqlite3.Connection) -> None:
    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript(
        """
        CREATE TABLE chunks (
            id TEXT PRIMARY KEY,
            authorship TEXT NOT NULL,
            period TEXT NOT NULL,
            genre TEXT NOT NULL,
            corpus_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            duplicate_group TEXT NOT NULL,
            parent_ids TEXT NOT NULL,
            route TEXT NOT NULL
        );
        CREATE TABLE nodes (
            id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            parent_id TEXT,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            summary TEXT NOT NULL,
            path TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE chunk_search USING fts5(
            chunk_id UNINDEXED,
            terms,
            tokenize='unicode61'
        );
        """
    )


def _stream_node_index(
    workspace: Path, connection: sqlite3.Connection, source_hashes: Mapping[str, str], errors: list[str]
) -> bool:
    descriptor: int | None = None
    valid = True
    try:
        descriptor = _open_regular_relative(workspace, ("corpora", "index", "nodes.jsonl"))
        before = os.fstat(descriptor)
        for line_number, raw_line, line_error in _iter_bounded_jsonl_lines(descriptor):
            if line_error:
                errors.append(f"资料节点 JSONL 第 {line_number} 行{line_error}")
                valid = False
                continue
            if raw_line is None or not raw_line.strip():
                continue
            try:
                value = json.loads(raw_line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
                errors.append(f"资料节点 JSONL 第 {line_number} 行无法读取：{error}")
                valid = False
                continue
            node = _node_row(value, line_number, source_hashes, errors)
            if node is None:
                valid = False
                continue
            try:
                connection.execute(
                    """
                    INSERT INTO nodes(id, source_id, parent_id, kind, title, summary, path)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    node,
                )
            except sqlite3.IntegrityError:
                errors.append(f"资料节点 JSONL 第 {line_number} 行存在重复 node ID：{node[0]}")
                valid = False
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            errors.append("资料节点在读取期间发生变化：corpora/index/nodes.jsonl")
            valid = False
        if not _validate_node_graph(connection, source_hashes, errors):
            valid = False
        connection.commit()
    except (OSError, sqlite3.Error) as error:
        errors.append(f"资料节点无法读取：corpora/index/nodes.jsonl：{error}")
        valid = False
    finally:
        if descriptor is not None:
            os.close(descriptor)
    return valid


def _stream_chunk_index(
    workspace: Path,
    connection: sqlite3.Connection,
    errors: list[str],
    source_hashes: Mapping[str, str] | None = None,
    *,
    validate_parents: bool = True,
) -> None:
    if source_hashes is None:
        manifest = _load_workspace(workspace, errors)
        if manifest is None:
            return
        source_hashes = {source.source_id: source.source_hash for source in manifest.sources}
    descriptor: int | None = None
    try:
        descriptor = _open_regular_relative(workspace, ("corpora", "index", "chunks.jsonl"))
        before = os.fstat(descriptor)
        for line_number, raw_line, line_error in _iter_bounded_jsonl_lines(descriptor):
            if line_error:
                errors.append(f"资料索引 JSONL 第 {line_number} 行{line_error}")
                continue
            if raw_line is None or not raw_line.strip():
                continue
            try:
                value = json.loads(raw_line.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
                errors.append(f"资料索引 JSONL 第 {line_number} 行无法读取：{error}")
                continue
            row = _chunk_row(value, line_number, errors)
            if row is None:
                continue
            (
                chunk_id,
                authorship,
                period,
                genre,
                corpus_id,
                source_id,
                source_hash,
                duplicate_group,
                parent_ids,
                route,
                terms,
                parent_id_values,
            ) = row
            declared_hash = source_hashes.get(source_id)
            if declared_hash is None:
                errors.append(
                    f"资料索引 JSONL 第 {line_number} 行 sourceId 不在 workspace.json sources 中：{source_id}"
                )
                continue
            if source_hash != declared_hash:
                errors.append(
                    f"资料索引 JSONL 第 {line_number} 行 sourceHash 与 workspace.json sources 不一致：{source_id}"
                )
                continue
            if validate_parents:
                verified_route = _verified_chunk_route(connection, source_id, parent_id_values, line_number, errors)
                if verified_route is None:
                    continue
                route = verified_route
            else:
                route = ""
            try:
                connection.execute(
                    """
                    INSERT INTO chunks(
                        id, authorship, period, genre, corpus_id, source_id,
                        duplicate_group, parent_ids, route
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (chunk_id, authorship, period, genre, corpus_id, source_id, duplicate_group, parent_ids, route),
                )
                connection.execute(
                    "INSERT INTO chunk_search(chunk_id, terms) VALUES (?, ?)", (chunk_id, terms)
                )
            except sqlite3.IntegrityError:
                errors.append(f"资料索引 JSONL 第 {line_number} 行存在重复 chunk ID：{chunk_id}")
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            errors.append("资料索引在读取期间发生变化：corpora/index/chunks.jsonl")
        connection.commit()
    except (OSError, sqlite3.Error) as error:
        errors.append(f"资料索引无法读取：corpora/index/chunks.jsonl：{error}")
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _iter_bounded_jsonl_lines(descriptor: int):
    line_number = 1
    pending = bytearray()
    overflow = False
    with os.fdopen(os.dup(descriptor), "rb") as stream:
        while block := stream.read(64 * 1024):
            start = 0
            while start < len(block):
                newline = block.find(b"\n", start)
                end = len(block) if newline < 0 else newline
                segment = block[start:end]
                if not overflow:
                    if len(pending) + len(segment) > MAX_JSONL_LINE_BYTES:
                        pending.clear()
                        overflow = True
                    else:
                        pending.extend(segment)
                if newline < 0:
                    break
                if overflow:
                    yield line_number, None, f"超过 {MAX_JSONL_LINE_BYTES} 字节上限"
                else:
                    yield line_number, bytes(pending).rstrip(b"\r"), None
                line_number += 1
                pending.clear()
                overflow = False
                start = newline + 1
        if pending or overflow:
            if overflow:
                yield line_number, None, f"超过 {MAX_JSONL_LINE_BYTES} 字节上限"
            else:
                yield line_number, bytes(pending).rstrip(b"\r"), None


def _chunk_row(
    value: object, line_number: int, errors: list[str]
) -> tuple[str, str, str, str, str, str, str, str, str, str, str, tuple[str, ...]] | None:
    if not isinstance(value, dict):
        errors.append(f"资料索引 JSONL 第 {line_number} 行必须是对象")
        return None
    required_strings = (
        "id",
        "authorship",
        "period",
        "genre",
        "sourceId",
        "sourceHash",
        "duplicateGroup",
        "text",
    )
    values: dict[str, str] = {}
    for field_name in required_strings:
        candidate = value.get(field_name)
        if not isinstance(candidate, str) or not candidate.strip():
            errors.append(f"资料索引 JSONL 第 {line_number} 行缺少或包含无效 {field_name}")
            return None
        values[field_name] = candidate.strip()
    if values["authorship"] not in _VALID_AUTHORSHIPS:
        errors.append(f"资料索引 JSONL 第 {line_number} 行 authorship 无效：{values['authorship']}")
        return None
    if values["genre"] not in _VALID_GENRES:
        errors.append(f"资料索引 JSONL 第 {line_number} 行 genre 无效：{values['genre']}")
        return None
    if values["period"] == "unknown":
        errors.append(f"资料索引 JSONL 第 {line_number} 行 period 无效：unknown")
        return None
    parent_ids = value.get("parentIds")
    if not isinstance(parent_ids, list) or not parent_ids or any(
        not isinstance(item, str) or not item.strip() for item in parent_ids
    ):
        errors.append(f"资料索引 JSONL 第 {line_number} 行 parentIds 必须是非空字符串数组")
        return None
    field_values: list[list[str]] = []
    for field_name in ("keywords", "ngrams"):
        candidate = value.get(field_name)
        if not isinstance(candidate, list) or any(not isinstance(item, str) for item in candidate):
            errors.append(f"资料索引 JSONL 第 {line_number} 行 {field_name} 必须是字符串数组")
            return None
        field_values.append(candidate)
    normalized_parent_ids = tuple(item.strip() for item in parent_ids)
    top_level = normalized_parent_ids[1] if len(normalized_parent_ids) > 1 else normalized_parent_ids[0]
    route = f"{values['sourceId']}:{top_level.strip()}"
    return (
        values["id"],
        values["authorship"],
        values["period"],
        values["genre"],
        "unassigned",
        values["sourceId"],
        values["sourceHash"],
        values["duplicateGroup"],
        json.dumps(normalized_parent_ids, ensure_ascii=False, separators=(",", ":")),
        route,
        _fts_terms(field_values[0], field_values[1]),
        normalized_parent_ids,
    )


def _node_row(
    value: object, line_number: int, source_hashes: Mapping[str, str], errors: list[str]
) -> tuple[str, str, str | None, str, str, str, str] | None:
    if not isinstance(value, dict):
        errors.append(f"资料节点 JSONL 第 {line_number} 行必须是对象")
        return None
    node_id = value.get("id")
    source_id = value.get("sourceId")
    if not isinstance(node_id, str) or not node_id.strip():
        errors.append(f"资料节点 JSONL 第 {line_number} 行缺少或包含无效 id")
        return None
    if not isinstance(source_id, str) or not source_id.strip():
        errors.append(f"资料节点 JSONL 第 {line_number} 行缺少或包含无效 sourceId")
        return None
    source_id = source_id.strip()
    if source_id not in source_hashes:
        errors.append(f"资料节点 JSONL 第 {line_number} 行 sourceId 不在 workspace.json sources 中：{source_id}")
        return None
    parent_id = value.get("parentId")
    valid = True
    if parent_id is not None and (not isinstance(parent_id, str) or not parent_id.strip()):
        errors.append(f"资料节点 JSONL 第 {line_number} 行缺少或包含无效 parentId")
        valid = False
    strings: dict[str, str] = {}
    for field_name in ("kind", "title", "summary"):
        candidate = value.get(field_name)
        if not isinstance(candidate, str) or not candidate.strip():
            errors.append(f"资料节点 JSONL 第 {line_number} 行缺少或包含无效 {field_name}")
            valid = False
            continue
        strings[field_name] = candidate.strip()
    path = value.get("path")
    if not isinstance(path, list) or not path or any(not isinstance(item, str) or not item.strip() for item in path):
        errors.append(f"资料节点 JSONL 第 {line_number} 行 path 必须是非空字符串数组")
        valid = False
    if not valid:
        return None
    return (
        node_id.strip(),
        source_id,
        parent_id.strip() if isinstance(parent_id, str) else None,
        strings["kind"],
        strings["title"],
        strings["summary"],
        json.dumps(tuple(item.strip() for item in path), ensure_ascii=False, separators=(",", ":")),
    )


def _node_records(connection: sqlite3.Connection, parent_ids: tuple[str, ...]) -> dict[str, dict[str, str | None]]:
    unique_ids = tuple(sorted(set(parent_ids)))
    if not unique_ids:
        return {}
    placeholders = ",".join("?" for _ in unique_ids)
    rows = connection.execute(
        f"SELECT id, source_id, parent_id, kind FROM nodes WHERE id IN ({placeholders})", unique_ids
    )
    return {row["id"]: dict(row) for row in rows}


def _validate_node_graph(
    connection: sqlite3.Connection, source_hashes: Mapping[str, str], errors: list[str]
) -> bool:
    rows = [dict(row) for row in connection.execute("SELECT id, source_id, parent_id, kind FROM nodes")]
    by_id = {row["id"]: row for row in rows}
    valid = True
    roots_by_source: dict[str, list[str]] = {source_id: [] for source_id in source_hashes}
    for row in rows:
        node_id = str(row["id"])
        source_id = str(row["source_id"])
        parent_id = row["parent_id"]
        if row["kind"] == "source":
            roots_by_source.setdefault(source_id, []).append(node_id)
            if parent_id is not None:
                errors.append(f"资料节点 {node_id} 为 source root，parentId 必须为 null")
                valid = False
            continue
        if parent_id is None:
            errors.append(f"资料节点 {node_id} 不是 source root，parentId 必须存在")
            valid = False
            continue
        parent = by_id.get(str(parent_id))
        if parent is None:
            errors.append(f"资料节点 {node_id} parentId 引用了不存在的 node ID：{parent_id}")
            valid = False
        elif parent["source_id"] != source_id:
            errors.append(f"资料节点 {node_id} parentId 与 node sourceId 不一致：{parent_id}")
            valid = False
    for source_id, roots in sorted(roots_by_source.items()):
        if len(roots) != 1:
            errors.append(f"资料节点 sourceId {source_id} 必须恰有一个 source root，实际 {len(roots)}")
            valid = False
    visited: set[str] = set()
    reported_cycles: set[tuple[str, ...]] = set()
    for node_id in sorted(by_id):
        if node_id in visited:
            continue
        chain: list[str] = []
        positions: dict[str, int] = {}
        current: str | None = node_id
        while current is not None and current in by_id and current not in visited:
            if current in positions:
                cycle = tuple(sorted(chain[positions[current] :]))
                if cycle not in reported_cycles:
                    errors.append(f"资料节点存在 parentId 环：{', '.join(cycle)}")
                    reported_cycles.add(cycle)
                valid = False
                break
            positions[current] = len(chain)
            chain.append(current)
            parent = by_id[current]["parent_id"]
            current = str(parent) if parent is not None else None
        visited.update(chain)
    return valid


def _verified_chunk_route(
    connection: sqlite3.Connection,
    source_id: str,
    parent_ids: tuple[str, ...],
    line_number: int,
    errors: list[str],
) -> str | None:
    parent_rows = _node_records(connection, parent_ids)
    missing_parents = sorted(set(parent_ids) - set(parent_rows))
    if missing_parents:
        errors.append(
            f"资料索引 JSONL 第 {line_number} 行 parentIds 引用了不存在的 node ID：{', '.join(missing_parents)}"
        )
        return None
    foreign_parents = sorted(
        parent_id for parent_id, parent in parent_rows.items() if parent["source_id"] != source_id
    )
    if foreign_parents:
        errors.append(
            f"资料索引 JSONL 第 {line_number} 行 parentIds 与 chunk sourceId 不一致：{', '.join(foreign_parents)}"
        )
        return None
    first = parent_rows[parent_ids[0]]
    if first["kind"] != "source" or first["parent_id"] is not None:
        errors.append(f"资料索引 JSONL 第 {line_number} 行 parentIds 必须从 source root 逐级连接")
        return None
    for previous_id, current_id in zip(parent_ids, parent_ids[1:]):
        if parent_rows[current_id]["parent_id"] != previous_id:
            errors.append(f"资料索引 JSONL 第 {line_number} 行 parentIds 必须从 source root 逐级连接")
            return None
    top_level = parent_ids[1] if len(parent_ids) > 1 else parent_ids[0]
    return f"{source_id}:{top_level}"


def _fts_terms(keywords: list[str], ngrams: list[str]) -> str:
    """One bounded chunk row retains every B2 keyword and n-gram for FTS recall."""
    return " ".join(value.strip().lower() for value in (*keywords, *ngrams) if value.strip())


def _load_workspace(workspace: Path, errors: list[str]) -> WorkspaceV2 | None:
    try:
        payload = json.loads(_read_regular_relative(workspace, ("workspace.json",), MAX_ASSET_BYTES).decode("utf-8"))
        return WorkspaceV2.from_dict(payload)
    except (BuildError, OSError, UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error:
        errors.append(f"V2 workspace 无法读取：{error}")
        return None


def _load_asset_paths(workspace: Path, errors: list[str]) -> AgentAssetPaths | None:
    manifest = _load_workspace(workspace, errors)
    return manifest.assets if manifest is not None else None


def _read_text_asset(workspace: Path, asset_path: str, name: str, errors: list[str]) -> str | None:
    try:
        return read_v2_asset_bytes(workspace, asset_path).decode("utf-8")
    except (OSError, UnicodeError, ValueError) as error:
        errors.append(f"{name} 无法读取：{asset_path}：{error}")
        return None


def _read_json_asset(workspace: Path, asset_path: str, name: str, errors: list[str]) -> dict[str, Any] | None:
    text = _read_text_asset(workspace, asset_path, name, errors)
    if text is None:
        return None
    try:
        value = json.loads(text)
    except (json.JSONDecodeError, RecursionError) as error:
        errors.append(f"{name} 无法读取：{asset_path}：{error}")
        return None
    if not isinstance(value, dict):
        errors.append(f"{name} 必须是 JSON 对象：{asset_path}")
        return None
    return value


def _read_jsonl_asset(workspace: Path, asset_path: str, name: str, errors: list[str]) -> list[dict[str, Any]] | None:
    try:
        payload = read_v2_asset_bytes(workspace, asset_path)
    except (OSError, ValueError) as error:
        errors.append(f"{name} 无法读取：{asset_path}：{error}")
        return None
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(payload.splitlines(), start=1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
            errors.append(f"{name} JSONL 第 {line_number} 行无法读取：{asset_path}：{error}")
            continue
        if not isinstance(value, dict):
            errors.append(f"{name} JSONL 第 {line_number} 行必须是 JSON 对象：{asset_path}")
            continue
        rows.append(value)
    return rows


def read_v2_asset_bytes(workspace: Path, asset_path: str) -> bytes:
    """Read a bounded agent asset via no-follow descriptors for builder and evaluator."""
    path = PurePosixPath(asset_path)
    if len(path.parts) < 2 or path.parts[0] != "agent" or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError("人物资产必须位于 agent 目录内")
    return _read_regular_relative(Path(workspace).expanduser().resolve(), tuple(path.parts), MAX_ASSET_BYTES)


def _read_regular_relative(workspace: Path, parts: tuple[str, ...], max_bytes: int) -> bytes:
    descriptor = _open_regular_relative(workspace, parts)
    try:
        before = os.fstat(descriptor)
        content = bytearray()
        with os.fdopen(os.dup(descriptor), "rb") as stream:
            while block := stream.read(64 * 1024):
                content.extend(block)
                if len(content) > max_bytes:
                    raise ValueError(f"文件超过 {max_bytes} 字节上限：{'/'.join(parts)}")
        after = os.fstat(descriptor)
        if not _same_file_identity(before, after):
            raise OSError(f"文件在读取期间发生变化：{'/'.join(parts)}")
        return bytes(content)
    finally:
        os.close(descriptor)


def _open_regular_relative(workspace: Path, parts: tuple[str, ...]) -> int:
    if not parts or any("/" in part or "\\" in part or part in {"", ".", ".."} for part in parts):
        raise ValueError("不安全的工作区相对路径")
    directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(workspace, directory_flags)
    try:
        if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
            raise OSError("工作区必须是目录")
        for part in parts[:-1]:
            if stat.S_ISLNK(os.stat(part, dir_fd=descriptor, follow_symlinks=False).st_mode):
                raise OSError(f"不安全的符号链接：{'/'.join(parts)}")
            child = os.open(part, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError(f"路径必须是目录：{part}")
        if stat.S_ISLNK(os.stat(parts[-1], dir_fd=descriptor, follow_symlinks=False).st_mode):
            raise OSError(f"不安全的符号链接：{'/'.join(parts)}")
        leaf = os.open(parts[-1], os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0), dir_fd=descriptor)
        if not stat.S_ISREG(os.fstat(leaf).st_mode):
            os.close(leaf)
            raise OSError(f"路径必须是普通文件：{'/'.join(parts)}")
        return leaf
    except OSError as error:
        if error.errno == errno.ELOOP:
            raise OSError(f"不安全的符号链接：{'/'.join(parts)}") from error
        raise
    finally:
        os.close(descriptor)


def _validate_identity(
    identity: dict[str, Any] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    if identity is None:
        return
    _validate_string_list(identity.get("selfNames"), "identity.selfNames", errors)
    _validate_non_empty_string(identity.get("timeHorizon"), "identity.timeHorizon", errors)
    _validate_string_list(identity.get("roles"), "identity.roles", errors)
    relationships = identity.get("relationships")
    if not isinstance(relationships, list):
        errors.append("identity.relationships 必须是数组")
        return
    for index, relation in enumerate(relationships, start=1):
        label = f"identity.relationships[{index}]"
        if not isinstance(relation, dict):
            errors.append(f"{label} 必须是对象")
            continue
        _validate_non_empty_string(relation.get("subject"), f"{label}.subject", errors)
        _validate_non_empty_string(relation.get("relation"), f"{label}.relation", errors)
        evidence = _validate_evidence(relation.get("evidence"), label, chunks, errors)
        _validate_period_compatibility(relation.get("period"), evidence, label, chunks, errors)


def _validate_voice(
    voice: dict[str, Any] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    if voice is None:
        return
    _validate_non_empty_string(voice.get("defaultForm"), "voice.defaultForm", errors)
    for field_name in ("sentenceRhythm", "rhetoricalMoves", "preferredTerms", "avoidPatterns"):
        _validate_string_list(voice.get(field_name), f"voice.{field_name}", errors)
    evidence = _validate_evidence(voice.get("evidence"), "voice", chunks, errors)
    for chunk_id in evidence:
        chunk = _chunk(chunks, chunk_id)
        if chunk and chunk.get("authorship") not in _DIRECT_AUTHORSHIPS:
            errors.append("voice 只能引用 direct 或 edited_direct")


def _validate_worldview(
    rows: list[dict[str, Any]] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    for index, row in _records(rows, "worldview", errors):
        label = f"worldview[{index}]"
        _validate_non_empty_string(row.get("topic"), f"{label}.topic", errors)
        _validate_non_empty_string(row.get("statement"), f"{label}.statement", errors)
        _validate_string_list(row.get("conditions"), f"{label}.conditions", errors)
        _validate_string_list(row.get("aliases"), f"{label}.aliases", errors)
        _validate_confidence(row.get("confidence"), f"{label}.confidence", errors)
        evidence = _validate_evidence(row.get("evidence"), label, chunks, errors)
        _validate_period_compatibility(row.get("period"), evidence, label, chunks, errors)


def _validate_episodes(
    rows: list[dict[str, Any]] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    for index, row in _records(rows, "episodes", errors):
        label = f"episodes[{index}]"
        _validate_non_empty_string(row.get("location"), f"{label}.location", errors)
        _validate_string_list(row.get("participants"), f"{label}.participants", errors)
        _validate_non_empty_string(row.get("summary"), f"{label}.summary", errors)
        _validate_non_empty_string(row.get("meaning"), f"{label}.meaning", errors)
        evidence = _validate_evidence(row.get("evidence"), label, chunks, errors)
        _validate_period_compatibility(row.get("period"), evidence, label, chunks, errors)
        if evidence and not any(
            (chunk := _chunk(chunks, chunk_id)) and chunk.get("authorship") in _DIRECT_AUTHORSHIPS
            for chunk_id in evidence
        ):
            errors.append("episode 至少需要一条 direct 或 edited_direct 证据")


def _validate_concepts(
    concepts: dict[str, Any] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    if concepts is None:
        return
    values = concepts.get("concepts")
    if not isinstance(values, list):
        errors.append("concepts.concepts 必须是数组")
        return
    ids: set[str] = set()
    for index, row in enumerate(values, start=1):
        label = f"concepts[{index}]"
        if not isinstance(row, dict):
            errors.append(f"{label} 必须是对象")
            continue
        _validate_semantic_id(row.get("id"), label, "concepts", ids, errors)
        _validate_non_empty_string(row.get("name"), f"{label}.name", errors)
        _validate_string_list(row.get("aliases"), f"{label}.aliases", errors)
        _validate_string_list(row.get("keywords"), f"{label}.keywords", errors)
        if "evidence" in row:
            _validate_evidence(row.get("evidence"), label, chunks, errors)


def _validate_examples(
    rows: list[dict[str, Any]] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    for index, row in _records(rows, "examples", errors):
        label = f"examples[{index}]"
        _validate_non_empty_string(row.get("intent"), f"{label}.intent", errors)
        _validate_non_empty_string(row.get("user"), f"{label}.user", errors)
        _validate_non_empty_string(row.get("assistant"), f"{label}.assistant", errors)
        _validate_string_list(row.get("styleTags"), f"{label}.styleTags", errors)
        _validate_evidence(row.get("evidence"), label, chunks, errors)
        if row.get("generationType") != "synthesized":
            errors.append("examples.generationType 必须是 synthesized")


def _validate_openers(openers: dict[str, Any] | None, errors: list[str]) -> None:
    if openers is None:
        return
    if not isinstance(openers.get("default"), str):
        errors.append("openers.default 必须是字符串")
    alternatives = _validate_string_list(openers.get("alternatives"), "openers.alternatives", errors)
    if len(alternatives) > 2:
        errors.append("openers.alternatives 最多只能包含 2 条")


def _validate_evaluations(
    rows: list[dict[str, Any]] | None,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    for index, row in _records(rows, "eval", errors):
        label = f"eval[{index}]"
        category = row.get("category")
        if category not in EVALUATION_CATEGORIES:
            errors.append(f"{label}.category 无效：{category}")
        _validate_non_empty_string(row.get("question"), f"{label}.question", errors)
        evidence = _validate_evidence(row.get("expectedEvidence"), label, chunks, errors, "expectedEvidence")
        if category in {"stance", "temporal"}:
            _validate_period_compatibility(row.get("period"), evidence, label, chunks, errors)
        if category == "voice":
            _validate_direct_evidence(evidence, f"{label}.voice expectedEvidence", chunks, errors)
        if "corpusId" in row and (not isinstance(row["corpusId"], str) or not row["corpusId"].strip()):
            errors.append(f"{label}.corpusId 必须是非空字符串，或省略为 unassigned")
        if category in {"diversity", "global"}:
            _validate_structural_eval_category(category, evidence, label, chunks, errors)


def _records(
    rows: list[dict[str, Any]] | None, asset: str, errors: list[str]
) -> tuple[tuple[int, dict[str, Any]], ...]:
    if rows is None:
        return ()
    seen: set[str] = set()
    values: list[tuple[int, dict[str, Any]]] = []
    for index, row in enumerate(rows, start=1):
        _validate_semantic_id(row.get("id"), f"{asset}[{index}]", asset, seen, errors)
        values.append((index, row))
    return tuple(values)


def _validate_semantic_id(
    value: object, label: str, asset: str, seen: set[str], errors: list[str]
) -> None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{label}.id 必须是非空字符串")
    elif value in seen:
        errors.append(f"{asset} 存在重复语义 ID：{value}")
    else:
        seen.add(value)


def _validate_non_empty_string(value: object, label: str, errors: list[str]) -> str | None:
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{label} 必须是非空字符串")
        return None
    return value.strip()


def _validate_string_list(value: object, label: str, errors: list[str]) -> tuple[str, ...]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{label} 必须是字符串数组")
        return ()
    return tuple(value)


def _validate_confidence(value: object, label: str, errors: list[str]) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= value <= 1:
        errors.append(f"{label} 必须是 0 到 1 之间的数值")


def _validate_evidence(
    value: object,
    label: str,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
    field_name: str = "evidence",
) -> tuple[str, ...]:
    if not isinstance(value, list) or not value:
        errors.append(f"{label}.{field_name} 必须是非空 chunk ID 数组")
        return ()
    ids: list[str] = []
    for chunk_id in value:
        if not isinstance(chunk_id, str) or not chunk_id.strip():
            errors.append(f"{label}.{field_name} 必须只包含非空字符串")
            continue
        ids.append(chunk_id)
    if len(ids) != len(set(ids)):
        errors.append(f"{label}.{field_name} 不能包含重复 chunk ID")
    for chunk_id in sorted(set(ids)):
        if _chunk(chunks, chunk_id) is None:
            errors.append(f"{label}.{field_name} 引用了不存在的 chunk ID：{chunk_id}")
    return tuple(ids)


def _validate_period_compatibility(
    period: object,
    evidence: object,
    label: str,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    if not isinstance(period, str) or not period.strip() or period == "unknown":
        errors.append(f"{label}.period 必须是已声明的非空字符串")
        return
    if not isinstance(evidence, (list, tuple)):
        return
    known = [_chunk(chunks, item) for item in evidence if isinstance(item, str)]
    known = [item for item in known if item is not None]
    if known and any(item.get("period") != period for item in known):
        errors.append(f"{label}.period 与 evidence 时期不兼容")


def _validate_direct_evidence(
    evidence: tuple[str, ...],
    label: str,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    if any(
        (chunk := _chunk(chunks, chunk_id)) is not None and chunk.get("authorship") not in _DIRECT_AUTHORSHIPS
        for chunk_id in evidence
    ):
        errors.append(f"{label} 只能引用 direct 或 edited_direct")


def _validate_structural_eval_category(
    category: object,
    evidence: tuple[str, ...],
    label: str,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
) -> None:
    metadata = [_chunk(chunks, chunk_id) for chunk_id in sorted(set(evidence))]
    metadata = [chunk for chunk in metadata if chunk is not None]
    if len(metadata) < 2:
        errors.append(f"{category} 至少需要 2 条可归因 expectedEvidence：{label}")
        return
    if category == "diversity":
        if len({chunk["source_id"] for chunk in metadata}) < 2:
            errors.append(f"diversity expectedEvidence 必须来自至少 2 个 sourceId：{label}")
        if len({chunk["duplicate_group"] for chunk in metadata}) < 2:
            errors.append(f"diversity expectedEvidence 必须来自至少 2 个 duplicateGroup：{label}")
    elif category == "global":
        if len({chunk["source_id"] for chunk in metadata}) < 2:
            errors.append(f"global expectedEvidence 必须来自至少 2 个 sourceId：{label}")
        if len({chunk["route"] for chunk in metadata}) < 2:
            errors.append(f"global expectedEvidence 必须来自至少 2 条 source/top-level route：{label}")


def _read_eval_rows(workspace: Path, assets: AgentAssetPaths, errors: list[str]) -> tuple[_EvalRow, ...]:
    raw_rows = _read_jsonl_asset(workspace, assets.eval, "eval", errors)
    if raw_rows is None:
        return ()
    rows: list[_EvalRow] = []
    for row in raw_rows:
        category = row.get("category")
        question = row.get("question")
        period = row.get("period")
        evidence = row.get("expectedEvidence")
        corpus_id = row.get("corpusId", "unassigned")
        if (
            category not in EVALUATION_CATEGORIES
            or not isinstance(question, str)
            or not question.strip()
            or not isinstance(period, str)
            or not period.strip()
            or not isinstance(evidence, list)
            or not evidence
            or any(not isinstance(item, str) or not item.strip() for item in evidence)
            or ("corpusId" in row and (not isinstance(corpus_id, str) or not corpus_id.strip()))
        ):
            continue
        rows.append(
            _EvalRow(
                category=category,
                corpus_id=corpus_id.strip() if isinstance(corpus_id, str) and corpus_id.strip() else "unassigned",
                expected_evidence=tuple(evidence),
                period=period,
                question=question,
            )
        )
    return tuple(rows)


def _voice_evidence(workspace: Path, assets: AgentAssetPaths) -> tuple[str, ...]:
    values: list[str] = []
    errors: list[str] = []
    voice = _read_json_asset(workspace, assets.voice, "voice", errors)
    if isinstance(voice, dict) and isinstance(voice.get("evidence"), list):
        values.extend(item for item in voice["evidence"] if isinstance(item, str) and item.strip())
    return tuple(values)


def _asset_coverage(
    workspace: Path, assets: AgentAssetPaths, chunks: _ChunkLookup | Mapping[str, Mapping[str, str]]
) -> tuple[dict[str, object], dict[str, object]]:
    errors: list[str] = []
    identity = _read_json_asset(workspace, assets.identity, "identity", errors) or {}
    voice = _read_json_asset(workspace, assets.voice, "voice", errors) or {}
    worldview = _read_jsonl_asset(workspace, assets.worldview, "worldview", errors) or []
    episodes = _read_jsonl_asset(workspace, assets.episodes, "episodes", errors) or []
    concepts = _read_json_asset(workspace, assets.concepts, "concepts", errors) or {}
    examples = _read_jsonl_asset(workspace, assets.examples, "examples", errors) or []
    relationships = identity.get("relationships") if isinstance(identity.get("relationships"), list) else []
    concept_rows = concepts.get("concepts") if isinstance(concepts.get("concepts"), list) else []
    factual_rows = [
        *[row for row in relationships if isinstance(row, dict)],
        *([voice] if isinstance(voice, dict) else []),
        *worldview,
        *episodes,
        *[row for row in concept_rows if isinstance(row, dict)],
        *examples,
    ]
    stance_rows = list(worldview)
    return _coverage_from_rows(factual_rows, chunks), _coverage_from_rows(stance_rows, chunks)


def _coverage_from_rows(
    rows: list[dict[str, Any]], chunks: _ChunkLookup | Mapping[str, Mapping[str, str]]
) -> dict[str, object]:
    evidence_lists = [
        row.get("expectedEvidence", row.get("evidence", []))
        for row in rows
        if isinstance(row.get("expectedEvidence", row.get("evidence", [])), list)
    ]
    all_evidence = [
        item for values in evidence_lists for item in values if isinstance(item, str) and item.strip()
    ]
    covered_evidence = [item for item in all_evidence if _chunk(chunks, item) is not None]
    item_covered = sum(
        1
        for values in evidence_lists
        if any(isinstance(item, str) and _chunk(chunks, item) is not None for item in values)
    )
    periods = sorted({row["period"] for row in rows if isinstance(row.get("period"), str) and row["period"].strip()})
    covered_periods = sum(
        1
        for period in periods
        if any(
            isinstance(item, str)
            and (chunk := _chunk(chunks, item)) is not None
            and chunk.get("period") == period
            for values in evidence_lists
            for item in values
        )
    )
    return {
        "assetItems": _coverage_metric(len(rows), item_covered),
        "evidence": _coverage_metric(len(all_evidence), len(covered_evidence)),
        "periods": _coverage_metric(len(periods), covered_periods),
    }


def _coverage_metric(total: int, covered: int) -> dict[str, object]:
    return {"total": total, "covered": covered, "rate": round(covered / total, 6) if total else 0.0}


def _evaluate_rows(
    rows: tuple[_EvalRow, ...], lookup: _SqliteChunks
) -> tuple[dict[str, CategoryMetric], dict[str, CategoryMetric], dict[str, CategoryMetric], dict[str, CategoryMetric]]:
    results: list[tuple[_EvalRow, bool, str]] = []
    for row in rows:
        expected = {chunk_id: lookup.get(chunk_id) for chunk_id in row.expected_evidence}
        expected = {chunk_id: chunk for chunk_id, chunk in expected.items() if chunk is not None}
        retrieved_expected = {
            chunk_id: expected[chunk_id]
            for chunk_id in lookup.retrieve(row.question)
            if chunk_id in expected
        }
        passed = _category_passes(row, retrieved_expected)
        authorship = "+".join(sorted({chunk["authorship"] for chunk in expected.values()})) or "unassigned"
        results.append((row, passed, authorship))
    category = _metrics_by(((row.category, passed) for row, passed, _ in results), include=EVALUATION_CATEGORIES)
    periods = _metrics_by((row.period, passed) for row, passed, _ in results)
    authorships = _metrics_by((authorship, passed) for _, passed, authorship in results)
    corpora = _metrics_by((row.corpus_id, passed) for row, passed, _ in results)
    return category, periods, authorships, corpora


def _category_passes(row: _EvalRow, retrieved: Mapping[str, Mapping[str, str]]) -> bool:
    if not retrieved:
        return False
    values = tuple(retrieved.values())
    if row.category == "grounding":
        return True
    if row.category == "stance":
        return all(chunk["period"] == row.period for chunk in values)
    if row.category == "voice":
        return all(chunk["authorship"] in _DIRECT_AUTHORSHIPS for chunk in values)
    if row.category == "temporal":
        return all(chunk["period"] == row.period for chunk in values)
    if row.category == "diversity":
        return (
            len(values) >= 2
            and len({chunk["source_id"] for chunk in values}) >= 2
            and len({chunk["duplicate_group"] for chunk in values}) >= 2
        )
    if row.category == "global":
        return (
            len(values) >= 2
            and len({chunk["source_id"] for chunk in values}) >= 2
            and len({chunk["route"] for chunk in values}) >= 2
        )
    return False


def _metrics_by(rows: Any, *, include: tuple[str, ...] = ()) -> dict[str, CategoryMetric]:
    totals: Counter[str] = Counter()
    passes: Counter[str] = Counter()
    for key, passed in rows:
        totals[key] += 1
        if passed:
            passes[key] += 1
    for key in include:
        totals.setdefault(key, 0)
        passes.setdefault(key, 0)
    return {
        key: CategoryMetric(total, passes[key], round(passes[key] / total, 6) if total else 0.0)
        for key, total in sorted(totals.items())
    }


def _append_release_errors(category: Mapping[str, CategoryMetric], errors: list[str]) -> None:
    for name, minimum in MINIMUM_EVAL_COUNTS.items():
        metric = category.get(name, CategoryMetric(0, 0, 0.0))
        if metric.total < minimum:
            errors.append(f"{name} 评估题不足：需要 {minimum}，实际 {metric.total}")
    grounding = category.get("grounding", CategoryMetric(0, 0, 0.0))
    if grounding.rate < MIN_GROUNDING_RATE:
        errors.append(f"grounding 通过率不足：{grounding.rate:.1%}，要求至少 {MIN_GROUNDING_RATE:.1%}")
    stance = category.get("stance", CategoryMetric(0, 0, 0.0))
    if stance.rate < MIN_STANCE_RATE:
        errors.append(f"stance 通过率不足：{stance.rate:.1%}，要求 {MIN_STANCE_RATE:.1%}")


def _query_terms(text: str) -> tuple[str, ...]:
    normalized = text.lower()
    terms: list[str] = []
    for run in _cjk_runs(normalized):
        terms.extend(run[index : index + 2] for index in range(max(1, len(run) - 1)))
    token = ""
    for character in normalized:
        if character.isascii() and character.isalnum():
            token += character
        else:
            if len(token) >= 2:
                terms.append(token)
            token = ""
    if len(token) >= 2:
        terms.append(token)
    return tuple(sorted(set(terms))[:_MAX_RETRIEVAL_TERMS])


def _quote_fts_term(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _cjk_runs(text: str) -> tuple[str, ...]:
    runs: list[str] = []
    current: list[str] = []
    for character in text:
        if "\u3400" <= character <= "\u9fff":
            current.append(character)
        elif current:
            runs.append("".join(current))
            current = []
    if current:
        runs.append("".join(current))
    return tuple(runs)


def _chunk(chunks: _ChunkLookup | Mapping[str, Mapping[str, str]], chunk_id: str) -> Mapping[str, str] | None:
    return chunks.get(chunk_id)


def _same_file_identity(left: os.stat_result, right: os.stat_result) -> bool:
    return (left.st_dev, left.st_ino, left.st_size, left.st_mtime_ns, left.st_ctime_ns) == (
        right.st_dev,
        right.st_ino,
        right.st_size,
        right.st_mtime_ns,
        right.st_ctime_ns,
    )


def _new_metrics() -> dict[str, CategoryMetric]:
    return {name: CategoryMetric(0, 0, 0.0) for name in EVALUATION_CATEGORIES}


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))
