"""Prepare deterministic source hierarchy and search data for ``.hwiki``."""

from __future__ import annotations

import hashlib
import re
import shutil
import sqlite3
import tempfile
from collections.abc import Iterable, Sequence
from pathlib import Path

from tools.package_format import canonical_json_bytes

from .extractors import extract_documents, iter_document_sections, stable_id
from .models import BuildError, PreparedDocument
from .normalization import (
    NORMALIZATION_MAP_HASH,
    NORMALIZATION_VERSION,
    chinese_ngrams,
    normalize_for_search,
    original_chinese_ngrams,
)
from .schema import validate_identifier
from .sqlite_schema import create_content_database, validate_sqlite_shape
from .workspace import initialize_workspace_files

SOFT_CHUNK_CHARS = 1200
HARD_CHUNK_CHARS = 2000
SEARCH_OVERLAP_CHARS = 200
_MARKDOWN_HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$")


def prepare_workspace(
    inputs: Sequence[Path],
    output: Path,
    wiki_id: str,
    title: str,
    version: int,
    concept_namespace: str,
) -> Path:
    """Build a complete base workspace and publish it only after validation."""

    validate_identifier(wiki_id, "wiki.id")
    validate_identifier(concept_namespace, "conceptNamespace")
    if not isinstance(title, str) or not title.strip():
        raise BuildError("wiki.title 不能为空")
    if type(version) is not int or version <= 0:
        raise BuildError("wiki.version 必须是正整数")

    target = Path(output)
    if target.exists() or target.is_symlink():
        raise FileExistsError(target)
    documents = tuple(extract_documents(inputs))

    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{target.name}.staging-", dir=target.parent))
    published = False
    try:
        initialize_workspace_files(
            staging,
            documents,
            wiki_id=wiki_id,
            title=title,
            version=version,
            concept_namespace=concept_namespace,
        )
        _build_content_database(staging / "content.sqlite", documents)
        if target.exists() or target.is_symlink():
            raise FileExistsError(target)
        staging.rename(target)
        published = True
        return target
    finally:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)


def pack_workspace(
    workspace: Path,
    output: Path,
    private_key_path: Path,
    evaluation_path: Path | None = None,
):
    """Compatibility entry point for signing a prepared workspace."""

    from .packaging import pack_workspace as package_workspace

    return package_workspace(workspace, output, private_key_path, evaluation_path)


def _build_content_database(
    database_path: Path,
    documents: tuple[PreparedDocument, ...],
) -> None:
    database = create_content_database(database_path)
    try:
        database.execute("BEGIN IMMEDIATE")
        total_chunks = 0
        for document in documents:
            total_chunks += _insert_document(database, document)
        if total_chunks == 0:
            raise BuildError("没有可提取文本")
        metadata = {
            "builderName": "harness-wiki-builder",
            "builderVersion": "1",
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
            raise BuildError("content.sqlite 完整性检查失败")
        database.execute("VACUUM")
    except BaseException:
        database.rollback()
        raise
    finally:
        database.close()


def _insert_document(database: sqlite3.Connection, document: PreparedDocument) -> int:
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
            "",
            "und",
            "unverified",
            document.source_hash,
            document.ordinal,
            _json_text({"fileName": document.source_path.name}),
        ),
    )

    section_ids: dict[tuple[str, ...], str] = {}
    chunk_ordinals: dict[str, int] = {}
    search_tails: dict[str, str] = {}
    next_section_ordinal = 0
    inserted_chunks = 0

    for extracted in iter_document_sections(document):
        parts = _location_parts(extracted.location)
        parent_id: str | None = None
        for depth in range(1, len(parts) + 1):
            key = parts[:depth]
            section_id = section_ids.get(key)
            if section_id is None:
                section_id = stable_id("section", document.document_id, *key)
                section_ids[key] = section_id
                chunk_ordinals[section_id] = 0
                search_tails[section_id] = ""
                database.execute(
                    """
                    INSERT INTO sections(
                        section_id, document_id, parent_section_id, title,
                        path, ordinal, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        section_id,
                        document.document_id,
                        parent_id,
                        key[-1],
                        " / ".join(key),
                        next_section_ordinal,
                        "{}",
                    ),
                )
                next_section_ordinal += 1
            parent_id = section_id

        leaf_id = parent_id
        if leaf_id is None:
            raise BuildError(f"来源章节位置为空：{document.source_path.name}")
        text = _remove_structural_heading(extracted.text, parts[-1])
        for original_text in _chunk_text(text):
            ordinal = chunk_ordinals[leaf_id]
            locator = {
                "documentId": document.document_id,
                "fileName": document.source_path.name,
                "sectionPath": " / ".join(parts),
                "chunkOrdinal": ordinal,
            }
            content_hash = hashlib.sha256(original_text.encode("utf-8")).hexdigest()
            chunk_id = stable_id("chunk", leaf_id, ordinal, content_hash)
            prior_tail = search_tails[leaf_id]
            search_text = f"{prior_tail}\n{original_text}" if prior_tail else original_text
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
                    leaf_id,
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
                """
                INSERT INTO chunks_original_fts(chunk_id, original_text, original_ngrams)
                VALUES (?, ?, ?)
                """,
                (chunk_id, original_text, original_ngrams),
            )
            database.execute(
                """
                INSERT INTO chunks_normalized_fts(chunk_id, normalized_text, normalized_ngrams)
                VALUES (?, ?, ?)
                """,
                (chunk_id, normalized_text, normalized_ngrams),
            )
            database.execute(
                """
                INSERT INTO source_locators(locator_id, chunk_id, label, locator_json)
                VALUES (?, ?, ?, ?)
                """,
                (
                    stable_id("locator", chunk_id),
                    chunk_id,
                    f"{document.source_path.name} · {' / '.join(parts)} · {ordinal + 1}",
                    locator_json,
                ),
            )
            chunk_ordinals[leaf_id] = ordinal + 1
            search_tails[leaf_id] = original_text[-SEARCH_OVERLAP_CHARS:]
            inserted_chunks += 1

    return inserted_chunks


def _location_parts(location: str) -> tuple[str, ...]:
    parts = tuple(part.strip() for part in location.split(" / ") if part.strip())
    return parts or ("正文",)


def _remove_structural_heading(text: str, expected_title: str) -> str:
    first, separator, remainder = text.partition("\n")
    match = _MARKDOWN_HEADING.fullmatch(first)
    if match and match.group(1).strip() == expected_title:
        return remainder.lstrip("\n") if separator else ""
    return text


def _chunk_text(text: str) -> Iterable[str]:
    if not text.strip():
        return
    current = ""
    for paragraph in re.split(r"\n{2,}", text.strip("\n")):
        if not paragraph:
            continue
        fragments = (
            paragraph[index : index + HARD_CHUNK_CHARS]
            for index in range(0, len(paragraph), HARD_CHUNK_CHARS)
        )
        for fragment in fragments:
            candidate = f"{current}\n\n{fragment}" if current else fragment
            if current and len(candidate) > SOFT_CHUNK_CHARS:
                yield current
                current = fragment
            else:
                current = candidate
            if len(current) >= HARD_CHUNK_CHARS:
                yield current
                current = ""
    if current:
        yield current


def _json_text(value: object) -> str:
    return canonical_json_bytes(value).decode("utf-8")


__all__ = [
    "HARD_CHUNK_CHARS",
    "SEARCH_OVERLAP_CHARS",
    "SOFT_CHUNK_CHARS",
    "pack_workspace",
    "prepare_workspace",
]
