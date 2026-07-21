"""Read-only structural, evidence, and retrieval publication gates."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from tools.package_format import canonical_json_bytes

from .evaluation import (
    RetrievalReport,
    evaluate_retrieval,
    load_retrieval_cases,
)
from .models import BuildError
from .sqlite_schema import validate_sqlite_shape
from .workspace import WikiWorkspace, load_workspace

_HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


@dataclass(frozen=True)
class ValidationReport:
    publishable: bool
    error_codes: tuple[str, ...]
    errors: tuple[str, ...]
    integrity_check: str
    foreign_key_errors: int
    extracted_source_coverage: float
    orphan_evidence_count: int
    invalid_locator_count: int
    unreferenced_assertion_count: int
    missing_document_summary_count: int
    missing_volume_summary_count: int
    retrieval: RetrievalReport

    def to_dict(self) -> dict[str, object]:
        return {
            "publishable": self.publishable,
            "errorCodes": list(self.error_codes),
            "errors": list(self.errors),
            "integrityCheck": self.integrity_check,
            "foreignKeyErrors": self.foreign_key_errors,
            "extractedSourceCoverage": self.extracted_source_coverage,
            "orphanEvidenceCount": self.orphan_evidence_count,
            "invalidLocatorCount": self.invalid_locator_count,
            "unreferencedAssertionCount": self.unreferenced_assertion_count,
            "missingDocumentSummaryCount": self.missing_document_summary_count,
            "missingVolumeSummaryCount": self.missing_volume_summary_count,
            "retrieval": self.retrieval.to_dict(),
        }


def validate_workspace(
    workspace: Path,
    evaluation_path: Path | None = None,
) -> ValidationReport:
    errors: list[tuple[str, str]] = []
    try:
        loaded = load_workspace(workspace)
    except BuildError as error:
        return _failed_report("workspace", str(error))

    connection = _open_read_only(loaded.database_path)
    try:
        connection.execute("BEGIN")
        try:
            validate_sqlite_shape(connection)
        except (sqlite3.Error, ValueError) as error:
            errors.append(("sqlite_shape", str(error)))

        try:
            integrity_check = connection.execute("PRAGMA integrity_check").fetchone()[0]
        except sqlite3.Error as error:
            integrity_check = f"error: {error}"
        if integrity_check != "ok":
            errors.append(("integrity_check", f"SQLite integrity_check：{integrity_check}"))

        foreign_key_errors = len(connection.execute("PRAGMA foreign_key_check").fetchall())
        if foreign_key_errors:
            errors.append(
                ("foreign_key_check", f"SQLite 外键错误：{foreign_key_errors}")
            )

        source_coverage = _source_coverage(connection)
        if source_coverage != 1.0:
            errors.append(("source_coverage", f"来源提取覆盖率：{source_coverage:.6f}"))

        source_error = _validate_sources(connection, loaded)
        if source_error:
            errors.append(("source_catalog", source_error))

        hierarchy_errors = _invalid_hierarchy_count(connection)
        if hierarchy_errors:
            errors.append(("invalid_hierarchy", f"目录层级错误：{hierarchy_errors}"))

        orphan_evidence = _orphan_evidence_count(connection)
        if orphan_evidence:
            errors.append(("orphan_evidence", f"孤立证据引用：{orphan_evidence}"))

        unreferenced = _unreferenced_assertion_count(connection)
        if unreferenced:
            errors.append(("unreferenced_assertion", f"无证据语义资产：{unreferenced}"))

        invalid_locators = _invalid_locator_count(connection)
        if invalid_locators:
            errors.append(("invalid_locator", f"无效 locator：{invalid_locators}"))

        noncanonical_json = _noncanonical_json_count(connection)
        if noncanonical_json:
            errors.append(("noncanonical_json", f"非规范 JSON 字段：{noncanonical_json}"))

        missing_document_summaries = connection.execute(
            """
            SELECT COUNT(*) FROM documents AS d
            WHERE NOT EXISTS (
                SELECT 1 FROM summaries AS s
                WHERE s.owner_type='document' AND s.owner_id=d.document_id
            )
            """
        ).fetchone()[0]
        if missing_document_summaries:
            errors.append(
                (
                    "missing_document_summary",
                    f"缺少文档摘要：{missing_document_summaries}",
                )
            )

        missing_volume_summaries = connection.execute(
            """
            SELECT COUNT(*) FROM sections AS section
            WHERE EXISTS (SELECT 1 FROM chunks WHERE chunks.section_id=section.section_id)
              AND NOT EXISTS (
                  SELECT 1 FROM summaries AS summary
                  WHERE summary.owner_type='section'
                    AND summary.owner_id=section.section_id
              )
            """
        ).fetchone()[0]
        if missing_volume_summaries:
            errors.append(
                (
                    "missing_volume_summary",
                    f"缺少叶级章节摘要：{missing_volume_summaries}",
                )
            )

        fts_errors = _fts_coverage_errors(connection)
        if fts_errors:
            errors.append(("fts_coverage", f"FTS 覆盖错误：{fts_errors}"))
    except sqlite3.Error as error:
        return _failed_report("sqlite_read", f"SQLite 验证失败：{error}")
    finally:
        connection.close()

    retrieval = RetrievalReport.empty()
    eval_path = evaluation_path or loaded.root / "evaluation/retrieval-eval.jsonl"
    if not eval_path.is_file() or eval_path.is_symlink():
        errors.append(("missing_evaluation", f"缺少检索评测集：{eval_path}"))
    else:
        try:
            cases = load_retrieval_cases(eval_path)
            retrieval = evaluate_retrieval(loaded.database_path, cases)
        except BuildError as error:
            errors.append(("evaluation_invalid", str(error)))

    if retrieval.overall_recall_at_20 < 0.90:
        errors.append(
            (
                "overall_recall",
                f"Recall@20 未达 0.90：{retrieval.overall_recall_at_20:.6f}",
            )
        )
    minimum_category = min(retrieval.category_recall.values(), default=0.0)
    if minimum_category < 0.85:
        errors.append(
            (
                "category_recall",
                f"分类 Recall@20 未达 0.85：{minimum_category:.6f}",
            )
        )

    return ValidationReport(
        publishable=not errors,
        error_codes=tuple(code for code, _message in errors),
        errors=tuple(message for _code, message in errors),
        integrity_check=integrity_check,
        foreign_key_errors=foreign_key_errors,
        extracted_source_coverage=source_coverage,
        orphan_evidence_count=orphan_evidence,
        invalid_locator_count=invalid_locators,
        unreferenced_assertion_count=unreferenced,
        missing_document_summary_count=missing_document_summaries,
        missing_volume_summary_count=missing_volume_summaries,
        retrieval=retrieval,
    )


def _failed_report(code: str, message: str) -> ValidationReport:
    return ValidationReport(
        publishable=False,
        error_codes=(code,),
        errors=(message,),
        integrity_check="unavailable",
        foreign_key_errors=0,
        extracted_source_coverage=0.0,
        orphan_evidence_count=0,
        invalid_locator_count=0,
        unreferenced_assertion_count=0,
        missing_document_summary_count=0,
        missing_volume_summary_count=0,
        retrieval=RetrievalReport.empty(),
    )


def _source_coverage(connection: sqlite3.Connection) -> float:
    total = connection.execute("SELECT COUNT(*) FROM documents").fetchone()[0]
    if total == 0:
        return 0.0
    covered = connection.execute(
        """
        SELECT COUNT(DISTINCT documents.document_id)
        FROM documents
        JOIN sections USING(document_id)
        JOIN chunks USING(section_id)
        """
    ).fetchone()[0]
    return round(covered / total, 6)


def _validate_sources(connection: sqlite3.Connection, workspace: WikiWorkspace) -> str | None:
    rows = connection.execute(
        "SELECT document_id, source_hash FROM documents ORDER BY ordinal"
    ).fetchall()
    if any(not _HASH_PATTERN.fullmatch(source_hash) for _document_id, source_hash in rows):
        return "documents 包含无效 source_hash"
    catalog_path = workspace.root / "source-catalog.json"
    try:
        raw_bytes = catalog_path.read_bytes()
        catalog = json.loads(raw_bytes, parse_constant=_reject_json_constant)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        return f"source-catalog.json 无法读取：{error}"
    if raw_bytes != canonical_json_bytes(catalog):
        return "source-catalog.json 不是规范 JSON"
    if not isinstance(catalog, dict) or set(catalog) != {"schemaVersion", "sources"}:
        return "source-catalog.json 结构无效"
    sources = catalog.get("sources")
    if catalog.get("schemaVersion") != 1 or not isinstance(sources, list):
        return "source-catalog.json 版本或 sources 无效"
    pairs = [(row.get("documentId"), row.get("sha256")) for row in sources if isinstance(row, dict)]
    if len(pairs) != len(sources) or pairs != rows:
        return "source-catalog.json 与 documents 不一致"
    registry = connection.execute(
        "SELECT value FROM build_metadata WHERE key='conceptRegistryHash'"
    ).fetchone()
    if registry is None or not _HASH_PATTERN.fullmatch(registry[0]):
        return "缺少有效 conceptRegistryHash"
    registry_path = workspace.enrichment_path / "concept-registry.jsonl"
    if registry_path.is_symlink() or not registry_path.is_file():
        return "concept-registry.jsonl 不可安全读取"
    digest = hashlib.sha256(registry_path.read_bytes()).hexdigest()
    if digest != registry[0]:
        return "conceptRegistryHash 与文件不一致"
    return None


def _invalid_hierarchy_count(connection: sqlite3.Connection) -> int:
    rows = connection.execute(
        "SELECT section_id, document_id, parent_section_id FROM sections"
    ).fetchall()
    by_id = {section_id: (document_id, parent_id) for section_id, document_id, parent_id in rows}
    errors = 0
    for section_id, (document_id, parent_id) in by_id.items():
        seen = {section_id}
        current = parent_id
        while current is not None:
            parent = by_id.get(current)
            if parent is None or parent[0] != document_id or current in seen:
                errors += 1
                break
            seen.add(current)
            current = parent[1]
    return errors


def _orphan_evidence_count(connection: sqlite3.Connection) -> int:
    owner_tables = {
        "summary": ("summaries", "summary_id"),
        "term": ("terms", "term_id"),
        "alias": ("aliases", "alias_id"),
        "mention": ("mentions", "mention_id"),
        "annotation": ("annotations", "annotation_id"),
        "link": ("links", "link_id"),
    }
    count = 0
    for owner_type, owner_id in connection.execute(
        "SELECT owner_type, owner_id FROM evidence_refs"
    ):
        target = owner_tables.get(owner_type)
        if target is None:
            count += 1
            continue
        table, column = target
        if connection.execute(
            f"SELECT 1 FROM {table} WHERE {column}=?", (owner_id,)
        ).fetchone() is None:
            count += 1
    return count


def _unreferenced_assertion_count(connection: sqlite3.Connection) -> int:
    total = 0
    for owner_type, table, column in (
        ("summary", "summaries", "summary_id"),
        ("term", "terms", "term_id"),
        ("alias", "aliases", "alias_id"),
        ("mention", "mentions", "mention_id"),
        ("annotation", "annotations", "annotation_id"),
        ("link", "links", "link_id"),
    ):
        total += connection.execute(
            f"""
            SELECT COUNT(*) FROM {table} AS asset
            WHERE NOT EXISTS (
                SELECT 1 FROM evidence_refs AS evidence
                WHERE evidence.owner_type=? AND evidence.owner_id=asset.{column}
            )
            """,
            (owner_type,),
        ).fetchone()[0]
    return total


def _invalid_locator_count(connection: sqlite3.Connection) -> int:
    invalid = connection.execute(
        """
        SELECT COUNT(*) FROM chunks
        WHERE (SELECT COUNT(*) FROM source_locators
               WHERE source_locators.chunk_id=chunks.chunk_id) != 1
        """
    ).fetchone()[0]
    for chunk_locator, source_locator in connection.execute(
        """
        SELECT chunks.locator_json, source_locators.locator_json
        FROM chunks JOIN source_locators USING(chunk_id)
        """
    ):
        if chunk_locator != source_locator or not _is_canonical_json(source_locator):
            invalid += 1
            continue
        value = json.loads(source_locator)
        if set(value) != {"chunkOrdinal", "documentId", "fileName", "sectionPath"}:
            invalid += 1
    return invalid


def _noncanonical_json_count(connection: sqlite3.Connection) -> int:
    count = 0
    for table, column in (
        ("documents", "metadata_json"),
        ("sections", "metadata_json"),
        ("chunks", "locator_json"),
        ("terms", "metadata_json"),
        ("annotations", "value_json"),
        ("source_locators", "locator_json"),
    ):
        count += sum(
            1
            for (value,) in connection.execute(f"SELECT {column} FROM {table}")
            if not _is_canonical_json(value)
        )
    return count


def _fts_coverage_errors(connection: sqlite3.Connection) -> int:
    expected_actual = (
        ("chunks", "chunks_original_fts"),
        ("chunks", "chunks_normalized_fts"),
        ("summaries", "summaries_fts"),
        ("terms", "terms_aliases_fts"),
    )
    return sum(
        abs(
            connection.execute(f"SELECT COUNT(*) FROM {expected}").fetchone()[0]
            - connection.execute(f"SELECT COUNT(*) FROM {actual}").fetchone()[0]
        )
        for expected, actual in expected_actual
    )


def _is_canonical_json(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = json.loads(value, parse_constant=_reject_json_constant)
    except (json.JSONDecodeError, ValueError):
        return False
    return value.encode("utf-8") == canonical_json_bytes(parsed)


def _open_read_only(path: Path) -> sqlite3.Connection:
    try:
        return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
    except sqlite3.Error as error:
        raise BuildError(f"content.sqlite 无法只读打开：{error}") from error


def _reject_json_constant(value: str) -> object:
    raise ValueError(f"不允许 JSON 常量 {value}")


__all__ = ["ValidationReport", "validate_workspace"]
