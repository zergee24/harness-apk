"""Deterministic four-channel retrieval and recall evaluation."""

from __future__ import annotations

import json
import re
import sqlite3
import struct
from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from .models import BuildError
from .normalization import (
    chinese_ngrams,
    normalize_for_search,
    original_chinese_ngrams,
)

RETRIEVAL_LIMIT = 20
_CHANNEL_CANDIDATE_LIMIT = 200
_WORD_PATTERN = re.compile(r"[A-Za-z0-9]+")


@dataclass(frozen=True)
class RetrievalCase:
    case_id: str
    category: str
    query: str
    expected_chunk_ids: frozenset[str]


@dataclass(frozen=True)
class RetrievalReport:
    case_count: int
    overall_recall_at_20: float
    category_recall: dict[str, float]
    failed_case_ids: tuple[str, ...]

    @classmethod
    def empty(cls) -> "RetrievalReport":
        return cls(0, 0.0, {}, ())

    def to_dict(self) -> dict[str, object]:
        return {
            "caseCount": self.case_count,
            "overallRecallAt20": self.overall_recall_at_20,
            "categoryRecall": dict(sorted(self.category_recall.items())),
            "failedCaseIds": list(self.failed_case_ids),
        }


def reciprocal_rank_fusion(
    rankings: Sequence[Sequence[str]],
    k: int = 60,
) -> list[str]:
    if k < 1:
        raise ValueError("RRF k 必须为正整数")
    scores: dict[str, float] = defaultdict(float)
    for ranking in rankings:
        seen: set[str] = set()
        for rank, item_id in enumerate(ranking, start=1):
            if item_id in seen:
                continue
            seen.add(item_id)
            scores[item_id] += 1.0 / (k + rank)
    return sorted(scores, key=lambda item_id: (-scores[item_id], item_id))


def evaluate_retrieval(
    database: Path,
    cases: Sequence[RetrievalCase],
) -> RetrievalReport:
    case_ids = [case.case_id for case in cases]
    if len(case_ids) != len(set(case_ids)):
        raise BuildError("检索评测 caseId 重复")
    if any(not case.case_id or not case.category or not case.query.strip() for case in cases):
        raise BuildError("检索评测包含空 caseId、category 或 query")
    if not cases:
        return RetrievalReport.empty()

    connection = _open_read_only(Path(database))
    try:
        known_chunks = {row[0] for row in connection.execute("SELECT chunk_id FROM chunks")}
        expected = set().union(*(case.expected_chunk_ids for case in cases))
        missing = sorted(expected - known_chunks)
        if missing:
            raise BuildError(f"检索评测引用了不存在的 chunk：{', '.join(missing)}")

        recalls: list[float] = []
        by_category: dict[str, list[float]] = defaultdict(list)
        failed: list[str] = []
        for case in cases:
            ranking = _retrieve(connection, case.query)[:RETRIEVAL_LIMIT]
            if case.expected_chunk_ids:
                recall = len(case.expected_chunk_ids.intersection(ranking)) / len(
                    case.expected_chunk_ids
                )
            else:
                recall = 1.0 if not ranking else 0.0
            recalls.append(recall)
            by_category[case.category].append(recall)
            if recall < 1.0:
                failed.append(case.case_id)
        category_recall = {
            category: round(sum(values) / len(values), 6)
            for category, values in sorted(by_category.items())
        }
        return RetrievalReport(
            case_count=len(cases),
            overall_recall_at_20=round(sum(recalls) / len(recalls), 6),
            category_recall=category_recall,
            failed_case_ids=tuple(sorted(failed)),
        )
    finally:
        connection.close()


def retrieve_chunks(
    database: Path,
    query: str,
    *,
    limit: int = RETRIEVAL_LIMIT,
) -> tuple[str, ...]:
    """Run the production-equivalent deterministic retrieval without evaluation."""

    if not isinstance(query, str) or not query.strip():
        raise BuildError("检索 query 必须是非空字符串")
    if type(limit) is not int or limit < 1 or limit > _CHANNEL_CANDIDATE_LIMIT:
        raise BuildError(
            f"检索 limit 必须在 1 到 {_CHANNEL_CANDIDATE_LIMIT} 之间"
        )
    connection = _open_read_only(Path(database))
    try:
        return tuple(_retrieve(connection, query)[:limit])
    finally:
        connection.close()


def load_retrieval_cases(path: Path) -> tuple[RetrievalCase, ...]:
    source = Path(path)
    if source.is_symlink() or not source.is_file():
        raise BuildError(f"检索评测文件不可安全读取：{source}")
    cases: list[RetrievalCase] = []
    previous: str | None = None
    try:
        with source.open("rb") as stream:
            for line_number, raw in enumerate(stream, start=1):
                try:
                    value = json.loads(raw, parse_constant=_reject_json_constant)
                except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                    raise BuildError(f"检索评测第 {line_number} 行 JSON 无效：{error}") from error
                if not isinstance(value, dict):
                    raise BuildError(f"检索评测第 {line_number} 行必须是对象")
                expected_fields = {"caseId", "category", "query", "expectedChunkIds"}
                if set(value) != expected_fields:
                    raise BuildError(f"检索评测第 {line_number} 行字段无效")
                if raw != canonical_json_bytes(value) + b"\n":
                    raise BuildError(f"检索评测第 {line_number} 行不是规范 JSONL")
                case_id = _nonempty_string(value["caseId"], "caseId", line_number)
                if previous == case_id:
                    raise BuildError(f"检索评测 caseId 重复：{case_id}")
                if previous is not None and case_id < previous:
                    raise BuildError("检索评测必须按 caseId 升序排列")
                previous = case_id
                category = _nonempty_string(value["category"], "category", line_number)
                query = _nonempty_string(value["query"], "query", line_number)
                expected_ids = value["expectedChunkIds"]
                if not isinstance(expected_ids, list) or any(
                    not isinstance(chunk_id, str) or not chunk_id
                    for chunk_id in expected_ids
                ):
                    raise BuildError(
                        f"检索评测第 {line_number} 行 expectedChunkIds 无效"
                    )
                if len(expected_ids) != len(set(expected_ids)):
                    raise BuildError(
                        f"检索评测第 {line_number} 行 expectedChunkIds 重复"
                    )
                cases.append(
                    RetrievalCase(
                        case_id=case_id,
                        category=category,
                        query=query,
                        expected_chunk_ids=frozenset(expected_ids),
                    )
                )
    except OSError as error:
        raise BuildError(f"检索评测文件无法读取：{error}") from error
    return tuple(cases)


def _retrieve(connection: sqlite3.Connection, query: str) -> list[str]:
    original_ids = _query_fts(
        connection,
        table="chunks_original_fts",
        id_column="chunk_id",
        tokens=_query_tokens(query, normalized=False),
    )
    normalized_ids = _query_fts(
        connection,
        table="chunks_normalized_fts",
        id_column="chunk_id",
        tokens=_query_tokens(query, normalized=True),
    )
    summary_ids = _query_fts(
        connection,
        table="summaries_fts",
        id_column="summary_id",
        tokens=_query_tokens(query, normalized=True),
    )
    term_ids = _query_fts(
        connection,
        table="terms_aliases_fts",
        id_column="owner_id",
        tokens=_query_tokens(query, normalized=True),
    )
    return reciprocal_rank_fusion(
        [
            original_ids,
            normalized_ids,
            _summary_chunks(connection, summary_ids),
            _term_chunks(connection, term_ids),
        ]
    )


def _query_fts(
    connection: sqlite3.Connection,
    *,
    table: str,
    id_column: str,
    tokens: tuple[str, ...],
) -> list[str]:
    if not tokens:
        return []
    match = " OR ".join(f'"{token.replace(chr(34), chr(34) * 2)}"' for token in tokens)
    return [
        row[0]
        for row in connection.execute(
            f"""
            SELECT {id_column} FROM {table}
            WHERE {table} MATCH ?
            ORDER BY _hwiki_fts_rank(matchinfo({table}, 'pcx')) DESC, {id_column}
            LIMIT ?
            """,
            (match, _CHANNEL_CANDIDATE_LIMIT),
        )
    ]


def _query_tokens(query: str, *, normalized: bool) -> tuple[str, ...]:
    ngrams = chinese_ngrams(query) if normalized else original_chinese_ngrams(query)
    surface = normalize_for_search(query) if normalized else query
    words = {word.lower() for word in _WORD_PATTERN.findall(surface)}
    return tuple(sorted(set(ngrams) | words))


def _summary_chunks(connection: sqlite3.Connection, summary_ids: Sequence[str]) -> list[str]:
    chunks: list[str] = []
    for summary_id in summary_ids:
        chunks.extend(
            row[0]
            for row in connection.execute(
                """
                SELECT chunk_id FROM evidence_refs
                WHERE owner_type='summary' AND owner_id=?
                ORDER BY ordinal, chunk_id
                """,
                (summary_id,),
            )
        )
    return _deduplicated(chunks)


def _term_chunks(connection: sqlite3.Connection, term_ids: Sequence[str]) -> list[str]:
    chunks: list[str] = []
    for term_id in term_ids:
        chunks.extend(
            row[0]
            for row in connection.execute(
                "SELECT chunk_id FROM mentions WHERE term_id=? ORDER BY mention_id",
                (term_id,),
            )
        )
        chunks.extend(
            row[0]
            for row in connection.execute(
                """
                SELECT chunk_id FROM evidence_refs
                WHERE owner_type='term' AND owner_id=?
                ORDER BY ordinal, chunk_id
                """,
                (term_id,),
            )
        )
    return _deduplicated(chunks)


def _deduplicated(values: Sequence[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def _open_read_only(path: Path) -> sqlite3.Connection:
    try:
        connection = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
        connection.create_function(
            "_hwiki_fts_rank",
            1,
            _fts_match_score,
            deterministic=True,
        )
        return connection
    except sqlite3.Error as error:
        raise BuildError(f"content.sqlite 无法只读打开：{error}") from error


def _fts_match_score(payload: bytes | None) -> float:
    if payload is None or len(payload) < 8 or len(payload) % 4:
        return 0.0
    values = struct.unpack(f"={len(payload) // 4}I", payload)
    phrase_count, column_count = values[:2]
    expected = 2 + phrase_count * column_count * 3
    if len(values) != expected:
        return 0.0
    score = 0.0
    offset = 2
    for _phrase in range(phrase_count):
        for _column in range(column_count):
            hits_in_row, _hits_in_all_rows, rows_with_hits = values[offset : offset + 3]
            if hits_in_row:
                score += hits_in_row / (1.0 + rows_with_hits)
            offset += 3
    return score


def _nonempty_string(value: object, field: str, line_number: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BuildError(f"检索评测第 {line_number} 行 {field} 必须是非空字符串")
    return value


def _reject_json_constant(value: str) -> object:
    raise ValueError(f"不允许 JSON 常量 {value}")


__all__ = [
    "RETRIEVAL_LIMIT",
    "RetrievalCase",
    "RetrievalReport",
    "evaluate_retrieval",
    "load_retrieval_cases",
    "reciprocal_rank_fusion",
    "retrieve_chunks",
]
