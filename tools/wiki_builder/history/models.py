"""Structured source records shared by history adapters."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class HistoryParagraphRecord:
    paragraph_id: str
    text: str
    ordinal: int
    locator: dict[str, object]
    source_hash: str


@dataclass(frozen=True)
class HistorySectionRecord:
    section_id: str
    document_id: str
    parent_section_id: str | None
    title: str
    path: str
    ordinal: int
    source_path: str | None
    source_hash: str | None
    paragraphs: tuple[HistoryParagraphRecord, ...] = ()
    metadata: dict[str, object] | None = None


@dataclass(frozen=True)
class HistoryDocumentRecord:
    document_id: str
    title: str
    ordinal: int
    source_path: Path
    source_hash: str
    source_size_bytes: int
    source_format: str
    sections: tuple[HistorySectionRecord, ...]
    metadata: dict[str, object] | None = None


@dataclass(frozen=True)
class HistorySourceRecord:
    source_id: str
    git_revision: str
    documents: tuple[HistoryDocumentRecord, ...]
