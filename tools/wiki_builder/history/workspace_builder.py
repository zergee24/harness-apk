"""Streaming population of generic Wiki workspaces from structured history rows."""

from __future__ import annotations

import hashlib
import shutil
import sqlite3
import tempfile
from collections.abc import Callable, Iterable
from pathlib import Path

from tools.package_format import canonical_json_bytes

from ..builder import HARD_CHUNK_CHARS, SEARCH_OVERLAP_CHARS, SOFT_CHUNK_CHARS
from ..extractors import stable_id
from ..models import BuildError, PreparedDocument
from ..normalization import (
    NORMALIZATION_MAP_HASH,
    NORMALIZATION_VERSION,
    chinese_ngrams,
    normalize_for_search,
    original_chinese_ngrams,
)
from ..sqlite_schema import create_content_database, validate_sqlite_shape
from ..workspace import initialize_workspace_files
from .models import (
    HistoryDocumentRecord,
    HistoryExclusionAudit,
    HistoryParagraphRecord,
    HistorySectionRecord,
)


def populate_history_workspace(
    documents: Iterable[HistoryDocumentRecord],
    output: Path,
    *,
    wiki_id: str,
    title: str,
    version: int,
    concept_namespace: str,
    source_id: str,
    source_revision: str,
    exclusions: tuple[str, ...],
    before_publish: Callable[[], None] | None = None,
) -> Path:
    target = Path(output)
    if target.exists() or target.is_symlink():
        raise FileExistsError(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{target.name}.history-", dir=target.parent))
    published = False
    database: sqlite3.Connection | None = None
    try:
        database = create_content_database(staging / "content.sqlite")
        database.execute("BEGIN IMMEDIATE")
        source_records = (staging / "source-records.jsonl").open("xb")
        source_map = (staging / "source-map.jsonl").open("xb")
        prepared_documents: list[PreparedDocument] = []
        document_count = 0
        chunk_count = 0
        try:
            for expected_ordinal, document in enumerate(documents):
                if document.ordinal != expected_ordinal:
                    raise BuildError("history documents 必须按连续 ordinal 输入")
                document_count += 1
                prepared_documents.append(
                    PreparedDocument(
                        document_id=document.document_id,
                        title=document.title,
                        source_path=document.source_path,
                        source_hash=document.source_hash,
                        size_bytes=document.source_size_bytes,
                        ordinal=document.ordinal,
                    )
                )
                chunk_count += _insert_document(
                    database,
                    document,
                    source_id,
                    source_revision,
                    source_records,
                    source_map,
                )
        finally:
            source_records.close()
            source_map.close()
        if not document_count or not chunk_count:
            raise BuildError("history structured source 不能为空")
        metadata = {
            "builderName": "harness-wiki-builder",
            "builderVersion": "1",
            "historySourceId": source_id,
            "historySourceRevision": source_revision,
            "historyExclusions": canonical_json_bytes(list(exclusions)).decode("utf-8"),
            "normalizationMapHash": NORMALIZATION_MAP_HASH,
            "normalizationVersion": str(NORMALIZATION_VERSION),
            "chunkSoftLimit": str(SOFT_CHUNK_CHARS),
            "chunkHardLimit": str(HARD_CHUNK_CHARS),
            "searchOverlapChars": str(SEARCH_OVERLAP_CHARS),
        }
        database.executemany(
            "INSERT INTO build_metadata(key, value) VALUES (?, ?)",
            sorted(metadata.items()),
        )
        database.commit()
        validate_sqlite_shape(database)
        if database.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise BuildError("history content.sqlite 完整性检查失败")
        database.execute("VACUUM")
        database.close()
        database = None

        initialize_workspace_files(
            staging,
            tuple(prepared_documents),
            wiki_id=wiki_id,
            title=title,
            version=version,
            concept_namespace=concept_namespace,
            builder_profile="history-v1",
        )
        _rewrite_source_catalog(
            staging,
            prepared_documents,
            source_id,
            source_revision,
            exclusions,
        )
        if before_publish is not None:
            before_publish()
        if target.exists() or target.is_symlink():
            raise FileExistsError(target)
        staging.rename(target)
        published = True
        return target
    except BaseException:
        if database is not None:
            database.rollback()
        raise
    finally:
        if database is not None:
            database.close()
        if not published:
            shutil.rmtree(staging, ignore_errors=True)


def _insert_document(
    database: sqlite3.Connection,
    document: HistoryDocumentRecord,
    source_id: str,
    source_revision: str,
    source_records,
    source_map,
) -> int:
    database.execute(
        """
        INSERT INTO documents(
            document_id, title, responsibility, edition, language, rights,
            source_hash, ordinal, metadata_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            document.document_id,
            document.title,
            "",
            source_revision,
            "zh-Hant",
            "private-local-install",
            document.source_hash,
            document.ordinal,
            _json_text(
                {
                    "sourceId": source_id,
                    "sourceRevision": source_revision,
                    **(document.metadata or {}),
                }
            ),
        ),
    )
    known_sections: set[str] = set()
    chunk_count = 0
    tails: dict[str, str] = {}
    ordinals: dict[str, int] = {}
    for section in document.sections:
        if section.document_id != document.document_id:
            raise BuildError(f"section documentId 不一致：{section.section_id}")
        if section.parent_section_id is not None and section.parent_section_id not in known_sections:
            raise BuildError(f"section parent 尚未写入：{section.section_id}")
        if section.section_id in known_sections:
            raise BuildError(f"sectionId 重复：{section.section_id}")
        known_sections.add(section.section_id)
        tails[section.section_id] = ""
        ordinals[section.section_id] = 0
        database.execute(
            """
            INSERT INTO sections(
                section_id, document_id, parent_section_id, title,
                path, ordinal, metadata_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                section.section_id,
                section.document_id,
                section.parent_section_id,
                section.title,
                section.path,
                section.ordinal,
                _json_text(section.metadata or {}),
            ),
        )
        if section.source_path is not None:
            source_map.write(
                canonical_json_bytes(
                    {
                        "documentId": document.document_id,
                        "sectionId": section.section_id,
                        "sourcePath": section.source_path,
                        "sourceHash": section.source_hash,
                        "paragraphCount": len(section.paragraphs),
                    }
                )
                + b"\n"
            )
        if section.exclusion_audit is not None:
            _write_exclusion_audit(
                source_map,
                section.exclusion_audit,
                document.document_id,
                section.section_id,
                section.source_path,
            )
        for paragraph in section.paragraphs:
            source_records.write(
                canonical_json_bytes(
                    {
                        "recordId": paragraph.paragraph_id,
                        "documentId": document.document_id,
                        "sectionId": section.section_id,
                        "ordinal": paragraph.ordinal,
                        "text": paragraph.text,
                        "sourceHash": paragraph.source_hash,
                        "sourcePath": section.source_path,
                        "locator": paragraph.locator,
                    }
                )
                + b"\n"
            )
            if paragraph.exclusion_audit is not None:
                _write_exclusion_audit(
                    source_map,
                    paragraph.exclusion_audit,
                    document.document_id,
                    section.section_id,
                    section.source_path,
                )
            for segment_number, original_text in enumerate(_chunk_text(paragraph.text), start=1):
                ordinal = ordinals[section.section_id]
                locator = {
                    "documentId": document.document_id,
                    "fileName": Path(section.source_path or document.source_path.name).name,
                    "sectionPath": section.path,
                    "chunkOrdinal": ordinal,
                    **paragraph.locator,
                }
                if segment_number > 1:
                    locator["segmentNumber"] = segment_number
                content_hash = hashlib.sha256(original_text.encode("utf-8")).hexdigest()
                chunk_id = stable_id(
                    "chunk",
                    paragraph.paragraph_id,
                    segment_number,
                    content_hash,
                )
                prior = tails[section.section_id]
                search_text = f"{prior}\n{original_text}" if prior else original_text
                normalized_text = normalize_for_search(search_text)
                original_ngrams = " ".join(original_chinese_ngrams(search_text))
                normalized_ngrams = " ".join(chinese_ngrams(search_text))
                locator_json = _json_text(locator)
                database.execute(
                    """
                    INSERT INTO chunks(
                        chunk_id, section_id, ordinal, original_text, normalized_text,
                        original_ngrams, normalized_ngrams, locator_json, content_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        chunk_id,
                        section.section_id,
                        ordinal,
                        original_text,
                        normalized_text,
                        original_ngrams,
                        normalized_ngrams,
                        locator_json,
                        content_hash,
                    ),
                )
                database.execute(
                    "INSERT INTO chunks_original_fts VALUES (?, ?, ?)",
                    (chunk_id, original_text, original_ngrams),
                )
                database.execute(
                    "INSERT INTO chunks_normalized_fts VALUES (?, ?, ?)",
                    (chunk_id, normalized_text, normalized_ngrams),
                )
                database.execute(
                    "INSERT INTO source_locators VALUES (?, ?, ?, ?)",
                    (
                        stable_id("locator", chunk_id),
                        chunk_id,
                        _locator_label(paragraph.locator, segment_number),
                        locator_json,
                    ),
                )
                ordinals[section.section_id] = ordinal + 1
                tails[section.section_id] = original_text[-SEARCH_OVERLAP_CHARS:]
                chunk_count += 1
    return chunk_count


def _rewrite_source_catalog(
    staging: Path,
    documents: list[PreparedDocument],
    source_id: str,
    source_revision: str,
    exclusions: tuple[str, ...],
) -> None:
    catalog = {
        "schemaVersion": 1,
        "sources": [
            {
                "sourceId": source_id,
                "documentId": document.document_id,
                "fileName": document.source_path.name,
                "format": document.source_path.suffix.lower().lstrip("."),
                "sha256": document.source_hash,
                "sizeBytes": document.size_bytes,
                "ordinal": document.ordinal,
                "rights": "private-local-install",
                "gitRevision": source_revision,
                "exclusions": list(exclusions),
            }
            for document in documents
        ],
    }
    (staging / "source-catalog.json").write_bytes(canonical_json_bytes(catalog))


def _write_exclusion_audit(
    stream,
    audit: HistoryExclusionAudit,
    document_id: str,
    section_id: str,
    source_path: str | None,
) -> None:
    stream.write(
        canonical_json_bytes(
            {
                "recordId": audit.record_id,
                "documentId": document_id,
                "sectionId": section_id,
                "sourcePath": source_path,
                "kind": audit.kind,
                "sourceTextSha256": audit.source_text_hash,
                "excludedTextSha256": audit.excluded_text_hash,
                "reason": audit.reason,
                "sourceLine": audit.source_line,
                "excludedLine": audit.excluded_line,
            }
        )
        + b"\n"
    )


def _chunk_text(text: str):
    if not text:
        return
    for index in range(0, len(text), HARD_CHUNK_CHARS):
        yield text[index : index + HARD_CHUNK_CHARS]


def _locator_label(locator: dict[str, object], segment_number: int) -> str:
    if locator.get("blockType") == "heading":
        heading = locator.get("eraHeading") or locator.get("chapterTitle", "")
        label = f"{locator.get('documentTitle', '')} · {heading} · 标题"
    else:
        label = (
            f"{locator.get('documentTitle', '')} · "
            f"{locator.get('chapterTitle', '')} · "
            f"第 {locator.get('paragraphNumber', '')} 段"
        )
    return f"{label} · 分段 {segment_number}" if segment_number > 1 else label


def _json_text(value: object) -> str:
    return canonical_json_bytes(value).decode("utf-8")


__all__ = ["populate_history_workspace"]
