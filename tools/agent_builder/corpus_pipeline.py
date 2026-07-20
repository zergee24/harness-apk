"""Deterministic V2 corpus indexing with auditable duplicate preservation."""

from __future__ import annotations

import hashlib
import json
import math
import re
import os
import shutil
import sqlite3
import tempfile
import unicodedata
from collections import Counter
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Iterable

from .models import BuildError, ExtractedDocument, ExtractedSection
from .schema_v2 import SourceRecord


CHUNK_TARGET_CHARS = 1200
CHUNK_OVERLAP_CHARS = 120
MAX_CONTEXT_CHARS = 320
SAFE_NEAR_REPLACEMENTS = (("以后", "之后"),)


@dataclass(frozen=True)
class HierarchyNode:
    id: str
    kind: str
    title: str
    source_id: str
    parent_id: str | None
    path: tuple[str, ...]
    summary: str

    def to_dict(self) -> dict[str, object]:
        return {
            "id": self.id,
            "kind": self.kind,
            "parentId": self.parent_id,
            "path": list(self.path),
            "sourceId": self.source_id,
            "summary": self.summary,
            "title": self.title,
        }


@dataclass(frozen=True)
class ContextualChunk:
    id: str
    source_id: str
    source_hash: str
    source_title: str
    genre: str
    authorship: str
    period: str
    location: str
    parent_ids: tuple[str, ...]
    context: str
    text: str
    keywords: tuple[str, ...]
    ngrams: tuple[str, ...]
    conflict_key: str
    duplicate_group: str
    source_aliases: tuple[str, ...]
    normalized_hash: str
    safe_near_hash: str
    semantic_guard_hash: str
    simhash: int

    def to_dict(self) -> dict[str, object]:
        return {
            "authorship": self.authorship,
            "conflictKey": self.conflict_key,
            "context": self.context,
            "duplicateGroup": self.duplicate_group,
            "genre": self.genre,
            "id": self.id,
            "keywords": list(self.keywords),
            "location": self.location,
            "ngrams": list(self.ngrams),
            "parentIds": list(self.parent_ids),
            "period": self.period,
            "sourceAliases": list(self.source_aliases),
            "sourceHash": self.source_hash,
            "sourceId": self.source_id,
            "sourceTitle": self.source_title,
            "text": self.text,
            "simHash": f"{self.simhash:016x}",
        }


@dataclass(frozen=True)
class DuplicateRecord:
    duplicate_chunk_id: str
    physical_chunk_id: str
    duplicate_source_id: str
    primary_source_id: str
    match_type: str
    period: str
    conflict_key: str

    def to_dict(self) -> dict[str, str]:
        return {
            "conflictKey": self.conflict_key,
            "duplicateChunkId": self.duplicate_chunk_id,
            "duplicateSourceId": self.duplicate_source_id,
            "matchType": self.match_type,
            "period": self.period,
            "physicalChunkId": self.physical_chunk_id,
            "primarySourceId": self.primary_source_id,
        }


@dataclass(frozen=True)
class DeduplicationStats:
    source_count: int
    raw_bytes: int
    extracted_characters: int
    estimated_tokens: int
    chunks_before_deduplication: int
    chunks_after_deduplication: int
    exact_duplicate_count: int
    near_duplicate_count: int
    extraction_failures: tuple[str, ...]
    metadata_coverage: float

    @property
    def deduplication_ratio(self) -> float:
        if not self.chunks_before_deduplication:
            return 0.0
        return round(
            1 - (self.chunks_after_deduplication / self.chunks_before_deduplication),
            6,
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "chunksAfterDeduplication": self.chunks_after_deduplication,
            "chunksBeforeDeduplication": self.chunks_before_deduplication,
            "deduplicationRatio": self.deduplication_ratio,
            "estimatedTokens": self.estimated_tokens,
            "exactDuplicateCount": self.exact_duplicate_count,
            "extractedCharacters": self.extracted_characters,
            "extractionFailures": list(self.extraction_failures),
            "metadataCoverage": self.metadata_coverage,
            "nearDuplicateCount": self.near_duplicate_count,
            "rawBytes": self.raw_bytes,
            "sourceCount": self.source_count,
        }


@dataclass(frozen=True)
class CorpusIndexResult:
    nodes: tuple[HierarchyNode, ...]
    chunks: tuple[ContextualChunk, ...]
    duplicates: tuple[DuplicateRecord, ...]
    stats: DeduplicationStats


@dataclass(frozen=True)
class _Candidate:
    chunk: ContextualChunk
    sort_key: tuple[str, str, str, int, str]


def build_corpus_index(
    documents: Iterable[ExtractedDocument],
    source_records: Iterable[SourceRecord],
) -> CorpusIndexResult:
    """Build a canonical index independently of caller input order."""
    records = tuple(sorted(source_records, key=_source_key))
    documents_by_key = {
        (document.source_hash, document.source_path.name): document
        for document in documents
    }
    nodes: dict[str, HierarchyNode] = {}
    candidates: list[_Candidate] = []
    extracted_characters = 0

    for source in records:
        document = documents_by_key.get((source.source_hash, source.file_name))
        if document is None:
            raise BuildError(f"索引缺少来源文档：{source.source_id}")
        source_node = _source_node(source)
        nodes[source_node.id] = source_node
        for section_index, section in enumerate(document.sections):
            if not section.text.strip():
                continue
            extracted_characters += len(section.text)
            parent_ids = _hierarchy_nodes(nodes, source, section.location)
            context = _context(source, section.location)
            for chunk_index, text in enumerate(_chunk_text(section.text)):
                candidates.append(
                    _Candidate(
                        chunk=_contextual_chunk(
                            source,
                            section,
                            text,
                            parent_ids,
                            context,
                            section_index,
                            chunk_index,
                        ),
                        sort_key=(
                            source.source_id,
                            source.source_hash,
                            section.location,
                            section_index * 1_000_000 + chunk_index,
                            text,
                        ),
                    )
                )

    chunks, duplicates, exact_count, near_count = _deduplicate(candidates)
    coverage = _metadata_coverage(records)
    stats = DeduplicationStats(
        source_count=len(records),
        raw_bytes=sum(source.raw_size_bytes for source in records),
        extracted_characters=extracted_characters,
        estimated_tokens=math.ceil(extracted_characters / 4) if extracted_characters else 0,
        chunks_before_deduplication=len(candidates),
        chunks_after_deduplication=len(chunks),
        exact_duplicate_count=exact_count,
        near_duplicate_count=near_count,
        extraction_failures=(),
        metadata_coverage=coverage,
    )
    return CorpusIndexResult(
        nodes=tuple(sorted(nodes.values(), key=lambda node: node.id)),
        chunks=tuple(sorted(chunks, key=lambda chunk: chunk.id)),
        duplicates=tuple(sorted(duplicates, key=lambda row: row.duplicate_chunk_id)),
        stats=stats,
    )


def write_corpus_index(workspace: Path, result: CorpusIndexResult) -> None:
    root = Path(workspace) / "corpora" / "index"
    root.mkdir(parents=True, exist_ok=True)
    _write_jsonl(root / "nodes.jsonl", (node.to_dict() for node in result.nodes))
    _write_jsonl(root / "chunks.jsonl", (chunk.to_dict() for chunk in result.chunks))
    _write_jsonl(root / "duplicates.jsonl", (row.to_dict() for row in result.duplicates))
    (root / "report.json").write_bytes(_canonical_json_bytes(result.stats.to_dict()))


def build_corpus_index_streaming(
    workspace: Path,
    source_records: Iterable[SourceRecord],
    sections_for_source: Callable[[SourceRecord], Iterable[ExtractedSection]],
) -> DeduplicationStats:
    """Build the production index with disk-backed candidates and duplicate indexes.

    The in-memory API above intentionally remains convenient for small callers and
    unit tests. V2 preparation uses this function so source text and the complete
    candidate set are never retained in Python memory together.
    """
    records = tuple(sorted(source_records, key=_source_key))
    workspace = Path(workspace)
    output_root = workspace / "corpora" / "index"
    output_parent = output_root.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    if output_root.exists():
        raise BuildError(f"语料索引输出目录已存在：{output_root}")
    temporary_output_root: Path | None = None
    database_path: Path | None = None
    connection: sqlite3.Connection | None = None
    extracted_characters = 0
    chunks_before = 0
    exact_count = 0
    near_count = 0
    try:
        temporary_output_root = Path(
            tempfile.mkdtemp(prefix=".index-staging-", dir=output_parent)
        )
        descriptor, database_name = tempfile.mkstemp(
            prefix=".corpus-index-", suffix=".sqlite3", dir=workspace
        )
        os.close(descriptor)
        database_path = Path(database_name)
        connection = sqlite3.connect(database_path)
        connection.row_factory = sqlite3.Row
        _create_streaming_schema(connection)
        for source in records:
            source_node = _source_node(source)
            _insert_nodes(connection, (source_node,))
            for section_index, section in enumerate(sections_for_source(source)):
                if not section.text.strip():
                    continue
                extracted_characters += len(section.text)
                parent_ids, section_nodes = _streaming_hierarchy_nodes(source, section.location)
                _insert_nodes(connection, section_nodes)
                context = _context(source, section.location)
                for chunk_index, text in enumerate(_chunk_text(section.text)):
                    chunks_before += 1
                    candidate = _contextual_chunk(
                        source,
                        section,
                        text,
                        parent_ids,
                        context,
                        section_index,
                        chunk_index,
                    )
                    match = _find_exact_streaming_match(connection, candidate)
                    match_type = "exact"
                    if match is None:
                        match = _find_near_streaming_match(connection, candidate)
                        match_type = "near"
                    if match is None:
                        _insert_physical_chunk(connection, candidate, _stream_sort_key(candidate))
                    else:
                        _merge_streaming_chunk(connection, match, candidate)
                        _insert_duplicate(connection, _duplicate_record(candidate, match, match_type))
                        if match_type == "exact":
                            exact_count += 1
                        else:
                            near_count += 1
        if not chunks_before:
            raise BuildError("输入资料没有生成任何文本块")
        connection.commit()
        chunks_after = connection.execute("SELECT COUNT(*) FROM physical_chunks").fetchone()[0]
        stats = DeduplicationStats(
            source_count=len(records),
            raw_bytes=sum(source.raw_size_bytes for source in records),
            extracted_characters=extracted_characters,
            estimated_tokens=math.ceil(extracted_characters / 4) if extracted_characters else 0,
            chunks_before_deduplication=chunks_before,
            chunks_after_deduplication=chunks_after,
            exact_duplicate_count=exact_count,
            near_duplicate_count=near_count,
            extraction_failures=(),
            metadata_coverage=_metadata_coverage(records),
        )
        _write_streaming_index_files(connection, temporary_output_root, stats)
        temporary_output_root.replace(output_root)
        return stats
    finally:
        if connection is not None:
            connection.close()
        if database_path is not None:
            database_path.unlink(missing_ok=True)
        if temporary_output_root is not None and temporary_output_root.exists():
            shutil.rmtree(temporary_output_root)


def _create_streaming_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        CREATE TABLE nodes (
            id TEXT PRIMARY KEY,
            payload TEXT NOT NULL
        );
        CREATE TABLE physical_chunks (
            id TEXT PRIMARY KEY,
            sort_key TEXT NOT NULL UNIQUE,
            period TEXT NOT NULL,
            conflict_key TEXT NOT NULL,
            genre TEXT NOT NULL,
            authorship TEXT NOT NULL,
            normalized_hash TEXT NOT NULL,
            safe_near_hash TEXT NOT NULL,
            semantic_guard_hash TEXT NOT NULL,
            simhash TEXT NOT NULL,
            source_id TEXT NOT NULL,
            source_hash TEXT NOT NULL,
            location TEXT NOT NULL,
            aliases TEXT NOT NULL,
            payload TEXT NOT NULL
        );
        CREATE INDEX physical_exact_index
            ON physical_chunks(
                period, conflict_key, genre, authorship,
                normalized_hash, semantic_guard_hash, sort_key
            );
        CREATE INDEX physical_exact_unknown_index
            ON physical_chunks(
                period, conflict_key, genre, authorship,
                source_id, source_hash, location,
                normalized_hash, semantic_guard_hash, sort_key
            );
        CREATE INDEX physical_safe_near_index
            ON physical_chunks(
                period, conflict_key, genre, authorship,
                safe_near_hash, semantic_guard_hash, sort_key
            );
        CREATE INDEX physical_safe_near_unknown_index
            ON physical_chunks(
                period, conflict_key, genre, authorship,
                source_id, source_hash, location,
                safe_near_hash, semantic_guard_hash, sort_key
            );
        CREATE TABLE duplicates (
            duplicate_chunk_id TEXT PRIMARY KEY,
            payload TEXT NOT NULL
        );
        """
    )


def _insert_nodes(connection: sqlite3.Connection, nodes: Iterable[HierarchyNode]) -> None:
    for node in nodes:
        connection.execute(
            "INSERT OR IGNORE INTO nodes(id, payload) VALUES (?, ?)",
            (node.id, _canonical_json_text(node.to_dict())),
        )


def _find_exact_streaming_match(
    connection: sqlite3.Connection, candidate: ContextualChunk
) -> ContextualChunk | None:
    where, params = _streaming_match_constraints(candidate, "physical_chunks")
    row = connection.execute(
        "SELECT * FROM physical_chunks WHERE "
        + where
        + " AND normalized_hash = ? AND semantic_guard_hash = ? "
        "ORDER BY sort_key LIMIT 1",
        (*params, candidate.normalized_hash, candidate.semantic_guard_hash),
    ).fetchone()
    return _chunk_from_streaming_row(row) if row is not None else None


def _find_near_streaming_match(
    connection: sqlite3.Connection, candidate: ContextualChunk
) -> ContextualChunk | None:
    """Merge only explicit safe wording equivalents, never fuzzy rewrites."""
    where, params = _streaming_match_constraints(candidate, "physical_chunks")
    row = connection.execute(
        "SELECT * FROM physical_chunks WHERE "
        + where
        + " AND safe_near_hash = ? AND semantic_guard_hash = ? "
        "AND normalized_hash != ? ORDER BY sort_key LIMIT 1",
        (
            *params,
            candidate.safe_near_hash,
            candidate.semantic_guard_hash,
            candidate.normalized_hash,
        ),
    ).fetchone()
    return _chunk_from_streaming_row(row) if row is not None else None


def _streaming_match_constraints(
    candidate: ContextualChunk, table: str
) -> tuple[str, tuple[str, ...]]:
    prefix = f"{table}."
    where = (
        f"{prefix}period = ? AND {prefix}conflict_key = ? AND "
        f"{prefix}genre = ? AND {prefix}authorship = ?"
    )
    params: tuple[str, ...] = (
        candidate.period,
        candidate.conflict_key,
        candidate.genre,
        candidate.authorship,
    )
    if candidate.period == "unknown":
        where += (
            f" AND {prefix}source_id = ? AND {prefix}source_hash = ? "
            f"AND {prefix}location = ?"
        )
        params += (candidate.source_id, candidate.source_hash, candidate.location)
    return where, params


def _insert_physical_chunk(
    connection: sqlite3.Connection, chunk: ContextualChunk, sort_key: str
) -> None:
    connection.execute(
        """
        INSERT INTO physical_chunks(
            id, sort_key, period, conflict_key, genre, authorship, normalized_hash,
            safe_near_hash, semantic_guard_hash, simhash,
            source_id, source_hash, location, aliases, payload
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            chunk.id,
            sort_key,
            chunk.period,
            chunk.conflict_key,
            chunk.genre,
            chunk.authorship,
            chunk.normalized_hash,
            chunk.safe_near_hash,
            chunk.semantic_guard_hash,
            f"{chunk.simhash:016x}",
            chunk.source_id,
            chunk.source_hash,
            chunk.location,
            _canonical_json_text(list(chunk.source_aliases)),
            _canonical_json_text(chunk.to_dict()),
        ),
    )
def _merge_streaming_chunk(
    connection: sqlite3.Connection, primary: ContextualChunk, duplicate: ContextualChunk
) -> None:
    aliases = tuple(sorted({
        *primary.source_aliases,
        duplicate.source_id,
        *duplicate.source_aliases,
    } - {primary.source_id}))
    merged = replace(primary, source_aliases=aliases)
    connection.execute(
        "UPDATE physical_chunks SET aliases = ?, payload = ? WHERE id = ?",
        (
            _canonical_json_text(list(aliases)),
            _canonical_json_text(merged.to_dict()),
            primary.id,
        ),
    )


def _insert_duplicate(connection: sqlite3.Connection, row: DuplicateRecord) -> None:
    connection.execute(
        "INSERT INTO duplicates(duplicate_chunk_id, payload) VALUES (?, ?)",
        (row.duplicate_chunk_id, _canonical_json_text(row.to_dict())),
    )


def _chunk_from_streaming_row(row: sqlite3.Row) -> ContextualChunk:
    payload = json.loads(row["payload"])
    return ContextualChunk(
        id=payload["id"],
        source_id=payload["sourceId"],
        source_hash=payload["sourceHash"],
        source_title=payload["sourceTitle"],
        genre=payload["genre"],
        authorship=payload["authorship"],
        period=payload["period"],
        location=payload["location"],
        parent_ids=tuple(payload["parentIds"]),
        context=payload["context"],
        text=payload["text"],
        keywords=tuple(payload["keywords"]),
        ngrams=tuple(payload["ngrams"]),
        conflict_key=payload["conflictKey"],
        duplicate_group=payload["duplicateGroup"],
        source_aliases=tuple(json.loads(row["aliases"])),
        normalized_hash=row["normalized_hash"],
        safe_near_hash=row["safe_near_hash"],
        semantic_guard_hash=row["semantic_guard_hash"],
        simhash=int(row["simhash"], 16),
    )


def _stream_sort_key(chunk: ContextualChunk) -> str:
    return "\u001f".join((chunk.source_id, chunk.source_hash, chunk.location, chunk.id))


def _write_streaming_index_files(
    connection: sqlite3.Connection, root: Path, stats: DeduplicationStats
) -> None:
    _write_jsonl_cursor(
        root / "nodes.jsonl",
        connection.execute("SELECT payload FROM nodes ORDER BY id"),
    )
    _write_jsonl_cursor(
        root / "chunks.jsonl",
        connection.execute("SELECT payload FROM physical_chunks ORDER BY id"),
    )
    _write_jsonl_cursor(
        root / "duplicates.jsonl",
        connection.execute("SELECT payload FROM duplicates ORDER BY duplicate_chunk_id"),
    )
    (root / "report.json").write_bytes(_canonical_json_bytes(stats.to_dict()))


def _write_jsonl_cursor(path: Path, rows: Iterable[sqlite3.Row]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(row["payload"])
            stream.write("\n")


def _canonical_json_text(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalize_for_dedup(text: str) -> str:
    folded = unicodedata.normalize("NFKC", text).casefold()
    normalized = re.sub(r"[\W_]+", "", folded)
    # A punctuation-only chunk has no lexical feature. Preserve a deterministic
    # fallback so distinct source evidence never shares an empty exact-hash key.
    return normalized or "\x00" + folded


def safe_near_canonical(text: str) -> str:
    """Canonicalize only explicitly approved wording variants for physical merge."""
    folded = unicodedata.normalize("NFKC", text).casefold()
    for source, replacement in SAFE_NEAR_REPLACEMENTS:
        folded = folded.replace(source, replacement)
    normalized = re.sub(r"[\W_]+", "", folded)
    return normalized or "\x00" + folded


def semantic_guard_canonical(text: str) -> str:
    """Keep evidence-significant lexical boundaries out of punctuation-only merges."""
    folded = unicodedata.normalize("NFKC", text).casefold()
    latin_tokens, latin_token_boundaries = _latin_digit_tokens_with_boundaries(folded)
    digits = tuple(re.findall(r"\d+(?:\.\d+)?", folded))
    negations = tuple(
        f"{character}:{_semantic_boundary_kind(folded[index + 1:index + 2])}"
        for index, character in enumerate(folded)
        if character in "不非无未莫勿"
    )
    stances = tuple(
        match.group(0)
        for match in re.finditer(
            r"不赞成|不支持|不同意|不应该|不应|反对|赞成|支持|拥护|同意|拒绝|抵制|否定|主张",
            folded,
        )
    )
    return _canonical_json_text(
        {
            "digits": digits,
            "latinTokens": latin_tokens,
            "latinTokenBoundaries": latin_token_boundaries,
            "negationForms": negations,
            "stances": stances,
        }
    )


def _latin_digit_tokens_with_boundaries(text: str) -> tuple[tuple[str, ...], tuple[str, ...]]:
    tokens: list[str] = []
    boundaries: list[str] = []
    for match in re.finditer(r"[a-z0-9]+", text):
        tokens.append(match.group(0))
        boundaries.append(
            f"{_latin_token_boundary(text, match.start() - 1)}"
            f">{match.group(0)}<"
            f"{_latin_token_boundary(text, match.end())}"
        )
    return tuple(tokens), tuple(boundaries)


def _latin_token_boundary(text: str, index: int) -> str:
    if index < 0 or index >= len(text):
        return "edge"
    character = text[index]
    if character.isspace():
        return "space"
    if "\u3400" <= character <= "\u9fff":
        return "cjk"
    if character.isalnum():
        return "word"
    return f"punct:{character}"


def _semantic_boundary_kind(value: str) -> str:
    if not value:
        return "end"
    character = value[0]
    if "\u3400" <= character <= "\u9fff":
        return "cjk"
    if character.isalnum():
        return "word"
    return "boundary"


def simhash64(text: str) -> int:
    features = _character_shingles(normalize_for_dedup(text), width=4)
    if not features:
        return 0
    vector = [0] * 64
    for feature in features:
        digest = int.from_bytes(hashlib.sha256(feature.encode("utf-8")).digest()[:8], "big")
        for bit in range(64):
            vector[bit] += 1 if digest & (1 << bit) else -1
    return sum((1 << bit) for bit, value in enumerate(vector) if value >= 0)


def _source_key(source: SourceRecord) -> tuple[str, str, str]:
    return (source.source_id, source.source_hash, source.file_name)


def _source_node(source: SourceRecord) -> HierarchyNode:
    node_id = _stable_id("node-source", source.source_id)
    return HierarchyNode(
        id=node_id,
        kind="source",
        title=source.title,
        source_id=source.source_id,
        parent_id=None,
        path=(source.title,),
        summary=_bounded(" | ".join((source.title, source.period, source.authorship.value))),
    )


def _hierarchy_nodes(
    nodes: dict[str, HierarchyNode], source: SourceRecord, location: str
) -> tuple[str, ...]:
    parent = _source_node(source)
    parent_id = parent.id
    parent_ids = [parent_id]
    path = [source.title]
    for depth, title in enumerate(_location_parts(location), start=1):
        path.append(title)
        node_id = _stable_id("node-section", source.source_id, str(depth), "\u001f".join(path))
        nodes.setdefault(
            node_id,
            HierarchyNode(
                id=node_id,
                kind="section",
                title=title,
                source_id=source.source_id,
                parent_id=parent_id,
                path=tuple(path),
                summary=_bounded(" | ".join((source.title, " / ".join(path[1:]), source.period))),
            ),
        )
        parent_id = node_id
        parent_ids.append(node_id)
    if len(parent_ids) == 1:
        section_id = _stable_id("node-section", source.source_id, "0", "正文")
        nodes.setdefault(
            section_id,
            HierarchyNode(
                id=section_id,
                kind="section",
                title="正文",
                source_id=source.source_id,
                parent_id=parent_id,
                path=(source.title, "正文"),
                summary=_bounded(" | ".join((source.title, "正文", source.period))),
            ),
        )
        parent_ids.append(section_id)
    return tuple(parent_ids)


def _streaming_hierarchy_nodes(
    source: SourceRecord, location: str
) -> tuple[tuple[str, ...], tuple[HierarchyNode, ...]]:
    """Create only the current section path; SQLite owns all prior hierarchy rows."""
    source_node = _source_node(source)
    parent_id = source_node.id
    parent_ids = [parent_id]
    path = [source.title]
    nodes: list[HierarchyNode] = []
    for depth, title in enumerate(_location_parts(location), start=1):
        path.append(title)
        node_id = _stable_id("node-section", source.source_id, str(depth), "\u001f".join(path))
        nodes.append(
            HierarchyNode(
                id=node_id,
                kind="section",
                title=title,
                source_id=source.source_id,
                parent_id=parent_id,
                path=tuple(path),
                summary=_bounded(" | ".join((source.title, " / ".join(path[1:]), source.period))),
            )
        )
        parent_id = node_id
        parent_ids.append(node_id)
    if len(parent_ids) == 1:
        section_id = _stable_id("node-section", source.source_id, "0", "正文")
        nodes.append(
            HierarchyNode(
                id=section_id,
                kind="section",
                title="正文",
                source_id=source.source_id,
                parent_id=parent_id,
                path=(source.title, "正文"),
                summary=_bounded(" | ".join((source.title, "正文", source.period))),
            )
        )
        parent_ids.append(section_id)
    return tuple(parent_ids), tuple(nodes)


def _context(source: SourceRecord, location: str) -> str:
    parts = [source.title, " / ".join(_location_parts(location)), source.period, source.authorship.value]
    return _bounded(" | ".join(part for part in parts if part))


def _contextual_chunk(
    source: SourceRecord,
    section: ExtractedSection,
    text: str,
    parent_ids: tuple[str, ...],
    context: str,
    section_index: int,
    chunk_index: int,
) -> ContextualChunk:
    normalized = normalize_for_dedup(text)
    safe_near = safe_near_canonical(text)
    semantic_guard = semantic_guard_canonical(text)
    candidate_id = _stable_id(
        "chunk",
        source.source_id,
        source.source_hash,
        section.location,
        str(section_index),
        str(chunk_index),
        text,
    )
    return ContextualChunk(
        id=candidate_id,
        source_id=source.source_id,
        source_hash=source.source_hash,
        source_title=source.title,
        genre=source.genre.value,
        authorship=source.authorship.value,
        period=source.period,
        location=section.location,
        parent_ids=parent_ids,
        context=context,
        text=text,
        keywords=tuple(_keywords(text)),
        ngrams=tuple(_ngrams(text)),
        conflict_key=section.conflict_key.strip(),
        duplicate_group=_duplicate_group(
            source,
            section.location,
            section.conflict_key.strip(),
            safe_near,
            semantic_guard,
        ),
        source_aliases=(),
        normalized_hash=hashlib.sha256(normalized.encode("utf-8")).hexdigest(),
        safe_near_hash=hashlib.sha256(safe_near.encode("utf-8")).hexdigest(),
        semantic_guard_hash=hashlib.sha256(semantic_guard.encode("utf-8")).hexdigest(),
        simhash=simhash64(text),
    )


def _duplicate_group(
    source: SourceRecord,
    location: str,
    conflict_key: str,
    safe_near: str,
    semantic_guard: str,
) -> str:
    scope = (
        safe_near,
        semantic_guard,
        source.period,
        conflict_key,
        source.genre.value,
        source.authorship.value,
    )
    if source.period == "unknown":
        scope += (source.source_id, source.source_hash, location)
    return _stable_id("duplicate", *scope)


def _deduplicate(
    candidates: Iterable[_Candidate],
) -> tuple[list[ContextualChunk], list[DuplicateRecord], int, int]:
    physical: list[ContextualChunk] = []
    duplicates: list[DuplicateRecord] = []
    exact_count = 0
    near_count = 0
    for candidate in sorted(candidates, key=lambda item: item.sort_key):
        chunk = candidate.chunk
        matches = [stored for stored in physical if _eligible_for_merge(stored, chunk)]
        exact = next((stored for stored in matches if _is_exact_duplicate(stored, chunk)), None)
        if exact is not None:
            physical = _merge_chunk(physical, exact, chunk)
            duplicates.append(_duplicate_record(chunk, exact, "exact"))
            exact_count += 1
            continue
        near = next((stored for stored in matches if _is_safe_near_duplicate(stored, chunk)), None)
        if near is not None:
            physical = _merge_chunk(physical, near, chunk)
            duplicates.append(_duplicate_record(chunk, near, "near"))
            near_count += 1
            continue
        physical.append(chunk)
    return physical, duplicates, exact_count, near_count


def _eligible_for_merge(left: ContextualChunk, right: ContextualChunk) -> bool:
    if (
        left.period != right.period
        or left.conflict_key != right.conflict_key
        or left.genre != right.genre
        or left.authorship != right.authorship
    ):
        return False
    if left.period == "unknown":
        return (
            left.source_id == right.source_id
            and left.source_hash == right.source_hash
            and left.location == right.location
        )
    return True


def _is_exact_duplicate(left: ContextualChunk, right: ContextualChunk) -> bool:
    """Treat punctuation/spacing hashes as candidates, never as semantic proof."""
    return (
        left.normalized_hash == right.normalized_hash
        and left.semantic_guard_hash == right.semantic_guard_hash
    )


def _is_safe_near_duplicate(left: ContextualChunk, right: ContextualChunk) -> bool:
    """Only merge an allow-listed wording equivalent after the semantic guard."""
    return (
        left.normalized_hash != right.normalized_hash
        and left.safe_near_hash == right.safe_near_hash
        and left.semantic_guard_hash == right.semantic_guard_hash
    )


def _merge_chunk(
    physical: list[ContextualChunk], primary: ContextualChunk, duplicate: ContextualChunk
) -> list[ContextualChunk]:
    aliases = tuple(sorted({
        *primary.source_aliases,
        duplicate.source_id,
        *duplicate.source_aliases,
    } - {primary.source_id}))
    merged = replace(primary, source_aliases=aliases)
    return [merged if item.id == primary.id else item for item in physical]


def _duplicate_record(
    duplicate: ContextualChunk, primary: ContextualChunk, match_type: str
) -> DuplicateRecord:
    return DuplicateRecord(
        duplicate_chunk_id=duplicate.id,
        physical_chunk_id=primary.id,
        duplicate_source_id=duplicate.source_id,
        primary_source_id=primary.source_id,
        match_type=match_type,
        period=primary.period,
        conflict_key=primary.conflict_key,
    )


def _chunk_text(text: str) -> tuple[str, ...]:
    paragraphs = [item.strip() for item in re.split(r"\n\s*\n", text) if item.strip()]
    if not paragraphs:
        return ()
    chunks: list[str] = []
    buffer = ""
    for paragraph in paragraphs:
        if len(paragraph) > CHUNK_TARGET_CHARS:
            if buffer:
                chunks.append(buffer)
                buffer = ""
            start = 0
            while start < len(paragraph):
                chunks.append(paragraph[start : start + CHUNK_TARGET_CHARS].strip())
                start += CHUNK_TARGET_CHARS - CHUNK_OVERLAP_CHARS
            continue
        candidate = paragraph if not buffer else f"{buffer}\n\n{paragraph}"
        if len(candidate) <= CHUNK_TARGET_CHARS:
            buffer = candidate
        else:
            chunks.append(buffer)
            overlap = buffer[-CHUNK_OVERLAP_CHARS:].strip()
            buffer = f"{overlap}\n\n{paragraph}" if overlap else paragraph
    if buffer:
        chunks.append(buffer)
    return tuple(chunks)


def _location_parts(location: str) -> tuple[str, ...]:
    parts = tuple(part.strip() for part in location.split("/") if part.strip())
    return parts if parts else ("正文",)


def _character_shingles(text: str, width: int) -> tuple[str, ...]:
    if not text:
        return ()
    if len(text) <= width:
        return (text,)
    return tuple(text[index : index + width] for index in range(len(text) - width + 1))


def _ngrams(text: str) -> list[str]:
    runs = re.findall(r"[\u3400-\u9fff]+", text)
    grams: list[str] = []
    for run in runs:
        grams.extend(run[index : index + 2] for index in range(max(1, len(run) - 1)))
    latin = re.findall(r"[A-Za-z0-9_]{2,}", text.lower())
    return list(dict.fromkeys(grams + latin))


def _keywords(text: str) -> list[str]:
    ngrams = _ngrams(text)
    common = [item for item, _ in Counter(ngrams).most_common(64)]
    return list(dict.fromkeys(re.findall(r"[A-Za-z0-9_]{2,}", text.lower()) + common))


def _metadata_coverage(sources: Iterable[SourceRecord]) -> float:
    rows = tuple(sources)
    if not rows:
        return 0.0
    covered = sum(
        source.genre.value != "unknown"
        and source.authorship.value != "unknown"
        and source.period != "unknown"
        for source in rows
    )
    return round(covered / len(rows), 6)


def _stable_id(prefix: str, *parts: str) -> str:
    value = "\u001f".join(parts).encode("utf-8")
    return f"{prefix}-" + hashlib.sha256(value).hexdigest()[:24]


def _bounded(value: str) -> str:
    return value[:MAX_CONTEXT_CHARS]


def _write_jsonl(path: Path, rows: Iterable[dict[str, object]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
            stream.write("\n")


def _canonical_json_bytes(value: dict[str, object]) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
