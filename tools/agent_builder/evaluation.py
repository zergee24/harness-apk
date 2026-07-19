"""Streaming V2 persona-asset validation and deterministic offline evaluation."""

from __future__ import annotations

import json
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
MAX_INDEX_TERMS_PER_CHUNK = 128
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

        categories = encode(self.category_metrics)
        return {
            "evaluation": {
                "byAuthorship": encode(self.by_authorship),
                "byCorpus": encode(self.by_corpus),
                "byPeriod": encode(self.by_period),
                "categories": categories,
                "minimumGroundingRate": self.minimum_grounding_rate,
                "minimumStanceRate": self.minimum_stance_rate,
            },
            "factualCoverage": categories.get("grounding", _empty_metric()),
            "stanceCoverage": categories.get("stance", _empty_metric()),
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
            "SELECT id, authorship, period, genre, corpus_id FROM chunks WHERE id = ?", (chunk_id,)
        ).fetchone()
        return dict(row) if row else None

    def retrieve(self, question: str) -> tuple[str, ...]:
        terms = _query_terms(question)
        if not terms:
            return ()
        placeholders = ",".join("?" for _ in terms)
        rows = self._connection.execute(
            f"""
            SELECT chunk_id, COUNT(*) AS score
            FROM chunk_terms
            WHERE term IN ({placeholders})
            GROUP BY chunk_id
            ORDER BY score DESC, chunk_id ASC
            LIMIT ?
            """,
            (*terms, _TOP_K),
        )
        return tuple(row["chunk_id"] for row in rows)

    def coverage_summaries(self, voice_evidence: tuple[str, ...]) -> tuple[dict[str, object], dict[str, object]]:
        total = int(self._connection.execute("SELECT COUNT(*) FROM chunks").fetchone()[0])
        direct_dialogue = int(
            self._connection.execute(
                """
                SELECT COUNT(*) FROM chunks
                WHERE authorship IN ('direct', 'edited_direct')
                  AND genre IN ('speech', 'conversation', 'letter', 'interview')
                """
            ).fetchone()[0]
        )
        evidence = tuple(sorted(set(voice_evidence)))
        if evidence:
            placeholders = ",".join("?" for _ in evidence)
            row = self._connection.execute(
                f"""
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN authorship IN ('direct', 'edited_direct') THEN 1 ELSE 0 END) AS direct_total,
                    SUM(CASE WHEN authorship IN ('direct', 'edited_direct')
                                   AND genre IN ('speech', 'conversation', 'letter', 'interview')
                             THEN 1 ELSE 0 END) AS direct_dialogue_total
                FROM chunks WHERE id IN ({placeholders})
                """,
                evidence,
            ).fetchone()
            voice_total = int(row["total"] or 0)
            direct_voice = int(row["direct_total"] or 0)
            direct_dialogue_voice = int(row["direct_dialogue_total"] or 0)
        else:
            voice_total = direct_voice = direct_dialogue_voice = 0
        dialogue = {
            "directDialogueChunks": direct_dialogue,
            "rate": round(direct_dialogue / total, 6) if total else 0.0,
            "totalChunks": total,
        }
        availability = {
            "available": direct_voice > 0,
            "directDialogueEvidenceChunks": direct_dialogue_voice,
            "directEvidenceChunks": direct_voice,
            "referencedVoiceChunks": voice_total,
        }
        return dialogue, availability


@dataclass(frozen=True)
class _EvalRow:
    category: str
    corpus_id: str
    expected_evidence: tuple[str, ...]
    period: str
    question: str


def validate_agent_assets(workspace: Path, chunks_by_id: _ChunkLookup | Mapping[str, Mapping[str, str]]) -> list[str]:
    """Validate semantic V2 assets against an existing chunk metadata lookup."""
    workspace = Path(workspace).expanduser().resolve()
    errors: list[str] = []
    assets = _load_asset_paths(workspace, errors)
    if assets is None:
        return errors
    identity = _read_json_asset(workspace, assets.identity, "identity", errors)
    voice = _read_json_asset(workspace, assets.voice, "voice", errors)
    worldview = _read_jsonl_asset(workspace, assets.worldview, "worldview", errors)
    episodes = _read_jsonl_asset(workspace, assets.episodes, "episodes", errors)
    examples = _read_jsonl_asset(workspace, assets.examples, "examples", errors)
    evaluations = _read_jsonl_asset(workspace, assets.eval, "eval", errors)

    if isinstance(identity, dict):
        relationships = identity.get("relationships", [])
        if not isinstance(relationships, list):
            errors.append("identity.relationships 必须是数组")
        else:
            for index, relation in enumerate(relationships, start=1):
                label = f"identity.relationships[{index}]"
                if not isinstance(relation, dict):
                    errors.append(f"{label} 必须是对象")
                    continue
                evidence = _validate_evidence(relation.get("evidence"), label, chunks_by_id, errors)
                _validate_period_compatibility(relation.get("period"), evidence, label, chunks_by_id, errors)

    if isinstance(voice, dict):
        evidence = _validate_evidence(voice.get("evidence"), "voice", chunks_by_id, errors)
        for chunk_id in evidence:
            chunk = _chunk(chunks_by_id, chunk_id)
            if chunk and chunk.get("authorship") not in _DIRECT_AUTHORSHIPS:
                errors.append("voice 只能引用 direct 或 edited_direct")

    _validate_records(worldview, "worldview", chunks_by_id, errors, required_text="statement", require_period=True)
    _validate_records(
        episodes,
        "episodes",
        chunks_by_id,
        errors,
        required_text="summary",
        require_period=True,
        require_direct_evidence=True,
    )
    _validate_records(
        examples,
        "examples",
        chunks_by_id,
        errors,
        required_text="assistant",
        require_generation_type=True,
    )
    _validate_records(
        evaluations,
        "eval",
        chunks_by_id,
        errors,
        required_text="question",
        evidence_field="expectedEvidence",
        require_period=True,
        validate_category=True,
        validate_corpus_id=True,
    )
    return errors


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
                _stream_chunk_index(workspace, connection, errors)
                lookup = _SqliteChunks(connection)
                errors.extend(validate_agent_assets(workspace, lookup))
                assets = _load_asset_paths(workspace, errors)
                rows = _read_eval_rows(workspace, assets, errors) if assets else ()
                category, periods, authorships, corpora = _evaluate_rows(rows, lookup)
                _append_release_errors(category, errors)
                voice_evidence = _voice_evidence(workspace, assets, errors) if assets else ()
                dialogue, availability = lookup.coverage_summaries(voice_evidence)
                return EvaluationReport(
                    category,
                    periods,
                    authorships,
                    corpora,
                    tuple(_unique(errors)),
                    dialogue,
                    availability,
                )
            finally:
                if connection is not None:
                    connection.close()
    except (sqlite3.Error, OSError, UnicodeError, RecursionError, ValueError) as error:
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
    connection.executescript(
        """
        CREATE TABLE chunks (
            id TEXT PRIMARY KEY,
            authorship TEXT NOT NULL,
            period TEXT NOT NULL,
            genre TEXT NOT NULL,
            corpus_id TEXT NOT NULL
        );
        CREATE TABLE chunk_terms (
            term TEXT NOT NULL,
            chunk_id TEXT NOT NULL,
            PRIMARY KEY (term, chunk_id)
        ) WITHOUT ROWID;
        CREATE INDEX chunk_terms_term ON chunk_terms(term, chunk_id);
        """
    )


def _stream_chunk_index(workspace: Path, connection: sqlite3.Connection, errors: list[str]) -> None:
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
            chunk_id, authorship, period, genre, corpus_id, terms = row
            try:
                connection.execute(
                    "INSERT INTO chunks(id, authorship, period, genre, corpus_id) VALUES (?, ?, ?, ?, ?)",
                    (chunk_id, authorship, period, genre, corpus_id),
                )
            except sqlite3.IntegrityError:
                errors.append(f"资料索引 JSONL 第 {line_number} 行存在重复 chunk ID：{chunk_id}")
                continue
            connection.executemany(
                "INSERT OR IGNORE INTO chunk_terms(term, chunk_id) VALUES (?, ?)",
                ((term, chunk_id) for term in terms),
            )
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
) -> tuple[str, str, str, str, str, tuple[str, ...]] | None:
    if not isinstance(value, dict):
        errors.append(f"资料索引 JSONL 第 {line_number} 行必须是对象")
        return None
    required_strings = ("id", "authorship", "period", "genre", "sourceId", "sourceHash", "text")
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
    field_values: list[list[str]] = []
    for field_name in ("keywords", "ngrams"):
        candidate = value.get(field_name)
        if not isinstance(candidate, list) or any(not isinstance(item, str) for item in candidate):
            errors.append(f"资料索引 JSONL 第 {line_number} 行 {field_name} 必须是字符串数组")
            return None
        field_values.append(candidate)
    terms = _bounded_index_terms(field_values[0], field_values[1])
    return values["id"], values["authorship"], values["period"], values["genre"], "unassigned", terms


def _bounded_index_terms(keywords: list[str], ngrams: list[str]) -> tuple[str, ...]:
    """Keep the disk index bounded: ranked keywords precede n-gram recall terms."""
    terms: list[str] = []
    for value in (*keywords, *ngrams):
        normalized = value.strip().lower()
        if normalized and normalized not in terms:
            terms.append(normalized)
            if len(terms) == MAX_INDEX_TERMS_PER_CHUNK:
                break
    return tuple(terms)


def _load_asset_paths(workspace: Path, errors: list[str]) -> AgentAssetPaths | None:
    try:
        payload = json.loads(_read_regular_relative(workspace, ("workspace.json",), MAX_ASSET_BYTES).decode("utf-8"))
        return WorkspaceV2.from_dict(payload).assets
    except (BuildError, OSError, UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error:
        errors.append(f"V2 workspace 资产路径无法读取：{error}")
        return None


def _read_json_asset(workspace: Path, asset_path: str, name: str, errors: list[str]) -> dict[str, Any] | None:
    try:
        value = json.loads(_read_asset_bytes(workspace, asset_path).decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error:
        errors.append(f"{name} 无法读取：{error}")
        return None
    if not isinstance(value, dict):
        errors.append(f"{name} 必须是 JSON 对象")
        return None
    return value


def _read_jsonl_asset(workspace: Path, asset_path: str, name: str, errors: list[str]) -> list[dict[str, Any]] | None:
    try:
        payload = _read_asset_bytes(workspace, asset_path)
    except (OSError, ValueError) as error:
        errors.append(f"{name} 无法读取：{error}")
        return None
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(payload.splitlines(), start=1):
        if not raw_line.strip():
            continue
        try:
            value = json.loads(raw_line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
            errors.append(f"{name} JSONL 第 {line_number} 行无法读取：{error}")
            continue
        if not isinstance(value, dict):
            errors.append(f"{name} JSONL 第 {line_number} 行必须是对象")
            continue
        rows.append(value)
    return rows


def _read_asset_bytes(workspace: Path, asset_path: str) -> bytes:
    path = PurePosixPath(asset_path)
    if len(path.parts) < 2 or path.parts[0] != "agent" or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError("人物资产必须位于 agent 目录内")
    return _read_regular_relative(workspace, tuple(path.parts), MAX_ASSET_BYTES)


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
            child = os.open(part, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
            if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError(f"路径必须是目录：{part}")
        leaf = os.open(parts[-1], os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0), dir_fd=descriptor)
        if not stat.S_ISREG(os.fstat(leaf).st_mode):
            os.close(leaf)
            raise OSError(f"路径必须是普通文件：{'/'.join(parts)}")
        return leaf
    finally:
        os.close(descriptor)


def _validate_records(
    rows: list[dict[str, Any]] | None,
    asset: str,
    chunks: _ChunkLookup | Mapping[str, Mapping[str, str]],
    errors: list[str],
    *,
    required_text: str,
    evidence_field: str = "evidence",
    require_period: bool = False,
    require_direct_evidence: bool = False,
    require_generation_type: bool = False,
    validate_category: bool = False,
    validate_corpus_id: bool = False,
) -> None:
    if rows is None:
        return
    ids: set[str] = set()
    for index, row in enumerate(rows, start=1):
        label = f"{asset}[{index}]"
        record_id = row.get("id")
        if not isinstance(record_id, str) or not record_id.strip():
            errors.append(f"{label}.id 必须是非空字符串")
        elif record_id in ids:
            errors.append(f"{asset} 存在重复语义 ID：{record_id}")
        else:
            ids.add(record_id)
        value = row.get(required_text)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{label}.{required_text} 必须是非空字符串")
        evidence = _validate_evidence(row.get(evidence_field), label, chunks, errors, evidence_field)
        if require_period:
            _validate_period_compatibility(row.get("period"), evidence, label, chunks, errors)
        if require_direct_evidence and evidence and not any(
            (chunk := _chunk(chunks, chunk_id)) and chunk.get("authorship") in _DIRECT_AUTHORSHIPS
            for chunk_id in evidence
        ):
            errors.append("episode 至少需要一条 direct 或 edited_direct 证据")
        if require_generation_type and row.get("generationType") != "synthesized":
            errors.append("examples.generationType 必须是 synthesized")
        if validate_category and row.get("category") not in EVALUATION_CATEGORIES:
            errors.append(f"{label}.category 无效：{row.get('category')}")
        if validate_corpus_id and "corpusId" in row and (
            not isinstance(row["corpusId"], str) or not row["corpusId"].strip()
        ):
            errors.append(f"{label}.corpusId 必须是非空字符串，或省略为 unassigned")


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
    if known and all(item.get("period") != period for item in known):
        errors.append(f"{label}.period 与 evidence 时期不兼容")


def _read_eval_rows(workspace: Path, assets: AgentAssetPaths, errors: list[str]) -> tuple[_EvalRow, ...]:
    raw_rows = _read_jsonl_asset(workspace, assets.eval, "eval", errors)
    if raw_rows is None:
        return ()
    rows: list[_EvalRow] = []
    for index, row in enumerate(raw_rows, start=1):
        category = row.get("category")
        question = row.get("question")
        period = row.get("period")
        evidence = row.get("expectedEvidence")
        corpus_id = row.get("corpusId", "unassigned")
        label = f"eval[{index}]"
        valid = True
        if category not in EVALUATION_CATEGORIES:
            valid = False
        if not isinstance(question, str) or not question.strip():
            valid = False
        if not isinstance(period, str) or not period.strip():
            valid = False
        if not isinstance(evidence, list) or not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
            valid = False
        if "corpusId" in row and (not isinstance(corpus_id, str) or not corpus_id.strip()):
            errors.append(f"{label}.corpusId 必须是非空字符串，或省略为 unassigned")
            valid = False
        if not valid:
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


def _voice_evidence(workspace: Path, assets: AgentAssetPaths, errors: list[str]) -> tuple[str, ...]:
    voice = _read_json_asset(workspace, assets.voice, "voice", errors)
    if not isinstance(voice, dict) or not isinstance(voice.get("evidence"), list):
        return ()
    return tuple(item for item in voice["evidence"] if isinstance(item, str) and item.strip())


def _evaluate_rows(
    rows: tuple[_EvalRow, ...], lookup: _SqliteChunks
) -> tuple[dict[str, CategoryMetric], dict[str, CategoryMetric], dict[str, CategoryMetric], dict[str, CategoryMetric]]:
    results: list[tuple[_EvalRow, bool, str]] = []
    for row in rows:
        expected = [lookup.get(chunk_id) for chunk_id in row.expected_evidence]
        expected = [chunk for chunk in expected if chunk is not None]
        found = set(lookup.retrieve(row.question))
        passed = bool(found.intersection(row.expected_evidence))
        if row.category == "stance" and expected and all(chunk["period"] != row.period for chunk in expected):
            passed = False
        authorship = "+".join(sorted({chunk["authorship"] for chunk in expected})) or "unassigned"
        results.append((row, passed, authorship))
    category = _metrics_by(((row.category, passed) for row, passed, _ in results), include=EVALUATION_CATEGORIES)
    periods = _metrics_by((row.period, passed) for row, passed, _ in results)
    authorships = _metrics_by((authorship, passed) for _, passed, authorship in results)
    corpora = _metrics_by((row.corpus_id, passed) for row, passed, _ in results)
    return category, periods, authorships, corpora


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


def _empty_metric() -> dict[str, object]:
    return {"total": 0, "passed": 0, "rate": 0.0}


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))
