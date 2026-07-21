"""Android-compatible SQLite schema for schema-v1 Wiki content."""

from __future__ import annotations

import os
import sqlite3
from pathlib import Path

CONTENT_SCHEMA_VERSION = 1

REQUIRED_TABLES = frozenset(
    {
        "documents",
        "sections",
        "chunks",
        "summaries",
        "terms",
        "aliases",
        "mentions",
        "annotations",
        "links",
        "evidence_refs",
        "source_locators",
        "build_metadata",
    }
)
REQUIRED_FTS_TABLES = frozenset(
    {
        "chunks_original_fts",
        "chunks_normalized_fts",
        "summaries_fts",
        "terms_aliases_fts",
    }
)

CORE_DDL = f"""
PRAGMA foreign_keys=ON;
CREATE TABLE documents(
  document_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  responsibility TEXT NOT NULL,
  edition TEXT NOT NULL,
  language TEXT NOT NULL,
  rights TEXT NOT NULL,
  source_hash TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE sections(
  section_id TEXT PRIMARY KEY,
  document_id TEXT NOT NULL REFERENCES documents(document_id),
  parent_section_id TEXT REFERENCES sections(section_id),
  title TEXT NOT NULL,
  path TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE chunks(
  chunk_id TEXT PRIMARY KEY,
  section_id TEXT NOT NULL REFERENCES sections(section_id),
  ordinal INTEGER NOT NULL,
  original_text TEXT NOT NULL,
  normalized_text TEXT NOT NULL,
  original_ngrams TEXT NOT NULL,
  normalized_ngrams TEXT NOT NULL,
  locator_json TEXT NOT NULL,
  content_hash TEXT NOT NULL
);
CREATE TABLE summaries(
  summary_id TEXT PRIMARY KEY,
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  level TEXT NOT NULL,
  text TEXT NOT NULL
);
CREATE TABLE terms(
  term_id TEXT PRIMARY KEY,
  concept_key TEXT NOT NULL,
  canonical_text TEXT NOT NULL,
  kind TEXT NOT NULL,
  confidence REAL NOT NULL,
  metadata_json TEXT NOT NULL
);
CREATE TABLE aliases(
  alias_id TEXT PRIMARY KEY,
  term_id TEXT NOT NULL REFERENCES terms(term_id),
  alias_text TEXT NOT NULL,
  normalized_alias TEXT NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE mentions(
  mention_id TEXT PRIMARY KEY,
  term_id TEXT NOT NULL REFERENCES terms(term_id),
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  start_offset INTEGER NOT NULL,
  end_offset INTEGER NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE annotations(
  annotation_id TEXT PRIMARY KEY,
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  value_json TEXT NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE links(
  link_id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  target_namespace TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  confidence REAL NOT NULL
);
CREATE TABLE evidence_refs(
  owner_type TEXT NOT NULL,
  owner_id TEXT NOT NULL,
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  role TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  PRIMARY KEY(owner_type, owner_id, chunk_id)
);
CREATE TABLE source_locators(
  locator_id TEXT PRIMARY KEY,
  chunk_id TEXT NOT NULL REFERENCES chunks(chunk_id),
  label TEXT NOT NULL,
  locator_json TEXT NOT NULL
);
CREATE TABLE build_metadata(
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE INDEX index_sections_document_ordinal
  ON sections(document_id, ordinal);
CREATE INDEX index_sections_parent_ordinal
  ON sections(parent_section_id, ordinal);
CREATE INDEX index_chunks_section_ordinal
  ON chunks(section_id, ordinal);
CREATE INDEX index_terms_concept_key
  ON terms(concept_key);
CREATE INDEX index_aliases_term_id
  ON aliases(term_id);
CREATE INDEX index_mentions_term_id
  ON mentions(term_id);
CREATE INDEX index_mentions_chunk_id
  ON mentions(chunk_id);
CREATE INDEX index_annotations_owner
  ON annotations(owner_type, owner_id);
CREATE INDEX index_annotations_kind
  ON annotations(kind);
CREATE INDEX index_links_source
  ON links(source_type, source_id);
CREATE INDEX index_links_target
  ON links(target_namespace, target_id);
CREATE INDEX index_evidence_refs_chunk_id
  ON evidence_refs(chunk_id);
CREATE INDEX index_source_locators_chunk_id
  ON source_locators(chunk_id);

CREATE VIRTUAL TABLE chunks_original_fts
  USING FTS4(chunk_id, original_text, original_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE chunks_normalized_fts
  USING FTS4(chunk_id, normalized_text, normalized_ngrams, tokenize=unicode61);
CREATE VIRTUAL TABLE summaries_fts
  USING FTS4(summary_id, text, tokenize=unicode61);
CREATE VIRTUAL TABLE terms_aliases_fts
  USING FTS4(owner_id, canonical_text, aliases_text, tokenize=unicode61);
PRAGMA user_version={CONTENT_SCHEMA_VERSION};
"""


def create_content_database(path: Path) -> sqlite3.Connection:
    """Create a new content database without replacing an existing target."""

    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(target)
        connection.executescript(CORE_DDL)
        connection.commit()
        if connection.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
            raise RuntimeError("无法启用 SQLite foreign_keys")
        return connection
    except BaseException:
        if connection is not None:
            connection.close()
        target.unlink(missing_ok=True)
        raise


def validate_sqlite_shape(connection: sqlite3.Connection) -> None:
    """Reject SQLite files that do not match the immutable v1 core shape."""

    version = connection.execute("PRAGMA user_version").fetchone()[0]
    if version != CONTENT_SCHEMA_VERSION:
        raise ValueError(
            f"SQLite user_version 应为 {CONTENT_SCHEMA_VERSION}，实际为 {version}"
        )

    rows = connection.execute(
        "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
    ).fetchall()
    names = {name for _kind, name, _sql in rows}
    missing = sorted((REQUIRED_TABLES | REQUIRED_FTS_TABLES) - names)
    if missing:
        raise ValueError(f"SQLite 缺少必需表: {', '.join(missing)}")

    forbidden = sorted(name for kind, name, _sql in rows if kind in {"trigger", "view"})
    if forbidden:
        raise ValueError(f"SQLite 包含禁止的 trigger 或 view: {', '.join(forbidden)}")

    virtual_tables: dict[str, str] = {}
    for kind, name, sql in rows:
        if kind == "table" and sql and "CREATE VIRTUAL TABLE" in sql.upper():
            virtual_tables[name] = sql
    unknown_virtual = sorted(set(virtual_tables) - REQUIRED_FTS_TABLES)
    if unknown_virtual:
        raise ValueError(f"SQLite 包含未知虚拟表: {', '.join(unknown_virtual)}")
    for name in sorted(REQUIRED_FTS_TABLES):
        sql = virtual_tables.get(name, "").upper()
        if "USING FTS4" not in sql:
            raise ValueError(f"SQLite 表 {name} 必须使用 FTS4")


__all__ = [
    "CONTENT_SCHEMA_VERSION",
    "CORE_DDL",
    "REQUIRED_FTS_TABLES",
    "REQUIRED_TABLES",
    "create_content_database",
    "validate_sqlite_shape",
]
